package com.presence.seckill.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 抢购下单请求。
 * <p>{@code requestId} 由前端一次点击生成；网络重试必须复用同一值，以保证 DB/Redis 幂等。
 */
public record PlaceOrderRequest(
        /** 秒杀活动 ID */
        @NotNull Long activityId,
        /** 活动下的 SKU ID */
        @NotNull Long skuId,
        /** 客户端幂等键：同用户同 requestId 只会落一单 */
        @NotBlank String requestId,
        /** 购买数量，缺省按 1 处理 */
        @Min(1) Integer qty
) {
    /** qty 为空时默认买 1 件，避免 NPE。 */
    public int safeQty() {
        return qty == null ? 1 : qty;
    }
}
