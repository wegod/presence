package com.presence.seckill.domain.model;

import lombok.Data;

import java.time.LocalDateTime;

/** 秒杀商品库存实体（MySQL 为库存真相源） */
@Data
public class SeckillSkuStock {
    private Long id;
    private Long activityId;
    private Long skuId;
    private String skuName;
    private Integer totalStock;
    private Integer availableStock;
    private Integer perUserLimit;
    private Boolean saleEnabled;
    private Integer unitPriceCent;
    private Integer version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
