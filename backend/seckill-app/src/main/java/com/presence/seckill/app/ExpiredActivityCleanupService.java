package com.presence.seckill.app;

import com.presence.seckill.domain.model.SeckillActivity;
import com.presence.seckill.domain.model.SeckillSkuStock;
import com.presence.seckill.infra.mapper.SeckillActivityMapper;
import com.presence.seckill.infra.mapper.SeckillSkuStockMapper;
import com.presence.seckill.infra.redis.SeckillRedisStockService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 活动过期后清理 Redis 活动域键。
 * <p>扫描 {@code end_time <= now} 的活动，删除 stock / limit / sale-enabled / warmup-token /
 * user-bought 等；重复执行幂等（key 已不存在时删除数为 0）。
 */
@Slf4j
@Service
public class ExpiredActivityCleanupService {

    private final SeckillActivityMapper activityMapper;
    private final SeckillSkuStockMapper stockMapper;
    private final SeckillRedisStockService redisStockService;

    public ExpiredActivityCleanupService(SeckillActivityMapper activityMapper,
                                         SeckillSkuStockMapper stockMapper,
                                         SeckillRedisStockService redisStockService) {
        this.activityMapper = activityMapper;
        this.stockMapper = stockMapper;
        this.redisStockService = redisStockService;
    }

    /**
     * @param limit 单次最多扫描的过期活动数
     * @return 本轮实际删除的 Redis key 总数
     */
    public int cleanupExpired(int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 200);
        List<SeckillActivity> expired = activityMapper.findExpired(safeLimit);
        if (expired.isEmpty()) {
            log.debug("expired-cleanup no expired activities");
            return 0;
        }
        int totalDeleted = 0;
        for (SeckillActivity activity : expired) {
            List<SeckillSkuStock> stocks = stockMapper.findByActivityId(activity.getId());
            int activityDeleted = 0;
            for (SeckillSkuStock stock : stocks) {
                try {
                    activityDeleted += redisStockService.clearActivitySkuKeys(activity.getId(), stock.getSkuId());
                } catch (Exception e) {
                    log.warn("expired-cleanup redis clear failed activityId={} skuId={}",
                            activity.getId(), stock.getSkuId(), e);
                }
            }
            totalDeleted += activityDeleted;
            log.info("expired-cleanup activity done activityId={} endTime={} skuCount={} deletedKeys={}",
                    activity.getId(), activity.getEndTime(), stocks.size(), activityDeleted);
        }
        log.info("expired-cleanup batch done activityCount={} deletedKeys={}", expired.size(), totalDeleted);
        return totalDeleted;
    }
}
