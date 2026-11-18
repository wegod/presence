package com.presence.seckill.web;

import com.presence.seckill.api.ApiResponse;
import com.presence.seckill.api.BusinessException;
import com.presence.seckill.api.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ApiResponse<Void> handleBusiness(BusinessException e) {
        return ApiResponse.fail(e.getErrorCode(), e.getMessage());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class, IllegalArgumentException.class})
    public ApiResponse<Void> handleValidation(Exception e) {
        return ApiResponse.fail(ErrorCode.INVALID_PARAM, e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ApiResponse<Void> handleOther(Exception e) {
        log.error("Unhandled error", e);
        return ApiResponse.fail(ErrorCode.SYSTEM_ERROR);
    }
}
