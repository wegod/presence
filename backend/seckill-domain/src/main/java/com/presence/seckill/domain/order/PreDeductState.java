package com.presence.seckill.domain.order;

/**
 * Redis 库存预扣记录状态机（权威在 Redis Hash，非 MySQL 补偿表；迁移由 Lua CAS）。
 * <pre>
 *   (new) ──pre_deduct──► HELD
 *   HELD  ──confirm─────► CONFIRMED
 *   HELD  ──rollback────► ROLLED_BACK      （补偿 / 未落单）
 *   CONFIRMED ──rollback► ROLLED_BACK      （取消 / 超时，订单创建后）
 *   ROLLED_BACK           终态（不可再迁出；confirm 非法）
 * </pre>
 * <ul>
 *   <li>{@link #HELD} 已预扣：等待订单落库或补偿到期处理（唯一可入待补偿 ZSet 的非终态）</li>
 *   <li>{@link #CONFIRMED} 订单+Outbox 已确认：应从 ZSet 移除；取消/超时仍可回补到 ROLLED_BACK</li>
 *   <li>{@link #ROLLED_BACK} 库存已回补：终态，幂等键保留不删</li>
 * </ul>
 */
public enum PreDeductState {

    /** 已预扣库存，等待订单落库或补偿 */
    HELD,
    /** 订单+Outbox 已落库，预扣确认（可再因取消/超时 → ROLLED_BACK） */
    CONFIRMED,
    /** 库存与限购已回补（终态） */
    ROLLED_BACK;

    public static PreDeductState from(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return PreDeductState.valueOf(raw.trim());
    }
}
