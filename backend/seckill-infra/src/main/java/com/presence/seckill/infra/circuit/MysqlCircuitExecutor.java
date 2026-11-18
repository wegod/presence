package com.presence.seckill.infra.circuit;

import com.presence.seckill.api.BusinessException;
import com.presence.seckill.api.ErrorCode;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * MySQL 写路径熔断执行器：熔断打开时快速失败为 {@link ErrorCode#DEPENDENCY_MYSQL_UNAVAILABLE}。
 * <p>替代下单前 {@code SELECT 1} 探针；只包裹真实写库（及写库所需的同路径读）。
 */
@Component
public class MysqlCircuitExecutor {

    private final CircuitBreakerRegistry registry;

    public MysqlCircuitExecutor(CircuitBreakerRegistry registry) {
        this.registry = registry;
    }

    public <T> T execute(Supplier<T> supplier) {
        CircuitBreaker cb = registry.circuitBreaker("mysql");
        try {
            return cb.executeSupplier(supplier::get);
        } catch (CallNotPermittedException e) {
            throw new BusinessException(ErrorCode.DEPENDENCY_MYSQL_UNAVAILABLE);
        }
    }
}
