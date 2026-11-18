package com.presence.seckill.app;

import com.presence.seckill.api.dto.OrderResponse;

/**
 * 下单结果：正常返回订单；幂等命中但订单尚未可见时 order 为空，带提示文案。
 */
public record PlaceOrderResult(OrderResponse order, String message) {

    public static PlaceOrderResult of(OrderResponse order) {
        return new PlaceOrderResult(order, null);
    }

    /** Lua 幂等命中 / 并发唯一键冲突但订单尚未可查：成功语义，引导查列表。 */
    public static PlaceOrderResult checkOrderList() {
        return new PlaceOrderResult(null, "请到订单列表查看");
    }
}
