package com.presence.seckill.domain.model;

import lombok.Data;

import java.time.LocalDateTime;

/** 支付记录实体 */
@Data
public class SeckillPayRecord {
    private Long id;
    private Long orderId;
    private Long userId;
    private String payTradeNo;
    private Integer amountCent;
    private String status;
    private LocalDateTime paidAt;
    private Boolean callbackReceived;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
