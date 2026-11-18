package com.presence.seckill.infra.mapper;

import com.presence.seckill.domain.model.SeckillOutbox;
import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface SeckillOutboxMapper {

    @Insert("""
            INSERT INTO seckill_outbox(
              aggregate_type, aggregate_id, event_type, payload, status,
              next_retry_at, retry_count, created_at, updated_at
            ) VALUES(
              #{aggregateType}, #{aggregateId}, #{eventType}, #{payload}, #{status},
              #{nextRetryAt}, #{retryCount}, #{createdAt}, #{updatedAt}
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(SeckillOutbox outbox);

    @Select("SELECT * FROM seckill_outbox WHERE id = #{id}")
    SeckillOutbox findById(@Param("id") Long id);

    @Select("""
            SELECT * FROM seckill_outbox
            WHERE aggregate_type = #{aggregateType}
              AND aggregate_id = #{aggregateId}
              AND event_type = #{eventType}
            """)
    SeckillOutbox findByUnique(@Param("aggregateType") String aggregateType,
                               @Param("aggregateId") Long aggregateId,
                               @Param("eventType") String eventType);

    @Update("""
            UPDATE seckill_outbox
            SET status = #{status}, updated_at = NOW(3)
            WHERE id = #{id}
            """)
    int updateStatus(@Param("id") Long id, @Param("status") int status);

    @Update("""
            UPDATE seckill_outbox
            SET status = #{status},
                retry_count = retry_count + 1,
                next_retry_at = #{nextRetryAt},
                updated_at = NOW(3)
            WHERE id = #{id}
            """)
    int markRetry(@Param("id") Long id, @Param("status") int status, @Param("nextRetryAt") LocalDateTime nextRetryAt);

    @Select("""
            SELECT * FROM seckill_outbox
            WHERE status IN (0, 2) AND next_retry_at <= #{now}
            ORDER BY id ASC LIMIT #{limit}
            """)
    List<SeckillOutbox> findPending(@Param("now") LocalDateTime now, @Param("limit") int limit);
}
