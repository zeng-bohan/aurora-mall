<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { catalogApi, type Sku, type SpuDetail } from '@/api/dtc'
import EmptyState from '@/components/EmptyState.vue'
import ProductCard from '@/components/ProductCard.vue'
import QuantityStepper from '@/components/QuantityStepper.vue'
import { useAuthStore } from '@/stores/auth'
import { useCartStore } from '@/stores/cart'
import { formatYuan } from '@/utils/money'
import { describeError } from '@/utils/errors'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const cart = useCartStore()

const product = ref<SpuDetail | null>(null)
const loading = ref(true)
const error = ref('')
const selectedValues = ref<Record<number, number>>({})
const quantity = ref(1)
const galleryIndex = ref(0)
const adding = ref(false)
const added = ref(false)

const variantAttributes = computed(() =>
  (product.value?.attributes ?? []).filter((attribute) => attribute.variant && attribute.values.length),
)

const galleryImages = computed(() => {
  const images: string[] = []
  for (const sku of product.value?.skus ?? []) {
    if (sku.imageUrl && !images.includes(sku.imageUrl)) {
      images.push(sku.imageUrl)
    }
  }
  return images
})

const currentSku = computed<Sku | undefined>(() => {
  const items = product.value?.skus ?? []
  const selected = selectedValues.value
  return items.find((sku) =>
    variantAttributes.value.every((attribute) => {
      const selectedValue = selected[attribute.id]
      const value = sku.attributes.find((item) => item.attributeId === attribute.id)?.valueId
      return selectedValue === undefined || value === selectedValue
    }),
  )
})

const currentImage = computed(() => {
  const image = galleryImages.value[galleryIndex.value]
  return image || currentSku.value?.imageUrl || product.value?.skus[0]?.imageUrl || '/products/placeholder.webp'
})

const price = computed(() => currentSku.value?.price ?? product.value?.skus[0]?.price ?? 0)
const stock = computed(() => currentSku.value?.stock ?? null)
const canBuy = computed(
  () => Boolean(currentSku.value && currentSku.value.status === 1 && (stock.value === null || stock.value > 0)),
)
const maxQuantity = computed(() => Math.max(1, Math.min(99, stock.value ?? 99)))
const selectedSummary = computed(() =>
  variantAttributes.value
    .map((attribute) => {
      const valueId = selectedValues.value[attribute.id]
      return attribute.values.find((value) => value.id === valueId)?.value
    })
    .filter(Boolean)
    .join(' / '),
)
const stockLabel = computed(() => {
  if (!currentSku.value) {
    return '请选择规格'
  }
  if (stock.value === null) {
    return '库存待确认'
  }
  if (stock.value <= 0) {
    return '暂时缺货'
  }
  if (stock.value < 10) {
    return `仅剩 ${stock.value} 件`
  }
  return '现货'
})

function syncSelection(sku: Sku | undefined): void {
  if (!sku) {
    return
  }
  selectedValues.value = Object.fromEntries(
    sku.attributes.map((attribute) => [attribute.attributeId, attribute.valueId]),
  )
  const index = galleryImages.value.indexOf(sku.imageUrl)
  if (index >= 0) {
    galleryIndex.value = index
  }
}

async function load(): Promise<void> {
  const id = Number(route.params.id)
  loading.value = true
  error.value = ''
  added.value = false
  try {
    product.value = await catalogApi.detail(id)
    selectedValues.value = {}
    quantity.value = 1
    galleryIndex.value = 0
    syncSelection(product.value.skus[0])
  } catch (e) {
    error.value = describeError(e)
    product.value = null
  } finally {
    loading.value = false
  }
}

function isAvailable(attributeId: number, valueId: number): boolean {
  const candidate = { ...selectedValues.value, [attributeId]: valueId }
  return (product.value?.skus ?? []).some(
    (sku) =>
      sku.status === 1 &&
      variantAttributes.value.every((attribute) => {
        const value = sku.attributes.find((item) => item.attributeId === attribute.id)?.valueId
        const wanted = candidate[attribute.id]
        return wanted === undefined || value === wanted
      }),
  )
}

function selectValue(attributeId: number, valueId: number): void {
  if (!isAvailable(attributeId, valueId)) {
    return
  }
  selectedValues.value = { ...selectedValues.value, [attributeId]: valueId }
  const sku = currentSku.value
  if (sku) {
    const index = galleryImages.value.indexOf(sku.imageUrl)
    if (index >= 0) {
      galleryIndex.value = index
    }
  }
}

function selectGallery(index: number): void {
  galleryIndex.value = index
  const image = galleryImages.value[index]
  const sku = (product.value?.skus ?? []).find((item) => item.imageUrl === image)
  if (sku) {
    selectedValues.value = Object.fromEntries(
      sku.attributes.map((attribute) => [attribute.attributeId, attribute.valueId]),
    )
  }
}

async function addToCart(): Promise<void> {
  if (!currentSku.value || !canBuy.value) {
    return
  }
  if (!auth.isLoggedIn) {
    void router.push({ name: 'login', query: { redirect: route.fullPath } })
    return
  }
  adding.value = true
  try {
    await cart.add(currentSku.value.id, Math.min(quantity.value, maxQuantity.value))
    added.value = true
    ElMessage.success('已加入购物车')
    window.setTimeout(() => {
      added.value = false
    }, 1800)
  } catch (e) {
    ElMessage.error(describeError(e))
  } finally {
    adding.value = false
  }
}

function buyNow(): void {
  if (!currentSku.value || !canBuy.value) {
    return
  }
  if (!auth.isLoggedIn) {
    void router.push({ name: 'login', query: { redirect: route.fullPath } })
    return
  }
  void router.push({
    name: 'checkout',
    query: { skuId: String(currentSku.value.id), quantity: String(quantity.value) },
  })
}

watch(
  () => route.params.id,
  () => {
    void load()
  },
  { immediate: true },
)
</script>

<template>
  <div>
    <div class="container breadcrumb">
      <RouterLink to="/">首页</RouterLink>
      <span>/</span>
      <RouterLink to="/products">商品目录</RouterLink>
      <template v-if="product">
        <span>/</span>
        <RouterLink :to="{ name: 'products', query: { categoryId: String(product.categoryId) } }">
          {{ product.categoryName }}
        </RouterLink>
        <span>/</span>
        <span>{{ product.title }}</span>
      </template>
    </div>

    <div v-if="loading" class="container detail-layout">
      <div class="skeleton" style="aspect-ratio: 1" />
      <div class="admin-stack">
        <div class="skeleton" style="height: 44px; width: 70%" />
        <div class="skeleton" style="height: 22px; width: 50%" />
        <div class="skeleton" style="height: 86px" />
      </div>
    </div>

    <div v-else-if="error || !product" class="container">
      <EmptyState title="商品暂时无法打开" :description="error || '商品可能已下架。'">
        <RouterLink class="btn btn--line" to="/products">返回商品目录</RouterLink>
      </EmptyState>
    </div>

    <template v-else>
      <div class="container detail-layout">
        <section class="gallery">
          <div class="gallery__main">
            <img :src="currentImage" :alt="product.title" />
          </div>
          <div v-if="galleryImages.length > 1" class="gallery__thumbs">
            <button
              v-for="(image, index) in galleryImages"
              :key="image"
              class="gallery__thumb"
              :class="{ 'is-active': galleryIndex === index }"
              type="button"
              @click="selectGallery(index)"
            >
              <img :src="image" :alt="`${product.title} 图 ${index + 1}`" />
            </button>
          </div>
        </section>

        <section class="product-info">
          <span class="eyebrow">{{ product.brand }} / {{ product.categoryName }}</span>
          <h1 class="display">{{ product.title }}</h1>
          <p class="product-info__subtitle">{{ product.subtitle }}</p>

          <div class="price-row">
            <span class="price-row__amount">{{ formatYuan(price) }}</span>
            <span class="price-row__note">{{ selectedSummary || '选择规格查看价格' }}</span>
          </div>

          <div v-for="attribute in variantAttributes" :key="attribute.id" class="variant-group">
            <div class="variant-group__head">
              <span class="variant-group__name">{{ attribute.name }}</span>
              <span class="variant-group__value">
                {{
                  attribute.values.find((value) => value.id === selectedValues[attribute.id])?.value ?? '请选择'
                }}
              </span>
            </div>
            <div class="option-row">
              <button
                v-for="value in attribute.values"
                :key="value.id"
                class="option"
                :class="{ 'is-selected': selectedValues[attribute.id] === value.id }"
                type="button"
                :disabled="!isAvailable(attribute.id, value.id)"
                @click="selectValue(attribute.id, value.id)"
              >
                <span v-if="value.colorHex" class="swatch-dot" :style="{ background: value.colorHex }" />
                {{ value.value }}
              </button>
            </div>
          </div>

          <div class="stock-line">
            <span class="stock-dot" :class="{ 'stock-dot--out': stock === 0 }" />
            {{ stockLabel }}
            <span v-if="currentSku">· {{ currentSku.skuCode }}</span>
          </div>

          <div class="quantity-row">
            <span class="tiny">数量</span>
            <QuantityStepper v-model="quantity" :min="1" :max="maxQuantity" :disabled="!canBuy" />
          </div>

          <div class="buy-row">
            <button class="btn btn--line" type="button" :disabled="!canBuy || adding" @click="addToCart">
              {{ adding ? '加入中…' : added ? '已加入购物车' : '加入购物车' }}
            </button>
            <button class="btn btn--primary" type="button" :disabled="!canBuy" @click="buyNow">
              立即购买
            </button>
          </div>

          <ul class="assurance-list">
            <li>券后满 ¥299 免运费，未满收取 ¥12。预计 1–2 个工作日发出。</li>
            <li>发货前支持部分或整单退款；退款金额按商品金额与优惠券门槛规则计算。</li>
            <li>发货后可在订单详情查看模拟运单，7 天未确认将自动完成。</li>
            <li>商品图为演示概念渲染，不冒充真实拍摄。</li>
          </ul>
        </section>
      </div>

      <section class="container detail-section">
        <span class="eyebrow">Product Notes</span>
        <h2 class="display" style="margin-top: 10px">为日常使用而设计。</h2>
        <p class="detail-section__copy">{{ product.description }}</p>
        <table v-if="product.specs.length" class="spec-table">
          <tbody>
            <tr v-for="spec in product.specs" :key="spec.name">
              <th>{{ spec.name }}</th>
              <td>{{ spec.value }}</td>
            </tr>
          </tbody>
        </table>
      </section>

      <section v-if="product.related.length" class="container detail-section">
        <div class="section-head">
          <div>
            <span class="eyebrow">Related</span>
            <h2 class="display">同一张桌面上的其他选择。</h2>
          </div>
          <RouterLink class="link-arrow" :to="{ name: 'products', query: { categoryId: String(product.categoryId) } }">
            查看分类 →
          </RouterLink>
        </div>
        <div class="related-grid">
          <ProductCard v-for="item in product.related" :key="item.id" :product="item" />
        </div>
      </section>
    </template>
  </div>
</template>
