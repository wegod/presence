package com.presence.seckill.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

/**
 * 创建秒杀活动（含单个 SKU）请求。
 * <p>业务约束：MVP 开抢后不可改库存；totalStock 上限 1000 便于演示压测。
 */
public record CreateActivityRequest(
        @NotBlank String name,
        @NotNull LocalDateTime startTime,
        @NotNull LocalDateTime endTime,
        @NotNull Long skuId,
        @NotBlank String skuName,
        /** 总库存，写入 MySQL 并在预热时灌入 Redis */
        @NotNull @Min(1) @Max(1000) Integer totalStock,
        /** 单用户限购 */
        @NotNull @Min(1) Integer perUserLimit,
        /** 单价（分） */
        @NotNull @Min(1) Integer unitPriceCent
) {
}
