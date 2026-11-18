package com.presence.seckill.infra.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.presence.seckill.domain.event.StockDeductEvent;
import com.presence.seckill.domain.model.SeckillActivity;
import com.presence.seckill.domain.model.SeckillOrder;
import com.presence.seckill.domain.order.OrderStatus;
import com.presence.seckill.domain.model.RedisCompensateLog;
import com.presence.seckill.infra.mapper.RedisCompensateLogMapper;
import com.presence.seckill.infra.mapper.SeckillActivityMapper;
import com.presence.seckill.infra.mapper.SeckillOrderMapper;
import com.presence.seckill.infra.mapper.SeckillSkuStockMapper;
import com.presence.seckill.infra.redis.SeckillRedisStockService;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 库存扣减事件消费者（异步落 MySQL 库存）。
 * <p>成功：条件扣库存 → 订单 PROCESSING→PENDING_PAY，再 MANUAL ack。
 * <p>失败（MySQL 无库存 / 活动已结束）：系统取消 + Redis {@code rollbackOrEnqueuePending}（Lua 回补或原子重新入队）。
 * <p>ACK 策略：业务成功后 {@code ack.acknowledge()}；异常不 ACK，依赖 Kafka 重投（配合 MANUAL 模式）。
 */
@Slf4j
@Component
public class StockDeductConsumer {

    private final ObjectMapper objectMapper;
    private final SeckillOrderMapper orderMapper;
    private final SeckillSkuStockMapper stockMapper;
    private final SeckillActivityMapper activityMapper;
    private final SeckillRedisStockService redisStockService;
    private final RedisCompensateLogMapper compensateLogMapper;

    public StockDeductConsumer(ObjectMapper objectMapper,
                               SeckillOrderMapper orderMapper,
                               SeckillSkuStockMapper stockMapper,
                               SeckillActivityMapper activityMapper,
                               SeckillRedisStockService redisStockService,
                               RedisCompensateLogMapper compensateLogMapper) {
        this.objectMapper = objectMapper;
        this.orderMapper = orderMapper;
        this.stockMapper = stockMapper;
        this.activityMapper = activityMapper;
        this.redisStockService = redisStockService;
        this.compensateLogMapper = compensateLogMapper;
    }

    /**
     * 监听库存扣减 Topic。处理成功才 ACK；抛错不 ACK 以便重投。
     */
    @KafkaListener(topics = KafkaTopics.STOCK_DEDUCT, groupId = "seckill-stock-deduct")
    @Transactional
    public void onMessage(ConsumerRecord<String, String> record, Acknowledgment ack) {
        try {
            StockDeductEvent event = objectMapper.readValue(record.value(), StockDeductEvent.class);
            log.info("consume stock-deduct receive orderId={} requestId={} skuId={} offset={}",
                    event.orderId(), event.requestId(), event.skuId(), record.offset());
            process(event);
            ack.acknowledge();
            log.info("consume stock-deduct ack orderId={} requestId={} skuId={} offset={}",
                    event.orderId(), event.requestId(), event.skuId(), record.offset());
        } catch (Exception e) {
            log.error("consume stock-deduct failed, will retry offset={}", record.offset(), e);
            // 不 ACK，等待重投
            throw new IllegalStateException("consume failed", e);
        }
    }

    /**
     * 核心业务：把 Redis 已预扣的库存同步到 MySQL，并推进订单状态。
     * 可被 Outbox 补偿路径复用（同事件幂等）。
     */
    public void process(StockDeductEvent event) {
        SeckillOrder order = orderMapper.findById(event.orderId());
        if (order == null) {
            log.warn("consume order missing, skip orderId={} requestId={} skuId={}",
                    event.orderId(), event.requestId(), event.skuId());
            return;
        }
        // 幂等闸门：非 PROCESSING 表示已成功/已取消，重复消息直接吞掉
        if (!OrderStatus.PROCESSING.name().equals(order.getStatus())) {
            log.info("consume skip non-PROCESSING orderId={} status={} requestId={}",
                    order.getId(), order.getStatus(), event.requestId());
            return;
        }

        // 活动已结束：不走扣库成功路径，系统取消并回补 Redis（MySQL 尚未扣）
        if (isActivityExpired(event.activityId())) {
            log.warn("consume activity expired, cancel order orderId={} requestId={} activityId={} skuId={}",
                    order.getId(), event.requestId(), event.activityId(), event.skuId());
            cancelProcessingAndRollbackRedis(order, event,
                    "activity ended before MySQL deduct");
            return;
        }

        int deducted = stockMapper.deductStock(event.activityId(), event.skuId(), event.qty());
        if (deducted == 1) {
            log.info("consume MySQL stock deduct success orderId={} requestId={} skuId={} qty={}",
                    order.getId(), event.requestId(), event.skuId(), event.qty());
            int updated = orderMapper.updateStatusOptimistic(
                    order.getId(),
                    OrderStatus.PROCESSING.name(),
                    OrderStatus.PENDING_PAY.name(),
                    order.getVersion()
            );
            if (updated == 0) {
                // 状态已被取消/超时抢先变更：撤销本次 MySQL 扣减，避免超卖回补遗漏
                stockMapper.rollbackStock(event.activityId(), event.skuId(), event.qty());
                log.warn("consume status race, rolled back MySQL stock orderId={} requestId={} skuId={}",
                        order.getId(), event.requestId(), event.skuId());
            } else {
                log.info("consume status PROCESSING->PENDING_PAY orderId={} requestId={} skuId={}",
                        order.getId(), event.requestId(), event.skuId());
            }
            return;
        }

        log.warn("consume MySQL stock deduct fail, stop-sale orderId={} requestId={} skuId={} qty={}",
                order.getId(), event.requestId(), event.skuId(), event.qty());

        // Redis/MySQL 库存不一致时：事件驱动停售，防止继续卖空，并取消本单回补 Redis
        stockMapper.disableSale(event.activityId(), event.skuId());
        try {
            redisStockService.disableSale(event.activityId(), event.skuId());
            log.info("consume stop-sale marked activityId={} skuId={}", event.activityId(), event.skuId());
        } catch (Exception ignored) {
            // 停售标记失败不阻断取消
            log.warn("consume redis stop-sale failed activityId={} skuId={}", event.activityId(), event.skuId());
        }

        cancelProcessingAndRollbackRedis(order, event, "consumer mysql stock fail");
    }

    /** {@code now >= endTime} 或活动不存在 → 视为不可再扣库成功。 */
    private boolean isActivityExpired(Long activityId) {
        SeckillActivity activity = activityMapper.findById(activityId);
        if (activity == null) {
            log.warn("consume activity missing, treat as expired activityId={}", activityId);
            return true;
        }
        LocalDateTime now = LocalDateTime.now();
        boolean ended = !now.isBefore(activity.getEndTime());
        if (ended) {
            log.info("consume activity window closed activityId={} endTime={} now={}",
                    activityId, activity.getEndTime(), now);
        }
        return ended;
    }

    /**
     * PROCESSING → CANCELED_SYSTEM，并回补 Redis（MySQL 未扣则无需 rollbackStock）。
     * 与扣库失败路径共用状态机规则。
     */
    private void cancelProcessingAndRollbackRedis(SeckillOrder order, StockDeductEvent event, String reason) {
        int canceled = orderMapper.updateStatusAndCompensate(
                order.getId(),
                OrderStatus.PROCESSING.name(),
                OrderStatus.CANCELED_SYSTEM.name(),
                true,
                order.getVersion()
        );
        if (canceled != 1) {
            log.warn("consume cancel race, skip redis rollback orderId={} requestId={} reason={}",
                    order.getId(), event.requestId(), reason);
            return;
        }
        log.info("consume status PROCESSING->CANCELED_SYSTEM orderId={} requestId={} skuId={} reason={}",
                order.getId(), event.requestId(), event.skuId(), reason);
        // Redis：rollback.lua 原子回补；失败则 enqueue_pending.lua 原子重新入队
        try {
            boolean rolledBack = redisStockService.rollbackOrEnqueuePending(
                    event.activityId(), event.skuId(), event.userId(),
                    event.requestId(), event.qty()
            );
            if (rolledBack) {
                log.info("consume redis rollback after cancel orderId={} requestId={} skuId={} reason={}",
                        order.getId(), event.requestId(), event.skuId(), reason);
            } else {
                log.error("consume Redis rollback failed, re-enqueued pending orderId={} requestId={} reason={}",
                        order.getId(), event.requestId(), reason);
                LocalDateTime now = LocalDateTime.now();
                RedisCompensateLog entity = new RedisCompensateLog();
                entity.setCompensateToken(UUID.randomUUID().toString().replace("-", ""));
                entity.setUserId(event.userId());
                entity.setRequestId(event.requestId());
                entity.setActivityId(event.activityId());
                entity.setSkuId(event.skuId());
                entity.setQty(event.qty());
                entity.setAction("ROLLBACK_STOCK");
                entity.setStatus("DONE");
                entity.setDetail(reason + ", re-enqueue ZSet via Lua");
                entity.setCreatedAt(now);
                entity.setUpdatedAt(now);
                try {
                    compensateLogMapper.insert(entity);
                } catch (Exception insertEx) {
                    log.error("consume write compensate audit failed orderId={}", order.getId(), insertEx);
                }
            }
        } catch (Exception e) {
            log.error("consume Redis rollback+enqueue failed orderId={} requestId={} reason={}",
                    order.getId(), event.requestId(), reason, e);
        }
    }
}
