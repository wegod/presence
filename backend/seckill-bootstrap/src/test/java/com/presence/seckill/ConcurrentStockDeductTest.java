package com.presence.seckill;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 纯并发扣减模型单测：模拟双闸门中的条件扣减，验证不超卖。
 */
class ConcurrentStockDeductTest {

    @Test
    void conditionalDeductNeverOversells() throws Exception {
        AtomicInteger available = new AtomicInteger(100);
        AtomicInteger success = new AtomicInteger();

        Thread[] threads = IntStream.range(0, 1000).mapToObj(i -> new Thread(() -> {
            while (true) {
                int cur = available.get();
                if (cur <= 0) {
                    return;
                }
                if (available.compareAndSet(cur, cur - 1)) {
                    success.incrementAndGet();
                    return;
                }
            }
        })).toArray(Thread[]::new);

        for (Thread t : threads) {
            t.start();
        }
        for (Thread t : threads) {
            t.join();
        }

        assertEquals(0, available.get());
        assertEquals(100, success.get());
        assertTrue(success.get() <= 100);
    }

    @Test
    void idempotentKeyProducesSingleWin() throws Exception {
        AtomicInteger wins = new AtomicInteger();
        Object lock = new Object();
        boolean[] taken = {false};

        Thread[] threads = IntStream.range(0, 50).mapToObj(i -> new Thread(() -> {
            synchronized (lock) {
                if (!taken[0]) {
                    taken[0] = true;
                    wins.incrementAndGet();
                }
            }
        })).toArray(Thread[]::new);

        for (Thread t : threads) {
            t.start();
        }
        for (Thread t : threads) {
            t.join();
        }
        assertEquals(1, wins.get());
    }
}
