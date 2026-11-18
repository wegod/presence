package com.presence.seckill.api.dto;

/** 演示登录响应：随机 userId，同时写入 Cookie {@code seckill_uid}。 */
public record LoginResponse(
        /** 会话用户 ID */
        Long userId
) {
}
