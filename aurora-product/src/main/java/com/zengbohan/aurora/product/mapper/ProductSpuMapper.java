package com.zengbohan.aurora.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zengbohan.aurora.product.entity.ProductSpu;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface ProductSpuMapper extends BaseMapper<ProductSpu> {

    @Select("SELECT id FROM product_spu WHERE category_id = #{categoryId} AND status = 1")
    List<Long> findOnSaleIdsByCategory(long categoryId);

    @Select("SELECT id FROM product_spu")
    List<Long> findAllIds();
}
