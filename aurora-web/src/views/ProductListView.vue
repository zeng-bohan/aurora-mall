<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import { productApi } from '@/api'
import type { Sku } from '@/api/product'
import { SKU_ON_SALE } from '@/api/product'
import { useAuthStore } from '@/stores/auth'
import { useCartStore } from '@/stores/cart'
import { describeError } from '@/utils/errors'
import { formatYuan } from '@/utils/money'

const router = useRouter()
const auth = useAuthStore()
const cart = useCartStore()

const records = ref<Sku[]>([])
const total = ref(0)
const current = ref(1)
const size = ref(12)
const loading = ref(false)
const addingId = ref<number | null>(null)

async function load(): Promise<void> {
  loading.value = true
  try {
    const page = await productApi.page(current.value, size.value)
    records.value = page.records
    total.value = page.total
  } catch (error) {
    ElMessage.error(describeError(error))
  } finally {
    loading.value = false
  }
}

function onChangePage(next: number): void {
  current.value = next
  void load()
}

async function addToCart(sku: Sku): Promise<void> {
  if (!auth.isLoggedIn) {
    await router.push({ name: 'login', query: { redirect: '/products' } })
    return
  }
  addingId.value = sku.id
  try {
    await cart.add(sku.id, 1)
    ElMessage.success(`已加入购物车：${sku.title}`)
  } catch (error) {
    ElMessage.error(describeError(error))
  } finally {
    addingId.value = null
  }
}

function inStock(sku: Sku): boolean {
  return sku.status === SKU_ON_SALE && sku.stock > 0
}

onMounted(load)
</script>

<template>
  <div class="aurora-page">
    <h2 class="aurora-title">商品</h2>

    <div v-loading="loading" class="aurora-grid">
      <el-card v-for="sku in records" :key="sku.id" shadow="hover" class="aurora-card">
        <RouterLink :to="{ name: 'product-detail', params: { id: sku.id } }" class="aurora-card__title">
          {{ sku.title }}
        </RouterLink>
        <div class="aurora-card__price aurora-amount">{{ formatYuan(sku.price) }}</div>
        <div class="aurora-card__meta">
          <span>库存 {{ sku.stock }}</span>
          <el-tag v-if="sku.status !== SKU_ON_SALE" type="info" size="small">已下架</el-tag>
          <el-tag v-else-if="sku.stock <= 0" type="warning" size="small">缺货</el-tag>
        </div>
        <el-button
          type="primary"
          size="small"
          :disabled="!inStock(sku)"
          :loading="addingId === sku.id"
          class="aurora-card__action"
          @click="addToCart(sku)"
        >
          加入购物车
        </el-button>
      </el-card>
    </div>

    <el-empty v-if="!loading && records.length === 0" description="暂无商品" />

    <div v-if="total > size" class="aurora-pager">
      <el-pagination
        layout="prev, pager, next, total"
        :total="total"
        :page-size="size"
        :current-page="current"
        @current-change="onChangePage"
      />
    </div>
  </div>
</template>

<style scoped>
.aurora-title {
  font-size: 20px;
  margin: 8px 0 16px;
}

.aurora-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 16px;
  min-height: 120px;
}

.aurora-card__title {
  display: block;
  font-weight: 600;
  margin-bottom: 12px;
  line-height: 1.4;
  min-height: 2.8em;
}

.aurora-card__price {
  font-size: 18px;
  color: #f56c6c;
  font-weight: 600;
  margin-bottom: 8px;
}

.aurora-card__meta {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: #909399;
  margin-bottom: 12px;
}

.aurora-card__action {
  width: 100%;
}

.aurora-pager {
  display: flex;
  justify-content: center;
  margin-top: 24px;
}
</style>
