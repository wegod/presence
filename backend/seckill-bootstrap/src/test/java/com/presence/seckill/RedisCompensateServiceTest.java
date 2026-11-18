package com.presence.seckill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.presence.seckill.app.RedisCompensateService;
import com.presence.seckill.domain.model.RedisCompensateLog;
import com.presence.seckill.domain.model.SeckillOrder;
import com.presence.seckill.domain.order.OrderStatus;
import com.presence.seckill.domain.order.PreDeductState;
import com.presence.seckill.infra.mapper.RedisCompensateLogMapper;
import com.presence.seckill.infra.mapper.SeckillOrderMapper;
import com.presence.seckill.infra.mapper.SeckillOutboxMapper;
import com.presence.seckill.infra.mapper.SeckillSkuStockMapper;
import com.presence.seckill.infra.redis.PreDeductRecord;
import com.presence.seckill.infra.redis.SeckillRedisStockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Redis 补偿 Job：扫 ZSet（claimDuePending / ZRANGEBYSCORE），RedisCompensateLog 仅审计。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RedisCompensateServiceTest {

    @Mock SeckillRedisStockService redisStockService;
    @Mock SeckillOrderMapper orderMapper;
    @Mock SeckillOutboxMapper outboxMapper;
    @Mock SeckillSkuStockMapper stockMapper;
    @Mock RedisCompensateLogMapper compensateLogMapper;
    @Mock TransactionTemplate transactionTemplate;

    RedisCompensateService service;

    @BeforeEach
    void setUp() {
        when(redisStockService.getClaimLeaseMs()).thenReturn(10_000L);
        service = new RedisCompensateService(
                redisStockService, orderMapper, outboxMapper, stockMapper,
                compensateLogMapper, transactionTemplate, new ObjectMapper(),
                10_000L, 15
        );
    }

    @Test
    void compensateClaimsViaZrangeByScorePathThenConfirmsActiveOrder() {
        String member = "42:rid-a";
        when(redisStockService.claimDuePending(10)).thenReturn(List.of(member));

        PreDeductRecord record = heldRecord(42L, "rid-a");
        when(redisStockService.getPreDeductRecord(42L, "rid-a")).thenReturn(record);

        SeckillOrder order = new SeckillOrder();
        order.setId(9L);
        order.setUserId(42L);
        order.setRequestId("rid-a");
        order.setStatus(OrderStatus.PROCESSING.name());
        when(orderMapper.findByUserAndRequest(42L, "rid-a")).thenReturn(order);

        int done = service.compensate(10);

        assertEquals(1, done);
        verify(redisStockService).claimDuePending(10);
        verify(redisStockService).markConfirmedAndRemovePending(42L, "rid-a");
        verify(redisStockService, never()).rollback(anyLong(), anyLong(), anyLong(), anyString(), anyInt());

        ArgumentCaptor<RedisCompensateLog> cap = ArgumentCaptor.forClass(RedisCompensateLog.class);
        verify(compensateLogMapper).insert(cap.capture());
        assertEquals("CONFIRM", cap.getValue().getAction());
        assertEquals("DONE", cap.getValue().getStatus());
    }

    @Test
    void compensateRollsBackHeldAfterGraceWhenNoOrder() {
        String member = "7:rid-old";
        when(redisStockService.claimDuePending(5)).thenReturn(List.of(member));

        PreDeductRecord record = heldRecord(7L, "rid-old");
        record.setCreatedAtMs(System.currentTimeMillis() - 60_000L); // beyond create-grace
        when(redisStockService.getPreDeductRecord(7L, "rid-old")).thenReturn(record);
        when(orderMapper.findByUserAndRequest(7L, "rid-old")).thenReturn(null);

        int done = service.compensate(5);

        assertEquals(1, done);
        verify(redisStockService).rollback(1L, 1001L, 7L, "rid-old", 1);

        ArgumentCaptor<RedisCompensateLog> cap = ArgumentCaptor.forClass(RedisCompensateLog.class);
        verify(compensateLogMapper).insert(cap.capture());
        assertEquals("ROLLBACK_STOCK", cap.getValue().getAction());
        assertEquals("DONE", cap.getValue().getStatus());
    }

    @Test
    void auditLogIsNeverUsedAsPendingQueueDriver() {
        when(redisStockService.claimDuePending(1)).thenReturn(List.of());

        assertEquals(0, service.compensate(1));

        verify(redisStockService).claimDuePending(1);
        verifyNoInteractions(compensateLogMapper);
        verify(compensateLogMapper, never()).findByToken(anyString());
    }

    private static PreDeductRecord heldRecord(long userId, String requestId) {
        PreDeductRecord r = new PreDeductRecord();
        r.setState(PreDeductState.HELD);
        r.setActivityId(1L);
        r.setSkuId(1001L);
        r.setUserId(userId);
        r.setRequestId(requestId);
        r.setQty(1);
        r.setCreatedAtMs(System.currentTimeMillis());
        return r;
    }
}
