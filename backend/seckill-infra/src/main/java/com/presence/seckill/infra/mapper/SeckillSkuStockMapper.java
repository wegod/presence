package com.presence.seckill.infra.mapper;

import com.presence.seckill.domain.model.SeckillSkuStock;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface SeckillSkuStockMapper {

    @Insert("""
            INSERT INTO seckill_sku_stock(
              activity_id, sku_id, sku_name, total_stock, available_stock,
              per_user_limit, sale_enabled, unit_price_cent, version, created_at, updated_at
            ) VALUES(
              #{activityId}, #{skuId}, #{skuName}, #{totalStock}, #{availableStock},
              #{perUserLimit}, #{saleEnabled}, #{unitPriceCent}, #{version}, #{createdAt}, #{updatedAt}
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(SeckillSkuStock stock);

    @Select("SELECT * FROM seckill_sku_stock WHERE activity_id = #{activityId} AND sku_id = #{skuId}")
    SeckillSkuStock findByActivityAndSku(@Param("activityId") Long activityId, @Param("skuId") Long skuId);

    @Select("SELECT * FROM seckill_sku_stock WHERE activity_id = #{activityId}")
    List<SeckillSkuStock> findByActivityId(@Param("activityId") Long activityId);

    /**
     * MySQL 条件扣减：available_stock >= qty 且可售时才成功。
     */
    @Update("""
            UPDATE seckill_sku_stock
            SET available_stock = available_stock - #{qty},
                version = version + 1,
                updated_at = NOW(3)
            WHERE activity_id = #{activityId}
              AND sku_id = #{skuId}
              AND sale_enabled = 1
              AND available_stock >= #{qty}
            """)
    int deductStock(@Param("activityId") Long activityId, @Param("skuId") Long skuId, @Param("qty") int qty);

    @Update("""
            UPDATE seckill_sku_stock
            SET available_stock = available_stock + #{qty},
                version = version + 1,
                updated_at = NOW(3)
            WHERE activity_id = #{activityId} AND sku_id = #{skuId}
            """)
    int rollbackStock(@Param("activityId") Long activityId, @Param("skuId") Long skuId, @Param("qty") int qty);

    @Update("""
            UPDATE seckill_sku_stock
            SET sale_enabled = 0, version = version + 1, updated_at = NOW(3)
            WHERE activity_id = #{activityId} AND sku_id = #{skuId} AND sale_enabled = 1
            """)
    int disableSale(@Param("activityId") Long activityId, @Param("skuId") Long skuId);
}
