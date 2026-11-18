package com.presence.seckill.infra.mapper;

import com.presence.seckill.domain.model.SeckillOrder;
import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface SeckillOrderMapper {

    @Insert("""
            INSERT INTO seckill_order(
              user_id, request_id, activity_id, sku_id, qty, amount_cent,
              status, version, pay_deadline, stock_compensated, created_at, updated_at
            ) VALUES(
              #{userId}, #{requestId}, #{activityId}, #{skuId}, #{qty}, #{amountCent},
              #{status}, #{version}, #{payDeadline}, #{stockCompensated}, #{createdAt}, #{updatedAt}
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(SeckillOrder order);

    @Select("SELECT * FROM seckill_order WHERE id = #{id}")
    SeckillOrder findById(@Param("id") Long id);

    @Select("SELECT * FROM seckill_order WHERE user_id = #{userId} AND request_id = #{requestId}")
    SeckillOrder findByUserAndRequest(@Param("userId") Long userId, @Param("requestId") String requestId);

    @Select("SELECT * FROM seckill_order WHERE user_id = #{userId} ORDER BY id DESC LIMIT #{limit}")
    List<SeckillOrder> findByUserId(@Param("userId") Long userId, @Param("limit") int limit);

    @Update("""
            UPDATE seckill_order
            SET status = #{newStatus}, version = version + 1, updated_at = NOW(3)
            WHERE id = #{id} AND status = #{expectStatus} AND version = #{version}
            """)
    int updateStatusOptimistic(@Param("id") Long id,
                               @Param("expectStatus") String expectStatus,
                               @Param("newStatus") String newStatus,
                               @Param("version") int version);

    @Update("""
            UPDATE seckill_order
            SET status = #{newStatus},
                stock_compensated = #{stockCompensated},
                version = version + 1,
                updated_at = NOW(3)
            WHERE id = #{id} AND status = #{expectStatus} AND version = #{version}
            """)
    int updateStatusAndCompensate(@Param("id") Long id,
                                  @Param("expectStatus") String expectStatus,
                                  @Param("newStatus") String newStatus,
                                  @Param("stockCompensated") boolean stockCompensated,
                                  @Param("version") int version);

    @Select("""
            SELECT * FROM seckill_order
            WHERE status = 'PENDING_PAY' AND pay_deadline < #{deadline} AND stock_compensated = 0
            ORDER BY id ASC LIMIT #{limit}
            """)
    List<SeckillOrder> findTimeoutPendingPay(@Param("deadline") LocalDateTime deadline, @Param("limit") int limit);

    @Select("""
            SELECT COUNT(*) FROM seckill_order
            WHERE activity_id = #{activityId} AND sku_id = #{skuId}
              AND status IN ('PROCESSING','PENDING_PAY','PAID')
            """)
    int countActiveOrders(@Param("activityId") Long activityId, @Param("skuId") Long skuId);

    /** 按活动分页查询抢购成功写入的订单（全部状态），新单在前。 */
    @Select("""
            SELECT * FROM seckill_order
            WHERE activity_id = #{activityId}
            ORDER BY id DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<SeckillOrder> findPageByActivityId(@Param("activityId") Long activityId,
                                            @Param("offset") int offset,
                                            @Param("limit") int limit);

    /** 活动下订单总数（管理端分页用）。 */
    @Select("SELECT COUNT(*) FROM seckill_order WHERE activity_id = #{activityId}")
    long countByActivityId(@Param("activityId") Long activityId);
}
