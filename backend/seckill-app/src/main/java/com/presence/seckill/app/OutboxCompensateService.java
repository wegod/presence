package com.presence.seckill.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.presence.seckill.domain.event.StockDeductEvent;
import com.presence.seckill.domain.model.SeckillOutbox;
import com.presence.seckill.domain.order.OutboxStatus;
import com.presence.seckill.infra.circuit.DependencyGuard;
import com.presence.seckill.infra.kafka.StockDeductProducer;
import com.presence.seckill.infra.mapper.SeckillOutboxMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Outbox 中继补偿：下单时 Kafka 发送失败留下的 NEW/FAILED 消息，在熔断恢复后补发。
 * <p>与主路径共用 StockDeductProducer；消费者侧靠订单状态幂等，重复发送安全。
 */
@Slf4j
@Service
public class OutboxCompensateService {

    private final SeckillOutboxMapper outboxMapper;
    private final StockDeductProducer producer;
    private final DependencyGuard dependencyGuard;
    private final ObjectMapper objectMapper;

    public OutboxCompensateService(SeckillOutboxMapper outboxMapper,
                                   StockDeductProducer producer,
                                   DependencyGuard dependencyGuard,
                                   ObjectMapper objectMapper) {
        this.outboxMapper = outboxMapper;
        this.producer = producer;
        this.dependencyGuard = dependencyGuard;
        this.objectMapper = objectMapper;
    }

    /**
     * 扫描到期待发 Outbox 并同步发送。
     *
     * @param limit 单次批次上限
     * @return 本批成功发送条数；Kafka 仍熔断时返回 0
     */
    public int compensate(int limit) {
        // 熔断未关闭时硬跳过，避免补偿流量把熔断打得更开
        if (dependencyGuard.isKafkaOpen()) {
            log.debug("outbox relay skip, Kafka circuit open");
            return 0;
        }
        List<SeckillOutbox> pending = outboxMapper.findPending(LocalDateTime.now(), limit);
        int sent = 0;
        for (SeckillOutbox outbox : pending) {
            try {
                StockDeductEvent event = objectMapper.readValue(outbox.getPayload(), StockDeductEvent.class);
                producer.sendSync(event);
                outboxMapper.updateStatus(outbox.getId(), OutboxStatus.SENT);
                sent++;
                log.info("outbox relay sent outboxId={} orderId={} requestId={} skuId={}",
                        outbox.getId(), event.orderId(), event.requestId(), event.skuId());
            } catch (Exception e) {
                // 指数退避：失败次数越多 nextRetryAt 越远
                log.warn("outbox relay failed outboxId={} aggregateId={}", outbox.getId(), outbox.getAggregateId(), e);
                outboxMapper.markRetry(outbox.getId(), OutboxStatus.FAILED, LocalDateTime.now().plusSeconds(5L * (outbox.getRetryCount() + 1)));
            }
        }
        return sent;
    }
}
