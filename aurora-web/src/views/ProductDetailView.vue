<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { productApi } from '@/api'
import type { Sku } from '@/api/product'
import { SKU_ON_SALE } from '@/api/product'
import { ApiError, ErrorCode } from '@/api/types'
import { useAuthStore } from '@/stores/auth'
import { useCartStore } from '@/stores/cart'
import { formatDateTime } from '@/utils/datetime'
import { describeError } from '@/utils/errors'
import { formatYuan } from '@/utils/money'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const cart = useCartStore()

const sku = ref<Sku | null>(null)
const loading = ref(false)
const notFound = ref(false)
const quantity = ref(1)
const adding = ref(false)

const skuId = computed(() => Number(route.params.id))
const sellable = computed(() => sku.value !== null && sku.value.status === SKU_ON_SALE && sku.value.stock > 0)
const maxQuantity = computed(() => Math.min(sku.value?.stock ?? 1, 999))

async function load(): Promise<void> {
  loading.value = true
  notFound.value = false
  sku.value = null
  try {
    sku.value = await productApi.detail(skuId.value)
    quantity.value = 1
  } catch (error) {
    // 商品不存在是正常结果（链接过期 / 手工改 URL），给一页友好提示而不是报错弹窗
    if (error instanceof ApiError && error.code === ErrorCode.NOT_FOUND) {
      notFound.value = true
      return
    }
    ElMessage.error(describeError(error))
  } finally {
    loading.value = false
  }
}

async function requireLogin(): Promise<boolean> {
  if (auth.isLoggedIn) {
    return true
  }
  await router.push({ name: 'login', query: { redirect: route.fullPath } })
  return false
}

async function addToCart(): Promise<void> {
  if (!sku.value || !(await requireLogin())) {
    return
  }
  adding.value = true
  try {
    await cart.add(sku.value.id, quantity.value)
    ElMessage.success('已加入购物车')
  } catch (error) {
    ElMessage.error(describeError(error))
  } finally {
    adding.value = false
  }
}

async function buyNow(): Promise<void> {
  if (!sku.value || !(await requireLogin())) {
    return
  }
  // 直接买：不经过购物车，结算页按 query 里的单品走
  await router.push({
    name: 'checkout',
    query: { skuId: String(sku.value.id), quantity: String(quantity.value) }
  })
}

onMounted(load)
watch(skuId, load)
</script>

<template>
  <div class="aurora-page">
    <el-page-header content="商品详情" @back="router.back()" />

    <el-skeleton v-if="loading" :rows="5" animated class="aurora-detail__skeleton" />

    <el-result
      v-else-if="notFound"
      icon="info"
      title="商品不存在"
      sub-title="它可能已经下架，或链接已经失效。"
    >
      <template #extra>
        <el-button type="primary" @click="router.replace('/products')">回到商品列表</el-button>
      </template>
    </el-result>

    <el-card v-else-if="sku" class="aurora-detail">
      <h2 class="aurora-detail__title">{{ sku.title }}</h2>
      <div class="aurora-detail__price">{{ formatYuan(sku.price) }}</div>

      <el-descriptions :column="1" border class="aurora-detail__info">
        <el-descriptions-item label="商品编号">{{ sku.id }}</el-descriptions-item>
        <el-descriptions-item label="库存">
          <span v-if="sku.status !== SKU_ON_SALE">已下架</span>
          <span v-else-if="sku.stock <= 0">缺货</span>
          <span v-else>{{ sku.stock }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="更新时间">{{ formatDateTime(sku.updatedAt) }}</el-descriptions-item>
      </el-descriptions>

      <div class="aurora-detail__buy">
        <el-input-number v-model="quantity" :min="1" :max="maxQuantity" :disabled="!sellable" />
        <el-button type="primary" :disabled="!sellable" :loading="adding" @click="addToCart">
          加入购物车
        </el-button>
        <el-button type="danger" :disabled="!sellable" @click="buyNow">立即购买</el-button>
      </div>

      <el-alert
        v-if="!sellable"
        type="warning"
        :closable="false"
        show-icon
        title="该商品当前不可购买"
        class="aurora-detail__alert"
      />
    </el-card>
  </div>
</template>

<style scoped>
.aurora-detail {
  margin-top: 16px;
}

.aurora-detail__skeleton {
  margin-top: 16px;
}

.aurora-detail__title {
  margin: 0 0 12px;
  font-size: 22px;
}

.aurora-detail__price {
  font-size: 28px;
  color: #f56c6c;
  font-weight: 700;
  margin-bottom: 20px;
}

.aurora-detail__info {
  max-width: 520px;
  margin-bottom: 24px;
}

.aurora-detail__buy {
  display: flex;
  align-items: center;
  gap: 12px;
}

.aurora-detail__alert {
  margin-top: 20px;
  max-width: 520px;
}
</style>
