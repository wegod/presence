package com.presence.seckill.it;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Compose 栈探测：未启动时 IT 自动 skip，避免本地无 Docker 时红灯。
 */
public final class ComposeStackSupport {

    public static final String BASE_URL = System.getenv().getOrDefault("BASE_URL", "http://localhost:18178");

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    private ComposeStackSupport() {
    }

    public static boolean isStackUp() {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(BASE_URL + "/actuator/health"))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            HttpResponse<String> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200 && resp.body() != null && resp.body().contains("\"status\":\"UP\"");
        } catch (Exception e) {
            return false;
        }
    }

    public static HttpClient client() {
        return CLIENT;
    }
}
