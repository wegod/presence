package com.presence.seckill.web;

import com.presence.seckill.api.ApiResponse;
import com.presence.seckill.api.BusinessException;
import com.presence.seckill.api.ErrorCode;
import com.presence.seckill.api.dto.OrderResponse;
import com.presence.seckill.api.dto.PayResponse;
import com.presence.seckill.api.dto.PlaceOrderRequest;
import com.presence.seckill.app.PayAppService;
import com.presence.seckill.app.PlaceOrderResult;
import com.presence.seckill.app.SeckillOrderAppService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 用户侧秒杀 API：下单 / 查单 / 取消 / 模拟支付。
 * <p>身份：优先 Cookie {@code seckill_uid}，其次请求头 {@code X-User-Id}；未登录返回 INVALID_PARAM。
 */
@RestController
@RequestMapping("/api/seckill")
public class SeckillOrderController {

    private final SeckillOrderAppService orderAppService;
    private final PayAppService payAppService;

    public SeckillOrderController(SeckillOrderAppService orderAppService, PayAppService payAppService) {
        this.orderAppService = orderAppService;
        this.payAppService = payAppService;
    }

    /**
     * 抢购下单。
     * <ul>
     *   <li>入参：activityId / skuId / requestId（前端一次点击生成，重试用同一值）/ qty</li>
     *   <li>出参：订单快照（通常 status=PROCESSING，异步扣库后变 PENDING_PAY）</li>
     *   <li>幂等命中但订单尚未落库可见时：code=0、data=null、message=请到订单列表查看</li>
     *   <li>常见错误：限流 RATE_LIMITED、DEPENDENCY_* 熔断、库存不足、限购、停售、未预热</li>
     * </ul>
     */
    @PostMapping("/orders")
    public ApiResponse<OrderResponse> placeOrder(@Valid @RequestBody PlaceOrderRequest request,
                                                 HttpServletRequest httpRequest) {
        Long userId = requireUserId(httpRequest);
        PlaceOrderResult result = orderAppService.placeOrder(userId, request);
        if (result.message() != null) {
            return ApiResponse.ok(result.order(), result.message());
        }
        return ApiResponse.ok(result.order());
    }

    /**
     * 查询单笔订单（仅本人）。
     * 错误：ORDER_NOT_FOUND（不存在或不属于当前用户）。
     */
    @GetMapping("/orders/{id}")
    public ApiResponse<OrderResponse> getOrder(@PathVariable("id") Long id, HttpServletRequest httpRequest) {
        return ApiResponse.ok(orderAppService.getOrder(requireUserId(httpRequest), id));
    }

    /**
     * 当前用户最近订单列表（上限由服务层控制，默认 50）。
     */
    @GetMapping("/orders")
    public ApiResponse<List<OrderResponse>> listOrders(HttpServletRequest httpRequest) {
        return ApiResponse.ok(orderAppService.listOrders(requireUserId(httpRequest)));
    }

    /**
     * 手动取消订单。
     * 仅 PROCESSING / PENDING_PAY 可取消；取消后回补 Redis（PENDING_PAY 时同时回补 MySQL 库存）。
     * 错误：ORDER_NOT_FOUND、ORDER_STATUS_INVALID。
     */
    @PostMapping("/orders/{id}/cancel")
    public ApiResponse<OrderResponse> cancel(@PathVariable("id") Long id, HttpServletRequest httpRequest) {
        return ApiResponse.ok(orderAppService.cancel(requireUserId(httpRequest), id));
    }

    /**
     * 模拟发起支付：创建支付单并立刻走成功回调，订单 → PAID。
     * 错误：非待支付、已超时、订单不存在。
     */
    @PostMapping("/orders/{id}/pay")
    public ApiResponse<PayResponse> pay(@PathVariable("id") Long id, HttpServletRequest httpRequest) {
        return ApiResponse.ok(payAppService.mockPay(requireUserId(httpRequest), id));
    }

    /**
     * 模拟支付回调（演示用，无需登录）。
     * body：payTradeNo 必填；success 缺省视为 true。
     */
    @PostMapping("/pay/callback")
    public ApiResponse<PayResponse> callback(@RequestBody Map<String, Object> body) {
        String tradeNo = String.valueOf(body.get("payTradeNo"));
        boolean success = body.get("success") == null || Boolean.parseBoolean(String.valueOf(body.get("success")));
        return ApiResponse.ok(payAppService.applyCallback(tradeNo, success));
    }

    /**
     * 查支付状态；若支付单仍为 INIT 且订单待支付，会补偿触发一次成功回调。
     */
    @GetMapping("/orders/{id}/pay-status")
    public ApiResponse<PayResponse> payStatus(@PathVariable("id") Long id, HttpServletRequest httpRequest) {
        return ApiResponse.ok(payAppService.queryPayStatus(requireUserId(httpRequest), id));
    }

    /** 解析当前用户；Cookie 与 Header 均无则视为未登录。 */
    private Long requireUserId(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (AuthController.COOKIE_USER_ID.equals(cookie.getName())) {
                    return Long.parseLong(cookie.getValue());
                }
            }
        }
        String header = request.getHeader("X-User-Id");
        if (header != null && !header.isBlank()) {
            return Long.parseLong(header);
        }
        throw new BusinessException(ErrorCode.INVALID_PARAM, "请先登录");
    }
}
