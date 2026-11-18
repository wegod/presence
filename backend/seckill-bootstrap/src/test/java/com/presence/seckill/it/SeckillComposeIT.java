package com.presence.seckill.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Compose 栈集成测试（依赖已启动的 MySQL/Redis/Kafka/App）。
 * 覆盖：并发不超卖、幂等、支付回调幂等、超时取消回补。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SeckillComposeIT {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final String BASE = ComposeStackSupport.BASE_URL;

    @BeforeAll
    static void requireStack() {
        Assumptions.assumeTrue(ComposeStackSupport.isStackUp(),
                "Compose stack not up at " + BASE + " — start with ./scripts/start.sh");
    }

    @Test
    @Order(1)
    @Timeout(value = 120)
    void concurrentDeductNeverOversells() throws Exception {
        int stock = 30;
        int concurrency = 200;
        JsonNode activity = createActivity("it-concurrent-" + System.currentTimeMillis(), stock, 2, 1001);
        long activityId = activity.path("activityId").asLong();
        long skuId = activity.path("skuId").asLong();
        warmup(activityId);
        waitUntil(activity.path("startTime").asText(), 1);

        AtomicInteger success = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(64);
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < concurrency; i++) {
            final int idx = i;
            tasks.add(() -> {
                long userId = 800_000_000L + idx;
                String requestId = "it-c-" + activityId + "-" + idx;
                JsonNode body = placeOrder(userId, activityId, skuId, requestId);
                if (body.path("code").asInt(-1) == 0) {
                    success.incrementAndGet();
                }
                return null;
            });
        }
        List<Future<Void>> futures = pool.invokeAll(tasks, 90, TimeUnit.SECONDS);
        pool.shutdown();
        for (Future<Void> f : futures) {
            assertTrue(f.isDone());
        }
        assertTrue(success.get() <= stock,
                "oversell: success=" + success.get() + " stock=" + stock);
        // 等待消费落库
        Thread.sleep(3000);
        JsonNode stockResp = get("/api/admin/activities/" + activityId + "/stock", null);
        assertEquals(0, stockResp.path("code").asInt());
        int available = stockResp.path("data").path("availableStock").asInt(-1);
        assertTrue(available >= 0, "available stock negative");
        assertTrue(available <= stock);
        // 成功预扣数不应超过库存
        assertTrue(success.get() <= stock);
    }

    @Test
    @Order(2)
    @Timeout(value = 60)
    void sameUserRequestIdIsIdempotent() throws Exception {
        JsonNode activity = createActivity("it-idem-" + System.currentTimeMillis(), 5, 2, 1002);
        long activityId = activity.path("activityId").asLong();
        long skuId = activity.path("skuId").asLong();
        warmup(activityId);
        waitUntil(activity.path("startTime").asText(), 1);

        long userId = 801_111_111L;
        String requestId = "it-idem-" + UUID.randomUUID();

        JsonNode first = placeOrder(userId, activityId, skuId, requestId);
        assertEquals(0, first.path("code").asInt(), first.toString());
        // Redesign: idempotent hit may briefly return data=null +「请到订单列表查看」before order is visible
        if (first.path("data").isNull() || first.path("data").isMissingNode()) {
            assertTrue(first.path("message").asText().contains("请到订单列表查看"), first.toString());
            JsonNode listed = null;
            long orderId = -1;
            for (int i = 0; i < 20; i++) {
                listed = get("/api/seckill/orders", userId);
                assertEquals(0, listed.path("code").asInt(), listed.toString());
                if (listed.path("data").isArray() && listed.path("data").size() > 0) {
                    for (JsonNode o : listed.path("data")) {
                        if (requestId.equals(o.path("requestId").asText())) {
                            orderId = o.path("orderId").asLong();
                            break;
                        }
                    }
                    if (orderId > 0) {
                        break;
                    }
                }
                Thread.sleep(500);
            }
            assertTrue(orderId > 0, "order should appear in list after idempotent-without-order: " + listed);
            JsonNode second = placeOrder(userId, activityId, skuId, requestId);
            assertEquals(0, second.path("code").asInt(), second.toString());
            if (!second.path("data").isNull() && !second.path("data").isMissingNode()) {
                assertEquals(orderId, second.path("data").path("orderId").asLong());
            }
            return;
        }
        long orderId = first.path("data").path("orderId").asLong();

        JsonNode second = placeOrder(userId, activityId, skuId, requestId);
        assertEquals(0, second.path("code").asInt(), second.toString());
        assertEquals(orderId, second.path("data").path("orderId").asLong(),
                "same userId+requestId must return the same orderId");
        // status may advance PROCESSING→PENDING_PAY between calls; identity is orderId
        assertTrue(second.path("data").path("status").asText().length() > 0);
    }

    @Test
    @Order(3)
    @Timeout(value = 90)
    void paymentCallbackIsIdempotent() throws Exception {
        JsonNode activity = createActivity("it-pay-" + System.currentTimeMillis(), 3, 2, 1003);
        long activityId = activity.path("activityId").asLong();
        long skuId = activity.path("skuId").asLong();
        warmup(activityId);
        waitUntil(activity.path("startTime").asText(), 1);

        long userId = 802_222_222L;
        JsonNode placed = placeOrder(userId, activityId, skuId, "it-pay-" + UUID.randomUUID());
        assertEquals(0, placed.path("code").asInt(), placed.toString());
        long orderId = placed.path("data").path("orderId").asLong();

        String status = waitForStatus(userId, orderId, "PENDING_PAY", 30);
        assertEquals("PENDING_PAY", status);

        JsonNode pay1 = post("/api/seckill/orders/" + orderId + "/pay", "{}", userId);
        assertEquals(0, pay1.path("code").asInt(), pay1.toString());
        String tradeNo = pay1.path("data").path("payTradeNo").asText();
        assertEquals("SUCCESS", pay1.path("data").path("status").asText());

        // 重复回调
        String cbBody = "{\"payTradeNo\":\"" + tradeNo + "\",\"success\":true}";
        JsonNode cb1 = post("/api/seckill/pay/callback", cbBody, null);
        JsonNode cb2 = post("/api/seckill/pay/callback", cbBody, null);
        assertEquals(0, cb1.path("code").asInt(), cb1.toString());
        assertEquals(0, cb2.path("code").asInt(), cb2.toString());
        assertEquals("SUCCESS", cb1.path("data").path("status").asText());
        assertEquals("SUCCESS", cb2.path("data").path("status").asText());

        JsonNode order = get("/api/seckill/orders/" + orderId, userId);
        assertEquals("PAID", order.path("data").path("status").asText());
    }

    @Test
    @Order(4)
    @Timeout(value = 90)
    void timeoutCancelRollsBackStock() throws Exception {
        assumeTrue(canExecMysql(), "mysql client / docker exec unavailable for deadline tweak");

        JsonNode activity = createActivity("it-timeout-" + System.currentTimeMillis(), 2, 2, 1004);
        long activityId = activity.path("activityId").asLong();
        long skuId = activity.path("skuId").asLong();
        warmup(activityId);
        waitUntil(activity.path("startTime").asText(), 1);

        long userId = 803_333_333L;
        JsonNode placed = placeOrder(userId, activityId, skuId, "it-to-" + UUID.randomUUID());
        assertEquals(0, placed.path("code").asInt(), placed.toString());
        long orderId = placed.path("data").path("orderId").asLong();
        waitForStatus(userId, orderId, "PENDING_PAY", 30);

        JsonNode before = get("/api/admin/activities/" + activityId + "/stock", null);
        int availableBefore = before.path("data").path("availableStock").asInt();

        // 将支付截止时间拨到过去，等待超时 Job（默认 10s）
        execMysql("UPDATE seckill_order SET pay_deadline = DATE_SUB(NOW(3), INTERVAL 1 MINUTE) WHERE id = " + orderId);

        String status = null;
        for (int i = 0; i < 40; i++) {
            JsonNode order = get("/api/seckill/orders/" + orderId, userId);
            status = order.path("data").path("status").asText();
            if ("CANCELED_TIMEOUT".equals(status)) {
                break;
            }
            Thread.sleep(1000);
        }
        assertEquals("CANCELED_TIMEOUT", status, "order should timeout-cancel");

        Thread.sleep(1000);
        JsonNode after = get("/api/admin/activities/" + activityId + "/stock", null);
        int availableAfter = after.path("data").path("availableStock").asInt();
        assertEquals(availableBefore + 1, availableAfter,
                "MySQL stock should roll back after timeout cancel");
    }

    // ----------------- helpers -----------------

    private static JsonNode createActivity(String name, int stock, int startOffsetSec, long skuId) throws Exception {
        LocalDateTime start = LocalDateTime.now().plusSeconds(startOffsetSec);
        LocalDateTime end = LocalDateTime.now().plusHours(1);
        String body = """
                {"name":"%s","startTime":"%s","endTime":"%s","skuId":%d,"skuName":"IT-SKU-%d",\
                "totalStock":%d,"perUserLimit":1,"unitPriceCent":100}
                """.formatted(name, start.format(FMT), end.format(FMT), skuId, skuId, stock);
        JsonNode resp = post("/api/admin/activities", body, null);
        assertEquals(0, resp.path("code").asInt(), resp.toString());
        return resp.path("data");
    }

    private static void warmup(long activityId) throws Exception {
        JsonNode resp = post("/api/admin/activities/" + activityId + "/warmup", "{}", null);
        assertEquals(0, resp.path("code").asInt(), resp.toString());
        assertTrue(resp.path("data").asInt() > 0, "warmup should warm at least 1 sku");
    }

    private static void waitUntil(String startTime, int bufferSec) throws Exception {
        LocalDateTime start = LocalDateTime.parse(startTime.length() > 19 ? startTime.substring(0, 19) : startTime, FMT);
        long waitMs = Duration.between(LocalDateTime.now(), start.plusSeconds(bufferSec)).toMillis();
        if (waitMs > 0) {
            Thread.sleep(waitMs);
        }
    }

    private static JsonNode placeOrder(long userId, long activityId, long skuId, String requestId) throws Exception {
        String body = "{\"activityId\":%d,\"skuId\":%d,\"requestId\":\"%s\",\"qty\":1}"
                .formatted(activityId, skuId, requestId);
        return post("/api/seckill/orders", body, userId);
    }

    private static String waitForStatus(long userId, long orderId, String expect, int maxSec) throws Exception {
        String status = "";
        for (int i = 0; i < maxSec; i++) {
            JsonNode order = get("/api/seckill/orders/" + orderId, userId);
            status = order.path("data").path("status").asText();
            if (expect.equals(status)) {
                return status;
            }
            Thread.sleep(1000);
        }
        return status;
    }

    private static JsonNode get(String path, Long userId) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(BASE + path))
                .timeout(Duration.ofSeconds(10))
                .GET();
        if (userId != null) {
            b.header("X-User-Id", String.valueOf(userId));
        }
        HttpResponse<String> resp = ComposeStackSupport.client().send(b.build(), HttpResponse.BodyHandlers.ofString());
        assertTrue(resp.statusCode() < 500, "GET " + path + " status=" + resp.statusCode());
        return MAPPER.readTree(resp.body());
    }

    private static JsonNode post(String path, String json, Long userId) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(BASE + path))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json == null ? "{}" : json));
        if (userId != null) {
            b.header("X-User-Id", String.valueOf(userId));
        }
        HttpResponse<String> resp = ComposeStackSupport.client().send(b.build(), HttpResponse.BodyHandlers.ofString());
        assertTrue(resp.statusCode() < 500, "POST " + path + " status=" + resp.statusCode() + " body=" + resp.body());
        return MAPPER.readTree(resp.body());
    }

    private static boolean canExecMysql() {
        try {
            Process p = new ProcessBuilder("docker", "exec", "seckill-mysql",
                    "mysql", "-u", "seckill", "-pseckill",
                    "seckill", "-N", "-e", "SELECT 1")
                    .redirectErrorStream(true)
                    .start();
            boolean finished = p.waitFor(8, TimeUnit.SECONDS);
            return finished && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static void execMysql(String sql) throws Exception {
        Process p = new ProcessBuilder("docker", "exec", "seckill-mysql",
                "mysql", "-u", "seckill", "-pseckill", "seckill", "-e", sql)
                .redirectErrorStream(true)
                .start();
        String out = new String(p.getInputStream().readAllBytes());
        boolean finished = p.waitFor(10, TimeUnit.SECONDS);
        assertTrue(finished && p.exitValue() == 0,
                "mysql exec failed for: " + sql + " out=" + out);
    }
}
