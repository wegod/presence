package com.presence.seckill.web;

import com.presence.seckill.api.ApiResponse;
import com.presence.seckill.api.dto.ActivityResponse;
import com.presence.seckill.api.dto.CreateActivityRequest;
import com.presence.seckill.api.dto.OrderResponse;
import com.presence.seckill.api.dto.PageResponse;
import com.presence.seckill.api.dto.StockResponse;
import com.presence.seckill.app.ActivityAppService;
import com.presence.seckill.app.WarmupAppService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

/**
 * 管理端活动 API：创建活动、分页列表、详情/库存/订单、删除、手动预热。
 * <p>MVP 无鉴权；生产需加管理权限。开抢后库存不可改，预热也不覆盖。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminActivityController {

    private final ActivityAppService activityAppService;
    private final WarmupAppService warmupAppService;

    public AdminActivityController(ActivityAppService activityAppService, WarmupAppService warmupAppService) {
        this.activityAppService = activityAppService;
        this.warmupAppService = warmupAppService;
    }

    /**
     * 创建秒杀活动（含单个 SKU 库存行）。
     * 入参见 {@link CreateActivityRequest}；出参含 activityId / sku 库存快照。
     */
    @PostMapping("/activities")
    public ApiResponse<ActivityResponse> create(@Valid @RequestBody CreateActivityRequest request) {
        return ApiResponse.ok(activityAppService.create(request));
    }

    /**
     * 活动分页列表。
     * <p>优先使用 {@code page}/{@code size}；若仅传 {@code limit}（旧参数）则等价于 page=1、size=limit。
     */
    @GetMapping("/activities")
    public ApiResponse<PageResponse<ActivityResponse>> list(
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size,
            @RequestParam(value = "limit", required = false) Integer limit) {
        int resolvedPage = page != null ? page : 1;
        int resolvedSize;
        if (size != null) {
            resolvedSize = size;
        } else if (limit != null) {
            resolvedSize = limit;
        } else {
            resolvedSize = 20;
        }
        return ApiResponse.ok(activityAppService.listActivities(resolvedPage, resolvedSize));
    }

    /**
     * 查询活动详情（含关联 SKU 库存、Redis 预热/库存视图）。
     * 错误：ACTIVITY_NOT_FOUND。
     */
    @GetMapping("/activities/{id}")
    public ApiResponse<ActivityResponse> get(@PathVariable("id") Long id) {
        return ApiResponse.ok(activityAppService.getActivity(id));
    }

    /**
     * 查询活动下 SKU 的 MySQL + Redis 库存视图。
     */
    @GetMapping("/activities/{id}/stock")
    public ApiResponse<StockResponse> stock(@PathVariable("id") Long id) {
        return ApiResponse.ok(activityAppService.queryStock(id));
    }

    /**
     * 分页查询该活动下抢购成功的订单（来自 MySQL {@code seckill_order}）。
     * <p>可选 {@code page}/{@code size}（默认 1/50，size 上限 100）。
     * 出参字段：订单号 orderId、用户ID userId、SKU skuId、数量 qty、
     * 金额（分）amountCent、订单/支付状态 status、支付截止 payDeadline、创建时间 createdAt 等。
     * 错误：ACTIVITY_NOT_FOUND。
     */
    @GetMapping("/activities/{id}/orders")
    public ApiResponse<PageResponse<OrderResponse>> orders(
            @PathVariable("id") Long id,
            @RequestParam(value = "page", required = false, defaultValue = "1") int page,
            @RequestParam(value = "size", required = false, defaultValue = "50") int size) {
        return ApiResponse.ok(activityAppService.listOrders(id, page, size));
    }

    /**
     * 软删除活动（status=OFFLINE）。进行中活动会拒绝删除。
     */
    @DeleteMapping("/activities/{id}")
    public ApiResponse<Void> delete(@PathVariable("id") Long id) {
        activityAppService.deleteActivity(id);
        return ApiResponse.ok(null);
    }

    /**
     * 测试/演示：手动触发 Redis 预热。
     * 出参：本次成功写入的 SKU 数；开抢后或 token 已存在时不会覆盖库存。
     * 错误：ACTIVITY_NOT_FOUND / ACTIVITY_ENDED（已结束不可预热）。
     */
    @PostMapping("/activities/{id}/warmup")
    public ApiResponse<Integer> warmup(@PathVariable("id") Long id) {
        return ApiResponse.ok(warmupAppService.warmupActivityById(id));
    }
}
