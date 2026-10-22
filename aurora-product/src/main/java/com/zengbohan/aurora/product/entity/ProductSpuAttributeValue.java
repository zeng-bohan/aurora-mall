package com.zengbohan.aurora.product.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("product_spu_attribute_value")
public class ProductSpuAttributeValue {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long spuId;
    private Long attributeId;
    private Long attributeValueId;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getSpuId() { return spuId; }
    public void setSpuId(Long spuId) { this.spuId = spuId; }
    public Long getAttributeId() { return attributeId; }
    public void setAttributeId(Long attributeId) { this.attributeId = attributeId; }
    public Long getAttributeValueId() { return attributeValueId; }
    public void setAttributeValueId(Long attributeValueId) { this.attributeValueId = attributeValueId; }
}
