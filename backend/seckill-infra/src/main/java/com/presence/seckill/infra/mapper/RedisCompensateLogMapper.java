package com.presence.seckill.infra.mapper;

import com.presence.seckill.domain.model.RedisCompensateLog;
import org.apache.ibatis.annotations.*;

/**
 * Redis 补偿审计表访问。Job 工作队列已迁至 Redis ZSet，本 Mapper 仅 insert/查询审计。
 */
@Mapper
public interface RedisCompensateLogMapper {

    @Insert("""
            INSERT INTO redis_compensate_log(
              compensate_token, user_id, request_id, activity_id, sku_id, qty,
              action, status, detail, created_at, updated_at
            ) VALUES(
              #{compensateToken}, #{userId}, #{requestId}, #{activityId}, #{skuId}, #{qty},
              #{action}, #{status}, #{detail}, #{createdAt}, #{updatedAt}
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(RedisCompensateLog log);

    @Select("SELECT * FROM redis_compensate_log WHERE compensate_token = #{token}")
    RedisCompensateLog findByToken(@Param("token") String token);
}
