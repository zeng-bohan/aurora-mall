package com.zengbohan.aurora.product.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("product_sku_attribute")
public class ProductSkuAttribute {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long skuId;
    private Long attributeId;
    private Long attributeValueId;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getSkuId() { return skuId; }
    public void setSkuId(Long skuId) { this.skuId = skuId; }
    public Long getAttributeId() { return attributeId; }
    public void setAttributeId(Long attributeId) { this.attributeId = attributeId; }
    public Long getAttributeValueId() { return attributeValueId; }
    public void setAttributeValueId(Long attributeValueId) { this.attributeValueId = attributeValueId; }
}
