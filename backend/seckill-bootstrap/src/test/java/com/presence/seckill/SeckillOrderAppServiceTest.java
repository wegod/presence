package com.presence.seckill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.presence.seckill.api.BusinessException;
import com.presence.seckill.api.ErrorCode;
import com.presence.seckill.api.dto.PlaceOrderRequest;
import com.presence.seckill.app.PlaceOrderResult;
import com.presence.seckill.app.SeckillOrderAppService;
import com.presence.seckill.domain.activity.ActivityStatus;
import com.presence.seckill.domain.model.RedisCompensateLog;
import com.presence.seckill.domain.model.SeckillActivity;
import com.presence.seckill.domain.model.SeckillOrder;
import com.presence.seckill.domain.model.SeckillOutbox;
import com.presence.seckill.domain.model.SeckillSkuStock;
import com.presence.seckill.domain.order.OrderStatus;
import com.presence.seckill.infra.circuit.MysqlCircuitExecutor;
import com.presence.seckill.infra.kafka.StockDeductProducer;
import com.presence.seckill.infra.mapper.RedisCompensateLogMapper;
import com.presence.seckill.infra.mapper.SeckillActivityMapper;
import com.presence.seckill.infra.mapper.SeckillOrderMapper;
import com.presence.seckill.infra.mapper.SeckillOutboxMapper;
import com.presence.seckill.infra.mapper.SeckillSkuStockMapper;
import com.presence.seckill.infra.redis.LuaPreDeductResult;
import com.presence.seckill.infra.redis.SeckillRedisStockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 下单热路径单元测试：时间窗校验 → Lua → MySQL → Kafka；幂等无单成功提示 / 无热路径 Redis rollback。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SeckillOrderAppServiceTest {

    @Mock SeckillActivityMapper activityMapper;
    @Mock SeckillSkuStockMapper stockMapper;
    @Mock SeckillOrderMapper orderMapper;
    @Mock SeckillOutboxMapper outboxMapper;
    @Mock RedisCompensateLogMapper compensateLogMapper;
    @Mock SeckillRedisStockService redisStockService;
    @Mock StockDeductProducer stockDeductProducer;
    @Mock MysqlCircuitExecutor mysqlCircuitExecutor;
    @Mock TransactionTemplate transactionTemplate;

    SeckillOrderAppService service;

    private static final long USER = 42L;
    private static final PlaceOrderRequest REQ = new PlaceOrderRequest(1L, 1001L, "rid-1", 1);

    @BeforeEach
    void setUp() {
        service = new SeckillOrderAppService(
                activityMapper, stockMapper, orderMapper, outboxMapper, compensateLogMapper,
                redisStockService, stockDeductProducer, mysqlCircuitExecutor,
                transactionTemplate, new ObjectMapper(), 15, 86400L
        );
        when(activityMapper.findById(1L)).thenReturn(ongoingActivity());
        // 默认：熔断器透明执行 supplier（doAnswer 避免 stubbing 时触发旧 answer）
        doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Supplier<Object> supplier = inv.getArgument(0);
            return supplier.get();
        }).when(mysqlCircuitExecutor).execute(any());
        doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            TransactionCallback<Object> cb = inv.getArgument(0);
            return cb.doInTransaction(new SimpleTransactionStatus());
        }).when(transactionTemplate).execute(any());
    }

    @Test
    void activityWindowCheckedThenLuaBeforeOrderWrite() {
        SeckillSkuStock stock = stock(100);
        when(stockMapper.findByActivityAndSku(1L, 1001L)).thenReturn(stock);
        when(orderMapper.insert(any())).thenAnswer(inv -> {
            SeckillOrder o = inv.getArgument(0);
            o.setId(99L);
            return 1;
        });
        when(outboxMapper.insert(any(SeckillOutbox.class))).thenAnswer(inv -> {
            SeckillOutbox ob = inv.getArgument(0);
            ob.setId(1001L);
            return 1;
        });

        AtomicBoolean luaBeforeOrderWrite = new AtomicBoolean(false);
        AtomicBoolean sawOrderWrite = new AtomicBoolean(false);
        when(redisStockService.preDeduct(anyLong(), anyLong(), anyLong(), anyString(), anyInt(), anyLong()))
                .thenAnswer(inv -> {
                    luaBeforeOrderWrite.set(!sawOrderWrite.get());
                    return new LuaPreDeductResult(1, "ok");
                });
        when(stockMapper.findByActivityAndSku(anyLong(), anyLong())).thenAnswer(inv -> {
            sawOrderWrite.set(true);
            return stock;
        });

        PlaceOrderResult result = service.placeOrder(USER, REQ);

        assertTrue(luaBeforeOrderWrite.get(), "Lua must run before order/stock write");
        verify(activityMapper).findById(1L);
        assertNotNull(result.order());
        assertEquals(99L, result.order().orderId());
        verify(redisStockService).confirmPreDeduct(USER, "rid-1");
        verify(redisStockService, never()).rollback(anyLong(), anyLong(), anyLong(), anyString(), anyInt());
        verify(redisStockService, never()).rollbackOrEnqueuePending(anyLong(), anyLong(), anyLong(), anyString(), anyInt());
    }

    @Test
    void rejectsWhenActivityNotStarted() {
        SeckillActivity a = ongoingActivity();
        a.setStartTime(LocalDateTime.now().plusMinutes(10));
        when(activityMapper.findById(1L)).thenReturn(a);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.placeOrder(USER, REQ));
        assertEquals(ErrorCode.ACTIVITY_NOT_STARTED, ex.getErrorCode());
        verifyNoInteractions(redisStockService, stockMapper, orderMapper, outboxMapper);
    }

    @Test
    void rejectsWhenActivityEnded() {
        SeckillActivity a = ongoingActivity();
        a.setEndTime(LocalDateTime.now().minusSeconds(1));
        when(activityMapper.findById(1L)).thenReturn(a);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.placeOrder(USER, REQ));
        assertEquals(ErrorCode.ACTIVITY_ENDED, ex.getErrorCode());
        verifyNoInteractions(redisStockService, stockMapper, orderMapper, outboxMapper);
    }

    @Test
    void idempotentHitWithoutOrderReturnsCheckListMessageAndDoesNotRollback() {
        when(redisStockService.preDeduct(anyLong(), anyLong(), anyLong(), anyString(), anyInt(), anyLong()))
                .thenReturn(new LuaPreDeductResult(2, "idem"));
        when(orderMapper.findByUserAndRequest(USER, "rid-1")).thenReturn(null);

        PlaceOrderResult result = service.placeOrder(USER, REQ);

        assertNull(result.order());
        assertEquals("请到订单列表查看", result.message());
        verify(stockMapper, never()).findByActivityAndSku(anyLong(), anyLong());
        verify(redisStockService, never()).rollback(anyLong(), anyLong(), anyLong(), anyString(), anyInt());
        verify(redisStockService, never()).rollbackOrEnqueuePending(anyLong(), anyLong(), anyLong(), anyString(), anyInt());
        verify(redisStockService, never()).confirmPreDeduct(anyLong(), anyString());
    }

    @Test
    void idempotentHitWithOrderReturnsExistingOrder() {
        when(redisStockService.preDeduct(anyLong(), anyLong(), anyLong(), anyString(), anyInt(), anyLong()))
                .thenReturn(new LuaPreDeductResult(2, "idem"));
        SeckillOrder existing = order(77L, OrderStatus.PENDING_PAY.name());
        when(orderMapper.findByUserAndRequest(USER, "rid-1")).thenReturn(existing);

        PlaceOrderResult result = service.placeOrder(USER, REQ);

        assertNotNull(result.order());
        assertEquals(77L, result.order().orderId());
        assertNull(result.message());
        verify(redisStockService, never()).rollback(anyLong(), anyLong(), anyLong(), anyString(), anyInt());
    }

    @Test
    void duplicateKeyWithoutOrderReturnsCheckListAndDoesNotRollback() {
        when(redisStockService.preDeduct(anyLong(), anyLong(), anyLong(), anyString(), anyInt(), anyLong()))
                .thenReturn(new LuaPreDeductResult(1, "ok"));
        doThrow(new RuntimeException("wrap", new DuplicateKeyException("uk_user_request")))
                .when(mysqlCircuitExecutor).execute(any());
        when(orderMapper.findByUserAndRequest(USER, "rid-1")).thenReturn(null);

        PlaceOrderResult result = service.placeOrder(USER, REQ);

        assertNull(result.order());
        assertEquals("请到订单列表查看", result.message());
        verify(redisStockService, never()).rollback(anyLong(), anyLong(), anyLong(), anyString(), anyInt());
        verify(redisStockService, never()).rollbackOrEnqueuePending(anyLong(), anyLong(), anyLong(), anyString(), anyInt());
    }

    @Test
    void duplicateKeyWithOrderConfirmsAndReturnsOrderWithoutRollback() {
        when(redisStockService.preDeduct(anyLong(), anyLong(), anyLong(), anyString(), anyInt(), anyLong()))
                .thenReturn(new LuaPreDeductResult(1, "ok"));
        doThrow(new RuntimeException("wrap", new DuplicateKeyException("uk_user_request")))
                .when(mysqlCircuitExecutor).execute(any());
        SeckillOrder existing = order(88L, OrderStatus.PROCESSING.name());
        when(orderMapper.findByUserAndRequest(USER, "rid-1")).thenReturn(existing);

        PlaceOrderResult result = service.placeOrder(USER, REQ);

        assertEquals(88L, result.order().orderId());
        verify(redisStockService).confirmPreDeduct(USER, "rid-1");
        verify(redisStockService, never()).rollback(anyLong(), anyLong(), anyLong(), anyString(), anyInt());
    }

    @Test
    void mysqlWriteFailureAuditsOnlyAndDoesNotRollbackRedis() {
        when(redisStockService.preDeduct(anyLong(), anyLong(), anyLong(), anyString(), anyInt(), anyLong()))
                .thenReturn(new LuaPreDeductResult(1, "ok"));
        doThrow(new BusinessException(ErrorCode.ACTIVITY_NOT_FOUND))
                .when(mysqlCircuitExecutor).execute(any());

        BusinessException ex = assertThrows(BusinessException.class, () -> service.placeOrder(USER, REQ));
        assertEquals(ErrorCode.ACTIVITY_NOT_FOUND, ex.getErrorCode());

        ArgumentCaptor<RedisCompensateLog> cap = ArgumentCaptor.forClass(RedisCompensateLog.class);
        verify(compensateLogMapper).insert(cap.capture());
        assertEquals("HELD_PENDING", cap.getValue().getAction());
        assertEquals("DONE", cap.getValue().getStatus());
        verify(redisStockService, never()).rollback(anyLong(), anyLong(), anyLong(), anyString(), anyInt());
        verify(redisStockService, never()).rollbackOrEnqueuePending(anyLong(), anyLong(), anyLong(), anyString(), anyInt());
    }

    @Test
    void luaRejectStockDoesNotTouchOrderWriters() {
        when(redisStockService.preDeduct(anyLong(), anyLong(), anyLong(), anyString(), anyInt(), anyLong()))
                .thenReturn(new LuaPreDeductResult(-1, "stock"));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.placeOrder(USER, REQ));
        assertEquals(ErrorCode.STOCK_INSUFFICIENT, ex.getErrorCode());
        verify(activityMapper).findById(1L);
        verifyNoInteractions(stockMapper, orderMapper, outboxMapper, compensateLogMapper);
    }

    @Test
    void successPathOrderIsWindowThenLuaThenMysqlThenConfirmThenKafka() {
        when(redisStockService.preDeduct(anyLong(), anyLong(), anyLong(), anyString(), anyInt(), anyLong()))
                .thenReturn(new LuaPreDeductResult(1, "ok"));
        when(stockMapper.findByActivityAndSku(1L, 1001L)).thenReturn(stock(50));
        when(orderMapper.insert(any())).thenAnswer(inv -> {
            ((SeckillOrder) inv.getArgument(0)).setId(11L);
            return 1;
        });
        when(outboxMapper.insert(any(SeckillOutbox.class))).thenAnswer(inv -> {
            ((SeckillOutbox) inv.getArgument(0)).setId(501L);
            return 1;
        });

        service.placeOrder(USER, REQ);

        InOrder inOrder = inOrder(activityMapper, redisStockService, stockMapper, stockDeductProducer);
        inOrder.verify(activityMapper).findById(1L);
        inOrder.verify(redisStockService).preDeduct(eq(1L), eq(1001L), eq(USER), eq("rid-1"), eq(1), eq(86400L));
        inOrder.verify(stockMapper).findByActivityAndSku(1L, 1001L);
        inOrder.verify(redisStockService).confirmPreDeduct(USER, "rid-1");
        inOrder.verify(stockDeductProducer).sendAsync(any(), eq(501L));
        verify(outboxMapper, never()).updateStatus(anyLong(), anyInt());
        verify(outboxMapper, never()).findByUnique(anyString(), anyLong(), anyString());
    }

    private static SeckillActivity ongoingActivity() {
        SeckillActivity a = new SeckillActivity();
        a.setId(1L);
        a.setStatus(ActivityStatus.ONLINE);
        a.setStartTime(LocalDateTime.now().minusHours(1));
        a.setEndTime(LocalDateTime.now().plusHours(2));
        return a;
    }

    private static SeckillSkuStock stock(int available) {
        SeckillSkuStock s = new SeckillSkuStock();
        s.setActivityId(1L);
        s.setSkuId(1001L);
        s.setAvailableStock(available);
        s.setUnitPriceCent(100);
        s.setSaleEnabled(true);
        return s;
    }

    private static SeckillOrder order(long id, String status) {
        SeckillOrder o = new SeckillOrder();
        o.setId(id);
        o.setUserId(USER);
        o.setRequestId("rid-1");
        o.setActivityId(1L);
        o.setSkuId(1001L);
        o.setQty(1);
        o.setAmountCent(100);
        o.setStatus(status);
        o.setVersion(0);
        o.setPayDeadline(LocalDateTime.now().plusMinutes(15));
        o.setCreatedAt(LocalDateTime.now());
        return o;
    }
}
