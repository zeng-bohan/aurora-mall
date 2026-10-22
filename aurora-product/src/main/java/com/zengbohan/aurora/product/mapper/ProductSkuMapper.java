package com.zengbohan.aurora.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zengbohan.aurora.product.entity.ProductSku;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface ProductSkuMapper extends BaseMapper<ProductSku> {

    @Select("SELECT * FROM product_sku WHERE spu_id = #{spuId} ORDER BY sort, id")
    List<ProductSku> findBySpu(long spuId);

    @Select("SELECT id FROM product_sku")
    List<Long> findAllIds();
}
