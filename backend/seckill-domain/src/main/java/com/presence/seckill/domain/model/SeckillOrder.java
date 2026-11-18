package com.presence.seckill.domain.model;

import lombok.Data;

import java.time.LocalDateTime;

/** 秒杀订单实体 */
@Data
public class SeckillOrder {
    private Long id;
    private Long userId;
    private String requestId;
    private Long activityId;
    private Long skuId;
    private Integer qty;
    private Integer amountCent;
    private String status;
    private Integer version;
    private LocalDateTime payDeadline;
    private Boolean stockCompensated;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
