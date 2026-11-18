package com.presence.seckill.infra.circuit;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.stereotype.Component;

/**
 * 依赖熔断状态只读门面。
 * <p>下单热路径不再做「任一依赖 OPEN 则整站停售」编排；Redis/MySQL/Kafka 各自在调用点熔断 fail-fast。
 * 本类仅供补偿任务（如 Outbox）查询 Kafka 熔断状态，避免雪崩。
 */
@Component
public class DependencyGuard {

    private final CircuitBreakerRegistry registry;

    public DependencyGuard(CircuitBreakerRegistry registry) {
        this.registry = registry;
    }

    /** Outbox 补偿用：Kafka 仍 OPEN 时不发消息。 */
    public boolean isKafkaOpen() {
        return registry.circuitBreaker("kafka").getState() == CircuitBreaker.State.OPEN;
    }
}
