package com.presence.seckill.api.dto;

import java.time.LocalDateTime;

/**
 * 订单查询/下单响应快照。
 * <p>status（订单/支付状态）取值见领域枚举 {@code OrderStatus}
 * （如 PROCESSING 处理中、PENDING_PAY 待支付、PAID 已支付）。
 */
public record OrderResponse(
        /** 订单号（主键） */
        Long orderId,
        /** 用户ID */
        Long userId,
        /** 客户端幂等键（requestId） */
        String requestId,
        /** 活动ID */
        Long activityId,
        /** SKU ID */
        Long skuId,
        /** 购买数量 */
        Integer qty,
        /** 应付金额（分）；前端可展示为元 */
        Integer amountCent,
        /** 订单/支付状态，与 OrderStatus.name() 一致 */
        String status,
        /** 支付截止时间；超时会取消并回补库存 */
        LocalDateTime payDeadline,
        /** 下单创建时间 */
        LocalDateTime createdAt
) {
}
