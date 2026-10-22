package com.zengbohan.aurora.product.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zengbohan.aurora.api.product.ProductSnapshot;
import com.zengbohan.aurora.common.exception.BusinessException;
import com.zengbohan.aurora.common.exception.ErrorCode;
import com.zengbohan.aurora.common.result.RemoteCall;
import com.zengbohan.aurora.common.result.Result;
import com.zengbohan.aurora.product.cache.ProductCacheService;
import com.zengbohan.aurora.product.client.InventoryStockClient;
import com.zengbohan.aurora.product.dto.ProductDtos.AttributeValueView;
import com.zengbohan.aurora.product.dto.ProductDtos.AttributeView;
import com.zengbohan.aurora.product.dto.ProductDtos.CategoryView;
import com.zengbohan.aurora.product.dto.ProductDtos.PageResult;
import com.zengbohan.aurora.product.dto.ProductDtos.SkuAttributeView;
import com.zengbohan.aurora.product.dto.ProductDtos.SkuView;
import com.zengbohan.aurora.product.dto.ProductDtos.SpecView;
import com.zengbohan.aurora.product.dto.ProductDtos.SpuCard;
import com.zengbohan.aurora.product.dto.ProductDtos.SpuDetail;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ProductQueryService {

    private static final Logger log = LoggerFactory.getLogger(ProductQueryService.class);
    private static final int MAX_PAGE_SIZE = 60;

    private final ProductCategoryMapper categoryMapper;
    private final ProductSpuMapper spuMapper;
    private final ProductSkuMapper skuMapper;
    private final ProductAttributeMapper attributeMapper;
    private final ProductAttributeValueMapper valueMapper;
    private final ProductSpuAttributeMapper spuAttributeMapper;
    private final ProductSpuAttributeValueMapper spuValueMapper;
    private final ProductSkuAttributeMapper skuAttributeMapper;
    private final ProductCacheService cacheService;
    private final InventoryStockClient stockClient;
    private final ObjectMapper objectMapper;

    public ProductQueryService(ProductCategoryMapper categoryMapper,
                               ProductSpuMapper spuMapper,
                               ProductSkuMapper skuMapper,
                               ProductAttributeMapper attributeMapper,
                               ProductAttributeValueMapper valueMapper,
                               ProductSpuAttributeMapper spuAttributeMapper,
                               ProductSpuAttributeValueMapper spuValueMapper,
                               ProductSkuAttributeMapper skuAttributeMapper,
                               ProductCacheService cacheService,
                               InventoryStockClient stockClient,
                               ObjectMapper objectMapper) {
        this.categoryMapper = categoryMapper;
        this.spuMapper = spuMapper;
        this.skuMapper = skuMapper;
        this.attributeMapper = attributeMapper;
        this.valueMapper = valueMapper;
        this.spuAttributeMapper = spuAttributeMapper;
        this.spuValueMapper = spuValueMapper;
        this.skuAttributeMapper = skuAttributeMapper;
        this.cacheService = cacheService;
        this.stockClient = stockClient;
        this.objectMapper = objectMapper;
    }

    public List<CategoryView> categories(boolean includeHidden) {
        QueryWrapper<ProductCategory> wrapper = new QueryWrapper<>();
        if (!includeHidden) {
            wrapper.eq("status", 1);
        }
        wrapper.orderByAsc("sort").orderByAsc("id");
        return categoryMapper.selectList(wrapper).stream()
                .map(c -> new CategoryView(c.getId(), c.getName(), c.getSlug(),
                        c.getSort(), c.getStatus()))
                .toList();
    }

    public List<AttributeView> attributes(Long categoryId, boolean includeHidden) {
        Set<Long> spuIds = null;
        if (categoryId != null) {
            spuIds = new HashSet<>(spuMapper.findOnSaleIdsByCategory(categoryId));
            if (spuIds.isEmpty()) {
                return List.of();
            }
        }
        QueryWrapper<ProductAttribute> attrWrapper = new QueryWrapper<>();
        if (!includeHidden) {
            attrWrapper.eq("status", 1);
        }
        attrWrapper.orderByAsc("sort").orderByAsc("id");
        List<ProductAttribute> attributes = attributeMapper.selectList(attrWrapper);
        if (attributes.isEmpty()) {
            return List.of();
        }
        Set<Long> boundIds = new HashSet<>();
        if (spuIds != null) {
            for (ProductSpuAttribute binding : spuAttributeMapper.selectList(
                    new QueryWrapper<ProductSpuAttribute>().in("spu_id", spuIds))) {
                boundIds.add(binding.getAttributeId());
            }
        } else {
            boundIds.addAll(attributes.stream().map(ProductAttribute::getId).toList());
        }
        Map<Long, List<ProductAttributeValue>> valuesByAttribute = enabledValues(includeHidden);
        Set<String> used = usedAttributeValues(spuIds);
        List<AttributeView> views = new ArrayList<>();
        for (ProductAttribute attribute : attributes) {
            if (!boundIds.contains(attribute.getId())) {
                continue;
            }
            List<AttributeValueView> values = valuesByAttribute.getOrDefault(attribute.getId(), List.of())
                    .stream()
                    .filter(v -> includeHidden || used.isEmpty() || used.contains(attribute.getId() + ":" + v.getId()))
                    .map(v -> new AttributeValueView(v.getId(), v.getValue(), v.getColorHex(),
                            v.getSort() == null ? 0 : v.getSort(),
                            v.getStatus() == null ? 1 : v.getStatus()))
                    .toList();
            if (values.isEmpty() && !includeHidden) {
                continue;
            }
            views.add(new AttributeView(attribute.getId(), attribute.getName(), attribute.getCode(),
                    attribute.getType(), attribute.getUnit(), yes(attribute.getFilterable()),
                    yes(attribute.getVariant()), yes(attribute.getRequired()),
                    yes(attribute.getDisplay()),
                    attribute.getSort() == null ? 0 : attribute.getSort(),
                    attribute.getStatus() == null ? 1 : attribute.getStatus(), values));
        }
        return views;
    }

    public PageResult<SpuCard> page(Long categoryId, String keyword, BigDecimal minPrice,
                                    BigDecimal maxPrice, String attrs, String sort,
                                    long current, long size, boolean includeOffShelf) {
        long safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        long safeCurrent = Math.max(current, 1);
        QueryWrapper<ProductSpu> wrapper = new QueryWrapper<>();
        if (!includeOffShelf) {
            wrapper.eq("status", 1);
        }
        if (categoryId != null) {
            wrapper.eq("category_id", categoryId);
        }
        if (keyword != null && !keyword.isBlank()) {
            String trimmed = keyword.trim();
            wrapper.and(q -> q.like("title", trimmed).or().like("subtitle", trimmed));
        }
        List<ProductSpu> spus = spuMapper.selectList(wrapper);
        if (spus.isEmpty()) {
            return new PageResult<>(List.of(), 0, safeCurrent, safeSize, 0);
        }
        List<Long> spuIds = spus.stream().map(ProductSpu::getId).toList();
        List<ProductSku> allSkus = skuMapper.selectList(new QueryWrapper<ProductSku>()
                .in("spu_id", spuIds).orderByAsc("sort").orderByAsc("id"));
        if (!includeOffShelf) {
            allSkus = allSkus.stream().filter(s -> yes(s.getStatus())).toList();
        }
        Map<Long, List<ProductSku>> skusBySpu = allSkus.stream()
                .collect(Collectors.groupingBy(ProductSku::getSpuId, LinkedHashMap::new, Collectors.toList()));
        Map<Long, Set<String>> skuAttributes = skuAttributeSets(allSkus);
        Map<Long, Set<String>> spuSkuAttributes = new HashMap<>();
        skusBySpu.forEach((spuId, skus) -> {
            Set<String> keys = new HashSet<>();
            for (ProductSku sku : skus) {
                keys.addAll(skuAttributes.getOrDefault(sku.getId(), Set.of()));
            }
            spuSkuAttributes.put(spuId, keys);
        });
        Map<Long, Set<String>> spuAttributes = spuAttributeSets(spuIds);
        Map<Long, LinkedHashMap<Long, LinkedHashSet<Long>>> filters = parseFilters(attrs);

        List<ProductSpu> matched = spus.stream()
                .filter(spu -> !skusBySpu.getOrDefault(spu.getId(), List.of()).isEmpty())
                .filter(spu -> matchesPrice(skusBySpu.get(spu.getId()), minPrice, maxPrice))
                .filter(spu -> matchesAttributes(spu.getId(), spuSkuAttributes, spuAttributes, filters))
                .sorted(comparator(sort, skusBySpu))
                .toList();
        long total = matched.size();
        long offset = offsetOrBeyond(safeCurrent, safeSize);
        List<ProductSpu> pageRows = offset >= total
                ? List.of()
                : matched.subList((int) offset, (int) Math.min(offset + safeSize, total));
        if (pageRows.isEmpty()) {
            return new PageResult<>(List.of(), total, safeCurrent, safeSize,
                    (total + safeSize - 1) / safeSize);
        }
        List<ProductSku> pageSkus = pageRows.stream()
                .flatMap(spu -> skusBySpu.getOrDefault(spu.getId(), List.of()).stream())
                .toList();
        Map<Long, Integer> stock = stockMap(pageSkus.stream().map(ProductSku::getId).toList());
        Map<Long, ProductCategory> categories = categoryMap();
        List<SpuCard> cards = pageRows.stream()
                .map(spu -> toCard(spu, skusBySpu.getOrDefault(spu.getId(), List.of()),
                        categories, stock))
                .toList();
        return new PageResult<>(cards, total, safeCurrent, safeSize,
                (total + safeSize - 1) / safeSize);
    }

    public SpuDetail detail(long id, boolean includeOffShelf) {
        if (includeOffShelf) {
            return loadDetail(id, true);
        }
        return cacheService.get(id, SpuDetail.class, () -> loadDetail(id, false));
    }

    public List<SpuCard> related(long id, int limit) {
        ProductSpu spu = spuMapper.selectById(id);
        if (spu == null) {
            return List.of();
        }
        PageResult<SpuCard> page = page(spu.getCategoryId(), null, null, null,
                null, "recommended", 1, Math.max(limit + 1, 2), false);
        return page.records().stream().filter(card -> card.id() != id).limit(limit).toList();
    }

    public List<ProductSnapshot> batchSnapshots(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<ProductSku> skus = skuMapper.selectList(new QueryWrapper<ProductSku>().in("id", ids));
        if (skus.isEmpty()) {
            return List.of();
        }
        Map<Long, ProductSpu> spus = spuMapper.selectBatchIds(
                        skus.stream().map(ProductSku::getSpuId).distinct().toList())
                .stream().collect(Collectors.toMap(ProductSpu::getId, Function.identity()));
        Map<Long, List<ProductSkuAttribute>> skuAttrs = skuAttributeMapper.selectList(
                        new QueryWrapper<ProductSkuAttribute>().in("sku_id",
                                skus.stream().map(ProductSku::getId).toList()))
                .stream().collect(Collectors.groupingBy(ProductSkuAttribute::getSkuId));
        Map<Long, ProductAttribute> attrs = attributeMapper.selectList(null).stream()
                .collect(Collectors.toMap(ProductAttribute::getId, Function.identity()));
        Map<Long, ProductAttributeValue> values = valueMapper.selectList(null).stream()
                .collect(Collectors.toMap(ProductAttributeValue::getId, Function.identity()));
        Map<Long, Integer> stock = stockMap(skus.stream().map(ProductSku::getId).toList());
        return skus.stream().filter(sku -> yes(sku.getStatus())).map(sku -> {
            ProductSpu spu = spus.get(sku.getSpuId());
            String attributesText = attributesText(skuAttrs.getOrDefault(sku.getId(), List.of()),
                    attrs, values);
            return new ProductSnapshot(sku.getId(), sku.getSpuId(), sku.getSkuCode(),
                    sku.getTitle(), attributesText, sku.getImageUrl(), sku.getPrice(),
                    stock.get(sku.getId()), sku.getStatus());
        }).toList();
    }

    public List<com.zengbohan.aurora.product.dto.ProductDtos.AdminSkuRow> adminSkus(String keyword) {
        QueryWrapper<ProductSku> wrapper = new QueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            String trimmed = keyword.trim();
            wrapper.and(q -> q.like("title", trimmed).or().like("sku_code", trimmed));
        }
        wrapper.orderByAsc("spu_id").orderByAsc("sort").orderByAsc("id");
        List<ProductSku> skus = skuMapper.selectList(wrapper);
        if (skus.isEmpty()) {
            return List.of();
        }
        Map<Long, ProductSpu> spus = spuMapper.selectBatchIds(
                        skus.stream().map(ProductSku::getSpuId).distinct().toList())
                .stream().collect(Collectors.toMap(ProductSpu::getId, Function.identity()));
        Map<Long, Integer> stock = stockMap(skus.stream().map(ProductSku::getId).toList());
        return skus.stream().map(sku -> new com.zengbohan.aurora.product.dto.ProductDtos.AdminSkuRow(
                sku.getId(), sku.getSpuId(),
                spus.containsKey(sku.getSpuId()) ? spus.get(sku.getSpuId()).getTitle() : "",
                sku.getSkuCode(), sku.getTitle(), sku.getPrice(), sku.getStatus(),
                sku.getImageUrl(), stock.get(sku.getId()))).toList();
    }

    public com.zengbohan.aurora.product.dto.ProductDtos.CatalogStats catalogStats() {
        long spuCount = spuMapper.selectCount(new QueryWrapper<ProductSpu>().eq("status", 1));
        long skuCount = skuMapper.selectCount(new QueryWrapper<ProductSku>()
                .eq("status", 1)
                .inSql("spu_id", "SELECT id FROM product_spu WHERE status = 1"));
        return new com.zengbohan.aurora.product.dto.ProductDtos.CatalogStats(spuCount, skuCount);
    }

    public ProductSnapshot snapshot(long skuId) {
        List<ProductSnapshot> batch = batchSnapshots(List.of(skuId));
        if (batch.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "商品不存在或已下架");
        }
        return batch.get(0);
    }

    private SpuDetail loadDetail(long id, boolean includeOffShelf) {
        ProductSpu spu = spuMapper.selectById(id);
        if (spu == null || (!includeOffShelf && !yes(spu.getStatus()))) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        ProductCategory category = categoryMapper.selectById(spu.getCategoryId());
        List<ProductSku> skus = skuMapper.findBySpu(id).stream()
                .filter(sku -> includeOffShelf || yes(sku.getStatus()))
                .toList();
        List<Long> skuIds = skus.stream().map(ProductSku::getId).toList();
        Map<Long, List<ProductSkuAttribute>> skuAttrs = skuIds.isEmpty()
                ? Map.of()
                : skuAttributeMapper.selectList(new QueryWrapper<ProductSkuAttribute>()
                        .in("sku_id", skuIds)).stream()
                .collect(Collectors.groupingBy(ProductSkuAttribute::getSkuId));
        Map<Long, ProductAttribute> attributes = attributeMapper.selectList(null).stream()
                .collect(Collectors.toMap(ProductAttribute::getId, Function.identity()));
        Map<Long, ProductAttributeValue> values = valueMapper.selectList(null).stream()
                .collect(Collectors.toMap(ProductAttributeValue::getId, Function.identity()));
        List<ProductSpuAttribute> bindings = spuAttributeMapper.selectList(
                new QueryWrapper<ProductSpuAttribute>().eq("spu_id", id)
                        .orderByAsc("sort").orderByAsc("id"));
        List<ProductSpuAttributeValue> spuValues = spuValueMapper.selectList(
                new QueryWrapper<ProductSpuAttributeValue>().eq("spu_id", id));
        Set<String> usedVariantValues = new HashSet<>();
        for (List<ProductSkuAttribute> rows : skuAttrs.values()) {
            for (ProductSkuAttribute row : rows) {
                usedVariantValues.add(row.getAttributeId() + ":" + row.getAttributeValueId());
            }
        }
        Set<String> usedSpuValues = spuValues.stream()
                .map(v -> v.getAttributeId() + ":" + v.getAttributeValueId())
                .collect(Collectors.toSet());
        List<AttributeView> attributeViews = new ArrayList<>();
        for (ProductSpuAttribute binding : bindings) {
            ProductAttribute attr = attributes.get(binding.getAttributeId());
            if (attr == null || (!includeOffShelf && !yes(attr.getStatus()))) {
                continue;
            }
            Set<String> used = yes(attr.getVariant()) ? usedVariantValues : usedSpuValues;
            List<AttributeValueView> attrValues = valueMapper.selectList(
                            new QueryWrapper<ProductAttributeValue>()
                                    .eq("attribute_id", attr.getId())
                                    .orderByAsc("sort").orderByAsc("id"))
                    .stream()
                    .filter(v -> includeOffShelf || used.contains(attr.getId() + ":" + v.getId()))
                    .filter(v -> includeOffShelf || yes(v.getStatus()))
                    .map(v -> new AttributeValueView(v.getId(), v.getValue(), v.getColorHex(),
                            v.getSort() == null ? 0 : v.getSort(),
                            v.getStatus() == null ? 1 : v.getStatus()))
                    .toList();
            attributeViews.add(new AttributeView(attr.getId(), attr.getName(), attr.getCode(),
                    attr.getType(), attr.getUnit(), yes(attr.getFilterable()),
                    yes(attr.getVariant()), yes(attr.getRequired()),
                    yes(attr.getDisplay()),
                    attr.getSort() == null ? 0 : attr.getSort(),
                    attr.getStatus() == null ? 1 : attr.getStatus(), attrValues));
        }

        Map<Long, Integer> stock = stockMap(skuIds);
        List<SkuView> skuViews = skus.stream().map(sku -> {
            List<SkuAttributeView> skuAttributeViews = skuAttrs.getOrDefault(sku.getId(), List.of())
                    .stream()
                    .filter(row -> attributes.containsKey(row.getAttributeId())
                            && values.containsKey(row.getAttributeValueId()))
                    .map(row -> {
                        ProductAttribute attr = attributes.get(row.getAttributeId());
                        ProductAttributeValue value = values.get(row.getAttributeValueId());
                        return new SkuAttributeView(attr.getId(), attr.getName(), value.getId(),
                                value.getValue(), value.getColorHex());
                    })
                    .sorted(Comparator.comparingLong(SkuAttributeView::attributeId))
                    .toList();
            return new SkuView(sku.getId(), sku.getSkuCode(), sku.getTitle(), sku.getPrice(),
                    sku.getStatus(), sku.getImageUrl(), stock.get(sku.getId()), skuAttributeViews);
        }).toList();

        Map<String, String> specMap = new LinkedHashMap<>();
        for (SpecView spec : parseSpecs(spu.getSpecJson())) {
            specMap.put(spec.name(), spec.value());
        }
        for (ProductSpuAttributeValue row : spuValues) {
            ProductAttribute attr = attributes.get(row.getAttributeId());
            ProductAttributeValue value = values.get(row.getAttributeValueId());
            if (attr != null && value != null && yes(attr.getDisplay())) {
                specMap.putIfAbsent(attr.getName(), value.getValue());
            }
        }
        List<SpecView> specs = specMap.entrySet().stream()
                .map(e -> new SpecView(e.getKey(), e.getValue()))
                .toList();
        List<SpuCard> related = related(id, 4);
        return new SpuDetail(spu.getId(), spu.getCategoryId(),
                category == null ? "" : category.getName(), spu.getBrand(), spu.getTitle(),
                spu.getSubtitle(), spu.getDescription(), spu.getStatus(), attributeViews,
                skuViews, specs, related);
    }

    private SpuCard toCard(ProductSpu spu, List<ProductSku> skus,
                           Map<Long, ProductCategory> categories, Map<Long, Integer> stock) {
        BigDecimal min = skus.stream().map(ProductSku::getPrice)
                .min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        BigDecimal max = skus.stream().map(ProductSku::getPrice)
                .max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        boolean anyStock = skus.stream().anyMatch(sku -> {
            Integer value = stock.get(sku.getId());
            return value != null && value > 0;
        });
        Boolean inStock = stock.isEmpty() ? null : anyStock;
        ProductCategory category = categories.get(spu.getCategoryId());
        return new SpuCard(spu.getId(), spu.getCategoryId(),
                category == null ? "" : category.getName(), spu.getTitle(), spu.getSubtitle(),
                skus.isEmpty() ? "" : skus.get(0).getImageUrl(), min, max, skus.size(), inStock, spu.getStatus());
    }

    private Comparator<ProductSpu> comparator(String sort, Map<Long, List<ProductSku>> skusBySpu) {
        if (sort == null) {
            sort = "recommended";
        }
        return switch (sort) {
            case "price_asc" -> Comparator.comparing(
                    (ProductSpu spu) -> minPrice(skusBySpu.getOrDefault(spu.getId(), List.of())),
                    Comparator.nullsLast(BigDecimal::compareTo))
                    .thenComparingLong(ProductSpu::getId);
            case "price_desc" -> Comparator.comparing(
                    (ProductSpu spu) -> maxPrice(skusBySpu.getOrDefault(spu.getId(), List.of())),
                    Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparingLong(ProductSpu::getId);
            case "newest" -> Comparator.comparingLong(ProductSpu::getId).reversed();
            default -> Comparator.comparingInt((ProductSpu spu) ->
                            spu.getSort() == null ? 0 : spu.getSort())
                    .thenComparingLong(ProductSpu::getId);
        };
    }

    private static BigDecimal minPrice(List<ProductSku> skus) {
        return skus.stream().map(ProductSku::getPrice).min(BigDecimal::compareTo).orElse(null);
    }

    private static BigDecimal maxPrice(List<ProductSku> skus) {
        return skus.stream().map(ProductSku::getPrice).max(BigDecimal::compareTo).orElse(null);
    }

    private static boolean matchesPrice(List<ProductSku> skus, BigDecimal min, BigDecimal max) {
        if (min == null && max == null) {
            return true;
        }
        return skus.stream().anyMatch(sku -> {
            BigDecimal price = sku.getPrice();
            return (min == null || price.compareTo(min) >= 0)
                    && (max == null || price.compareTo(max) <= 0);
        });
    }

    private static boolean matchesAttributes(long spuId, Map<Long, Set<String>> skuAttributesBySpu,
                                             Map<Long, Set<String>> spuAttributes,
                                             Map<Long, LinkedHashMap<Long, LinkedHashSet<Long>>> filters) {
        if (filters.isEmpty()) {
            return true;
        }
        Set<String> skuKeys = skuAttributesBySpu.getOrDefault(spuId, Set.of());
        Set<String> spuKeys = spuAttributes.getOrDefault(spuId, Set.of());
        for (Map.Entry<Long, LinkedHashMap<Long, LinkedHashSet<Long>>> entry : filters.entrySet()) {
            boolean matched = false;
            for (Long valueId : entry.getValue().getOrDefault(entry.getKey(), new LinkedHashSet<>())) {
                String key = entry.getKey() + ":" + valueId;
                if (spuKeys.contains(key) || skuKeys.contains(key)) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                return false;
            }
        }
        return true;
    }

    private static Map<Long, LinkedHashMap<Long, LinkedHashSet<Long>>> parseFilters(String attrs) {
        Map<Long, LinkedHashMap<Long, LinkedHashSet<Long>>> filters = new LinkedHashMap<>();
        if (attrs == null || attrs.isBlank()) {
            return filters;
        }
        for (String part : attrs.split(",")) {
            String[] pair = part.trim().split(":");
            if (pair.length != 2) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "属性筛选格式应为 attributeId:valueId");
            }
            try {
                long attributeId = Long.parseLong(pair[0]);
                long valueId = Long.parseLong(pair[1]);
                filters.computeIfAbsent(attributeId, key -> new LinkedHashMap<>())
                        .computeIfAbsent(attributeId, key -> new LinkedHashSet<>())
                        .add(valueId);
            } catch (NumberFormatException e) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "属性筛选参数必须为数字");
            }
        }
        return filters;
    }

    private Map<Long, Set<String>> skuAttributeSets(List<ProductSku> skus) {
        if (skus.isEmpty()) {
            return Map.of();
        }
        Map<Long, Set<String>> result = new HashMap<>();
        for (ProductSkuAttribute row : skuAttributeMapper.selectList(
                new QueryWrapper<ProductSkuAttribute>().in("sku_id",
                        skus.stream().map(ProductSku::getId).toList()))) {
            result.computeIfAbsent(row.getSkuId(), key -> new HashSet<>())
                    .add(row.getAttributeId() + ":" + row.getAttributeValueId());
        }
        return result;
    }

    private Map<Long, Set<String>> spuAttributeSets(List<Long> spuIds) {
        Map<Long, Set<String>> result = new HashMap<>();
        for (ProductSpuAttributeValue row : spuValueMapper.selectList(
                new QueryWrapper<ProductSpuAttributeValue>().in("spu_id", spuIds))) {
            result.computeIfAbsent(row.getSpuId(), key -> new HashSet<>())
                    .add(row.getAttributeId() + ":" + row.getAttributeValueId());
        }
        return result;
    }

    private Set<String> usedAttributeValues(Set<Long> spuIds) {
        Set<String> used = new HashSet<>();
        QueryWrapper<ProductSku> skuWrapper = new QueryWrapper<ProductSku>().eq("status", 1);
        if (spuIds != null) {
            skuWrapper.in("spu_id", spuIds);
        }
        List<Long> skuIds = skuMapper.selectList(skuWrapper).stream().map(ProductSku::getId).toList();
        if (!skuIds.isEmpty()) {
            for (ProductSkuAttribute row : skuAttributeMapper.selectList(
                    new QueryWrapper<ProductSkuAttribute>().in("sku_id", skuIds))) {
                used.add(row.getAttributeId() + ":" + row.getAttributeValueId());
            }
        }
        QueryWrapper<ProductSpuAttributeValue> valueWrapper = new QueryWrapper<>();
        if (spuIds != null) {
            valueWrapper.in("spu_id", spuIds);
        }
        for (ProductSpuAttributeValue row : spuValueMapper.selectList(valueWrapper)) {
            used.add(row.getAttributeId() + ":" + row.getAttributeValueId());
        }
        return used;
    }

    private Map<Long, List<ProductAttributeValue>> enabledValues(boolean includeHidden) {
        QueryWrapper<ProductAttributeValue> wrapper = new QueryWrapper<>();
        if (!includeHidden) {
            wrapper.eq("status", 1);
        }
        wrapper.orderByAsc("sort").orderByAsc("id");
        return valueMapper.selectList(wrapper).stream()
                .collect(Collectors.groupingBy(ProductAttributeValue::getAttributeId));
    }

    private Map<Long, ProductCategory> categoryMap() {
        return categoryMapper.selectList(null).stream()
                .collect(Collectors.toMap(ProductCategory::getId, Function.identity()));
    }

    private Map<Long, Integer> stockMap(List<Long> skuIds) {
        if (skuIds.isEmpty()) {
            return Map.of();
        }
        try {
            Result<List<InventoryStockClient.StockRow>> result = stockClient.batch(skuIds);
            List<InventoryStockClient.StockRow> rows = RemoteCall.data(result, "库存");
            Map<Long, Integer> stock = new HashMap<>();
            if (rows != null) {
                for (InventoryStockClient.StockRow row : rows) {
                    stock.put(row.skuId(), row.available());
                }
            }
            return stock;
        } catch (RuntimeException e) {
            log.warn("inventory stock lookup unavailable, returning unknown stock: {}", e.toString());
            return Map.of();
        }
    }

    private List<SpecView> parseSpecs(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<SpecView>>() {
            });
        } catch (Exception e) {
            log.warn("unreadable spec json, ignoring: {}", e.toString());
            return List.of();
        }
    }

    private static String attributesText(List<ProductSkuAttribute> rows,
                                         Map<Long, ProductAttribute> attrs,
                                         Map<Long, ProductAttributeValue> values) {
        return rows.stream()
                .sorted(Comparator.comparingLong(row -> {
                    ProductAttribute attr = attrs.get(row.getAttributeId());
                    return attr == null || attr.getSort() == null ? 999 : attr.getSort();
                }))
                .map(row -> values.get(row.getAttributeValueId()))
                .filter(value -> value != null)
                .map(ProductAttributeValue::getValue)
                .collect(Collectors.joining(" / "));
    }

    private static long offsetOrBeyond(long current, long size) {
        try {
            return Math.multiplyExact(current - 1, size);
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }

    private static boolean yes(Integer value) {
        return value != null && value == 1;
    }
}
