package com.presence.seckill.infra.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.presence.seckill.api.BusinessException;
import com.presence.seckill.api.ErrorCode;
import com.presence.seckill.domain.event.StockDeductEvent;
import com.presence.seckill.domain.order.OutboxStatus;
import com.presence.seckill.infra.mapper.SeckillOutboxMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 库存扣减事件生产者。
 * <p>热路径用 {@link #sendAsync}：提交后立即返回，成功/失败在回调里更新 Outbox，不阻塞请求线程。
 * 补偿任务仍用 {@link #sendSync} 同步确认投递。
 * 连续失败走 Resilience4j {@code kafka} 熔断；下单侧尽力发送，失败保留 Outbox 由补偿中继。
 */
@Slf4j
@Service
public class StockDeductProducer {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final SeckillOutboxMapper outboxMapper;

    public StockDeductProducer(KafkaTemplate<String, String> kafkaTemplate,
                               ObjectMapper objectMapper,
                               SeckillOutboxMapper outboxMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.outboxMapper = outboxMapper;
    }

    /**
     * 异步发送 STOCK_DEDUCT：仅提交到 producer buffer，不在请求线程 {@code get}。
     * <p>成功回调将 Outbox 标为 SENT；失败回调打日志并 markRetry(FAILED)，留给 OutboxCompensate。
     *
     * @param outboxId 同事务写入的 Outbox 主键
     * @throws BusinessException DEPENDENCY_KAFKA_UNAVAILABLE 熔断打开或提交阶段失败
     */
    @CircuitBreaker(name = "kafka", fallbackMethod = "sendAsyncFallback")
    public void sendAsync(StockDeductEvent event, Long outboxId) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            String key = String.valueOf(event.skuId());
            CompletableFuture<SendResult<String, String>> future =
                    kafkaTemplate.send(KafkaTopics.STOCK_DEDUCT, key, payload);
            future.whenComplete((result, ex) -> onSendComplete(event, outboxId, ex));
            log.info("Kafka sendAsync submitted orderId={} requestId={} skuId={} outboxId={}",
                    event.orderId(), event.requestId(), event.skuId(), outboxId);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "事件序列化失败");
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Kafka sendAsync submit failed orderId={} requestId={} skuId={} outboxId={}",
                    event.orderId(), event.requestId(), event.skuId(), outboxId, e);
            markOutboxFailed(outboxId, event);
            throw new BusinessException(ErrorCode.DEPENDENCY_KAFKA_UNAVAILABLE);
        }
    }

    /**
     * 同步发送（Outbox 补偿用）。短超时等待 broker ack。
     * @throws BusinessException DEPENDENCY_KAFKA_UNAVAILABLE 发送失败或熔断打开
     */
    @CircuitBreaker(name = "kafka", fallbackMethod = "sendSyncFallback")
    public void sendSync(StockDeductEvent event) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            String key = String.valueOf(event.skuId());
            CompletableFuture<SendResult<String, String>> future =
                    kafkaTemplate.send(KafkaTopics.STOCK_DEDUCT, key, payload);
            future.get(3, TimeUnit.SECONDS);
            log.info("Kafka sendSync success orderId={} requestId={} skuId={}",
                    event.orderId(), event.requestId(), event.skuId());
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "事件序列化失败");
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Kafka sendSync failed orderId={} requestId={} skuId={}",
                    event.orderId(), event.requestId(), event.skuId(), e);
            throw new BusinessException(ErrorCode.DEPENDENCY_KAFKA_UNAVAILABLE);
        }
    }

    private void onSendComplete(StockDeductEvent event, Long outboxId, Throwable ex) {
        if (ex == null) {
            try {
                outboxMapper.updateStatus(outboxId, OutboxStatus.SENT);
                log.info("Kafka sendAsync success orderId={} requestId={} skuId={} outboxId={}",
                        event.orderId(), event.requestId(), event.skuId(), outboxId);
            } catch (Exception dbEx) {
                log.error("Kafka sendAsync success but Outbox SENT update failed orderId={} outboxId={}",
                        event.orderId(), outboxId, dbEx);
            }
            return;
        }
        log.warn("Kafka sendAsync failed orderId={} requestId={} skuId={} outboxId={} cause={}",
                event.orderId(), event.requestId(), event.skuId(), outboxId, ex.toString());
        markOutboxFailed(outboxId, event);
    }

    private void markOutboxFailed(Long outboxId, StockDeductEvent event) {
        if (outboxId == null) {
            return;
        }
        try {
            outboxMapper.markRetry(outboxId, OutboxStatus.FAILED, LocalDateTime.now().plusSeconds(5));
        } catch (Exception dbEx) {
            log.error("Outbox mark FAILED failed orderId={} outboxId={} — leave NEW for compensate",
                    event != null ? event.orderId() : null, outboxId, dbEx);
        }
    }

    @SuppressWarnings("unused")
    private void sendAsyncFallback(StockDeductEvent event, Long outboxId, Throwable t) {
        log.warn("Kafka sendAsync circuit fallback orderId={} requestId={} skuId={} outboxId={} cause={}",
                event.orderId(), event.requestId(), event.skuId(), outboxId, t.toString());
        // 熔断打开：保留 Outbox NEW/FAILED，由补偿任务在熔断恢复后中继
        throw new BusinessException(ErrorCode.DEPENDENCY_KAFKA_UNAVAILABLE);
    }

    /** 熔断打开时的统一失败出口；Outbox 补偿通过 DependencyGuard.isKafkaOpen 跳过发送。 */
    @SuppressWarnings("unused")
    private void sendSyncFallback(StockDeductEvent event, Throwable t) {
        log.warn("Kafka sendSync circuit fallback orderId={} requestId={} skuId={} cause={}",
                event.orderId(), event.requestId(), event.skuId(), t.toString());
        throw new BusinessException(ErrorCode.DEPENDENCY_KAFKA_UNAVAILABLE);
    }
}
