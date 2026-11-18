package com.presence.seckill.infra.redis;

/**
 * 可读 Redis Key 命名。
 * <p>预扣状态机：单笔记录 Hash + 全局待补偿 ZSet（score=到期处理时间，member={@code userId:requestId}）。
 */
public final class RedisKeyNames {

    private RedisKeyNames() {
    }

    public static String stock(Long activityId, Long skuId) {
        return "seckill:stock:" + activityId + ":" + skuId;
    }

    public static String limit(Long activityId, Long skuId) {
        return "seckill:limit:" + activityId + ":" + skuId;
    }

    public static String userBought(Long activityId, Long skuId, Long userId) {
        return "seckill:user-bought:" + activityId + ":" + skuId + ":" + userId;
    }

    public static String idempotent(Long userId, String requestId) {
        return "seckill:idempotent:" + userId + ":" + requestId;
    }

    public static String saleEnabled(Long activityId, Long skuId) {
        return "seckill:sale-enabled:" + activityId + ":" + skuId;
    }

    public static String warmupToken(Long activityId, Long skuId) {
        return "seckill:warmup-token:" + activityId + ":" + skuId;
    }

    /** SCAN 匹配：某活动+SKU 下全部 user-bought 键。 */
    public static String userBoughtPattern(Long activityId, Long skuId) {
        return "seckill:user-bought:" + activityId + ":" + skuId + ":*";
    }

    /**
     * 单笔库存预扣记录（Hash）：state / activityId / skuId / userId / requestId / qty / createdAtMs …
     */
    public static String preDeduct(Long userId, String requestId) {
        return "seckill:pre-deduct:" + userId + ":" + requestId;
    }

    /**
     * 待补偿 ZSet 索引：score=到期处理时间(ms)，member={@code userId:requestId}。
     * <p>RedisCompensate Job 直接扫此集合，不依赖 MySQL {@code redis_compensate_log}。
     */
    public static String preDeductPending() {
        return "seckill:pre-deduct:pending";
    }
}
