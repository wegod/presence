package com.presence.seckill.infra.redis;

import com.presence.seckill.api.BusinessException;
import com.presence.seckill.api.ErrorCode;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Service;

import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Redis 秒杀库存门面。
 * <p><b>何时用 Lua</b>：一次业务需要改多个 key、且必须同成败时（预扣多步、回补多步、确认 CONFIRMED+ZREM、失败恢复 Hash+ZADD）。
 * <p><b>何时用 Java</b>：普通查询与单条命令（HGETALL、ZRANGEBYSCORE、单条 ZADD/ZREM、SET 停售等）；MVP 单实例领取待补偿也走 Java。
 * <p>预扣 state 机（权威在 Redis Hash）：{@code HELD → CONFIRMED | ROLLED_BACK}；
 * 取消/超时允许 {@code CONFIRMED → ROLLED_BACK}；{@code ROLLED_BACK} 终态不可再迁出。迁移均由 Lua CAS。
 * <p>热路径预扣/回补失败经 Resilience4j {@code redis} 熔断映射为 DEPENDENCY_REDIS_UNAVAILABLE。
 */
@Slf4j
@Service
public class SeckillRedisStockService {

    /** Lua 幂等槽占位：仅标记已受理，一致性靠 DB 唯一键 + 补偿任务。rollback 永不删除该键。 */
    public static final String IDEM_MARKER = "1";

    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<List> preDeductScript;
    private final DefaultRedisScript<Long> rollbackScript;
    private final DefaultRedisScript<Long> enqueuePendingScript;
    private final DefaultRedisScript<Long> confirmScript;
    private final long pendingDelayMs;
    private final long claimLeaseMs;

    public SeckillRedisStockService(StringRedisTemplate redisTemplate,
                                    @Value("${seckill.pre-deduct.pending-delay-ms:3000}") long pendingDelayMs,
                                    @Value("${seckill.pre-deduct.claim-lease-ms:10000}") long claimLeaseMs) {
        this.redisTemplate = redisTemplate;
        this.pendingDelayMs = pendingDelayMs;
        this.claimLeaseMs = claimLeaseMs;

        this.preDeductScript = new DefaultRedisScript<>();
        this.preDeductScript.setResultType(List.class);
        this.preDeductScript.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/pre_deduct.lua")));

        this.rollbackScript = new DefaultRedisScript<>();
        this.rollbackScript.setResultType(Long.class);
        this.rollbackScript.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/rollback.lua")));

        this.enqueuePendingScript = new DefaultRedisScript<>();
        this.enqueuePendingScript.setResultType(Long.class);
        this.enqueuePendingScript.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/enqueue_pending.lua")));

        this.confirmScript = new DefaultRedisScript<>();
        this.confirmScript.setResultType(Long.class);
        this.confirmScript.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/confirm.lua")));
    }

    /**
     * 原子预扣：校验可售/预热 → 扣库存 → 累加用户已购 → 写幂等标记 → 写 HELD 预扣记录 + ZADD 待补偿。
     *
     * @return code 1 成功 / 2 幂等命中 / 负数为业务拒绝（见 LuaPreDeductResult）
     */
    @CircuitBreaker(name = "redis", fallbackMethod = "preDeductFallback")
    public LuaPreDeductResult preDeduct(Long activityId, Long skuId, Long userId, String requestId,
                                        int qty, long ttlSeconds) {
        long now = System.currentTimeMillis();
        long dueScore = now + pendingDelayMs;
        String member = PreDeductRecord.memberToken(userId, requestId);
        List keys = Arrays.asList(
                RedisKeyNames.stock(activityId, skuId),
                RedisKeyNames.saleEnabled(activityId, skuId),
                RedisKeyNames.userBought(activityId, skuId, userId),
                RedisKeyNames.idempotent(userId, requestId),
                RedisKeyNames.limit(activityId, skuId),
                RedisKeyNames.preDeduct(userId, requestId),
                RedisKeyNames.preDeductPending()
        );
        List result = redisTemplate.execute(
                preDeductScript,
                keys,
                String.valueOf(qty),
                IDEM_MARKER,
                String.valueOf(ttlSeconds),
                String.valueOf(dueScore),
                member,
                String.valueOf(activityId),
                String.valueOf(skuId),
                String.valueOf(userId),
                requestId,
                String.valueOf(now)
        );
        if (result == null || result.isEmpty()) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "Redis Lua 预扣无返回");
        }
        int code = Integer.parseInt(String.valueOf(result.get(0)));
        String detail = result.size() > 1 ? String.valueOf(result.get(1)) : "";
        return new LuaPreDeductResult(code, detail);
    }

    /** Redis 不可用或熔断打开：上抛依赖错误，由下单层 fail-fast。 */
    @SuppressWarnings("unused")
    private LuaPreDeductResult preDeductFallback(Long activityId, Long skuId, Long userId, String requestId,
                                                 int qty, long ttlSeconds, Throwable t) {
        throw new BusinessException(ErrorCode.DEPENDENCY_REDIS_UNAVAILABLE);
    }

    /**
     * 原子回补：加回库存、扣减用户已购、记录 ROLLED_BACK、ZREM（{@code rollback.lua}）。
     * <p>CAS：{@code HELD|CONFIRMED → ROLLED_BACK}；已是 {@code ROLLED_BACK} 幂等（不双回补）。
     * 永不删除幂等键。
     *
     * @return 1 ok / 0 already rolled back / -1 illegal state（见 rollback.lua）
     */
    @CircuitBreaker(name = "redis", fallbackMethod = "rollbackFallback")
    public long rollback(Long activityId, Long skuId, Long userId, String requestId, int qty) {
        String member = PreDeductRecord.memberToken(userId, requestId);
        List<String> keys = Arrays.asList(
                RedisKeyNames.stock(activityId, skuId),
                RedisKeyNames.userBought(activityId, skuId, userId),
                RedisKeyNames.preDeduct(userId, requestId),
                RedisKeyNames.preDeductPending()
        );
        Long code = redisTemplate.execute(
                rollbackScript,
                keys,
                String.valueOf(qty),
                member,
                String.valueOf(System.currentTimeMillis())
        );
        long result = code == null ? 1L : code;
        if (result < 0) {
            log.warn("redis rollback illegal transition userId={} requestId={} skuId={} code={}",
                    userId, requestId, skuId, result);
        } else if (result == 0) {
            log.debug("redis rollback idempotent already ROLLED_BACK userId={} requestId={}", userId, requestId);
        }
        return result;
    }

    @SuppressWarnings("unused")
    private long rollbackFallback(Long activityId, Long skuId, Long userId, String requestId,
                                  int qty, Throwable t) {
        throw new BusinessException(ErrorCode.DEPENDENCY_REDIS_UNAVAILABLE);
    }

    /**
     * 同步回补；失败则走 {@link #enqueuePendingCompensate}（单 Lua：Hash+ZADD），避免 Java 拆多步半成功。
     *
     * @return true=rollback 成功（含幂等已回补）；false=已原子重新入队待补偿（供 Job 重试）
     */
    public boolean rollbackOrEnqueuePending(Long activityId, Long skuId, Long userId, String requestId, int qty) {
        try {
            long code = rollback(activityId, skuId, userId, requestId, qty);
            // -1 非法态不入队（避免把终态/脏态重新挂补偿）；0/1 均视为收敛
            return code >= 0;
        } catch (RuntimeException rollbackEx) {
            log.warn("redis rollback failed, enqueue pending via Lua userId={} requestId={} skuId={}",
                    userId, requestId, skuId, rollbackEx);
            try {
                enqueuePendingCompensate(activityId, skuId, userId, requestId, qty, System.currentTimeMillis());
                return false;
            } catch (RuntimeException enqueueEx) {
                enqueueEx.addSuppressed(rollbackEx);
                throw enqueueEx;
            }
        }
    }

    /**
     * 订单+Outbox 落库成功：预扣确认 CONFIRMED + ZREM（{@code confirm.lua} 原子 CAS）。
     * <p>返回：1 ok / 0 already confirmed / -1 illegal（ROLLED_BACK 等）；异常吞掉留给 Job。
     */
    public long confirmPreDeduct(Long userId, String requestId) {
        try {
            String member = PreDeductRecord.memberToken(userId, requestId);
            Long code = redisTemplate.execute(
                    confirmScript,
                    Arrays.asList(
                            RedisKeyNames.preDeduct(userId, requestId),
                            RedisKeyNames.preDeductPending()
                    ),
                    member,
                    String.valueOf(System.currentTimeMillis())
            );
            long result = code == null ? 1L : code;
            if (result < 0) {
                log.warn("redis confirm illegal transition userId={} requestId={} code={}",
                        userId, requestId, result);
            }
            return result;
        } catch (Exception e) {
            // 确认失败留给 Job：到期后查到订单即 CONFIRMED+ZREM
            log.debug("redis confirm failed, leave to compensate job userId={} requestId={}",
                    userId, requestId, e);
            return -1L;
        }
    }

    /**
     * 原子重新挂入待补偿（{@code enqueue_pending.lua}：仅 HELD/缺失→HELD 才 ZADD）。
     * <p>用于同步回补失败后的失败恢复；勿再拆成 Java 多次 Redis 写。
     *
     * @return 1 enqueued / 0 skipped CONFIRMED / -1 skipped ROLLED_BACK / -2 unknown
     */
    public long enqueuePendingCompensate(Long activityId, Long skuId, Long userId, String requestId,
                                         int qty, long dueAtMs) {
        String member = PreDeductRecord.memberToken(userId, requestId);
        long now = System.currentTimeMillis();
        Long code = redisTemplate.execute(
                enqueuePendingScript,
                Arrays.asList(
                        RedisKeyNames.preDeduct(userId, requestId),
                        RedisKeyNames.preDeductPending()
                ),
                member,
                String.valueOf(dueAtMs),
                String.valueOf(activityId),
                String.valueOf(skuId),
                String.valueOf(userId),
                requestId,
                String.valueOf(qty),
                String.valueOf(now)
        );
        long result = code == null ? 1L : code;
        if (result != 1L) {
            log.info("redis enqueue_pending skipped userId={} requestId={} code={}", userId, requestId, result);
        }
        return result;
    }

    /** 普通查询：单条 HGETALL，无需 Lua。 */
    public PreDeductRecord getPreDeductRecord(Long userId, String requestId) {
        Map<Object, Object> hash = redisTemplate.opsForHash().entries(RedisKeyNames.preDeduct(userId, requestId));
        return PreDeductRecord.fromHash(hash);
    }

    /** 标记 CONFIRMED 并 ZREM（Job 确认订单已存在时用）。 */
    public long markConfirmedAndRemovePending(Long userId, String requestId) {
        return confirmPreDeduct(userId, requestId);
    }

    /**
     * 领取到期待补偿 member。
     * <p>MVP 单实例：用 Java {@code ZRANGEBYSCORE} 查询到期成员；租约推迟用逐条 {@code ZADD}（非原子）。
     * 可接受短暂重复领取——rollback/confirm 脚本幂等。多实例严格互斥时再考虑 ZPOPMIN 或专用 claim Lua。
     */
    public List<String> claimDuePending(int limit) {
        String zset = RedisKeyNames.preDeductPending();
        long now = System.currentTimeMillis();
        Set<String> due = redisTemplate.opsForZSet().rangeByScore(
                zset, Double.NEGATIVE_INFINITY, (double) now, 0, limit);
        if (due == null || due.isEmpty()) {
            return List.of();
        }
        List<String> members = new ArrayList<>(due);
        // MVP：领取后推迟 score，降低同进程 Job 重叠时的忙等；与查询非同一原子，可接受
        long claimUntil = now + claimLeaseMs;
        for (String member : members) {
            redisTemplate.opsForZSet().add(zset, member, claimUntil);
        }
        return members;
    }

    /** 处理失败：单条 ZADD 重新调度，无需 Lua。 */
    public void reschedulePending(String member, long dueAtMs) {
        redisTemplate.opsForZSet().add(RedisKeyNames.preDeductPending(), member, dueAtMs);
    }

    /** 显式单条 ZREM（记录已终态时清理索引），无需 Lua。 */
    public void removePending(String member) {
        redisTemplate.opsForZSet().remove(RedisKeyNames.preDeductPending(), member);
    }

    public long getPendingDelayMs() {
        return pendingDelayMs;
    }

    public long getClaimLeaseMs() {
        return claimLeaseMs;
    }

    /**
     * 一次性预热：仅当 warmup_token 不存在或与本次 token 不同且活动未开抢时写入。
     * 开抢后绝不覆盖（调用方保证；此处若 token 已存在且相同则跳过）。
     * <p>预热为管理路径，非热路径多 key 原子约束；SETNX token 后再写库存/限购/可售。
     */
    @CircuitBreaker(name = "redis", fallbackMethod = "warmupFallback")
    public boolean warmupOnce(Long activityId, Long skuId, int stock, int perUserLimit,
                              boolean saleEnabled, String warmupToken) {
        String tokenKey = RedisKeyNames.warmupToken(activityId, skuId);
        Boolean set = redisTemplate.opsForValue().setIfAbsent(tokenKey, warmupToken);
        if (Boolean.FALSE.equals(set)) {
            String existing = redisTemplate.opsForValue().get(tokenKey);
            // 已有其它 token：MVP 不覆盖
            return warmupToken.equals(existing);
        }
        redisTemplate.opsForValue().set(RedisKeyNames.stock(activityId, skuId), String.valueOf(stock));
        redisTemplate.opsForValue().set(RedisKeyNames.limit(activityId, skuId), String.valueOf(perUserLimit));
        redisTemplate.opsForValue().set(RedisKeyNames.saleEnabled(activityId, skuId), saleEnabled ? "1" : "0");
        return true;
    }

    @SuppressWarnings("unused")
    private boolean warmupFallback(Long activityId, Long skuId, int stock, int perUserLimit,
                                   boolean saleEnabled, String warmupToken, Throwable t) {
        throw new BusinessException(ErrorCode.DEPENDENCY_REDIS_UNAVAILABLE);
    }

    /** 管理端：是否已预热（warmup_token 存在）。 */
    public boolean isWarmed(Long activityId, Long skuId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(RedisKeyNames.warmupToken(activityId, skuId)));
    }

    /** 管理端：读取 Redis 剩余库存；未写入时返回 null。 */
    public Integer getRedisStock(Long activityId, Long skuId) {
        String raw = redisTemplate.opsForValue().get(RedisKeyNames.stock(activityId, skuId));
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            log.warn("invalid redis stock value activityId={} skuId={} raw={}", activityId, skuId, raw);
            return null;
        }
    }

    /** 管理端：读取 Redis 可售标记；未写入时返回 null。 */
    public Boolean getRedisSaleEnabled(Long activityId, Long skuId) {
        String raw = redisTemplate.opsForValue().get(RedisKeyNames.saleEnabled(activityId, skuId));
        if (raw == null) {
            return null;
        }
        return "1".equals(raw);
    }

    /** 事件驱动停售：单条 SET，无需 Lua；预扣脚本会因 saleEnabled=0 拒绝新请求。 */
    @CircuitBreaker(name = "redis", fallbackMethod = "disableSaleFallback")
    public void disableSale(Long activityId, Long skuId) {
        redisTemplate.opsForValue().set(RedisKeyNames.saleEnabled(activityId, skuId), "0");
    }

    @SuppressWarnings("unused")
    private void disableSaleFallback(Long activityId, Long skuId, Throwable t) {
        // 停售标记失败留给补偿；不阻断主流程（MySQL 侧已 disable）
    }

    /**
     * 活动结束后清理该 SKU 的活动域 Redis 键（幂等：key 不存在也安全）。
     * <p>删除：stock / limit / sale-enabled / warmup-token，以及 user-bought:{activityId}:{skuId}:*。
     * 不碰全局 pending ZSet、幂等键与预扣 Hash（按 userId:requestId，由补偿/TTL 收敛）。
     *
     * @return 实际删除的 key 数量
     */
    public int clearActivitySkuKeys(Long activityId, Long skuId) {
        Set<String> toDelete = new HashSet<>();
        toDelete.add(RedisKeyNames.stock(activityId, skuId));
        toDelete.add(RedisKeyNames.limit(activityId, skuId));
        toDelete.add(RedisKeyNames.saleEnabled(activityId, skuId));
        toDelete.add(RedisKeyNames.warmupToken(activityId, skuId));

        ScanOptions scanOptions = ScanOptions.scanOptions()
                .match(RedisKeyNames.userBoughtPattern(activityId, skuId))
                .count(200)
                .build();
        try (Cursor<String> cursor = redisTemplate.scan(scanOptions)) {
            while (cursor.hasNext()) {
                toDelete.add(cursor.next());
            }
        } catch (Exception e) {
            log.warn("clearActivitySkuKeys scan user-bought failed activityId={} skuId={}", activityId, skuId, e);
        }

        if (toDelete.isEmpty()) {
            return 0;
        }
        Long deleted = redisTemplate.delete(toDelete);
        int n = deleted == null ? 0 : deleted.intValue();
        log.info("clearActivitySkuKeys activityId={} skuId={} deletedKeys={}", activityId, skuId, n);
        return n;
    }
}
