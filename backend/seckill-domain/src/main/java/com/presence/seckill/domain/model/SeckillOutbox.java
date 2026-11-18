package com.presence.seckill.domain.model;

import lombok.Data;

import java.time.LocalDateTime;

/** Outbox 消息实体 */
@Data
public class SeckillOutbox {
    private Long id;
    private String aggregateType;
    private Long aggregateId;
    private String eventType;
    private String payload;
    private Integer status;
    private LocalDateTime nextRetryAt;
    private Integer retryCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
