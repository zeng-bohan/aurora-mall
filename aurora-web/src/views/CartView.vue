<script setup lang="ts">
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'

import EmptyState from '@/components/EmptyState.vue'
import QuantityStepper from '@/components/QuantityStepper.vue'
import { useCartStore } from '@/stores/cart'
import { describeError } from '@/utils/errors'
import { formatYuan, toCents } from '@/utils/money'

const router = useRouter()
const cart = useCartStore()
const selected = ref<number[]>([])
const initialized = ref(false)

const activeItems = computed(() => cart.items.filter((item) => item.status === 1))
const selectedItems = computed(() =>
  cart.items.filter((item) => item.status === 1 && selected.value.includes(item.skuId)),
)
const allSelected = computed(
  () => activeItems.value.length > 0 && activeItems.value.every((item) => selected.value.includes(item.skuId)),
)
const subtotal = computed(() =>
  selectedItems.value.reduce((sum, item) => sum + toCents(item.price) * item.quantity, 0) / 100,
)
const totalQuantity = computed(() =>
  selectedItems.value.reduce((sum, item) => sum + item.quantity, 0),
)
const hasStockIssue = computed(() =>
  selectedItems.value.some((item) => item.stock !== null && item.stock < item.quantity),
)

watch(
  () => cart.items,
  (items) => {
    const valid = new Set(items.filter((item) => item.status === 1).map((item) => item.skuId))
    selected.value = selected.value.filter((id) => valid.has(id))
    if (!initialized.value && items.length) {
      selected.value = [...valid]
      initialized.value = true
    }
  },
  { deep: true, immediate: true },
)

function toggleAll(): void {
  selected.value = allSelected.value ? [] : activeItems.value.map((item) => item.skuId)
}

function toggle(skuId: number): void {
  selected.value = selected.value.includes(skuId)
    ? selected.value.filter((id) => id !== skuId)
    : [...selected.value, skuId]
}

async function changeQuantity(skuId: number, quantity: number): Promise<void> {
  try {
    await cart.updateQuantity(skuId, quantity)
  } catch (e) {
    ElMessage.error(describeError(e))
  }
}

async function remove(skuId: number): Promise<void> {
  try {
    await cart.remove(skuId)
    selected.value = selected.value.filter((id) => id !== skuId)
    ElMessage.success('已移出购物车')
  } catch (e) {
    ElMessage.error(describeError(e))
  }
}

async function clear(): Promise<void> {
  try {
    await ElMessageBox.confirm('清空购物车中的全部商品？', '清空购物车', {
      confirmButtonText: '清空',
      cancelButtonText: '取消',
      type: 'warning',
    })
    await cart.clear()
    selected.value = []
    ElMessage.success('购物车已清空')
  } catch {
    // 用户取消
  }
}

function checkout(): void {
  if (!selectedItems.value.length) {
    ElMessage.warning('请先选择结算商品')
    return
  }
  if (hasStockIssue.value) {
    ElMessage.warning('部分商品库存不足，请调整数量')
    return
  }
  void router.push({
    name: 'checkout',
    query: { skus: selectedItems.value.map((item) => item.skuId).join(',') },
  })
}

onMounted(() => {
  void cart.load().catch((e) => ElMessage.error(describeError(e)))
})
</script>

<template>
  <div>
    <div class="container page-head">
      <span class="eyebrow">Cart</span>
      <h1 class="display">购物车。</h1>
      <p>勾选需要结算的商品。多件商品会合并成一个父订单，一次支付、一次发货。</p>
    </div>

    <div class="container">
      <div v-if="cart.loading" class="page-loading">正在读取购物车…</div>
      <EmptyState
        v-else-if="!cart.items.length"
        title="购物车还是空的"
        description="从桌面或随身系列里挑一件，先把每天用的工具换掉。"
      >
        <RouterLink class="btn btn--primary" to="/products">去逛逛</RouterLink>
      </EmptyState>

      <div v-else class="cart-layout">
        <section>
          <div class="cart-select-all">
            <label style="display: flex; align-items: center; gap: 9px">
              <input type="checkbox" class="checkbox" :checked="allSelected" @change="toggleAll" />
              全选（{{ activeItems.length }} 种商品）
            </label>
            <button type="button" style="text-decoration: underline; text-underline-offset: 3px" @click="clear">
              清空购物车
            </button>
          </div>

          <div class="cart-list">
            <article v-for="item in cart.items" :key="item.skuId" class="cart-line">
              <input
                type="checkbox"
                class="checkbox"
                :checked="selected.includes(item.skuId)"
                :disabled="item.status !== 1"
                @change="toggle(item.skuId)"
              />
              <RouterLink class="cart-line__media" :to="`/products/${item.spuId}`">
                <img :src="item.imageUrl" :alt="item.title" />
              </RouterLink>
              <div>
                <RouterLink class="cart-line__title" :to="`/products/${item.spuId}`">{{ item.title }}</RouterLink>
                <div class="cart-line__meta">{{ item.attributesText }}</div>
                <div class="cart-line__meta">
                  {{ item.status === 1 ? '在售' : '已下架' }}
                  <span v-if="item.stock !== null && item.stock < item.quantity" style="color: var(--danger)">
                    · 库存仅剩 {{ item.stock }} 件
                  </span>
                </div>
                <div class="cart-line__foot">
                  <span class="cart-line__price">{{ formatYuan(item.price) }}</span>
                  <QuantityStepper
                    :model-value="item.quantity"
                    :min="1"
                    :max="99"
                    :disabled="item.status !== 1"
                    @update:model-value="(quantity: number) => changeQuantity(item.skuId, quantity)"
                  />
                </div>
              </div>
              <div class="cart-line__right">
                <span class="tiny">{{ formatYuan(item.price * item.quantity) }}</span>
                <button class="cart-line__remove" type="button" @click="remove(item.skuId)">移除</button>
              </div>
            </article>
          </div>
        </section>

        <aside class="summary-card">
          <h3>订单摘要</h3>
          <div class="summary-list">
            <div class="summary-row">
              <span>已选商品</span>
              <strong>{{ totalQuantity }} 件</strong>
            </div>
            <div class="summary-row">
              <span>商品小计</span>
              <strong>{{ formatYuan(subtotal) }}</strong>
            </div>
            <div class="summary-row">
              <span>运费</span>
              <span>结算时按券后金额计算</span>
            </div>
          </div>
          <div class="summary-total">
            <span>预计合计</span>
            <strong>{{ formatYuan(subtotal) }}</strong>
          </div>
          <button class="btn btn--primary btn--block" type="button" :disabled="!selectedItems.length || hasStockIssue" @click="checkout">
            去结算
          </button>
          <p class="summary-note">满 ¥299 免运费，未满 ¥12。优惠券在结算页选择。</p>
          <p v-if="hasStockIssue" class="error-text" style="margin-top: 8px">部分商品库存不足，请调整数量后再结算。</p>
        </aside>
      </div>
    </div>
  </div>
</template>
