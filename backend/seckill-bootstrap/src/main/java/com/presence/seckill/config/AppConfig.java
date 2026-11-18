package com.presence.seckill.config;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import jakarta.annotation.PostConstruct;
import java.util.Collections;

/**
 * 启动期装配：Sentinel 下单 QPS、编程式事务模板、CORS。
 */
@Configuration
public class AppConfig {

    @Value("${seckill.sentinel.place-order-qps:500}")
    private double placeOrderQps;

    /**
     * 注册 {@code seckill:placeOrder} 流控规则（QPS），与 SeckillOrderAppService 资源名对齐。
     * 超限抛 BlockException → RATE_LIMITED。
     */
    @PostConstruct
    public void initSentinel() {
        FlowRule rule = new FlowRule();
        rule.setResource("seckill:placeOrder");
        rule.setGrade(RuleConstant.FLOW_GRADE_QPS);
        rule.setCount(placeOrderQps);
        FlowRuleManager.loadRules(Collections.singletonList(rule));
    }

    @Bean
    public TransactionTemplate transactionTemplate(PlatformTransactionManager tm) {
        return new TransactionTemplate(tm);
    }

    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/api/**")
                        .allowedOriginPatterns("*")
                        .allowedMethods("*")
                        .allowCredentials(true);
            }
        };
    }
}
