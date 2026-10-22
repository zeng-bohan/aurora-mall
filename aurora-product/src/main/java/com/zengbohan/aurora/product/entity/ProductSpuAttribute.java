package com.zengbohan.aurora.product.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("product_spu_attribute")
public class ProductSpuAttribute {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long spuId;
    private Long attributeId;
    private Integer sort;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getSpuId() { return spuId; }
    public void setSpuId(Long spuId) { this.spuId = spuId; }
    public Long getAttributeId() { return attributeId; }
    public void setAttributeId(Long attributeId) { this.attributeId = attributeId; }
    public Integer getSort() { return sort; }
    public void setSort(Integer sort) { this.sort = sort; }
}
