package com.presence.seckill.infra.mapper;

import com.presence.seckill.domain.model.SeckillPayRecord;
import org.apache.ibatis.annotations.*;

@Mapper
public interface SeckillPayRecordMapper {

    @Insert("""
            INSERT INTO seckill_pay_record(
              order_id, user_id, pay_trade_no, amount_cent, status,
              paid_at, callback_received, created_at, updated_at
            ) VALUES(
              #{orderId}, #{userId}, #{payTradeNo}, #{amountCent}, #{status},
              #{paidAt}, #{callbackReceived}, #{createdAt}, #{updatedAt}
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(SeckillPayRecord record);

    @Select("SELECT * FROM seckill_pay_record WHERE order_id = #{orderId}")
    SeckillPayRecord findByOrderId(@Param("orderId") Long orderId);

    @Select("SELECT * FROM seckill_pay_record WHERE pay_trade_no = #{payTradeNo}")
    SeckillPayRecord findByTradeNo(@Param("payTradeNo") String payTradeNo);

    @Update("""
            UPDATE seckill_pay_record
            SET status = #{status},
                paid_at = #{paidAt},
                callback_received = #{callbackReceived},
                updated_at = NOW(3)
            WHERE id = #{id} AND status = 'INIT'
            """)
    int markSuccess(@Param("id") Long id,
                    @Param("status") String status,
                    @Param("paidAt") java.time.LocalDateTime paidAt,
                    @Param("callbackReceived") boolean callbackReceived);
}
