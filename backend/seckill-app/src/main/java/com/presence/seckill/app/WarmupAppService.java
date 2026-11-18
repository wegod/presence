package com.presence.seckill.app;

import com.presence.seckill.api.BusinessException;
import com.presence.seckill.api.ErrorCode;
import com.presence.seckill.domain.model.SeckillActivity;
import com.presence.seckill.domain.model.SeckillSkuStock;
import com.presence.seckill.infra.mapper.SeckillActivityMapper;
import com.presence.seckill.infra.mapper.SeckillSkuStockMapper;
import com.presence.seckill.infra.redis.SeckillRedisStockService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 开场前 10 分钟一次性预热；warmup_token 保证开抢后不覆盖。
 * <p>活动已结束（{@code now >= endTime}）禁止预热；开场前允许（Job / 手动）；
 * 手动预热在开抢后仍允许首次写入（不覆盖已有 token）。
 */
@Slf4j
@Service
public class WarmupAppService {

    private final SeckillActivityMapper activityMapper;
    private final SeckillSkuStockMapper stockMapper;
    private final SeckillRedisStockService redisStockService;

    public WarmupAppService(SeckillActivityMapper activityMapper,
                            SeckillSkuStockMapper stockMapper,
                            SeckillRedisStockService redisStockService) {
        this.activityMapper = activityMapper;
        this.stockMapper = stockMapper;
        this.redisStockService = redisStockService;
    }

    public int warmupUpcoming(int minutesBefore) {
        List<SeckillActivity> activities = activityMapper.findUpcomingWithinMinutes(minutesBefore);
        // 也允许对已开始但仍需补预热的活动：若 token 不存在且未开抢才写；开抢后由 never-overwrite 保护
        int warmed = 0;
        for (SeckillActivity activity : activities) {
            warmed += warmupActivity(activity);
        }
        return warmed;
    }

    public int warmupActivityById(Long activityId) {
        SeckillActivity activity = activityMapper.findById(activityId);
        if (activity == null) {
            throw new BusinessException(ErrorCode.ACTIVITY_NOT_FOUND);
        }
        rejectIfEnded(activity);
        // 允许开抢后首次写入；开抢后亦不覆盖已有 warmup_token（由 Redis setIfAbsent 保证）
        return warmupActivity(activity, true);
    }

    private int warmupActivity(SeckillActivity activity) {
        // 定时任务：仅预热未开抢活动；已结束直接跳过
        LocalDateTime now = LocalDateTime.now();
        if (!now.isBefore(activity.getEndTime())) {
            log.info("warmup skip ended activityId={} endTime={}", activity.getId(), activity.getEndTime());
            return 0;
        }
        if (!now.isBefore(activity.getStartTime())) {
            return 0;
        }
        return warmupActivity(activity, false);
    }

    private int warmupActivity(SeckillActivity activity, boolean allowAfterStartFirstWrite) {
        rejectIfEnded(activity);
        if (!allowAfterStartFirstWrite && !LocalDateTime.now().isBefore(activity.getStartTime())) {
            return 0;
        }
        List<SeckillSkuStock> stocks = stockMapper.findByActivityId(activity.getId());
        int count = 0;
        for (SeckillSkuStock stock : stocks) {
            // token 绑定开抢时间：同一活动重复预热不换 token，开抢后绝不覆盖
            String token = "warmup-" + activity.getId() + "-" + stock.getSkuId() + "-" +
                    activity.getStartTime().toString();
            boolean ok = redisStockService.warmupOnce(
                    activity.getId(),
                    stock.getSkuId(),
                    stock.getAvailableStock(),
                    stock.getPerUserLimit(),
                    Boolean.TRUE.equals(stock.getSaleEnabled()),
                    token
            );
            if (ok) {
                count++;
                log.info("warmup wrote activityId={} skuId={} stock={}", activity.getId(), stock.getSkuId(), stock.getAvailableStock());
            } else {
                log.info("warmup skip overwrite activityId={} skuId={}", activity.getId(), stock.getSkuId());
            }
        }
        return count;
    }

    /** {@code now >= endTime} → 活动已结束，禁止预热。 */
    private static void rejectIfEnded(SeckillActivity activity) {
        if (!LocalDateTime.now().isBefore(activity.getEndTime())) {
            log.warn("warmup rejected ACTIVITY_ENDED activityId={} endTime={}",
                    activity.getId(), activity.getEndTime());
            throw new BusinessException(ErrorCode.ACTIVITY_ENDED);
        }
    }
}
