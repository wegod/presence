package com.presence.seckill.api;

/**
 * 统一业务错误码（HTTP 仍返回 200，通过 code 区分业务结果）。
 */
public enum ErrorCode {
    OK(0, "成功"),
    ACTIVITY_NOT_STARTED(1001, "活动未开始"),
    ACTIVITY_ENDED(1002, "活动已结束"),
    STOCK_INSUFFICIENT(1003, "库存不足"),
    USER_LIMIT_EXCEEDED(1004, "超过用户限购"),
    IDEMPOTENT_REPLAY(1005, "幂等重放"),
    SECKILL_STOPPED(1006, "秒杀已停止"),
    DEPENDENCY_REDIS_UNAVAILABLE(1007, "Redis 不可用，秒杀已停止"),
    DEPENDENCY_MYSQL_UNAVAILABLE(1008, "MySQL 不可用，秒杀已停止"),
    DEPENDENCY_KAFKA_UNAVAILABLE(1009, "Kafka 不可用，秒杀已停止"),
    ORDER_NOT_FOUND(1010, "订单不存在"),
    ORDER_STATUS_INVALID(1011, "订单状态不允许该操作"),
    RATE_LIMITED(1012, "请求过于频繁，请稍后重试"),
    SALE_DISABLED(1013, "商品已停售"),
    WARMUP_NOT_READY(1014, "库存尚未预热完成"),
    ACTIVITY_NOT_FOUND(1015, "活动不存在"),
    INVALID_PARAM(1016, "参数不合法"),
    SYSTEM_ERROR(1999, "系统错误");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
