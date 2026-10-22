package com.zengbohan.aurora.product.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zengbohan.aurora.common.exception.BusinessException;
import com.zengbohan.aurora.common.exception.ErrorCode;
import com.zengbohan.aurora.product.cache.ProductCacheService;
import com.zengbohan.aurora.product.dto.ProductDtos.SaveAttributeRequest;
import com.zengbohan.aurora.product.dto.ProductDtos.SaveAttributeValueRequest;
import com.zengbohan.aurora.product.dto.ProductDtos.SaveCategoryRequest;
import com.zengbohan.aurora.product.dto.ProductDtos.SaveSkuRequest;
import com.zengbohan.aurora.product.dto.ProductDtos.SaveSpuRequest;
import com.zengbohan.aurora.product.dto.ProductDtos.SpecView;
import com.zengbohan.aurora.product.entity.ProductAttribute;
import com.zengbohan.aurora.product.entity.ProductAttributeValue;
import com.zengbohan.aurora.product.entity.ProductCategory;
import com.zengbohan.aurora.product.entity.ProductSku;
import com.zengbohan.aurora.product.entity.ProductSkuAttribute;
import com.zengbohan.aurora.product.entity.ProductSpu;
import com.zengbohan.aurora.product.entity.ProductSpuAttribute;
import com.zengbohan.aurora.product.entity.ProductSpuAttributeValue;
import com.zengbohan.aurora.product.mapper.ProductAttributeMapper;
import com.zengbohan.aurora.product.mapper.ProductAttributeValueMapper;
import com.zengbohan.aurora.product.mapper.ProductCategoryMapper;
import com.zengbohan.aurora.product.mapper.ProductSkuAttributeMapper;
import com.zengbohan.aurora.product.mapper.ProductSkuMapper;
import com.zengbohan.aurora.product.mapper.ProductSpuAttributeMapper;
import com.zengbohan.aurora.product.mapper.ProductSpuAttributeValueMapper;
import com.zengbohan.aurora.product.mapper.ProductSpuMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ProductAdminService {

    private static final int TITLE_MAX = 96;
    private static final int SUBTITLE_MAX = 160;
    private static final String DEFAULT_IMAGE = "/products/placeholder.webp";

    private final ProductCategoryMapper categoryMapper;
    private final ProductSpuMapper spuMapper;
    private final ProductSkuMapper skuMapper;
    private final ProductAttributeMapper attributeMapper;
    private final ProductAttributeValueMapper valueMapper;
    private final ProductSpuAttributeMapper spuAttributeMapper;
    private final ProductSpuAttributeValueMapper spuValueMapper;
    private final ProductSkuAttributeMapper skuAttributeMapper;
    private final ProductCacheService cacheService;
    private final ObjectMapper objectMapper;

    public ProductAdminService(ProductCategoryMapper categoryMapper, ProductSpuMapper spuMapper,
                               ProductSkuMapper skuMapper, ProductAttributeMapper attributeMapper,
                               ProductAttributeValueMapper valueMapper,
                               ProductSpuAttributeMapper spuAttributeMapper,
                               ProductSpuAttributeValueMapper spuValueMapper,
                               ProductSkuAttributeMapper skuAttributeMapper,
                               ProductCacheService cacheService, ObjectMapper objectMapper) {
        this.categoryMapper = categoryMapper;
        this.spuMapper = spuMapper;
        this.skuMapper = skuMapper;
        this.attributeMapper = attributeMapper;
        this.valueMapper = valueMapper;
        this.spuAttributeMapper = spuAttributeMapper;
        this.spuValueMapper = spuValueMapper;
        this.skuAttributeMapper = skuAttributeMapper;
        this.cacheService = cacheService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public long save(SaveSpuRequest request) {
        ProductCategory category = categoryMapper.selectById(request.categoryId());
        if (category == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "分类不存在");
        }
        if (request.title().isBlank() || request.title().length() > TITLE_MAX) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "商品标题必填且不超过 " + TITLE_MAX + " 字");
        }
        if (request.subtitle().isBlank() || request.subtitle().length() > SUBTITLE_MAX) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "副标题必填且不超过 " + SUBTITLE_MAX + " 字");
        }
        boolean creating = request.id() == null;
        ProductSpu spu = creating ? new ProductSpu() : spuMapper.selectById(request.id());
        if (spu == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        if (creating) {
            spu.setBrand("AURORA");
            spu.setSort(0);
        }
        spu.setCategoryId(request.categoryId());
        spu.setTitle(request.title().trim());
        spu.setSubtitle(request.subtitle().trim());
        spu.setDescription(request.description());
        spu.setSpecJson(specJson(request.specs()));
        spu.setStatus(request.status() == null ? 1 : request.status());
        try {
            if (creating) {
                spuMapper.insert(spu);
            } else {
                spuMapper.updateById(spu);
            }
        } catch (DuplicateKeyException e) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "商品数据冲突，请刷新后重试");
        }

        List<Long> attributeIds = request.attributeIds() == null
                ? existingAttributeIds(spu.getId())
                : request.attributeIds().stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (creating && attributeIds.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "至少绑定一个商品属性");
        }
        List<ProductAttribute> attributes = attributeIds.isEmpty()
                ? List.of()
                : attributeMapper.selectBatchIds(attributeIds);
        if (attributes.size() != attributeIds.size()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "存在无效的商品属性");
        }
        Map<Long, ProductAttribute> attrMap = attributes.stream()
                .collect(Collectors.toMap(ProductAttribute::getId, Function.identity()));
        replaceSpuAttributes(spu.getId(), attributeIds);
        replaceSpuAttributeValues(spu.getId(), request.attributeValues(), attrMap);
        saveSkus(spu, request.skus(), attrMap);
        cacheService.evictAfterCommit(spu.getId());
        cacheService.bloomPut(spu.getId());
        return spu.getId();
    }

    public void changeStatus(long id, int status) {
        if (status != 0 && status != 1) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "状态只能是 0 或 1");
        }
        ProductSpu patch = new ProductSpu();
        patch.setId(id);
        patch.setStatus(status);
        cacheService.doubleDeleteAfterUpdate(id, () -> {
            if (spuMapper.updateById(patch) == 0) {
                throw new BusinessException(ErrorCode.NOT_FOUND);
            }
        });
    }

    public long createCategory(SaveCategoryRequest request) {
        validateCategory(request);
        ProductCategory category = new ProductCategory();
        apply(category, request);
        try {
            categoryMapper.insert(category);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "分类标识已存在");
        }
        return category.getId();
    }

    public void updateCategory(long id, SaveCategoryRequest request) {
        validateCategory(request);
        ProductCategory category = categoryMapper.selectById(id);
        if (category == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        apply(category, request);
        try {
            categoryMapper.updateById(category);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "分类标识已存在");
        }
        evictCategorySpus(id);
    }

    public long createAttribute(SaveAttributeRequest request) {
        validateAttribute(request);
        ProductAttribute attribute = new ProductAttribute();
        apply(attribute, request);
        try {
            attributeMapper.insert(attribute);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "属性编码已存在");
        }
        return attribute.getId();
    }

    public void updateAttribute(long id, SaveAttributeRequest request) {
        validateAttribute(request);
        ProductAttribute attribute = attributeMapper.selectById(id);
        if (attribute == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        if (!attribute.getCode().equals(request.code())) {
            boolean used = spuAttributeMapper.selectCount(new QueryWrapper<ProductSpuAttribute>()
                    .eq("attribute_id", id)) > 0;
            if (used) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "已被商品使用的属性不能修改编码");
            }
        }
        apply(attribute, request);
        try {
            attributeMapper.updateById(attribute);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "属性编码已存在");
        }
        evictAttributeSpus(id);
    }

    public long addValue(long attributeId, SaveAttributeValueRequest request) {
        ProductAttribute attribute = attributeMapper.selectById(attributeId);
        if (attribute == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        ProductAttributeValue value = new ProductAttributeValue();
        value.setAttributeId(attributeId);
        value.setValue(request.value().trim());
        value.setColorHex(blankToNull(request.colorHex()));
        value.setSort(request.sort() == null ? 0 : request.sort());
        value.setStatus(request.status() == null ? 1 : request.status());
        try {
            valueMapper.insert(value);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "属性值已存在");
        }
        return value.getId();
    }

    public void updateValue(long valueId, SaveAttributeValueRequest request) {
        ProductAttributeValue value = valueMapper.selectById(valueId);
        if (value == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        int nextStatus = request.status() == null ? value.getStatus() : request.status();
        if (value.getStatus() == 1 && nextStatus == 0 && valueInUse(valueId)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "该属性值仍被在售 SKU 使用，不能停用");
        }
        value.setValue(request.value().trim());
        value.setColorHex(blankToNull(request.colorHex()));
        value.setSort(request.sort() == null ? value.getSort() : request.sort());
        value.setStatus(nextStatus);
        try {
            valueMapper.updateById(value);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "属性值已存在");
        }
        evictValueSpus(valueId);
    }

    private void saveSkus(ProductSpu spu, List<SaveSkuRequest> skus, Map<Long, ProductAttribute> attrs) {
        if (skus == null || skus.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "至少保留一个 SKU");
        }
        Set<Long> variantAttributeIds = attrs.values().stream()
                .filter(a -> a.getVariant() != null && a.getVariant() == 1)
                .map(ProductAttribute::getId).collect(Collectors.toSet());
        Set<Long> requiredVariantIds = attrs.values().stream()
                .filter(a -> a.getVariant() != null && a.getVariant() == 1
                        && a.getRequired() != null && a.getRequired() == 1)
                .map(ProductAttribute::getId).collect(Collectors.toSet());
        Map<Long, ProductAttributeValue> allValues = valueMapper.selectList(null).stream()
                .collect(Collectors.toMap(ProductAttributeValue::getId, Function.identity()));
        Set<String> combinations = new HashSet<>();
        Set<String> codes = new HashSet<>();
        List<Long> orderedVariantIds = new ArrayList<>(variantAttributeIds);
        orderedVariantIds.sort(Long::compareTo);
        Set<Long> retainedIds = skus.stream().map(SaveSkuRequest::id)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        for (ProductSku existing : skuMapper.selectList(
                new QueryWrapper<ProductSku>().eq("spu_id", spu.getId()))) {
            if (!retainedIds.contains(existing.getId())) {
                skuAttributeMapper.delete(new QueryWrapper<ProductSkuAttribute>()
                        .eq("sku_id", existing.getId()));
                skuMapper.deleteById(existing.getId());
            }
        }

        for (SaveSkuRequest request : skus) {
            Map<Long, Long> selections = request.attributes() == null
                    ? Map.of() : new LinkedHashMap<>(request.attributes());
            for (Long required : requiredVariantIds) {
                if (!selections.containsKey(required)) {
                    throw new BusinessException(ErrorCode.PARAM_ERROR,
                            "SKU 必须为每个必选变体属性选择一个值");
                }
            }
            for (Map.Entry<Long, Long> entry : selections.entrySet()) {
                ProductAttribute attr = attrs.get(entry.getKey());
                ProductAttributeValue value = allValues.get(entry.getValue());
                if (attr == null) {
                    throw new BusinessException(ErrorCode.PARAM_ERROR, "SKU 引用了未绑定到该 SPU 的属性");
                }
                if (value == null || !value.getAttributeId().equals(attr.getId())) {
                    throw new BusinessException(ErrorCode.PARAM_ERROR, "SKU 属性值不属于对应属性");
                }
            }
            String combinationKey = selections.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(e -> e.getKey() + ":" + e.getValue())
                    .collect(Collectors.joining("|"));
            if (combinationKey.isBlank()) {
                combinationKey = "default";
            }
            if (!combinations.add(combinationKey)) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "同一 SPU 下存在重复的属性组合");
            }
            String skuCode = request.skuCode() == null ? "" : request.skuCode().trim();
            if (skuCode.isBlank()) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "SKU 编码必填");
            }
            if (!codes.add(skuCode)) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "SKU 编码重复");
            }
            if (request.price() == null || request.price().compareTo(BigDecimal.ZERO) <= 0) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "SKU 价格必须大于 0");
            }
            ProductSku sku = request.id() == null ? new ProductSku() : skuMapper.selectById(request.id());
            if (request.id() != null && (sku == null || !sku.getSpuId().equals(spu.getId()))) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "SKU 不属于当前商品");
            }
            if (sku == null) {
                sku = new ProductSku();
                sku.setSpuId(spu.getId());
                sku.setSort(skus.indexOf(request));
            }
            sku.setSkuCode(skuCode);
            sku.setTitle(blankToNull(request.title()) == null
                    ? composeSkuTitle(spu, selections, attrs, allValues) : request.title().trim());
            sku.setPrice(request.price());
            sku.setStatus(request.status() == null ? 1 : request.status());
            sku.setImageUrl(blankToNull(request.imageUrl()) == null
                    ? (blankToNull(sku.getImageUrl()) == null ? DEFAULT_IMAGE : sku.getImageUrl())
                    : request.imageUrl().trim());
            sku.setCombinationKey(combinationKey);
            try {
                if (sku.getId() == null) {
                    skuMapper.insert(sku);
                } else {
                    skuMapper.updateById(sku);
                }
            } catch (DuplicateKeyException e) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "SKU 编码或属性组合已存在");
            }
            skuAttributeMapper.delete(new QueryWrapper<ProductSkuAttribute>().eq("sku_id", sku.getId()));
            for (Map.Entry<Long, Long> entry : selections.entrySet()) {
                ProductSkuAttribute row = new ProductSkuAttribute();
                row.setSkuId(sku.getId());
                row.setAttributeId(entry.getKey());
                row.setAttributeValueId(entry.getValue());
                skuAttributeMapper.insert(row);
            }
        }
    }

    private void replaceSpuAttributes(long spuId, List<Long> attributeIds) {
        spuAttributeMapper.delete(new QueryWrapper<ProductSpuAttribute>().eq("spu_id", spuId));
        int sort = 1;
        for (Long attributeId : attributeIds) {
            ProductSpuAttribute row = new ProductSpuAttribute();
            row.setSpuId(spuId);
            row.setAttributeId(attributeId);
            row.setSort(sort++);
            spuAttributeMapper.insert(row);
        }
    }

    private void replaceSpuAttributeValues(long spuId, Map<Long, Long> values,
                                           Map<Long, ProductAttribute> attrs) {
        spuValueMapper.delete(new QueryWrapper<ProductSpuAttributeValue>().eq("spu_id", spuId));
        if (values == null || values.isEmpty()) {
            return;
        }
        Map<Long, ProductAttributeValue> allValues = valueMapper.selectList(null).stream()
                .collect(Collectors.toMap(ProductAttributeValue::getId, Function.identity()));
        for (Map.Entry<Long, Long> entry : values.entrySet()) {
            ProductAttribute attr = attrs.get(entry.getKey());
            ProductAttributeValue value = allValues.get(entry.getValue());
            if (attr == null) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "SPU 属性值引用了未绑定的属性");
            }
            if (value == null || !value.getAttributeId().equals(attr.getId())) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "SPU 属性值不属于对应属性");
            }
            ProductSpuAttributeValue row = new ProductSpuAttributeValue();
            row.setSpuId(spuId);
            row.setAttributeId(entry.getKey());
            row.setAttributeValueId(entry.getValue());
            spuValueMapper.insert(row);
        }
    }

    private List<Long> existingAttributeIds(long spuId) {
        return spuAttributeMapper.selectList(new QueryWrapper<ProductSpuAttribute>()
                        .eq("spu_id", spuId).orderByAsc("sort"))
                .stream().map(ProductSpuAttribute::getAttributeId).toList();
    }

    private boolean valueInUse(long valueId) {
        long skuHits = skuAttributeMapper.selectCount(new QueryWrapper<ProductSkuAttribute>()
                .eq("attribute_value_id", valueId)
                .inSql("sku_id", "SELECT id FROM product_sku WHERE status = 1"));
        long spuHits = spuValueMapper.selectCount(new QueryWrapper<ProductSpuAttributeValue>()
                .eq("attribute_value_id", valueId)
                .inSql("spu_id", "SELECT id FROM product_spu WHERE status = 1"));
        return skuHits + spuHits > 0;
    }

    private void evictCategorySpus(long categoryId) {
        evictSpus(spuMapper.selectList(new QueryWrapper<ProductSpu>()
                        .eq("category_id", categoryId))
                .stream().map(ProductSpu::getId).toList());
    }

    private void evictAttributeSpus(long attributeId) {
        evictSpus(spuAttributeMapper.selectList(new QueryWrapper<ProductSpuAttribute>()
                        .eq("attribute_id", attributeId))
                .stream().map(ProductSpuAttribute::getSpuId).toList());
    }

    private void evictValueSpus(long valueId) {
        Set<Long> spuIds = new HashSet<>();
        List<Long> skuIds = skuAttributeMapper.selectList(new QueryWrapper<ProductSkuAttribute>()
                        .eq("attribute_value_id", valueId))
                .stream().map(ProductSkuAttribute::getSkuId).toList();
        if (!skuIds.isEmpty()) {
            spuIds.addAll(skuMapper.selectBatchIds(skuIds).stream()
                    .map(ProductSku::getSpuId).toList());
        }
        spuIds.addAll(spuValueMapper.selectList(new QueryWrapper<ProductSpuAttributeValue>()
                        .eq("attribute_value_id", valueId))
                .stream().map(ProductSpuAttributeValue::getSpuId).toList());
        evictSpus(spuIds);
    }

    private void evictSpus(java.util.Collection<Long> spuIds) {
        spuIds.forEach(cacheService::evictWithDelayedSecondDelete);
    }

    private String composeSkuTitle(ProductSpu spu, Map<Long, Long> selections,
                                   Map<Long, ProductAttribute> attrs,
                                   Map<Long, ProductAttributeValue> values) {
        String suffix = selections.entrySet().stream()
                .sorted(Comparator.comparingLong(Map.Entry::getKey))
                .map(e -> {
                    ProductAttribute attr = attrs.get(e.getKey());
                    ProductAttributeValue value = values.get(e.getValue());
                    return attr == null || value == null ? null : value.getValue();
                })
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.joining(" · "));
        return suffix.isBlank() ? spu.getTitle() : spu.getTitle() + " · " + suffix;
    }

    private String specJson(List<SpecView> specs) {
        try {
            return objectMapper.writeValueAsString(specs == null ? List.of() : specs);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "商品参数格式不正确");
        }
    }

    private void validateCategory(SaveCategoryRequest request) {
        if (request.name().isBlank() || request.name().length() > 32) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "分类名称必填且不超过 32 字");
        }
        if (request.slug().isBlank() || !request.slug().matches("[a-z0-9-]{2,48}")) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "分类标识只能使用小写字母、数字和连字符");
        }
    }

    private static void apply(ProductCategory category, SaveCategoryRequest request) {
        category.setName(request.name().trim());
        category.setSlug(request.slug().trim());
        category.setSort(request.sort() == null ? 0 : request.sort());
        category.setStatus(request.status() == null ? 1 : request.status());
    }

    private void validateAttribute(SaveAttributeRequest request) {
        if (request.name().isBlank() || request.name().length() > 32) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "属性名称必填且不超过 32 字");
        }
        if (request.code().isBlank() || !request.code().matches("[a-z0-9_]{2,48}")) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "属性编码只能使用小写字母、数字和下划线");
        }
        String type = request.type() == null ? "TEXT" : request.type().toUpperCase();
        if (!Set.of("TEXT", "COLOR", "NUMBER").contains(type)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "属性类型只能是 TEXT、COLOR 或 NUMBER");
        }
    }

    private static void apply(ProductAttribute attribute, SaveAttributeRequest request) {
        attribute.setName(request.name().trim());
        attribute.setCode(request.code().trim());
        attribute.setType(request.type() == null ? "TEXT" : request.type().toUpperCase());
        attribute.setUnit(blankToNull(request.unit()));
        attribute.setFilterable(flag(request.filterable()));
        attribute.setVariant(flag(request.variant()));
        attribute.setRequired(flag(request.required()));
        attribute.setDisplay(request.display() == null ? 1 : flag(request.display()));
        attribute.setSort(request.sort() == null ? 0 : request.sort());
        attribute.setStatus(request.status() == null ? 1 : request.status());
    }

    private static Integer flag(Boolean value) {
        return value != null && value ? 1 : 0;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
