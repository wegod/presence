package com.presence.seckill.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.presence.seckill.domain.event.StockDeductEvent;
import com.presence.seckill.domain.model.RedisCompensateLog;
import com.presence.seckill.domain.model.SeckillOrder;
import com.presence.seckill.domain.model.SeckillOutbox;
import com.presence.seckill.domain.model.SeckillSkuStock;
import com.presence.seckill.domain.order.OrderStatus;
import com.presence.seckill.domain.order.OutboxStatus;
import com.presence.seckill.domain.order.PreDeductState;
import com.presence.seckill.infra.mapper.RedisCompensateLogMapper;
import com.presence.seckill.infra.mapper.SeckillOrderMapper;
import com.presence.seckill.infra.mapper.SeckillOutboxMapper;
import com.presence.seckill.infra.mapper.SeckillSkuStockMapper;
import com.presence.seckill.infra.redis.PreDeductRecord;
import com.presence.seckill.infra.redis.SeckillRedisStockService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Redis 预扣补偿：直接扫描待补偿 ZSet（{@code seckill:pre-deduct:pending}），不驱动于 MySQL 补偿表。
 * <p>规则（见方法内中文注释）：
 * <ul>
 *   <li>订单已存在且为活跃态 → 确认预扣 CONFIRMED + ZREM</li>
 *   <li>订单已取消 → 回补 Redis（幂等）+ ROLLED_BACK + ZREM</li>
 *   <li>无订单且仍在补单宽限内、记录仍为 HELD → 幂等补建 PROCESSING+Outbox</li>
 *   <li>无订单且超出宽限 → 回补库存 + ROLLED_BACK</li>
 * </ul>
 * {@link RedisCompensateLog} 仅作审计追加，不是工作队列。
 */
@Slf4j
@Service
public class RedisCompensateService {

    private static final Set<String> ACTIVE_ORDER_STATUSES = Set.of(
            OrderStatus.PROCESSING.name(),
            OrderStatus.PENDING_PAY.name(),
            OrderStatus.PAID.name()
    );

    private final SeckillRedisStockService redisStockService;
    private final SeckillOrderMapper orderMapper;
    private final SeckillOutboxMapper outboxMapper;
    private final SeckillSkuStockMapper stockMapper;
    private final RedisCompensateLogMapper compensateLogMapper;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private final long createGraceMs;
    private final int payWindowMinutes;

    public RedisCompensateService(SeckillRedisStockService redisStockService,
                                  SeckillOrderMapper orderMapper,
                                  SeckillOutboxMapper outboxMapper,
                                  SeckillSkuStockMapper stockMapper,
                                  RedisCompensateLogMapper compensateLogMapper,
                                  TransactionTemplate transactionTemplate,
                                  ObjectMapper objectMapper,
                                  @Value("${seckill.pre-deduct.create-grace-ms:10000}") long createGraceMs,
                                  @Value("${seckill.pay-window-minutes:1}") int payWindowMinutes) {
        this.redisStockService = redisStockService;
        this.orderMapper = orderMapper;
        this.outboxMapper = outboxMapper;
        this.stockMapper = stockMapper;
        this.compensateLogMapper = compensateLogMapper;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
        this.createGraceMs = createGraceMs;
        this.payWindowMinutes = payWindowMinutes;
    }

    /**
     * @return 本批成功收敛（确认或回补或补单）条数；失败项已重新调度 score
     * <p>领取：Java ZRANGEBYSCORE（+MVP 逐条租约 ZADD）；回补/确认：rollback.lua / confirm.lua。
     */
    public int compensate(int limit) {
        List<String> due = redisStockService.claimDuePending(limit);
        int done = 0;
        for (String member : due) {
            try {
                if (processOne(member)) {
                    done++;
                }
            } catch (Exception e) {
                log.warn("redis compensate failed member={}", member, e);
                audit(member, null, null, null, null, null, "COMPENSATE", "FAILED",
                        e.getMessage() == null ? "exception" : e.getMessage());
                redisStockService.reschedulePending(member, System.currentTimeMillis() + redisStockService.getClaimLeaseMs());
            }
        }
        return done;
    }

    /**
     * 处理单条到期预扣。
     *
     * @return true 表示本轮已收敛（确认/回补/补单成功）
     */
    private boolean processOne(String member) {
        String[] parts = PreDeductRecord.parseMember(member);
        if (parts == null) {
            log.error("redis compensate invalid member={}", member);
            redisStockService.removePending(member);
            return false;
        }
        Long userId = Long.parseLong(parts[0]);
        String requestId = parts[1];

        PreDeductRecord record = redisStockService.getPreDeductRecord(userId, requestId);
        if (record == null) {
            // 无记录的滞留索引：直接摘掉
            log.warn("redis compensate missing record, ZREM member={}", member);
            redisStockService.removePending(member);
            return true;
        }
        if (record.getState() == PreDeductState.ROLLED_BACK) {
            redisStockService.removePending(member);
            return true;
        }

        SeckillOrder order = orderMapper.findByUserAndRequest(userId, requestId);

        // 1) 活跃订单存在 → 确认预扣（热路径 ZREM 丢失时由 Job 愈合）
        if (order != null && ACTIVE_ORDER_STATUSES.contains(order.getStatus())) {
            redisStockService.markConfirmedAndRemovePending(userId, requestId);
            audit(member, userId, requestId, record.getActivityId(), record.getSkuId(), record.getQty(),
                    "CONFIRM", "DONE", "order exists status=" + order.getStatus());
            log.info("redis compensate confirmed member={} orderId={} status={}",
                    member, order.getId(), order.getStatus());
            return true;
        }

        // 2) 订单已取消（含系统/超时/手动）→ 回补 Redis 库存与限购
        if (order != null) {
            return rollbackHeld(member, record, "order canceled status=" + order.getStatus());
        }

        // 3) 无订单：宽限内尝试幂等补建 PROCESSING+Outbox；否则回补库存
        // 规则：仅当记录仍为 HELD（Lua 预扣成功且尚未确认）时才补单；其它状态只清理/回补。
        long now = System.currentTimeMillis();
        long created = record.getCreatedAtMs() == null ? now : record.getCreatedAtMs();
        boolean withinGrace = (now - created) <= createGraceMs;
        boolean held = record.getState() == null || record.getState() == PreDeductState.HELD;

        if (held && withinGrace) {
            boolean createdOrder = tryCreateOrderAndOutbox(record);
            if (createdOrder) {
                redisStockService.markConfirmedAndRemovePending(userId, requestId);
                audit(member, userId, requestId, record.getActivityId(), record.getSkuId(), record.getQty(),
                        "CREATE_ORDER", "DONE", "idempotent create PROCESSING+Outbox");
                log.info("redis compensate create-order ok member={}", member);
                return true;
            }
            // 补单暂失败：留在 ZSet（claim 已推迟），下次再试或等宽限过后回补
            log.warn("redis compensate create-order deferred member={}", member);
            audit(member, userId, requestId, record.getActivityId(), record.getSkuId(), record.getQty(),
                    "CREATE_ORDER", "FAILED", "create deferred, will retry or rollback after grace");
            return false;
        }

        if (held) {
            return rollbackHeld(member, record, "no order after grace");
        }

        // CONFIRMED 但无活跃订单且不在取消分支（极少）：摘索引
        redisStockService.removePending(member);
        audit(member, userId, requestId, record.getActivityId(), record.getSkuId(), record.getQty(),
                "DISCARD", "DONE", "state=" + record.getState());
        return true;
    }

    private boolean rollbackHeld(String member, PreDeductRecord record, String reason) {
        try {
            redisStockService.rollback(
                    record.getActivityId(), record.getSkuId(), record.getUserId(),
                    record.getRequestId(), record.getQty()
            );
            audit(member, record.getUserId(), record.getRequestId(), record.getActivityId(),
                    record.getSkuId(), record.getQty(), "ROLLBACK_STOCK", "DONE", reason);
            log.info("redis compensate rollback done member={} reason={}", member, reason);
            return true;
        } catch (Exception e) {
            log.error("redis compensate rollback failed member={} reason={}", member, reason, e);
            audit(member, record.getUserId(), record.getRequestId(), record.getActivityId(),
                    record.getSkuId(), record.getQty(), "ROLLBACK_STOCK", "FAILED",
                    reason + ": " + e.getMessage());
            redisStockService.reschedulePending(member, System.currentTimeMillis() + redisStockService.getClaimLeaseMs());
            return false;
        }
    }

    /**
     * 幂等补建：预扣仍 HELD、MySQL 尚无单时写入 PROCESSING+STOCK_DEDUCT Outbox。
     * DuplicateKey 视为并发已建成。
     */
    private boolean tryCreateOrderAndOutbox(PreDeductRecord record) {
        try {
            SeckillSkuStock stock = stockMapper.findByActivityAndSku(record.getActivityId(), record.getSkuId());
            if (stock == null) {
                log.error("redis compensate create-order sku missing activityId={} skuId={}",
                        record.getActivityId(), record.getSkuId());
                return false;
            }
            LocalDateTime now = LocalDateTime.now();
            transactionTemplate.executeWithoutResult(status -> {
                SeckillOrder order = new SeckillOrder();
                order.setUserId(record.getUserId());
                order.setRequestId(record.getRequestId());
                order.setActivityId(record.getActivityId());
                order.setSkuId(record.getSkuId());
                order.setQty(record.getQty());
                order.setAmountCent(stock.getUnitPriceCent() * record.getQty());
                order.setStatus(OrderStatus.PROCESSING.name());
                order.setVersion(0);
                order.setPayDeadline(now.plusMinutes(payWindowMinutes));
                order.setStockCompensated(false);
                order.setCreatedAt(now);
                order.setUpdatedAt(now);
                orderMapper.insert(order);

                StockDeductEvent event = new StockDeductEvent(
                        order.getId(), record.getUserId(), record.getRequestId(),
                        record.getActivityId(), record.getSkuId(), record.getQty()
                );
                String payload;
                try {
                    payload = objectMapper.writeValueAsString(event);
                } catch (Exception e) {
                    throw new IllegalStateException("Outbox serialize failed", e);
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
            });
            return true;
        } catch (DuplicateKeyException dup) {
            log.info("redis compensate create-order duplicate userId={} requestId={}",
                    record.getUserId(), record.getRequestId());
            return orderMapper.findByUserAndRequest(record.getUserId(), record.getRequestId()) != null;
        } catch (Exception e) {
            Throwable cur = e;
            while (cur != null) {
                if (cur instanceof DuplicateKeyException) {
                    return orderMapper.findByUserAndRequest(record.getUserId(), record.getRequestId()) != null;
                }
                cur = cur.getCause();
            }
            log.error("redis compensate create-order failed userId={} requestId={}",
                    record.getUserId(), record.getRequestId(), e);
            return false;
        }
    }

    /** 审计写入：status 用 DONE/FAILED，不再作为 PENDING 工作队列。 */
    private void audit(String member, Long userId, String requestId, Long activityId, Long skuId,
                       Integer qty, String action, String status, String detail) {
        try {
            LocalDateTime now = LocalDateTime.now();
            RedisCompensateLog entity = new RedisCompensateLog();
            entity.setCompensateToken(UUID.randomUUID().toString().replace("-", ""));
            entity.setUserId(userId == null ? 0L : userId);
            entity.setRequestId(requestId == null ? member : requestId);
            entity.setActivityId(activityId == null ? 0L : activityId);
            entity.setSkuId(skuId == null ? 0L : skuId);
            entity.setQty(qty == null ? 0 : qty);
            entity.setAction(action);
            entity.setStatus(status);
            entity.setDetail(detail == null ? null : detail.substring(0, Math.min(detail.length(), 500)));
            entity.setCreatedAt(now);
            entity.setUpdatedAt(now);
            compensateLogMapper.insert(entity);
        } catch (Exception e) {
            log.error("redis compensate audit insert failed member={} action={}", member, action, e);
        }
    }
}
