package com.presence.seckill.api.dto;

import java.time.LocalDateTime;

/**
 * 活动创建/查询响应（含关联 SKU 库存摘要与 Redis 预热视图）。
 */
public record ActivityResponse(
        Long activityId,
        String name,
        LocalDateTime startTime,
        LocalDateTime endTime,
        /** 活动状态码，见 ActivityStatus */
        Integer status,
        Long skuId,
        String skuName,
        Integer totalStock,
        Integer availableStock,
        Integer perUserLimit,
        Integer unitPriceCent,
        Boolean saleEnabled,
        /** Redis 剩余库存；未预热时为 null */
        Integer redisStock,
        /** 是否已写入 Redis（warmup_token 存在） */
        Boolean warmed,
        /** Redis 可售标记；未预热时为 null */
        Boolean redisSaleEnabled
) {
}
