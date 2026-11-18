package com.presence.seckill.infra.redis;

import com.presence.seckill.domain.order.PreDeductState;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;

/**
 * Redis 库存预扣记录（Hash 字段视图）。
 * <p>Key：{@code seckill:pre-deduct:{userId}:{requestId}}；member 与之对应 {@code userId:requestId}。
 */
@Data
public class PreDeductRecord {

    public static final String FIELD_STATE = "state";
    public static final String FIELD_ACTIVITY_ID = "activityId";
    public static final String FIELD_SKU_ID = "skuId";
    public static final String FIELD_USER_ID = "userId";
    public static final String FIELD_REQUEST_ID = "requestId";
    public static final String FIELD_QTY = "qty";
    public static final String FIELD_CREATED_AT_MS = "createdAtMs";
    public static final String FIELD_UPDATED_AT_MS = "updatedAtMs";

    private PreDeductState state;
    private Long activityId;
    private Long skuId;
    private Long userId;
    private String requestId;
    private Integer qty;
    private Long createdAtMs;
    private Long updatedAtMs;

    public static String memberToken(Long userId, String requestId) {
        return userId + ":" + requestId;
    }

    /** 解析 ZSet member {@code userId:requestId}；非法则返回 null。 */
    public static String[] parseMember(String member) {
        if (member == null) {
            return null;
        }
        int idx = member.indexOf(':');
        if (idx <= 0 || idx >= member.length() - 1) {
            return null;
        }
        return new String[]{member.substring(0, idx), member.substring(idx + 1)};
    }

    public static PreDeductRecord fromHash(Map<Object, Object> hash) {
        if (hash == null || hash.isEmpty()) {
            return null;
        }
        Map<String, String> m = new HashMap<>();
        hash.forEach((k, v) -> {
            if (k != null && v != null) {
                m.put(String.valueOf(k), String.valueOf(v));
            }
        });
        PreDeductRecord r = new PreDeductRecord();
        r.state = PreDeductState.from(m.get(FIELD_STATE));
        r.activityId = parseLong(m.get(FIELD_ACTIVITY_ID));
        r.skuId = parseLong(m.get(FIELD_SKU_ID));
        r.userId = parseLong(m.get(FIELD_USER_ID));
        r.requestId = m.get(FIELD_REQUEST_ID);
        r.qty = parseInt(m.get(FIELD_QTY));
        r.createdAtMs = parseLong(m.get(FIELD_CREATED_AT_MS));
        r.updatedAtMs = parseLong(m.get(FIELD_UPDATED_AT_MS));
        return r;
    }

    public Map<String, String> toHash() {
        Map<String, String> m = new HashMap<>();
        if (state != null) {
            m.put(FIELD_STATE, state.name());
        }
        if (activityId != null) {
            m.put(FIELD_ACTIVITY_ID, String.valueOf(activityId));
        }
        if (skuId != null) {
            m.put(FIELD_SKU_ID, String.valueOf(skuId));
        }
        if (userId != null) {
            m.put(FIELD_USER_ID, String.valueOf(userId));
        }
        if (requestId != null) {
            m.put(FIELD_REQUEST_ID, requestId);
        }
        if (qty != null) {
            m.put(FIELD_QTY, String.valueOf(qty));
        }
        if (createdAtMs != null) {
            m.put(FIELD_CREATED_AT_MS, String.valueOf(createdAtMs));
        }
        if (updatedAtMs != null) {
            m.put(FIELD_UPDATED_AT_MS, String.valueOf(updatedAtMs));
        }
        return m;
    }

    private static Long parseLong(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return Long.parseLong(s);
    }

    private static Integer parseInt(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return Integer.parseInt(s);
    }
}
