package com.presence.seckill.domain.order;

/**
 * Outbox 消息状态常量（存 int）。
 * NEW 待发 → SENT 已成功投递；FAILED 表示投递失败，由补偿任务按 nextRetryAt 重试。
 */
public final class OutboxStatus {
    /** 事务内刚写入，尚未确认投递 */
    public static final int NEW = 0;
    /** 已成功发到 Kafka */
    public static final int SENT = 1;
    /** 发送失败，等待补偿 */
    public static final int FAILED = 2;

    private OutboxStatus() {
    }
}
