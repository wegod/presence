package com.presence.seckill.web;

import com.presence.seckill.api.ApiResponse;
import com.presence.seckill.api.dto.LoginResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 演示登录：随机生成 userId 并写入 Cookie，供秒杀接口识别用户。
 * <p>非真实鉴权；仅便于本地压测与联调。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    /** 用户身份 Cookie 名，与下单接口 {@code requireUserId} 约定一致。 */
    public static final String COOKIE_USER_ID = "seckill_uid";

    /**
     * 伪登录。
     * 出参：userId；副作用：设置 HttpOnly Cookie，有效期 7 天。
     */
    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(HttpServletResponse response) {
        long userId = ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_000_000_000L);
        Cookie cookie = new Cookie(COOKIE_USER_ID, String.valueOf(userId));
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        cookie.setMaxAge(7 * 24 * 3600);
        response.addCookie(cookie);
        return ApiResponse.ok(new LoginResponse(userId));
    }

    /**
     * 读取当前 Cookie 中的 userId（刷新页面后恢复登录态）。
     * 未登录时 data 为 null。
     */
    @GetMapping("/me")
    public ApiResponse<LoginResponse> me(HttpServletRequest request) {
        Long userId = readUserId(request);
        if (userId == null) {
            return ApiResponse.ok(null);
        }
        return ApiResponse.ok(new LoginResponse(userId));
    }

    /**
     * 登出：清除 Cookie。
     */
    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletResponse response) {
        Cookie cookie = new Cookie(COOKIE_USER_ID, "");
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        cookie.setMaxAge(0);
        response.addCookie(cookie);
        return ApiResponse.ok(null);
    }

    static Long readUserId(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (COOKIE_USER_ID.equals(cookie.getName())) {
                try {
                    return Long.parseLong(cookie.getValue());
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
    }
}
