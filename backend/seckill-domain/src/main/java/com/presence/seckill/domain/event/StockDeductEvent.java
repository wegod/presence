package com.presence.seckill.domain.event;

/**
 * Kafka 库存扣减事件（topic: seckill.stock.deduct，key=skuId）。
 */
public record StockDeductEvent(
        Long orderId,
        Long userId,
        String requestId,
        Long activityId,
        Long skuId,
        Integer qty
) {
}
