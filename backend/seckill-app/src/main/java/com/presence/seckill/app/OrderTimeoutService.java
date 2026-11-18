package com.presence.seckill.app;

import com.presence.seckill.domain.model.RedisCompensateLog;
import com.presence.seckill.domain.model.SeckillOrder;
import com.presence.seckill.domain.order.OrderStatus;
import com.presence.seckill.infra.mapper.RedisCompensateLogMapper;
import com.presence.seckill.infra.mapper.SeckillOrderMapper;
import com.presence.seckill.infra.mapper.SeckillSkuStockMapper;
import com.presence.seckill.infra.redis.SeckillRedisStockService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 订单超时对账：扫描过 payDeadline 的 PENDING_PAY → CANCELED_TIMEOUT，并回补 MySQL + Redis（含限购计数）。
 * <p>由 {@link com.presence.seckill.app.job.SeckillJobs#orderTimeout()} 周期性触发。
 * <p>Redis 回补走 {@code rollbackOrEnqueuePending}：成功=rollback.lua；失败=enqueue_pending.lua 原子入队。
 * {@code redis_compensate_log} 仅审计。
 */
@Slf4j
@Service
public class OrderTimeoutService {

    private final SeckillOrderMapper orderMapper;
    private final SeckillSkuStockMapper stockMapper;
    private final SeckillRedisStockService redisStockService;
    private final RedisCompensateLogMapper compensateLogMapper;

    public OrderTimeoutService(SeckillOrderMapper orderMapper,
                               SeckillSkuStockMapper stockMapper,
                               SeckillRedisStockService redisStockService,
                               RedisCompensateLogMapper compensateLogMapper) {
        this.orderMapper = orderMapper;
        this.stockMapper = stockMapper;
        this.redisStockService = redisStockService;
        this.compensateLogMapper = compensateLogMapper;
    }

    /**
     * @param limit 单批上限
     * @return 本批成功取消数（乐观锁冲突不计）
     */
    @Transactional
    public int cancelTimeoutOrders(int limit) {
        List<SeckillOrder> orders = orderMapper.findTimeoutPendingPay(LocalDateTime.now(), limit);
        int count = 0;
        for (SeckillOrder order : orders) {
            if (cancelOne(order)) {
                count++;
            }
        }
        return count;
    }

    private boolean cancelOne(SeckillOrder order) {
        int updated = orderMapper.updateStatusAndCompensate(
                order.getId(),
                OrderStatus.PENDING_PAY.name(),
                OrderStatus.CANCELED_TIMEOUT.name(),
                true,
                order.getVersion()
        );
        if (updated == 0) {
            return false;
        }
        log.info("timeout cancel orderId={} requestId={} skuId={}",
                order.getId(), order.getRequestId(), order.getSkuId());
        stockMapper.rollbackStock(order.getActivityId(), order.getSkuId(), order.getQty());
        // Redis：rollback.lua 原子回补；失败则 enqueue_pending.lua 原子重新入队（勿拆成多次 Java Redis 写）
        try {
            boolean rolledBack = redisStockService.rollbackOrEnqueuePending(
                    order.getActivityId(), order.getSkuId(), order.getUserId(),
                    order.getRequestId(), order.getQty()
            );
            if (!rolledBack) {
                log.error("timeout cancel redis rollback failed, re-enqueued pending orderId={} requestId={} skuId={}",
                        order.getId(), order.getRequestId(), order.getSkuId());
                LocalDateTime now = LocalDateTime.now();
                RedisCompensateLog entity = new RedisCompensateLog();
                entity.setCompensateToken(UUID.randomUUID().toString().replace("-", ""));
                entity.setUserId(order.getUserId());
                entity.setRequestId(order.getRequestId());
                entity.setActivityId(order.getActivityId());
                entity.setSkuId(order.getSkuId());
                entity.setQty(order.getQty());
                entity.setAction("ROLLBACK_STOCK");
                entity.setStatus("DONE");
                entity.setDetail("timeout cancel sync fail, re-enqueue ZSet via Lua");
                entity.setCreatedAt(now);
                entity.setUpdatedAt(now);
                try {
                    compensateLogMapper.insert(entity);
                } catch (Exception insertEx) {
                    log.error("timeout write compensate audit failed orderId={}", order.getId(), insertEx);
                }
            }
        } catch (Exception e) {
            log.error("timeout cancel redis rollback+enqueue failed orderId={} requestId={} skuId={}",
                    order.getId(), order.getRequestId(), order.getSkuId(), e);
        }
        return true;
    }
}
