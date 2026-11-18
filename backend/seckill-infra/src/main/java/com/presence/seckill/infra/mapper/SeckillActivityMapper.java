package com.presence.seckill.infra.mapper;

import com.presence.seckill.domain.model.SeckillActivity;
import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface SeckillActivityMapper {

    @Insert("""
            INSERT INTO seckill_activity(name, start_time, end_time, status, created_at, updated_at)
            VALUES(#{name}, #{startTime}, #{endTime}, #{status}, #{createdAt}, #{updatedAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(SeckillActivity activity);

    @Select("SELECT * FROM seckill_activity WHERE id = #{id}")
    SeckillActivity findById(@Param("id") Long id);

    @Select("""
            SELECT * FROM seckill_activity
            WHERE status = 1
              AND start_time > NOW(3)
              AND start_time <= DATE_ADD(NOW(3), INTERVAL #{minutes} MINUTE)
            """)
    List<SeckillActivity> findUpcomingWithinMinutes(@Param("minutes") int minutes);

    @Select("""
            SELECT * FROM seckill_activity
            WHERE status = 1 AND start_time <= NOW(3) AND end_time > NOW(3)
            """)
    List<SeckillActivity> findOngoing();

    /**
     * 已过结束时间的活动（含已下架），供过期 Redis 清理 Job 扫描。
     * <p>幂等：重复扫描已清理过的活动只会空删 key。
     */
    @Select("""
            SELECT * FROM seckill_activity
            WHERE end_time <= NOW(3)
            ORDER BY end_time ASC, id ASC
            LIMIT #{limit}
            """)
    List<SeckillActivity> findExpired(@Param("limit") int limit);

    @Select("""
            SELECT * FROM seckill_activity
            ORDER BY id DESC
            LIMIT #{limit}
            """)
    List<SeckillActivity> findRecent(@Param("limit") int limit);

    @Select("""
            SELECT COUNT(1) FROM seckill_activity
            WHERE status <> 2
            """)
    long countActive();

    @Select("""
            SELECT * FROM seckill_activity
            WHERE status <> 2
            ORDER BY id DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<SeckillActivity> findPage(@Param("offset") int offset, @Param("limit") int limit);

    @Update("""
            UPDATE seckill_activity
            SET status = #{status}, updated_at = #{updatedAt}
            WHERE id = #{id}
            """)
    int updateStatus(@Param("id") Long id,
                     @Param("status") Integer status,
                     @Param("updatedAt") LocalDateTime updatedAt);
}
