package com.zengbohan.aurora.product.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public final class ProductDtos {

    private ProductDtos() {
    }

    public record CategoryView(long id, String name, String slug, int sort, int status) {
    }

    public record AttributeValueView(long id, String value, String colorHex, int sort, int status) {
    }

    public record AttributeView(long id, String name, String code, String type, String unit,
                                boolean filterable, boolean variant, boolean required,
                                boolean display, int sort, int status,
                                List<AttributeValueView> values) {
    }

    public record SkuAttributeView(long attributeId, String name, long valueId,
                                   String value, String colorHex) {
    }

    public record SkuView(long id, String skuCode, String title, BigDecimal price,
                          int status, String imageUrl, Integer stock,
                          List<SkuAttributeView> attributes) {
    }

    public record SpecView(String name, String value) {
    }

    public record SpuCard(long id, long categoryId, String categoryName, String title,
                          String subtitle, String imageUrl, BigDecimal minPrice,
                          BigDecimal maxPrice, int skuCount, Boolean inStock, int status) {
    }

    public record SpuDetail(long id, long categoryId, String categoryName, String brand,
                            String title, String subtitle, String description, int status,
                            List<AttributeView> attributes, List<SkuView> skus,
                            List<SpecView> specs, List<SpuCard> related) {
    }

    public record PageResult<T>(List<T> records, long total, long current, long size, long pages) {
    }

    public record AdminSkuRow(long id, long spuId, String spuTitle, String skuCode,
                              String title, BigDecimal price, int status, String imageUrl,
                              Integer stock) {
    }

    public record CatalogStats(long spuCount, long skuCount) {
    }

    public record SaveSkuRequest(Long id, String skuCode,
                                 @NotNull @DecimalMin("0.01") BigDecimal price,
                                 String imageUrl, Integer status,
                                 Map<Long, Long> attributes, String title) {
    }

    public record SaveSpuRequest(Long id,
                                 @NotNull Long categoryId,
                                 @NotBlank String title,
                                 @NotBlank String subtitle,
                                 @NotBlank String description,
                                 List<Long> attributeIds,
                                 Map<Long, Long> attributeValues,
                                 List<SpecView> specs,
                                 @NotEmpty @Valid List<SaveSkuRequest> skus,
                                 Integer status) {
    }

    public record SaveCategoryRequest(@NotBlank String name, @NotBlank String slug,
                                      Integer sort, Integer status) {
    }

    public record SaveAttributeRequest(@NotBlank String name, @NotBlank String code,
                                       String type, String unit, Boolean filterable,
                                       Boolean variant, Boolean required, Boolean display,
                                       Integer sort, Integer status) {
    }

    public record SaveAttributeValueRequest(@NotBlank String value, String colorHex,
                                            Integer sort, Integer status) {
    }
}
