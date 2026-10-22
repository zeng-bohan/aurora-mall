<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { catalogApi, type Attribute, type Category, type SpuCard } from '@/api/dtc'
import EmptyState from '@/components/EmptyState.vue'
import ProductCard from '@/components/ProductCard.vue'
import { describeError } from '@/utils/errors'

const route = useRoute()
const router = useRouter()

const categories = ref<Category[]>([])
const attributes = ref<Attribute[]>([])
const products = ref<SpuCard[]>([])
const total = ref(0)
const pages = ref(0)
const loading = ref(true)
const error = ref('')
const filterOpen = ref(false)

const categoryId = ref<number | undefined>()
const keyword = ref('')
const keywordInput = ref('')
const sort = ref('recommended')
const selectedAttrs = ref<string[]>([])
const selectedBand = ref(-1)
const current = ref(1)
const size = 9

const priceBands = [
  { label: '¥0 – ¥199', min: 0, max: 199 },
  { label: '¥200 – ¥499', min: 200, max: 499 },
  { label: '¥500 – ¥999', min: 500, max: 999 },
  { label: '¥1000 以上', min: 1000, max: undefined },
]

const filterAttributes = computed(() =>
  attributes.value.filter((attribute) => attribute.filterable && attribute.values.length > 0),
)

const categoryName = computed(
  () => categories.value.find((item) => item.id === categoryId.value)?.name ?? '全部商品',
)

const activeChips = computed(() => {
  const chips: { key: string; label: string; remove: () => void }[] = []
  if (categoryId.value) {
    chips.push({
      key: 'category',
      label: categoryName.value,
      remove: () => apply({ categoryId: null, attrs: [] }),
    })
  }
  if (keyword.value) {
    chips.push({
      key: 'keyword',
      label: `搜索：${keyword.value}`,
      remove: () => apply({ keyword: '' }),
    })
  }
  if (selectedBand.value >= 0) {
    chips.push({
      key: 'price',
      label: priceBands[selectedBand.value].label,
      remove: () => apply({ band: -1 }),
    })
  }
  for (const key of selectedAttrs.value) {
    const [attrId, valueId] = key.split(':').map(Number)
    const attribute = attributes.value.find((item) => item.id === attrId)
    const value = attribute?.values.find((item) => item.id === valueId)
    chips.push({
      key,
      label: `${attribute?.name ?? ''}：${value?.value ?? ''}`,
      remove: () => apply({ attrs: selectedAttrs.value.filter((item) => item !== key) }),
    })
  }
  return chips
})

function first(value: unknown): string {
  if (Array.isArray(value)) {
    return typeof value[0] === 'string' ? value[0] : ''
  }
  return typeof value === 'string' ? value : ''
}

function parseQuery(): void {
  const rawCategory = first(route.query.categoryId)
  categoryId.value = rawCategory ? Number(rawCategory) : undefined
  keyword.value = first(route.query.keyword)
  keywordInput.value = keyword.value
  sort.value = first(route.query.sort) || 'recommended'
  const rawAttrs = first(route.query.attrs)
  selectedAttrs.value = rawAttrs ? rawAttrs.split(',').filter(Boolean) : []
  const min = first(route.query.minPrice) ? Number(first(route.query.minPrice)) : undefined
  const max = first(route.query.maxPrice) ? Number(first(route.query.maxPrice)) : undefined
  selectedBand.value = priceBands.findIndex((band) => band.min === min && band.max === max)
  current.value = Number(first(route.query.current)) || 1
}

async function loadCategories(): Promise<void> {
  try {
    categories.value = await catalogApi.categories()
  } catch {
    categories.value = []
  }
}

async function loadAttributes(): Promise<void> {
  try {
    attributes.value = await catalogApi.attributes(categoryId.value)
  } catch {
    attributes.value = []
  }
}

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const band = selectedBand.value >= 0 ? priceBands[selectedBand.value] : undefined
    const page = await catalogApi.page({
      categoryId: categoryId.value,
      keyword: keyword.value || undefined,
      minPrice: band?.min,
      maxPrice: band?.max,
      attrs: selectedAttrs.value.length ? selectedAttrs.value.join(',') : undefined,
      sort: sort.value,
      current: current.value,
      size,
    })
    products.value = page.records
    total.value = page.total
    pages.value = page.pages
  } catch (e) {
    error.value = describeError(e)
    products.value = []
    total.value = 0
    pages.value = 0
  } finally {
    loading.value = false
  }
}

function apply(
  patch: {
    categoryId?: number | null
    keyword?: string
    sort?: string
    attrs?: string[]
    band?: number
    current?: number
  },
  resetPage = true,
): void {
  const nextCategory = patch.categoryId !== undefined ? patch.categoryId ?? undefined : categoryId.value
  const nextKeyword = patch.keyword !== undefined ? patch.keyword : keyword.value
  const nextSort = patch.sort !== undefined ? patch.sort : sort.value
  const nextAttrs = patch.attrs !== undefined ? patch.attrs : selectedAttrs.value
  const nextBand = patch.band !== undefined ? patch.band : selectedBand.value
  const nextCurrent = patch.current !== undefined ? patch.current : resetPage ? 1 : current.value
  const band = nextBand >= 0 ? priceBands[nextBand] : undefined
  const query: Record<string, string> = {}
  if (nextCategory) query.categoryId = String(nextCategory)
  if (nextKeyword) query.keyword = nextKeyword
  if (nextSort && nextSort !== 'recommended') query.sort = nextSort
  if (nextAttrs.length) query.attrs = nextAttrs.join(',')
  if (band) {
    query.minPrice = String(band.min)
    if (band.max !== undefined) query.maxPrice = String(band.max)
  }
  if (nextCurrent > 1) query.current = String(nextCurrent)
  filterOpen.value = false
  void router.push({ name: 'products', query })
}

function submitKeyword(): void {
  apply({ keyword: keywordInput.value.trim() })
}

function clearAll(): void {
  void router.push({ name: 'products' })
}

function toggleAttr(key: string): void {
  const next = selectedAttrs.value.includes(key)
    ? selectedAttrs.value.filter((item) => item !== key)
    : [...selectedAttrs.value, key]
  apply({ attrs: next })
}

function toggleBand(index: number): void {
  apply({ band: selectedBand.value === index ? -1 : index })
}

watch(
  () => route.query,
  () => {
    parseQuery()
    void load()
  },
  { immediate: true },
)

watch(
  () => categoryId.value,
  () => {
    void loadAttributes()
  },
)

onMounted(() => {
  void loadCategories()
})
</script>

<template>
  <div>
    <div class="container page-head">
      <div class="page-head__row">
        <div>
          <span class="eyebrow">Catalog / {{ categories.length }} 个系列</span>
          <h1 class="display">{{ categoryName }}</h1>
          <p>为桌面、通勤和出行挑选耐用的科技工具。价格均为人民币含税价。</p>
        </div>
      </div>
    </div>

    <div class="container catalog-layout">
      <aside class="filters" :class="{ 'is-open': filterOpen }">
        <div class="filter-drawer-head">
          <strong>筛选</strong>
          <button class="btn btn--ghost btn--sm" type="button" @click="filterOpen = false">关闭</button>
        </div>

        <div class="filter-group">
          <h4>分类</h4>
          <label class="filter-option">
            <input
              type="radio"
              name="category"
              :checked="!categoryId"
              @change="apply({ categoryId: null, attrs: [] })"
            />
            全部商品
          </label>
          <label v-for="category in categories" :key="category.id" class="filter-option">
            <input
              type="radio"
              name="category"
              :checked="categoryId === category.id"
              @change="apply({ categoryId: category.id, attrs: [] })"
            />
            {{ category.name }}
          </label>
        </div>

        <div class="filter-group">
          <h4>价格</h4>
          <label v-for="(band, index) in priceBands" :key="band.label" class="filter-option">
            <input
              type="radio"
              name="price"
              :checked="selectedBand === index"
              @change="toggleBand(index)"
            />
            {{ band.label }}
          </label>
        </div>

        <div v-for="attribute in filterAttributes" :key="attribute.id" class="filter-group">
          <h4>{{ attribute.name }}</h4>
          <label v-for="value in attribute.values" :key="value.id" class="filter-option">
            <input
              type="checkbox"
              :checked="selectedAttrs.includes(`${attribute.id}:${value.id}`)"
              @change="toggleAttr(`${attribute.id}:${value.id}`)"
            />
            <span
              v-if="value.colorHex"
              class="swatch-dot"
              :style="{ background: value.colorHex }"
            />
            {{ value.value }}
          </label>
        </div>

        <button v-if="categoryId || keyword || selectedAttrs.length || selectedBand >= 0" class="filter-clear" type="button" @click="clearAll">
          清除全部筛选
        </button>
      </aside>

      <section>
        <div class="catalog-toolbar">
          <button class="btn btn--line btn--sm mobile-filter-trigger" type="button" @click="filterOpen = true">
            筛选
          </button>
          <span class="catalog-toolbar__count">{{ total }} 件商品</span>
          <form class="catalog-toolbar__search" @submit.prevent="submitKeyword">
            <input v-model="keywordInput" class="input" type="search" placeholder="搜索名称或描述" aria-label="搜索商品" />
          </form>
          <select v-model="sort" class="select catalog-toolbar__sort" aria-label="排序" @change="apply({ sort })">
            <option value="recommended">推荐排序</option>
            <option value="newest">最新上架</option>
            <option value="price_asc">价格从低到高</option>
            <option value="price_desc">价格从高到低</option>
          </select>
        </div>

        <div v-if="activeChips.length" class="active-filters">
          <span v-for="chip in activeChips" :key="chip.key" class="filter-chip">
            {{ chip.label }}
            <button type="button" aria-label="移除筛选" @click="chip.remove">×</button>
          </span>
        </div>

        <div v-if="error" class="error-banner">{{ error }}</div>
        <div v-else-if="loading" class="catalog-grid">
          <div v-for="i in 9" :key="i" class="skeleton" style="aspect-ratio: 1" />
        </div>
        <EmptyState
          v-else-if="!products.length"
          title="没有找到匹配的商品"
          description="试试减少筛选条件，或换一个关键词。"
        >
          <button class="btn btn--line" type="button" @click="clearAll">清除筛选</button>
        </EmptyState>
        <div v-else class="catalog-grid">
          <ProductCard v-for="product in products" :key="product.id" :product="product" />
        </div>

        <div v-if="pages > 1" class="pagination-row">
          <el-pagination
            background
            layout="prev, pager, next"
            :total="total"
            :page-size="size"
            :current-page="current"
            @current-change="(page: number) => apply({ current: page }, false)"
          />
        </div>
      </section>
    </div>
  </div>
</template>
