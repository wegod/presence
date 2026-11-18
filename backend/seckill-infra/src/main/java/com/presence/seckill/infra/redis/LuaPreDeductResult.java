package com.presence.seckill.infra.redis;

/**
 * Lua 预扣脚本返回值包装。
 * <ul>
 *   <li>1：预扣成功</li>
 *   <li>2：幂等键已存在（可能并发或重试）</li>
 *   <li>-1 库存不足 / -2 超限购 / -3 停售 / -4 未预热</li>
 * </ul>
 */
public record LuaPreDeductResult(
        int code,
        /** 脚本附带说明或已存在的幂等内容 */
        String detail
) {

    public boolean success() {
        return code == 1;
    }

    public boolean idempotentHit() {
        return code == 2;
    }
}
