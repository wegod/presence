package com.presence.seckill.api.dto;

/**
 * 支付相关响应。
 * status：SUCCESS / FAILED / INIT / NONE（无支付单）。
 */
public record PayResponse(
        Long orderId,
        /** 支付流水号；无支付单时可能为 null */
        String payTradeNo,
        String status,
        /** 金额（分） */
        Integer amountCent
) {
}
