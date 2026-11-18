package com.presence.seckill;

import com.presence.seckill.domain.order.OrderStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OrderStatusMachineTest {

    @Test
    void processingCanCancelButNotPay() {
        assertTrue(OrderStatus.PROCESSING.canCancel());
        assertFalse(OrderStatus.PROCESSING.canPay());
    }

    @Test
    void pendingPayCanPayAndCancel() {
        assertTrue(OrderStatus.PENDING_PAY.canPay());
        assertTrue(OrderStatus.PENDING_PAY.canCancel());
    }

    @Test
    void paidIsTerminal() {
        assertTrue(OrderStatus.PAID.isTerminal());
        assertFalse(OrderStatus.PAID.canCancel());
    }
}
