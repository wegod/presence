package com.presence.seckill.app;

import com.presence.seckill.api.BusinessException;
import com.presence.seckill.api.ErrorCode;
import com.presence.seckill.api.dto.ActivityResponse;
import com.presence.seckill.api.dto.CreateActivityRequest;
import com.presence.seckill.api.dto.OrderResponse;
import com.presence.seckill.api.dto.PageResponse;
import com.presence.seckill.api.dto.StockResponse;
import com.presence.seckill.domain.activity.ActivityStatus;
import com.presence.seckill.domain.model.SeckillActivity;
import com.presence.seckill.domain.model.SeckillOrder;
import com.presence.seckill.domain.model.SeckillSkuStock;
import com.presence.seckill.infra.mapper.SeckillActivityMapper;
import com.presence.seckill.infra.mapper.SeckillOrderMapper;
import com.presence.seckill.infra.mapper.SeckillSkuStockMapper;
import com.presence.seckill.infra.redis.SeckillRedisStockService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

@Service
public class ActivityAppService {

    private final SeckillActivityMapper activityMapper;
    private final SeckillSkuStockMapper stockMapper;
    private final SeckillOrderMapper orderMapper;
    private final SeckillRedisStockService redisStockService;

    public ActivityAppService(SeckillActivityMapper activityMapper,
                              SeckillSkuStockMapper stockMapper,
                              SeckillOrderMapper orderMapper,
                              SeckillRedisStockService redisStockService) {
        this.activityMapper = activityMapper;
        this.stockMapper = stockMapper;
        this.orderMapper = orderMapper;
        this.redisStockService = redisStockService;
    }

    @Transactional
    public ActivityResponse create(CreateActivityRequest request) {
        if (!request.endTime().isAfter(request.startTime())) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "结束时间必须晚于开始时间");
        }
        if (request.totalStock() > 1000) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "总库存上限 1000");
        }
        LocalDateTime now = LocalDateTime.now();
        SeckillActivity activity = new SeckillActivity();
        activity.setName(request.name());
        activity.setStartTime(request.startTime());
        activity.setEndTime(request.endTime());
        activity.setStatus(ActivityStatus.ONLINE);
        activity.setCreatedAt(now);
        activity.setUpdatedAt(now);
        activityMapper.insert(activity);

        SeckillSkuStock stock = new SeckillSkuStock();
        stock.setActivityId(activity.getId());
        stock.setSkuId(request.skuId());
        stock.setSkuName(request.skuName());
        stock.setTotalStock(request.totalStock());
        stock.setAvailableStock(request.totalStock());
        stock.setPerUserLimit(request.perUserLimit());
        stock.setSaleEnabled(true);
        stock.setUnitPriceCent(request.unitPriceCent());
        stock.setVersion(0);
        stock.setCreatedAt(now);
        stock.setUpdatedAt(now);
        stockMapper.insert(stock);

        return toActivityResponse(activity, stock, false);
    }

    public StockResponse queryStock(Long activityId) {
        SeckillActivity activity = requireActivity(activityId);
        SeckillSkuStock stock = requireStock(activityId);
        return toStockResponse(activity, stock);
    }

    public ActivityResponse getActivity(Long activityId) {
        SeckillActivity activity = requireActivity(activityId);
        SeckillSkuStock stock = requireStock(activityId);
        return toActivityResponse(activity, stock, true);
    }

    /**
     * 分页活动列表（排除已软删 / OFFLINE），含首个 SKU 摘要与 Redis 预热视图。
     */
    public PageResponse<ActivityResponse> listActivities(int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        long total = activityMapper.countActive();
        int offset = (safePage - 1) * safeSize;
        List<ActivityResponse> items = activityMapper.findPage(offset, safeSize).stream()
                .map(activity -> {
                    var stocks = stockMapper.findByActivityId(activity.getId());
                    if (stocks.isEmpty()) {
                        return null;
                    }
                    return toActivityResponse(activity, stocks.getFirst(), true);
                })
                .filter(Objects::nonNull)
                .toList();
        return new PageResponse<>(items, total, safePage, safeSize);
    }

    /**
     * 分页查询该活动下抢购成功写入 MySQL 的订单（全部订单/支付状态）。
     * <p>按订单号倒序；活动不存在或已下架时抛 ACTIVITY_NOT_FOUND。
     */
    public PageResponse<OrderResponse> listOrders(Long activityId, int page, int size) {
        requireActivity(activityId);
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        long total = orderMapper.countByActivityId(activityId);
        int offset = (safePage - 1) * safeSize;
        List<OrderResponse> items = orderMapper.findPageByActivityId(activityId, offset, safeSize).stream()
                .map(this::toOrderResponse)
                .toList();
        return new PageResponse<>(items, total, safePage, safeSize);
    }

    /**
     * 软删除活动（status=OFFLINE）。进行中（已开抢且未结束）的活动禁止删除。
     */
    @Transactional
    public void deleteActivity(Long activityId) {
        SeckillActivity activity = activityMapper.findById(activityId);
        if (activity == null) {
            throw new BusinessException(ErrorCode.ACTIVITY_NOT_FOUND);
        }
        if (Objects.equals(activity.getStatus(), ActivityStatus.OFFLINE)) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        boolean midSale = Objects.equals(activity.getStatus(), ActivityStatus.ONLINE)
                && !now.isBefore(activity.getStartTime())
                && now.isBefore(activity.getEndTime());
        if (midSale) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "活动进行中，无法删除");
        }
        activityMapper.updateStatus(activityId, ActivityStatus.OFFLINE, now);
        var stocks = stockMapper.findByActivityId(activityId);
        for (SeckillSkuStock stock : stocks) {
            stockMapper.disableSale(activityId, stock.getSkuId());
        }
    }

    private SeckillActivity requireActivity(Long activityId) {
        SeckillActivity activity = activityMapper.findById(activityId);
        if (activity == null || Objects.equals(activity.getStatus(), ActivityStatus.OFFLINE)) {
            throw new BusinessException(ErrorCode.ACTIVITY_NOT_FOUND);
        }
        return activity;
    }

    private SeckillSkuStock requireStock(Long activityId) {
        var stocks = stockMapper.findByActivityId(activityId);
        if (stocks.isEmpty()) {
            throw new BusinessException(ErrorCode.ACTIVITY_NOT_FOUND);
        }
        return stocks.getFirst();
    }

    private ActivityResponse toActivityResponse(SeckillActivity activity, SeckillSkuStock stock, boolean withRedis) {
        Integer redisStock = null;
        Boolean warmed = null;
        Boolean redisSaleEnabled = null;
        if (withRedis) {
            try {
                warmed = redisStockService.isWarmed(activity.getId(), stock.getSkuId());
                redisStock = redisStockService.getRedisStock(activity.getId(), stock.getSkuId());
                redisSaleEnabled = redisStockService.getRedisSaleEnabled(activity.getId(), stock.getSkuId());
            } catch (RuntimeException ignored) {
                // 管理端列表/详情：Redis 不可用不阻断 MySQL 视图
                warmed = false;
            }
        }
        return new ActivityResponse(
                activity.getId(),
                activity.getName(),
                activity.getStartTime(),
                activity.getEndTime(),
                activity.getStatus(),
                stock.getSkuId(),
                stock.getSkuName(),
                stock.getTotalStock(),
                stock.getAvailableStock(),
                stock.getPerUserLimit(),
                stock.getUnitPriceCent(),
                stock.getSaleEnabled(),
                redisStock,
                warmed,
                redisSaleEnabled
        );
    }

    private OrderResponse toOrderResponse(SeckillOrder order) {
        return new OrderResponse(
                order.getId(),
                order.getUserId(),
                order.getRequestId(),
                order.getActivityId(),
                order.getSkuId(),
                order.getQty(),
                order.getAmountCent(),
                order.getStatus(),
                order.getPayDeadline(),
                order.getCreatedAt()
        );
    }

    private StockResponse toStockResponse(SeckillActivity activity, SeckillSkuStock stock) {
        Integer redisStock = null;
        Boolean warmed = false;
        Boolean redisSaleEnabled = null;
        try {
            warmed = redisStockService.isWarmed(activity.getId(), stock.getSkuId());
            redisStock = redisStockService.getRedisStock(activity.getId(), stock.getSkuId());
            redisSaleEnabled = redisStockService.getRedisSaleEnabled(activity.getId(), stock.getSkuId());
        } catch (RuntimeException ignored) {
            warmed = false;
        }
        return new StockResponse(
                activity.getId(),
                stock.getSkuId(),
                stock.getSkuName(),
                stock.getTotalStock(),
                stock.getAvailableStock(),
                stock.getPerUserLimit(),
                stock.getSaleEnabled(),
                stock.getUnitPriceCent(),
                redisStock,
                warmed,
                redisSaleEnabled
        );
    }
}
