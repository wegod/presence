package com.presence.seckill;

import com.presence.seckill.app.PlaceOrderResult;
import com.presence.seckill.domain.order.PreDeductState;
import com.presence.seckill.infra.redis.RedisKeyNames;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.kafka.listener.ContainerProperties;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 契约单测：ZSet/预扣状态、Lua 脚本、SECKILL_QPS、Kafka MANUAL AckMode。
 */
class RedesignContractTest {

    @Test
    void preDeductStatesAndPendingZsetKey() {
        assertEquals("HELD", PreDeductState.HELD.name());
        assertEquals("CONFIRMED", PreDeductState.CONFIRMED.name());
        assertEquals("ROLLED_BACK", PreDeductState.ROLLED_BACK.name());
        assertEquals("seckill:pre-deduct:pending", RedisKeyNames.preDeductPending());
        assertEquals("seckill:pre-deduct:9:abc", RedisKeyNames.preDeduct(9L, "abc"));
    }

    @Test
    void multiKeyMutationsHaveDedicatedLuaScripts() {
        assertTrue(new ClassPathResource("lua/pre_deduct.lua").exists());
        assertTrue(new ClassPathResource("lua/rollback.lua").exists());
        assertTrue(new ClassPathResource("lua/enqueue_pending.lua").exists());
        assertTrue(new ClassPathResource("lua/confirm.lua").exists());
    }

    @Test
    void idempotentWithoutOrderMessage() {
        PlaceOrderResult r = PlaceOrderResult.checkOrderList();
        assertNull(r.order());
        assertEquals("请到订单列表查看", r.message());
    }

    @Test
    void defaultPlaceOrderQpsIs500() throws Exception {
        Path yml = Path.of("src/main/resources/application.yml");
        if (!Files.exists(yml)) {
            yml = Path.of("seckill-bootstrap/src/main/resources/application.yml");
        }
        String text = Files.readString(yml);
        assertTrue(text.contains("place-order-qps: ${SECKILL_QPS:500}"),
                "application.yml must default SECKILL_QPS to 500");

        Path compose = Path.of("../docker/docker-compose.yml");
        if (!Files.exists(compose)) {
            compose = Path.of("docker/docker-compose.yml");
        }
        if (!Files.exists(compose)) {
            compose = Path.of("../../docker/docker-compose.yml");
        }
        String composeText = Files.readString(compose);
        assertTrue(composeText.contains("SECKILL_QPS: \"500\"") || composeText.contains("SECKILL_QPS: '500'"),
                "compose must set SECKILL_QPS=500");
    }

    @Test
    void kafkaAckModeManualIsConfiguredInSource() throws Exception {
        Path cfg = Path.of("src/main/java/com/presence/seckill/config/KafkaConsumerConfig.java");
        if (!Files.exists(cfg)) {
            cfg = Path.of("seckill-bootstrap/src/main/java/com/presence/seckill/config/KafkaConsumerConfig.java");
        }
        String src = Files.readString(cfg);
        assertTrue(src.contains("AckMode.MANUAL"), "Kafka consumer must use AckMode.MANUAL");
        assertTrue(src.contains("ENABLE_AUTO_COMMIT_CONFIG, false")
                        || src.contains("ENABLE_AUTO_COMMIT_CONFIG\", false"),
                "auto-commit must be disabled with MANUAL ack");
        // enum still present in classpath for compile-time safety
        assertEquals(ContainerProperties.AckMode.MANUAL, ContainerProperties.AckMode.MANUAL);
    }

    @Test
    @SuppressWarnings("unchecked")
    void resilience4jCircuitBreakersDefined() throws Exception {
        Path yml = Path.of("src/main/resources/application.yml");
        if (!Files.exists(yml)) {
            yml = Path.of("seckill-bootstrap/src/main/resources/application.yml");
        }
        try (InputStream in = Files.newInputStream(yml)) {
            Map<String, Object> root = new Yaml().load(in);
            Map<String, Object> r4j = (Map<String, Object>) root.get("resilience4j");
            Map<String, Object> cb = (Map<String, Object>) r4j.get("circuitbreaker");
            Map<String, Object> instances = (Map<String, Object>) cb.get("instances");
            assertTrue(instances.containsKey("redis"));
            assertTrue(instances.containsKey("mysql"));
            assertTrue(instances.containsKey("kafka"));
        }
    }
}
