package com.presence.seckill.domain.model;

import lombok.Data;

import java.time.LocalDateTime;

/** 秒杀活动实体 */
@Data
public class SeckillActivity {
    private Long id;
    private String name;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
