<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { couponApi, orderApi, productApi } from '@/api'
import type { CouponView } from '@/api/coupon'
import { CouponStatus } from '@/api/coupon'
import { newIdempotencyKey } from '@/api/idempotency'
import PageHead from '@/components/PageHead.vue'
import StatusPill from '@/components/StatusPill.vue'
import { useCartStore } from '@/stores/cart'
import { describeError, isDuplicate } from '@/utils/errors'
import { formatYuan, toCents } from '@/utils/money'

interface Line {
  skuId: number
  title: string
  price: number
  quantity: number
}

interface LineResult {
  skuId: number
  title: string
  orderId: number | null
  error: string | null
}

interface IdempotencyRecord {
  key: string
  quantity: number
  couponId: number
}

const route = useRoute()
const router = useRouter()
const cart = useCartStore()

const lines = ref<Line[]>([])
const fromCart = ref(true)
const loading = ref(true)
const submitting = ref(false)
const coupons = ref<CouponView[]>([])
const couponError = ref('')
const NO_COUPON = 0
const selectedCouponId = ref<number>(NO_COUPON)
const results = ref<LineResult[] | null>(null)
const syncWarning = ref('')

/**
 * 幂等键绑定「这次提交意图」：商品、数量、券都没变才复用。
 * 成功的一行立刻从待提交列表移除并销掉键，避免重复点击生成第二笔订单。
 */
const idempotency = new Map<number, IdempotencyRecord>()

const singleLine = computed(() => lines.value.length === 1)

const totalCents = computed(() =>
  lines.value.reduce((sum, line) => sum + toCents(line.price) * line.quantity, 0)
)

const usableCoupons = computed(() => {
  if (!singleLine.value) {
    return []
  }
  return coupons.value.filter(
    (coupon) =>
      coupon.status === CouponStatus.UNUSED && toCents(coupon.thresholdAmount) <= totalCents.value
  )
})

const selectedCoupon = computed(
  () => coupons.value.find((coupon) => coupon.id === selectedCouponId.value) ?? null
)

const payableCents = computed(() => {
  const discount = selectedCoupon.value ? toCents(selectedCoupon.value.discountAmount) : 0
  return Math.max(totalCents.value - discount, 0)
})

const submitLabel = computed(() => {
  if (submitting.value) {
    return '提交中…'
  }
  return lines.value.length > 1 ? `提交 ${lines.value.length} 笔订单` : '提交订单'
})

watch(selectedCouponId, () => {
  idempotency.clear()
})

function keyFor(line: Line): string {
  const couponId = singleLine.value && selectedCouponId.value !== NO_COUPON ? selectedCouponId.value : NO_COUPON
  const existing = idempotency.get(line.skuId)
  if (existing && existing.quantity === line.quantity && existing.couponId === couponId) {
    return existing.key
  }
  const record: IdempotencyRecord = { key: newIdempotencyKey(), quantity: line.quantity, couponId }
  idempotency.set(line.skuId, record)
  return record.key
}

async function loadLines(): Promise<void> {
  loading.value = true
  try {
    const skuParam = route.query.skuId
    if (typeof skuParam === 'string' && skuParam !== '') {
      fromCart.value = false
      const sku = await productApi.detail(Number(skuParam))
      const parsed = Number(route.query.quantity)
      lines.value = [
        {
          skuId: sku.id,
          title: sku.title,
          price: sku.price,
          quantity: Number.isFinite(parsed) && parsed > 0 ? parsed : 1
        }
      ]
      return
    }
    fromCart.value = true
    await cart.load()
    lines.value = cart.checkoutItems.map((item) => ({
      skuId: item.skuId,
      title: item.title,
      price: item.price,
      quantity: item.quantity
    }))
  } catch (error) {
    ElMessage.error(describeError(error))
  } finally {
    loading.value = false
  }
}

async function loadCoupons(): Promise<void> {
  try {
    coupons.value = await couponApi.mine()
  } catch {
    couponError.value = '优惠券暂时无法加载，本单将不使用优惠券。'
    coupons.value = []
  }
}

async function submit(): Promise<void> {
  submitting.value = true
  results.value = null
  syncWarning.value = ''
  const pending = [...lines.value]
  const collected: LineResult[] = []
  const succeeded = new Set<number>()
  const couponId = singleLine.value && selectedCouponId.value !== NO_COUPON ? selectedCouponId.value : null

  for (const line of pending) {
    try {
      const orderId = await orderApi.place(
        { skuId: line.skuId, quantity: line.quantity },
        { idempotencyKey: keyFor(line), couponId }
      )
      collected.push({ skuId: line.skuId, title: line.title, orderId, error: null })
      succeeded.add(line.skuId)
      idempotency.delete(line.skuId)
    } catch (error) {
      collected.push({
        skuId: line.skuId,
        title: line.title,
        orderId: null,
        error: isDuplicate(error) ? '这次提交与另一笔下单冲突，请刷新后重试' : describeError(error)
      })
    }
  }

  results.value = collected
  lines.value = lines.value.filter((line) => !succeeded.has(line.skuId))

  if (fromCart.value) {
    for (const line of pending) {
      if (!succeeded.has(line.skuId)) {
        continue
      }
      try {
        await cart.remove(line.skuId)
      } catch {
        syncWarning.value = '订单已创建，但购物车未能同步移除已下单商品，请刷新购物车核对。'
      }
    }
  }

  submitting.value = false

  if (collected.every((result) => result.orderId !== null)) {
    ElMessage.success('下单成功，请在 30 分钟内完成支付')
    await router.replace({ name: 'orders' })
    return
  }

  if (succeeded.size > 0) {
    ElMessage.warning('部分商品下单成功，失败项仍保留在待提交列表')
  }
}

onMounted(async () => {
  await loadLines()
  await loadCoupons()
})
</script>

<template>
  <div class="app-page">
    <PageHead
      title="结算"
      :meta="fromCart ? '按购物车中在售商品结算' : '立即购买：单品直下，不经过购物车'"
    />

    <div v-if="loading" aria-busy="true" aria-label="正在加载结算信息">
      <div class="skeleton-block skeleton-row" />
      <div class="skeleton-block skeleton-row" />
      <div class="skeleton-block skeleton-row" />
    </div>

    <template v-else-if="lines.length === 0 && !results">
      <div class="empty-state">
        <h2>没有可结算的商品</h2>
        <p>购物车里的商品可能已经下架，或者你还没有挑选商品。</p>
        <RouterLink class="btn" to="/products">去目录挑选</RouterLink>
      </div>
    </template>

    <template v-else-if="lines.length === 0 && results">
      <section class="panel" aria-live="polite">
        <h2 class="panel-head">提交结果</h2>
        <div class="list-row" v-for="result in results" :key="result.skuId">
          <div>
            <p class="list-row__title">{{ result.title }}</p>
            <p class="list-row__meta">
              {{ result.orderId !== null ? `订单号 ${result.orderId}` : result.error }}
            </p>
          </div>
          <div class="list-row__end">
            <StatusPill
              v-if="result.orderId !== null"
              tone="paid"
              label="已下单"
            />
            <StatusPill v-else tone="danger" label="未下单" />
          </div>
        </div>
        <div class="summary-actions" style="margin-top: 20px">
          <RouterLink class="btn" to="/orders">去我的订单</RouterLink>
          <RouterLink class="btn btn--outline" to="/products">继续挑选</RouterLink>
        </div>
      </section>
    </template>

    <div v-else class="checkout-layout">
      <div class="checkout-main">
        <section class="panel" aria-label="订单内容">
          <h2 class="panel-head">订单内容</h2>
          <div class="checkout-line" v-for="line in lines" :key="line.skuId">
            <div>
              <p class="list-row__title">{{ line.title }}</p>
              <p class="list-row__meta">{{ formatYuan(line.price) }} × {{ line.quantity }}</p>
            </div>
            <span class="list-row__meta num">× {{ line.quantity }}</span>
            <span class="checkout-line__amount">{{ formatYuan(line.price * line.quantity) }}</span>
          </div>
        </section>

        <div v-if="lines.length > 1" class="notice notice--info">
          <strong>每件商品会各生成一个订单</strong>
          <p>
            后端订单是单商品模型，一次结算多件等于下多单；优惠券只对单个订单生效，所以多件时不可选。
          </p>
        </div>

        <section v-if="singleLine" class="panel" aria-label="优惠券">
          <h2 class="panel-head">优惠券</h2>
          <p v-if="couponError" class="notice notice--warning">{{ couponError }}</p>
          <div class="coupon-choices">
            <label class="coupon-choice">
              <input v-model="selectedCouponId" type="radio" :value="NO_COUPON" name="coupon" />
              <span>不使用优惠券</span>
            </label>
            <label v-for="coupon in usableCoupons" :key="coupon.id" class="coupon-choice">
              <input v-model="selectedCouponId" type="radio" :value="coupon.id" name="coupon" />
              <span>
                {{ coupon.title }} · 满 {{ formatYuan(coupon.thresholdAmount) }} 减
                {{ formatYuan(coupon.discountAmount) }}
              </span>
            </label>
          </div>
          <p v-if="usableCoupons.length === 0" class="field__hint" style="margin-top: 10px">
            当前没有可用于这笔订单的券（未使用、且订单金额达到门槛）。<RouterLink to="/coupons">去领券</RouterLink>
          </p>
        </section>

        <section v-if="results" class="panel checkout-results" aria-live="polite">
          <h2 class="panel-head">提交结果</h2>
          <div class="list-row" v-for="result in results" :key="result.skuId">
            <div>
              <p class="list-row__title">{{ result.title }}</p>
              <p class="list-row__meta">
                {{ result.orderId !== null ? `订单号 ${result.orderId}` : result.error }}
              </p>
            </div>
            <div class="list-row__end">
              <RouterLink
                v-if="result.orderId !== null"
                class="btn btn--outline btn--sm"
                :to="{ name: 'order-detail', params: { id: result.orderId } }"
              >
                查看订单
              </RouterLink>
              <StatusPill v-else tone="danger" label="未下单" />
            </div>
          </div>
        </section>
      </div>

      <aside class="checkout-summary">
        <div class="panel">
          <h2 class="panel-head">应付</h2>
          <div class="summary-line">
            <span>商品小计</span>
            <strong class="num">{{ formatYuan(totalCents / 100) }}</strong>
          </div>
          <div v-if="selectedCoupon" class="summary-line" style="margin-top: 8px">
            <span>优惠券</span>
            <strong class="num">−{{ formatYuan(selectedCoupon.discountAmount) }}</strong>
          </div>
          <div class="summary-total">
            <span>应付金额</span>
            <strong>{{ formatYuan(payableCents / 100) }}</strong>
          </div>
          <p class="field__hint" style="margin: 8px 0 16px">
            金额由订单服务按商品快照计算，这里的数字只是预览。提交后 30 分钟内未支付会自动关单。
          </p>
          <div class="summary-actions">
            <button type="button" class="btn btn--block" :disabled="submitting" @click="submit">
              {{ submitLabel }}
            </button>
            <RouterLink class="btn btn--outline btn--block" :to="fromCart ? '/cart' : '/products'">
              {{ fromCart ? '返回购物车' : '返回目录' }}
            </RouterLink>
          </div>
        </div>

        <p v-if="syncWarning" class="notice notice--warning" aria-live="polite">
          {{ syncWarning }}
        </p>

      </aside>
    </div>
  </div>
</template>

<style scoped>
.checkout-main {
  display: grid;
  gap: 16px;
}

.coupon-choices {
  display: grid;
  gap: 8px;
}

.coupon-choice {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 40px;
  cursor: pointer;
}

.coupon-choice input {
  width: 18px;
  height: 18px;
  accent-color: var(--ink);
}

.coupon-choice span {
  font-size: var(--step-2);
}
</style>
