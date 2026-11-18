package com.presence.seckill.app.job;

import com.presence.seckill.app.ExpiredActivityCleanupService;
import com.presence.seckill.app.OutboxCompensateService;
import com.presence.seckill.app.OrderTimeoutService;
import com.presence.seckill.app.RedisCompensateService;
import com.presence.seckill.app.WarmupAppService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 秒杀后台定时任务入口。
 * <ul>
 *   <li>warmup：开场前把 MySQL 库存灌入 Redis</li>
 *   <li>outbox：Kafka 发送失败后的中继补发</li>
 *   <li>redis-compensate：扫描待补偿 ZSet，确认预扣 / 补单 / 回补库存</li>
 *   <li>timeout：待支付超时关单并回补库存</li>
 *   <li>expired-cleanup：活动结束后清理 Redis 活动域键（幂等）</li>
 * </ul>
 * 停售由扣库失败事件驱动，此处不做停售巡检。
 */
@Slf4j
@Component
public class SeckillJobs {

    private final WarmupAppService warmupAppService;
    private final OutboxCompensateService outboxCompensateService;
    private final RedisCompensateService redisCompensateService;
    private final OrderTimeoutService orderTimeoutService;
    private final ExpiredActivityCleanupService expiredActivityCleanupService;

    public SeckillJobs(WarmupAppService warmupAppService,
                       OutboxCompensateService outboxCompensateService,
                       RedisCompensateService redisCompensateService,
                       OrderTimeoutService orderTimeoutService,
                       ExpiredActivityCleanupService expiredActivityCleanupService) {
        this.warmupAppService = warmupAppService;
        this.outboxCompensateService = outboxCompensateService;
        this.redisCompensateService = redisCompensateService;
        this.orderTimeoutService = orderTimeoutService;
        this.expiredActivityCleanupService = expiredActivityCleanupService;
    }

    /** 扫描开场前 N 分钟内的活动并预热（默认每 30s，窗口 10 分钟）。 */
    @Scheduled(fixedDelayString = "${seckill.jobs.warmup-delay-ms:30000}")
    public void warmup() {
        log.info("job warmup start");
        int n = warmupAppService.warmupUpcoming(10);
        log.info("job warmup end warmedSkuCount={}", n);
    }

    /** Outbox 中继：把 NEW/FAILED 的 STOCK_DEDUCT 补发到 Kafka（默认约 2s）。 */
    @Scheduled(fixedDelayString = "${seckill.jobs.outbox-delay-ms:2000}")
    public void outboxCompensate() {
        log.debug("job outbox-relay start");
        int n = outboxCompensateService.compensate(50);
        if (n > 0) {
            log.info("job outbox-relay end sentCount={}", n);
        } else {
            log.debug("job outbox-relay end sentCount=0");
        }
    }

    /** 扫描 Redis 待补偿 ZSet（到期 HELD），确认订单 / 幂等补单 / 回补库存。 */
    @Scheduled(fixedDelayString = "${seckill.jobs.redis-compensate-delay-ms:5000}")
    public void redisCompensate() {
        log.debug("job redis-compensate start");
        int n = redisCompensateService.compensate(50);
        if (n > 0) {
            log.info("job redis-compensate end doneCount={}", n);
        } else {
            log.debug("job redis-compensate end doneCount=0");
        }
    }

    /** 支付超时对账：PENDING_PAY 过 payDeadline → CANCELED_TIMEOUT 并回补库存。 */
    @Scheduled(fixedDelayString = "${seckill.jobs.timeout-delay-ms:10000}")
    public void orderTimeout() {
        log.debug("job timeout-reconcile start");
        int n = orderTimeoutService.cancelTimeoutOrders(100);
        if (n > 0) {
            log.info("job timeout-reconcile end canceledCount={}", n);
        } else {
            log.debug("job timeout-reconcile end canceledCount=0");
        }
    }

    /**
     * 活动过期清理：扫描 {@code end_time <= now} 的活动，删除 Redis 中 stock/limit/sale-enabled/
     * warmup-token/user-bought 等活动域键。幂等可重复执行。
     */
    @Scheduled(fixedDelayString = "${seckill.jobs.expired-cleanup-delay-ms:60000}")
    public void expiredActivityCleanup() {
        log.debug("job expired-cleanup start");
        int n = expiredActivityCleanupService.cleanupExpired(50);
        if (n > 0) {
            log.info("job expired-cleanup end deletedKeys={}", n);
        } else {
            log.debug("job expired-cleanup end deletedKeys=0");
        }
    }
}
