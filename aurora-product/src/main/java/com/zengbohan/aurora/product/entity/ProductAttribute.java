package com.zengbohan.aurora.product.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("product_attribute")
public class ProductAttribute {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String code;
    private String type;
    private String unit;
    private Integer filterable;
    private Integer variant;
    private Integer required;
    private Integer display;
    private Integer sort;
    private Integer status;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }
    public Integer getFilterable() { return filterable; }
    public void setFilterable(Integer filterable) { this.filterable = filterable; }
    public Integer getVariant() { return variant; }
    public void setVariant(Integer variant) { this.variant = variant; }
    public Integer getRequired() { return required; }
    public void setRequired(Integer required) { this.required = required; }
    public Integer getDisplay() { return display; }
    public void setDisplay(Integer display) { this.display = display; }
    public Integer getSort() { return sort; }
    public void setSort(Integer sort) { this.sort = sort; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
