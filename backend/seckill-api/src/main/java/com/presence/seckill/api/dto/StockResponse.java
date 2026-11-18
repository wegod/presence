package com.presence.seckill.api.dto;

/**
 * 活动库存查询响应（MySQL + Redis 视角，用于管理端核对）。
 */
public record StockResponse(
        Long activityId,
        Long skuId,
        String skuName,
        /** 活动配置总库存 */
        Integer totalStock,
        /** MySQL 剩余可售库存（异步扣减后更新） */
        Integer availableStock,
        /** 单用户限购件数 */
        Integer perUserLimit,
        /** 是否可售；MySQL 扣失败时事件驱动置 false */
        Boolean saleEnabled,
        /** 单价（分） */
        Integer unitPriceCent,
        /** Redis 剩余库存；未预热时为 null */
        Integer redisStock,
        /** 是否已写入 Redis（warmup_token 存在） */
        Boolean warmed,
        /** Redis 可售标记；未预热时为 null */
        Boolean redisSaleEnabled
) {
}
