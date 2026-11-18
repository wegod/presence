package com.presence.seckill.app;

import com.presence.seckill.api.BusinessException;
import com.presence.seckill.api.ErrorCode;
import com.presence.seckill.api.dto.PayResponse;
import com.presence.seckill.domain.model.SeckillOrder;
import com.presence.seckill.domain.model.SeckillPayRecord;
import com.presence.seckill.domain.order.OrderStatus;
import com.presence.seckill.infra.mapper.SeckillOrderMapper;
import com.presence.seckill.infra.mapper.SeckillPayRecordMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 模拟支付应用服务。
 * <p>演示链路：创建支付单(INIT) → 立刻走成功回调 → 订单 PENDING_PAY → PAID。
 * <p>真实环境可拆成「创单」与「渠道回调」两步；查单接口会对 INIT 做补偿回调。
 */
@Slf4j
@Service
public class PayAppService {

    private final SeckillOrderMapper orderMapper;
    private final SeckillPayRecordMapper payRecordMapper;

    public PayAppService(SeckillOrderMapper orderMapper, SeckillPayRecordMapper payRecordMapper) {
        this.orderMapper = orderMapper;
        this.payRecordMapper = payRecordMapper;
    }

    /**
     * 用户发起模拟支付。
     * 已支付则幂等返回；仅 PENDING_PAY 且未过 payDeadline 可创单。
     */
    @Transactional
    public PayResponse mockPay(Long userId, Long orderId) {
        SeckillOrder order = orderMapper.findById(orderId);
        if (order == null || !order.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.ORDER_NOT_FOUND);
        }
        if (OrderStatus.PAID.name().equals(order.getStatus())) {
            SeckillPayRecord existing = payRecordMapper.findByOrderId(orderId);
            return new PayResponse(orderId, existing != null ? existing.getPayTradeNo() : "", "SUCCESS", order.getAmountCent());
        }
        if (!OrderStatus.PENDING_PAY.name().equals(order.getStatus())) {
            log.warn("pay create reject invalid status orderId={} status={}", orderId, order.getStatus());
            throw new BusinessException(ErrorCode.ORDER_STATUS_INVALID, "仅待支付订单可支付");
        }
        if (order.getPayDeadline() != null && LocalDateTime.now().isAfter(order.getPayDeadline())) {
            log.warn("pay create reject past deadline orderId={} requestId={}", orderId, order.getRequestId());
            throw new BusinessException(ErrorCode.ORDER_STATUS_INVALID, "已超过支付截止时间");
        }

        SeckillPayRecord record = payRecordMapper.findByOrderId(orderId);
        if (record == null) {
            record = new SeckillPayRecord();
            record.setOrderId(orderId);
            record.setUserId(userId);
            record.setPayTradeNo("PAY" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
            record.setAmountCent(order.getAmountCent());
            record.setStatus("INIT");
            record.setCallbackReceived(false);
            LocalDateTime now = LocalDateTime.now();
            record.setCreatedAt(now);
            record.setUpdatedAt(now);
            try {
                payRecordMapper.insert(record);
                log.info("pay create record orderId={} requestId={} payTradeNo={}",
                        orderId, order.getRequestId(), record.getPayTradeNo());
            } catch (DuplicateKeyException e) {
                record = payRecordMapper.findByOrderId(orderId);
            }
        }

        // MVP：创单后立刻模拟渠道成功回调，省去外部支付网关
        return applyCallback(record.getPayTradeNo(), true);
    }

    /**
     * 支付回调入账。
     * 成功：订单乐观锁 PENDING_PAY→PAID，支付单标记 SUCCESS。
     * 失败：订单→PAY_FAILED（演示用；库存回补不在本服务，依赖取消/超时任务）。
     * 已成功回调则直接幂等返回。
     */
    @Transactional
    public PayResponse applyCallback(String payTradeNo, boolean success) {
        SeckillPayRecord record = payRecordMapper.findByTradeNo(payTradeNo);
        if (record == null) {
            throw new BusinessException(ErrorCode.ORDER_NOT_FOUND, "支付单不存在");
        }
        if ("SUCCESS".equals(record.getStatus())) {
            return new PayResponse(record.getOrderId(), record.getPayTradeNo(), "SUCCESS", record.getAmountCent());
        }

        SeckillOrder order = orderMapper.findById(record.getOrderId());
        if (order == null) {
            throw new BusinessException(ErrorCode.ORDER_NOT_FOUND);
        }
        if (OrderStatus.PAID.name().equals(order.getStatus())) {
            return new PayResponse(order.getId(), record.getPayTradeNo(), "SUCCESS", record.getAmountCent());
        }
        if (!OrderStatus.PENDING_PAY.name().equals(order.getStatus())) {
            log.warn("pay callback reject invalid status orderId={} status={} payTradeNo={}",
                    order.getId(), order.getStatus(), payTradeNo);
            throw new BusinessException(ErrorCode.ORDER_STATUS_INVALID);
        }

        LocalDateTime now = LocalDateTime.now();
        if (success) {
            int updated = orderMapper.updateStatusOptimistic(
                    order.getId(), OrderStatus.PENDING_PAY.name(), OrderStatus.PAID.name(), order.getVersion()
            );
            if (updated == 1) {
                payRecordMapper.markSuccess(record.getId(), "SUCCESS", now, true);
                log.info("pay callback success orderId={} requestId={} payTradeNo={}",
                        order.getId(), order.getRequestId(), payTradeNo);
            } else {
                log.warn("pay callback status race orderId={} payTradeNo={}", order.getId(), payTradeNo);
            }
            return new PayResponse(order.getId(), record.getPayTradeNo(), "SUCCESS", record.getAmountCent());
        } else {
            orderMapper.updateStatusOptimistic(
                    order.getId(), OrderStatus.PENDING_PAY.name(), OrderStatus.PAY_FAILED.name(), order.getVersion()
            );
            log.warn("pay callback failed orderId={} requestId={} payTradeNo={}",
                    order.getId(), order.getRequestId(), payTradeNo);
            return new PayResponse(order.getId(), record.getPayTradeNo(), "FAILED", record.getAmountCent());
        }
    }

    /**
     * 查支付状态。
     * 若支付单卡在 INIT 且订单仍待支付，视为回调丢失，补偿一次成功入账。
     */
    public PayResponse queryPayStatus(Long userId, Long orderId) {
        SeckillOrder order = orderMapper.findById(orderId);
        if (order == null || !order.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.ORDER_NOT_FOUND);
        }
        SeckillPayRecord record = payRecordMapper.findByOrderId(orderId);
        if (record == null) {
            return new PayResponse(orderId, null, "NONE", order.getAmountCent());
        }
        // 查单补偿：INIT 且订单仍待支付 → 模拟补回调成功（防演示环境丢回调）
        if ("INIT".equals(record.getStatus()) && OrderStatus.PENDING_PAY.name().equals(order.getStatus())) {
            log.info("pay status compensate callback orderId={} requestId={} payTradeNo={}",
                    orderId, order.getRequestId(), record.getPayTradeNo());
            return applyCallback(record.getPayTradeNo(), true);
        }
        return new PayResponse(orderId, record.getPayTradeNo(), record.getStatus(), record.getAmountCent());
    }
}
