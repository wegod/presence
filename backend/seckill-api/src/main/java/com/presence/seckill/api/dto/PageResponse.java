package com.presence.seckill.api.dto;

import java.util.List;

/**
 * 分页列表响应。
 */
public record PageResponse<T>(
        List<T> items,
        long total,
        int page,
        int size
) {
}
