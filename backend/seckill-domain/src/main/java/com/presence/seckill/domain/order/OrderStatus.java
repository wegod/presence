package com.presence.seckill.domain.order;

/**
 * 订单状态机（名称落库）。
 * <pre>
 * PROCESSING ──(Kafka 扣库成功)──► PENDING_PAY ──(支付成功)──► PAID
 *      │                              │
 *      │                              ├─(支付失败)──► PAY_FAILED
 *      │                              ├─(超时)──────► CANCELED_TIMEOUT
 *      │                              └─(用户取消)──► CANCELED_MANUAL
 *      ├─(扣库失败)──► CANCELED_SYSTEM
 *      └─(用户取消)──► CANCELED_MANUAL
 * </pre>
 * 终态：PAID / PAY_FAILED / 各类 CANCELED_*。仅 PENDING_PAY 可支付；PROCESSING 与 PENDING_PAY 可取消。
 */
public enum OrderStatus {
    /** Redis 已预扣，等待异步扣 MySQL */
    PROCESSING,
    /** MySQL 已扣，等待支付 */
    PENDING_PAY,
    /** 支付成功（终态） */
    PAID,
    /** 支付失败（终态；演示用） */
    PAY_FAILED,
    /** 支付超时取消（终态） */
    CANCELED_TIMEOUT,
    /** 用户主动取消（终态） */
    CANCELED_MANUAL,
    /** 系统取消，如 MySQL 扣库存失败（终态） */
    CANCELED_SYSTEM;

    /** 是否属于取消类终态。 */
    public boolean isCanceled() {
        return this == CANCELED_TIMEOUT || this == CANCELED_MANUAL || this == CANCELED_SYSTEM;
    }

    /** 是否终态（不可再支付/取消）。 */
    public boolean isTerminal() {
        return this == PAID || this == PAY_FAILED || isCanceled();
    }

    /** 仅待支付可发起支付。 */
    public boolean canPay() {
        return this == PENDING_PAY;
    }

    /** 处理中或待支付可取消。 */
    public boolean canCancel() {
        return this == PENDING_PAY || this == PROCESSING;
    }
}
