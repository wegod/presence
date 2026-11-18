package com.presence.seckill.domain.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * Redis 补偿审计日志实体（非工作队列）。
 * <p>待补偿真相源是 Redis ZSet {@code seckill:pre-deduct:pending}；本表仅追加记录 Job/失败路径动作。
 * status 常用 {@code DONE}/{@code FAILED}，不再作为 PENDING 扫表驱动。
 */
@Data
public class RedisCompensateLog {
    private Long id;
    private String compensateToken;
    private Long userId;
    private String requestId;
    private Long activityId;
    private Long skuId;
    private Integer qty;
    private String action;
    private String status;
    private String detail;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
