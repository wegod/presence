package com.presence.seckill.app;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.presence.seckill.api.BusinessException;
import com.presence.seckill.api.ErrorCode;
import com.presence.seckill.api.dto.OrderResponse;
import com.presence.seckill.api.dto.PlaceOrderRequest;
import com.presence.seckill.domain.activity.ActivityStatus;
import com.presence.seckill.domain.event.StockDeductEvent;
import com.presence.seckill.domain.model.RedisCompensateLog;
import com.presence.seckill.domain.model.SeckillActivity;
import com.presence.seckill.domain.model.SeckillOrder;
import com.presence.seckill.domain.model.SeckillOutbox;
import com.presence.seckill.domain.model.SeckillSkuStock;
import com.presence.seckill.domain.order.OrderStatus;
import com.presence.seckill.domain.order.OutboxStatus;
import com.presence.seckill.infra.circuit.MysqlCircuitExecutor;
import com.presence.seckill.infra.kafka.StockDeductProducer;
import com.presence.seckill.infra.mapper.RedisCompensateLogMapper;
import com.presence.seckill.infra.mapper.SeckillActivityMapper;
import com.presence.seckill.infra.mapper.SeckillOrderMapper;
import com.presence.seckill.infra.mapper.SeckillOutboxMapper;
import com.presence.seckill.infra.mapper.SeckillSkuStockMapper;
import com.presence.seckill.infra.redis.LuaPreDeductResult;
import com.presence.seckill.infra.redis.SeckillRedisStockService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 抢购下单应用服务（核心链路）。
 * <p>热路径：Sentinel 限流 → 活动时间窗校验 → Redis Lua 预扣（含幂等 + HELD 记录 + 待补偿 ZSet）
 * → 事务写订单+Outbox → 异步提交 Kafka。
 * <p>可售/限购仍由 warmup 写入的 Redis 键经 Lua 校验；时间窗（未开始 / 已结束）在 Lua 前用 MySQL 主键读拒绝，
 * 避免预扣后再回滚。
 * <p>Redis 成功后写库失败：保留 ZSet 中的 HELD，由 RedisCompensate Job 补单或回补；不把 MySQL 补偿表当队列。
 */
@Slf4j
@Service
public class SeckillOrderAppService {

    /** 与 AppConfig 中 Sentinel 流控资源名一致 */
    private static final String RESOURCE_SECKILL = "seckill:placeOrder";

    private final SeckillActivityMapper activityMapper;
    private final SeckillSkuStockMapper stockMapper;
    private final SeckillOrderMapper orderMapper;
    private final SeckillOutboxMapper outboxMapper;
    private final RedisCompensateLogMapper compensateLogMapper;
    private final SeckillRedisStockService redisStockService;
    private final StockDeductProducer stockDeductProducer;
    private final MysqlCircuitExecutor mysqlCircuitExecutor;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private final int payWindowMinutes;
    private final long idemTtlSeconds;

    public SeckillOrderAppService(SeckillActivityMapper activityMapper,
                                  SeckillSkuStockMapper stockMapper,
                                  SeckillOrderMapper orderMapper,
                                  SeckillOutboxMapper outboxMapper,
                                  RedisCompensateLogMapper compensateLogMapper,
                                  SeckillRedisStockService redisStockService,
                                  StockDeductProducer stockDeductProducer,
                                  MysqlCircuitExecutor mysqlCircuitExecutor,
                                  TransactionTemplate transactionTemplate,
                                  ObjectMapper objectMapper,
                                  @Value("${seckill.pay-window-minutes:1}") int payWindowMinutes,
                                  @Value("${seckill.idempotent-ttl-seconds:86400}") long idemTtlSeconds) {
        this.activityMapper = activityMapper;
        this.stockMapper = stockMapper;
        this.orderMapper = orderMapper;
        this.outboxMapper = outboxMapper;
        this.compensateLogMapper = compensateLogMapper;
        this.redisStockService = redisStockService;
        this.stockDeductProducer = stockDeductProducer;
        this.mysqlCircuitExecutor = mysqlCircuitExecutor;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
        this.payWindowMinutes = payWindowMinutes;
        this.idemTtlSeconds = idemTtlSeconds;
    }

    /**
     * 抢购入口：先过 Sentinel，再进入真正下单。
     *
     * @param userId  当前用户（Cookie / Header 解析）
     * @param request 含 requestId 幂等键
     * @return 订单快照；幂等命中但订单未可见时 order 为空并带提示 message
     * @throws BusinessException RATE_LIMITED 被限流；其它见 {@link #doPlaceOrder}
     */
    public PlaceOrderResult placeOrder(Long userId, PlaceOrderRequest request) {
        long tTotal = System.nanoTime();
        log.info("placeOrder entry userId={} activityId={} skuId={} requestId={}",
                userId, request.activityId(), request.skuId(), request.requestId());
        long tSentinel = System.nanoTime();
        try (Entry ignored = SphU.entry(RESOURCE_SECKILL)) {
            long costSentinelMs = elapsedMs(tSentinel);
            return doPlaceOrder(userId, request, tTotal, costSentinelMs);
        } catch (BlockException e) {
            log.warn("placeOrder Sentinel blocked userId={} activityId={} skuId={} requestId={} costMs.sentinel={} costMs.total={}",
                    userId, request.activityId(), request.skuId(), request.requestId(),
                    elapsedMs(tSentinel), elapsedMs(tTotal));
            throw new BusinessException(ErrorCode.RATE_LIMITED);
        }
    }

    /**
     * 下单主流程。先校验活动时间窗，再 Lua；写库失败收敛到 ZSet 补偿，避免多处回滚冲突。
     */
    private PlaceOrderResult doPlaceOrder(Long userId, PlaceOrderRequest request,
                                          long tTotal, long costSentinelMs) {
        int qty = request.safeQty();

        // 0. 活动时间窗（未开始 / 已结束）—— 在 Lua 前拒绝，避免无效预扣
        assertActivityWindowOpen(request.activityId());

        // 1. Redis Lua 预扣（幂等 + 可售 + 限购 + 库存 + HELD 记录 + ZADD）
        long tLua = System.nanoTime();
        LuaPreDeductResult luaResult;
        try {
            luaResult = redisStockService.preDeduct(
                    request.activityId(), request.skuId(), userId, request.requestId(),
                    qty, idemTtlSeconds
            );
        } catch (BusinessException e) {
            log.warn("placeOrder Redis preDeduct business fail userId={} requestId={} skuId={} code={} msg={} costMs.lua={} costMs.total={}",
                    userId, request.requestId(), request.skuId(), e.getErrorCode(), e.getMessage(),
                    elapsedMs(tLua), elapsedMs(tTotal));
            throw e;
        } catch (Exception e) {
            log.error("placeOrder Redis preDeduct unexpected userId={} requestId={} skuId={} costMs.lua={} costMs.total={}",
                    userId, request.requestId(), request.skuId(), elapsedMs(tLua), elapsedMs(tTotal), e);
            throw new BusinessException(ErrorCode.DEPENDENCY_REDIS_UNAVAILABLE);
        }
        long costLuaMs = elapsedMs(tLua);

        log.info("placeOrder Lua pre-deduct userId={} requestId={} skuId={} code={} success={} idempotentHit={} costMs.lua={}",
                userId, request.requestId(), request.skuId(), luaResult.code(), luaResult.success(),
                luaResult.idempotentHit(), costLuaMs);

        // 2. 幂等命中：有单则返回；无单（写库慢/崩溃窗口）直接成功提示查列表，绝不回滚 Redis
        if (luaResult.idempotentHit()) {
            SeckillOrder existed = orderMapper.findByUserAndRequest(userId, request.requestId());
            if (existed != null) {
                log.info("placeOrder idempotent hit with order orderId={} userId={} requestId={} skuId={} costMs.sentinel={} costMs.lua={} costMs.total={}",
                        existed.getId(), userId, request.requestId(), request.skuId(),
                        costSentinelMs, costLuaMs, elapsedMs(tTotal));
                return PlaceOrderResult.of(toResponse(existed));
            }
            log.warn("placeOrder idempotent hit without order yet userId={} requestId={} skuId={} detail={} costMs.lua={} costMs.total={}",
                    userId, request.requestId(), request.skuId(), luaResult.detail(), costLuaMs, elapsedMs(tTotal));
            return PlaceOrderResult.checkOrderList();
        }
        if (!luaResult.success()) {
            log.warn("placeOrder Lua reject userId={} requestId={} skuId={} code={} detail={} costMs.lua={} costMs.total={}",
                    userId, request.requestId(), request.skuId(), luaResult.code(), luaResult.detail(),
                    costLuaMs, elapsedMs(tTotal));
            throw mapLuaError(luaResult);
        }

        // 3. 事务写订单 + Outbox（MySQL 熔断；失败保留 ZSet HELD，由 Job 补单或回补）
        long tMysql = System.nanoTime();
        OrderOutboxCreated created;
        try {
            created = mysqlCircuitExecutor.execute(() -> {
                SeckillSkuStock stock = stockMapper.findByActivityAndSku(request.activityId(), request.skuId());
                if (stock == null) {
                    throw new BusinessException(ErrorCode.ACTIVITY_NOT_FOUND);
                }
                LocalDateTime now = LocalDateTime.now();
                return transactionTemplate.execute(status ->
                        createOrderAndOutbox(userId, request, stock, qty, now));
            });
        } catch (BusinessException e) {
            log.warn("placeOrder MySQL write business fail userId={} requestId={} skuId={} code={} msg={} costMs.mysql={} costMs.total={}",
                    userId, request.requestId(), request.skuId(), e.getErrorCode(), e.getMessage(),
                    elapsedMs(tMysql), elapsedMs(tTotal));
            // 不入 MySQL 补偿队列：HELD 已在 ZSet，Job 到期处理；仅审计
            auditCompensate(userId, request, qty, "HELD_PENDING",
                    "mysql write fail, leave ZSet: " + e.getErrorCode() + " " + e.getMessage());
            throw e;
        } catch (Exception e) {
            DuplicateKeyException dup = findDuplicateKey(e);
            if (dup != null) {
                log.warn("placeOrder DuplicateKeyException userId={} requestId={} skuId={} costMs.mysql={}",
                        userId, request.requestId(), request.skuId(), elapsedMs(tMysql), dup);
                SeckillOrder dupOrder = orderMapper.findByUserAndRequest(userId, request.requestId());
                if (dupOrder != null) {
                    redisStockService.confirmPreDeduct(userId, request.requestId());
                    log.info("placeOrder duplicate key, return existing orderId={} requestId={} costMs.total={}",
                            dupOrder.getId(), request.requestId(), elapsedMs(tTotal));
                    return PlaceOrderResult.of(toResponse(dupOrder));
                }
                // Redis 已幂等，不会双扣；订单暂不可见 → 成功语义，查列表
                return PlaceOrderResult.checkOrderList();
            }
            log.error("placeOrder create order failed userId={} requestId={} skuId={} costMs.mysql={} costMs.total={}",
                    userId, request.requestId(), request.skuId(), elapsedMs(tMysql), elapsedMs(tTotal), e);
            auditCompensate(userId, request, qty, "HELD_PENDING",
                    "mysql write fail, leave ZSet: " + e.getMessage());
            throw new BusinessException(ErrorCode.DEPENDENCY_MYSQL_UNAVAILABLE, "下单写库失败");
        }
        long costMysqlMs = elapsedMs(tMysql);

        if (created == null || created.order() == null) {
            log.error("placeOrder create order returned null userId={} requestId={} skuId={} costMs.mysql={} costMs.total={}",
                    userId, request.requestId(), request.skuId(), costMysqlMs, elapsedMs(tTotal));
            auditCompensate(userId, request, qty, "HELD_PENDING", "mysql write returned null, leave ZSet");
            throw new BusinessException(ErrorCode.DEPENDENCY_MYSQL_UNAVAILABLE, "下单写库失败");
        }

        SeckillOrder order = created.order();

        // 订单落库成功：确认预扣并从待补偿 ZSet 移除（失败由 Job 愈合）
        redisStockService.confirmPreDeduct(userId, request.requestId());

        log.info("placeOrder order+outbox created orderId={} userId={} requestId={} skuId={} outboxId={} costMs.mysql={}",
                order.getId(), userId, request.requestId(), request.skuId(), created.outboxId(), costMysqlMs);

        // 4. 异步提交 Kafka；Outbox 状态在回调中更新；失败保留 Outbox 由补偿中继
        StockDeductEvent event = new StockDeductEvent(
                order.getId(), userId, request.requestId(),
                request.activityId(), request.skuId(), qty
        );
        long tKafka = System.nanoTime();
        long costKafkaSubmitMs;
        try {
            stockDeductProducer.sendAsync(event, created.outboxId());
            costKafkaSubmitMs = elapsedMs(tKafka);
            log.info("placeOrder Kafka submit ok orderId={} requestId={} skuId={} outboxId={} costMs.kafkaSubmit={}",
                    order.getId(), request.requestId(), request.skuId(), created.outboxId(), costKafkaSubmitMs);
        } catch (BusinessException e) {
            costKafkaSubmitMs = elapsedMs(tKafka);
            log.warn("placeOrder Kafka submit failed, keep outbox for compensate orderId={} userId={} requestId={} skuId={} code={} costMs.kafkaSubmit={}",
                    order.getId(), userId, request.requestId(), request.skuId(), e.getErrorCode(), costKafkaSubmitMs);
        } catch (Exception e) {
            costKafkaSubmitMs = elapsedMs(tKafka);
            log.error("placeOrder Kafka submit unexpected orderId={} userId={} requestId={} skuId={} costMs.kafkaSubmit={}",
                    order.getId(), userId, request.requestId(), request.skuId(), costKafkaSubmitMs, e);
        }

        long costTotalMs = elapsedMs(tTotal);
        log.info("placeOrder timing userId={} requestId={} orderId={} costMs.sentinel={} costMs.lua={} costMs.mysql={} costMs.kafkaSubmit={} costMs.total={}",
                userId, request.requestId(), order.getId(),
                costSentinelMs, costLuaMs, costMysqlMs, costKafkaSubmitMs, costTotalMs);

        return PlaceOrderResult.of(toResponse(order));
    }

    /** 事务内插入订单与 STOCK_DEDUCT Outbox；失败整单回滚。 */
    private OrderOutboxCreated createOrderAndOutbox(Long userId, PlaceOrderRequest request,
                                                    SeckillSkuStock stock, int qty, LocalDateTime now) {
        SeckillOrder order = new SeckillOrder();
        order.setUserId(userId);
        order.setRequestId(request.requestId());
        order.setActivityId(request.activityId());
        order.setSkuId(request.skuId());
        order.setQty(qty);
        order.setAmountCent(stock.getUnitPriceCent() * qty);
        order.setStatus(OrderStatus.PROCESSING.name());
        order.setVersion(0);
        order.setPayDeadline(now.plusMinutes(payWindowMinutes));
        order.setStockCompensated(false);
        order.setCreatedAt(now);
        order.setUpdatedAt(now);
        orderMapper.insert(order);

        StockDeductEvent event = new StockDeductEvent(
                order.getId(), userId, request.requestId(),
                request.activityId(), request.skuId(), qty
        );
        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            log.error("placeOrder Outbox serialize failed orderId={} requestId={} skuId={}",
                    order.getId(), request.requestId(), request.skuId(), e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "Outbox 序列化失败");
        }

        SeckillOutbox outbox = new SeckillOutbox();
        outbox.setAggregateType("ORDER");
        outbox.setAggregateId(order.getId());
        outbox.setEventType("STOCK_DEDUCT");
        outbox.setPayload(payload);
        outbox.setStatus(OutboxStatus.NEW);
        outbox.setNextRetryAt(now);
        outbox.setRetryCount(0);
        outbox.setCreatedAt(now);
        outbox.setUpdatedAt(now);
        outboxMapper.insert(outbox);
        return new OrderOutboxCreated(order, outbox.getId());
    }

    /**
     * 审计日志（非队列）。写库失败时 HELD 已在 Redis ZSet，Job 负责收敛。
     */
    private void auditCompensate(Long userId, PlaceOrderRequest request, int qty, String action, String detail) {
        try {
            LocalDateTime now = LocalDateTime.now();
            RedisCompensateLog logEntity = new RedisCompensateLog();
            logEntity.setCompensateToken(UUID.randomUUID().toString().replace("-", ""));
            logEntity.setUserId(userId);
            logEntity.setRequestId(request.requestId());
            logEntity.setActivityId(request.activityId());
            logEntity.setSkuId(request.skuId());
            logEntity.setQty(qty);
            logEntity.setAction(action);
            logEntity.setStatus("DONE");
            logEntity.setDetail(detail == null ? null : detail.substring(0, Math.min(detail.length(), 500)));
            logEntity.setCreatedAt(now);
            logEntity.setUpdatedAt(now);
            compensateLogMapper.insert(logEntity);
            log.warn("placeOrder compensate audit action={} userId={} requestId={} skuId={}",
                    action, userId, request.requestId(), request.skuId());
        } catch (Exception e) {
            log.error("placeOrder write compensate audit failed userId={} requestId={} skuId={}",
                    userId, request.requestId(), request.skuId(), e);
        }
    }

    private static long elapsedMs(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    private static DuplicateKeyException findDuplicateKey(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            if (cur instanceof DuplicateKeyException dup) {
                return dup;
            }
            cur = cur.getCause();
        }
        return null;
    }

    /**
     * 活动必须存在且处于开抢窗口：{@code startTime <= now < endTime}。
     * @throws BusinessException ACTIVITY_NOT_FOUND / ACTIVITY_NOT_STARTED / ACTIVITY_ENDED
     */
    private void assertActivityWindowOpen(Long activityId) {
        SeckillActivity activity = activityMapper.findById(activityId);
        if (activity == null || Objects.equals(activity.getStatus(), ActivityStatus.OFFLINE)) {
            throw new BusinessException(ErrorCode.ACTIVITY_NOT_FOUND);
        }
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(activity.getStartTime())) {
            log.info("placeOrder reject ACTIVITY_NOT_STARTED activityId={} startTime={}",
                    activityId, activity.getStartTime());
            throw new BusinessException(ErrorCode.ACTIVITY_NOT_STARTED);
        }
        if (!now.isBefore(activity.getEndTime())) {
            log.info("placeOrder reject ACTIVITY_ENDED activityId={} endTime={}",
                    activityId, activity.getEndTime());
            throw new BusinessException(ErrorCode.ACTIVITY_ENDED);
        }
    }

    /** 将 Lua 负错误码映射为业务 ErrorCode（与 pre_deduct.lua 约定一致）。 */
    private BusinessException mapLuaError(LuaPreDeductResult result) {
        return switch (result.code()) {
            case -1 -> new BusinessException(ErrorCode.STOCK_INSUFFICIENT);
            case -2 -> new BusinessException(ErrorCode.USER_LIMIT_EXCEEDED);
            case -3 -> new BusinessException(ErrorCode.SALE_DISABLED);
            case -4 -> new BusinessException(ErrorCode.WARMUP_NOT_READY);
            default -> new BusinessException(ErrorCode.SYSTEM_ERROR, "预扣失败: " + result.detail());
        };
    }

    /**
     * 查单：仅本人可见。
     * @throws BusinessException ORDER_NOT_FOUND
     */
    public OrderResponse getOrder(Long userId, Long orderId) {
        SeckillOrder order = orderMapper.findById(orderId);
        if (order == null || !order.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.ORDER_NOT_FOUND);
        }
        return toResponse(order);
    }

    /** 当前用户最近订单（最多 50）。 */
    public List<OrderResponse> listOrders(Long userId) {
        return orderMapper.findByUserId(userId, 50).stream().map(this::toResponse).toList();
    }

    /**
     * 用户手动取消。
     * <ul>
     *   <li>PROCESSING：MySQL 库存尚未扣，只回补 Redis</li>
     *   <li>PENDING_PAY：MySQL 已扣，需同时回补 MySQL + Redis</li>
     * </ul>
     * 乐观锁更新失败视为被其它路径抢先（超时/系统取消），直接返回最新状态。
     */
    public OrderResponse cancel(Long userId, Long orderId) {
        SeckillOrder order = orderMapper.findById(orderId);
        if (order == null || !order.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.ORDER_NOT_FOUND);
        }
        OrderStatus status = OrderStatus.valueOf(order.getStatus());
        if (!status.canCancel()) {
            throw new BusinessException(ErrorCode.ORDER_STATUS_INVALID);
        }
        if (status == OrderStatus.PROCESSING) {
            // MySQL 尚未扣库存：只回补 Redis 预扣
            int updated = orderMapper.updateStatusAndCompensate(
                    order.getId(), OrderStatus.PROCESSING.name(),
                    OrderStatus.CANCELED_MANUAL.name(), true, order.getVersion()
            );
            if (updated == 1) {
                log.info("cancel order PROCESSING->CANCELED_MANUAL orderId={} requestId={} skuId={}",
                        orderId, order.getRequestId(), order.getSkuId());
                rollbackRedisAndMaybeMysql(order, false);
            }
        } else if (status == OrderStatus.PENDING_PAY) {
            // 已异步扣过 MySQL：两边库存都要回
            int updated = orderMapper.updateStatusAndCompensate(
                    order.getId(), OrderStatus.PENDING_PAY.name(),
                    OrderStatus.CANCELED_MANUAL.name(), true, order.getVersion()
            );
            if (updated == 1) {
                log.info("cancel order PENDING_PAY->CANCELED_MANUAL orderId={} requestId={} skuId={}",
                        orderId, order.getRequestId(), order.getSkuId());
                rollbackRedisAndMaybeMysql(order, true);
            }
        }
        return toResponse(orderMapper.findById(orderId));
    }

    /**
     * 取消后的库存回补。
     * @param rollbackMysql true 表示消费者已扣过 MySQL，需一并加回
     */
    private void rollbackRedisAndMaybeMysql(SeckillOrder order, boolean rollbackMysql) {
        if (rollbackMysql) {
            stockMapper.rollbackStock(order.getActivityId(), order.getSkuId(), order.getQty());
        }
        // Redis：rollback.lua 原子回补；失败则 enqueue_pending.lua 原子重新入队
        try {
            boolean rolledBack = redisStockService.rollbackOrEnqueuePending(
                    order.getActivityId(), order.getSkuId(), order.getUserId(),
                    order.getRequestId(), order.getQty()
            );
            if (rolledBack) {
                log.info("rollback redis done orderId={} requestId={} skuId={} rollbackMysql={}",
                        order.getId(), order.getRequestId(), order.getSkuId(), rollbackMysql);
            } else {
                log.error("rollback redis failed, re-enqueued pending orderId={} requestId={} skuId={}",
                        order.getId(), order.getRequestId(), order.getSkuId());
                PlaceOrderRequest fake = new PlaceOrderRequest(
                        order.getActivityId(), order.getSkuId(), order.getRequestId(), order.getQty()
                );
                auditCompensate(order.getUserId(), fake, order.getQty(), "ROLLBACK_STOCK",
                        "sync rollback fail, re-enqueue ZSet via Lua");
            }
        } catch (Exception e) {
            log.error("rollback redis+enqueue failed orderId={} requestId={} skuId={}",
                    order.getId(), order.getRequestId(), order.getSkuId(), e);
        }
    }

    public OrderResponse toResponse(SeckillOrder order) {
        return new OrderResponse(
                order.getId(),
                order.getUserId(),
                order.getRequestId(),
                order.getActivityId(),
                order.getSkuId(),
                order.getQty(),
                order.getAmountCent(),
                order.getStatus(),
                order.getPayDeadline(),
                order.getCreatedAt()
        );
    }

    /** 热路径事务结果：订单 + Outbox 主键（供异步 Kafka 回调更新状态）。 */
    private record OrderOutboxCreated(SeckillOrder order, Long outboxId) {
    }
}
