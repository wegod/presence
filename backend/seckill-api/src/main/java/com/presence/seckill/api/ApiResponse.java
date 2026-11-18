package com.presence.seckill.api;

/**
 * 统一响应包装：{code, message, data}
 */
public record ApiResponse<T>(int code, String message, T data) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(ErrorCode.OK.getCode(), ErrorCode.OK.getMessage(), data);
    }

    /** 成功但带自定义提示（如幂等命中订单尚未可见）。 */
    public static <T> ApiResponse<T> ok(T data, String message) {
        return new ApiResponse<>(ErrorCode.OK.getCode(), message, data);
    }

    public static <T> ApiResponse<T> fail(ErrorCode errorCode) {
        return new ApiResponse<>(errorCode.getCode(), errorCode.getMessage(), null);
    }

    public static <T> ApiResponse<T> fail(ErrorCode errorCode, String message) {
        return new ApiResponse<>(errorCode.getCode(), message, null);
    }

    public static <T> ApiResponse<T> fail(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }

    public boolean success() {
        return code == ErrorCode.OK.getCode();
    }
}
