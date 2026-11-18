package com.presence.seckill;

import com.presence.seckill.api.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ErrorCodeContractTest {

    @Test
    void frozenErrorCodes() {
        assertEquals(0, ErrorCode.OK.getCode());
        assertEquals(1001, ErrorCode.ACTIVITY_NOT_STARTED.getCode());
        assertEquals(1003, ErrorCode.STOCK_INSUFFICIENT.getCode());
        assertEquals(1006, ErrorCode.SECKILL_STOPPED.getCode());
        assertEquals(1009, ErrorCode.DEPENDENCY_KAFKA_UNAVAILABLE.getCode());
    }
}
