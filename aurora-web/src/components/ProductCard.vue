<script setup lang="ts">
import type { SpuCard } from '@/api/dtc'
import { formatYuan } from '@/utils/money'

defineProps<{ product: SpuCard }>()

function fallback(event: Event): void {
  const image = event.target as HTMLImageElement
  if (!image.src.endsWith('/products/placeholder.webp')) {
    image.src = '/products/placeholder.webp'
  }
}
</script>

<template>
  <RouterLink class="product-card" :to="`/products/${product.id}`">
    <div class="product-card__media">
      <img :src="product.imageUrl || '/products/placeholder.webp'" :alt="product.title" @error="fallback" />
      <span v-if="product.skuCount > 1" class="product-card__badge">{{ product.skuCount }} 种规格</span>
      <span class="product-card__action">查看详情 →</span>
    </div>
    <div class="product-card__info">
      <div class="product-card__category">{{ product.categoryName || 'AURORA' }}</div>
      <h3 class="product-card__title">{{ product.title }}</h3>
      <p class="product-card__subtitle">{{ product.subtitle }}</p>
      <div class="product-card__foot">
        <div class="product-card__price">
          {{ formatYuan(product.minPrice) }}
          <small v-if="product.maxPrice > product.minPrice">起</small>
        </div>
        <span
          class="product-card__stock"
          :class="{ 'product-card__stock--out': product.inStock === false }"
        >
          {{ product.inStock === false ? '暂时缺货' : product.inStock === null ? '库存待确认' : '现货' }}
        </span>
      </div>
    </div>
  </RouterLink>
</template>
