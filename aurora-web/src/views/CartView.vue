<script setup lang="ts">
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import { SKU_ON_SALE } from '@/api/product'
import EmptyState from '@/components/EmptyState.vue'
import PageHead from '@/components/PageHead.vue'
import ProductFigure from '@/components/ProductFigure.vue'
import { useCartStore } from '@/stores/cart'
import { describeError } from '@/utils/errors'
import { formatYuan } from '@/utils/money'

const router = useRouter()
const cart = useCartStore()
const busySkuId = ref<number | null>(null)
const clearing = ref(false)

const totalLines = computed(() => cart.items.length)
const offShelfCount = computed(() => cart.items.length - cart.checkoutItems.length)

async function changeQuantity(skuId: number, quantity: number): Promise<void> {
  if (quantity < 1 || quantity > 999) {
    return
  }
  busySkuId.value = skuId
  try {
    await cart.updateQuantity(skuId, quantity)
  } catch (error) {
    ElMessage.error(describeError(error))
    await cart.load().catch(() => undefined)
  } finally {
    busySkuId.value = null
  }
}

async function remove(skuId: number): Promise<void> {
  try {
    await ElMessageBox.confirm('确定从购物车移除这件商品吗？', '移除', { type: 'warning' })
  } catch {
    return
  }
  busySkuId.value = skuId
  try {
    await cart.remove(skuId)
  } catch (error) {
    ElMessage.error(describeError(error))
  } finally {
    busySkuId.value = null
  }
}

async function clearAll(): Promise<void> {
  try {
    await ElMessageBox.confirm('确定清空购物车吗？', '清空', { type: 'warning' })
  } catch {
    return
  }
  clearing.value = true
  try {
    await cart.clear()
  } catch (error) {
    ElMessage.error(describeError(error))
  } finally {
    clearing.value = false
  }
}

onMounted(() => {
  void cart.load().catch((error: unknown) => ElMessage.error(describeError(error)))
})
</script>

<template>
  <div class="app-page">
    <PageHead
      title="购物车"
      :meta="`${totalLines} 行 · ${cart.checkoutItems.length} 件可结算 · 金额仅统计在售商品`"
    />

    <div v-if="cart.loading && cart.items.length === 0" aria-busy="true" aria-label="正在加载购物车">
      <div class="skeleton-block skeleton-row" />
      <div class="skeleton-block skeleton-row" />
      <div class="skeleton-block skeleton-row" />
    </div>

    <EmptyState
      v-else-if="cart.items.length === 0"
      title="购物车是空的"
      hint="从目录里挑一件商品，或者去秒杀排期看看这一期有什么。"
    >
      <RouterLink class="btn" to="/products">去目录挑选</RouterLink>
    </EmptyState>

    <div v-else class="cart-layout">
      <section class="panel" aria-label="购物车商品">
        <div class="panel-head">
          <h2>商品</h2>
          <span class="num">合计 {{ formatYuan(cart.estimatedTotal) }}</span>
        </div>

        <div class="cart-line" v-for="item in cart.items" :key="item.skuId">
          <RouterLink class="cart-line__figure" :to="{ name: 'product-detail', params: { id: item.skuId } }">
            <ProductFigure :title="item.title" :label="`${item.title} 的目录图形`" />
          </RouterLink>

          <div>
            <RouterLink class="cart-line__title" :to="{ name: 'product-detail', params: { id: item.skuId } }">
              {{ item.title }}
            </RouterLink>
            <p class="cart-line__meta">
              {{ formatYuan(item.price) }} / 件
              <template v-if="item.status !== SKU_ON_SALE"> · 已下架，不会结算</template>
              <template v-else-if="item.stock <= 0"> · 参考库存为 0</template>
            </p>
            <button type="button" class="link-danger" @click="remove(item.skuId)">移除</button>
          </div>

          <div class="cart-line__controls">
            <div class="quantity quantity--sm" role="group" :aria-label="`${item.title} 的数量`">
              <button
                type="button"
                :disabled="busySkuId === item.skuId || item.quantity <= 1"
                @click="changeQuantity(item.skuId, item.quantity - 1)"
              >
                −
              </button>
              <output>{{ item.quantity }}</output>
              <button
                type="button"
                :disabled="busySkuId === item.skuId || item.quantity >= 999"
                @click="changeQuantity(item.skuId, item.quantity + 1)"
              >
                +
              </button>
            </div>
            <span class="cart-line__amount num">{{ formatYuan(item.price * item.quantity) }}</span>
          </div>
        </div>

        <div class="panel-head" style="margin: 16px 0 0">
          <button type="button" class="link-muted" :disabled="clearing" @click="clearAll">
            {{ clearing ? '清空中…' : '清空购物车' }}
          </button>
        </div>
      </section>

      <aside class="cart-summary">
        <div class="panel">
          <h2 class="panel-head">结算</h2>
          <div class="summary-line">
            <span>可结算商品</span>
            <strong class="num">{{ cart.checkoutItems.length }} 件</strong>
          </div>
          <div class="summary-line" style="margin-top: 8px">
            <span>商品合计</span>
            <strong class="num">{{ formatYuan(cart.estimatedTotal) }}</strong>
          </div>
          <div class="summary-total">
            <span>应付（预览）</span>
            <strong>{{ formatYuan(cart.estimatedTotal) }}</strong>
          </div>
          <p class="field__hint" style="margin: 8px 0 16px">
            最终金额由订单服务按商品快照计算，优惠券在结算页选择。
          </p>
          <div class="summary-actions">
            <button
              type="button"
              class="btn btn--block"
              :disabled="cart.checkoutItems.length === 0"
              @click="router.push({ name: 'checkout' })"
            >
              去结算
            </button>
            <RouterLink class="btn btn--outline btn--block" to="/products">继续挑选</RouterLink>
          </div>
        </div>

        <div v-if="offShelfCount > 0" class="notice notice--warning">
          <strong>{{ offShelfCount }} 行商品已下架</strong>
          <p>下架商品保留在购物车里，但不会被结算。</p>
        </div>
      </aside>
    </div>
  </div>
</template>
