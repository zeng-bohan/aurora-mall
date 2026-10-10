<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { couponApi, orderApi, productApi } from '@/api'
import type { CouponView } from '@/api/coupon'
import { CouponStatus } from '@/api/coupon'
import { newIdempotencyKey } from '@/api/idempotency'
import { useCartStore } from '@/stores/cart'
import { useMyOrdersStore } from '@/stores/myOrders'
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

const route = useRoute()
const router = useRouter()
const cart = useCartStore()
const myOrders = useMyOrdersStore()

const lines = ref<Line[]>([])
const fromCart = ref(true)
const loading = ref(true)
const submitting = ref(false)
const coupons = ref<CouponView[]>([])
const selectedCouponId = ref<number | null>(null)
const results = ref<LineResult[] | null>(null)

/**
 * 幂等键按「商品」缓存，而不是每次提交新生成：
 * 同一次提交意图重试时必须复用同一个键，后端的去重才起作用。
 */
const idempotencyKeys = new Map<number, string>()

function keyFor(skuId: number): string {
  let key = idempotencyKeys.get(skuId)
  if (!key) {
    key = newIdempotencyKey()
    idempotencyKeys.set(skuId, key)
  }
  return key
}

/**
 * 后端的订单模型是**单 SKU 的**（PlaceOrderRequest 只有 skuId + quantity），
 * 所以多件商品结算会生成多个订单。这里如实呈现，而不是假装能一次下单多件。
 */
const singleLine = computed(() => lines.value.length === 1)

const totalCents = computed(() =>
  lines.value.reduce((sum, line) => sum + toCents(line.price) * line.quantity, 0)
)

/** 券只能挂在一个订单上（Coupon-Id 是请求头，一单一个），所以多件商品时不提供选择。 */
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

/**
 * 应付金额预览。真实金额由订单服务按商品快照算——请求体里不传金额，
 * 所以这里算的只是给用户看的估计，最终以订单详情为准。
 */
const payableCents = computed(() => {
  const discount = selectedCoupon.value ? toCents(selectedCoupon.value.discountAmount) : 0
  return Math.max(totalCents.value - discount, 0)
})

async function loadLines(): Promise<void> {
  loading.value = true
  try {
    const skuParam = route.query.skuId
    if (typeof skuParam === 'string' && skuParam !== '') {
      // 立即购买：单品直下，不经过购物车
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
    // 券列表拿不到不影响下单，静默降级为"不使用优惠券"
    coupons.value = []
  }
}

async function submit(): Promise<void> {
  submitting.value = true
  results.value = null
  const collected: LineResult[] = []
  for (const line of lines.value) {
    try {
      const orderId = await orderApi.place(
        { skuId: line.skuId, quantity: line.quantity },
        {
          idempotencyKey: keyFor(line.skuId),
          couponId: singleLine.value ? selectedCouponId.value : null
        }
      )
      myOrders.remember(orderId)
      collected.push({ skuId: line.skuId, title: line.title, orderId, error: null })
    } catch (error) {
      collected.push({
        skuId: line.skuId,
        title: line.title,
        orderId: null,
        error: isDuplicate(error) ? '该订单已提交过（幂等拒绝），请到订单列表确认' : describeError(error)
      })
    }
  }
  submitting.value = false
  results.value = collected

  // 成功的行从购物车摘掉；失败的原样留着，用户改完还能再来一次
  for (const result of collected) {
    if (result.orderId !== null && fromCart.value) {
      await cart.remove(result.skuId).catch(() => undefined)
    }
  }

  if (collected.every((result) => result.orderId !== null)) {
    ElMessage.success('下单成功，请在 30 分钟内完成支付')
    await router.replace({ name: 'orders' })
  }
}

onMounted(async () => {
  await loadLines()
  await loadCoupons()
})
</script>

<template>
  <div class="aurora-page">
    <h2 class="aurora-title">结算</h2>

    <el-skeleton v-if="loading" :rows="4" animated />

    <el-empty v-else-if="lines.length === 0" description="没有可结算的商品">
      <el-button type="primary" @click="router.replace('/products')">去挑商品</el-button>
    </el-empty>

    <template v-else>
      <el-table :data="lines" border>
        <el-table-column prop="title" label="商品" min-width="200" />
        <el-table-column label="单价" width="120" align="right">
          <template #default="{ row }">
            <span class="aurora-amount">{{ formatYuan(row.price) }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="quantity" label="数量" width="90" align="center" />
        <el-table-column label="小计" width="140" align="right">
          <template #default="{ row }">
            <span class="aurora-amount">{{ formatYuan(row.price * row.quantity) }}</span>
          </template>
        </el-table-column>
      </el-table>

      <el-alert
        v-if="lines.length > 1"
        type="info"
        :closable="false"
        show-icon
        title="每件商品会各生成一个订单"
        description="后端订单是单商品模型，一次结算多件就等于下多单；优惠券也只对单个订单生效，所以多件时不可选。"
        class="aurora-checkout__note"
      />

      <el-card v-if="singleLine" class="aurora-checkout__coupon">
        <template #header>优惠券</template>
        <el-select v-model="selectedCouponId" placeholder="不使用优惠券" clearable class="aurora-checkout__select">
          <el-option label="不使用优惠券" :value="null" />
          <el-option
            v-for="coupon in usableCoupons"
            :key="coupon.id"
            :label="`${coupon.title}（满 ${formatYuan(coupon.thresholdAmount)} 减 ${formatYuan(coupon.discountAmount)}）`"
            :value="coupon.id"
          />
        </el-select>
        <p v-if="usableCoupons.length === 0" class="aurora-checkout__hint">
          当前没有可用于这笔订单的券（未使用、且订单金额达到门槛）。
        </p>
      </el-card>

      <div class="aurora-checkout__summary">
        <div>
          <span>合计：</span>
          <strong class="aurora-amount">{{ formatYuan(payableCents / 100) }}</strong>
          <span v-if="selectedCoupon" class="aurora-checkout__discount">
            已优惠 {{ formatYuan(selectedCoupon.discountAmount) }}
          </span>
        </div>
        <el-button type="primary" size="large" :loading="submitting" @click="submit">
          提交订单
        </el-button>
      </div>

      <el-alert
        type="warning"
        :closable="false"
        show-icon
        title="金额以订单服务计算为准"
        description="应付金额由服务端按商品快照算出，这里的数字只是预览。"
        class="aurora-checkout__note"
      />

      <el-card v-if="results" class="aurora-checkout__results">
        <template #header>提交结果</template>
        <el-table :data="results" border>
          <el-table-column prop="title" label="商品" min-width="180" />
          <el-table-column label="结果" min-width="220">
            <template #default="{ row }">
              <el-tag v-if="row.orderId !== null" type="success">订单号 {{ row.orderId }}</el-tag>
              <el-tag v-else type="danger">{{ row.error }}</el-tag>
            </template>
          </el-table-column>
        </el-table>
        <el-button
          v-if="results.some((row) => row.orderId !== null)"
          class="aurora-checkout__go"
          @click="router.push({ name: 'orders' })"
        >
          去我的订单
        </el-button>
      </el-card>
    </template>
  </div>
</template>

<style scoped>
.aurora-title {
  font-size: 20px;
  margin: 8px 0 16px;
}

.aurora-checkout__note {
  margin-top: 16px;
}

.aurora-checkout__coupon {
  margin-top: 16px;
}

.aurora-checkout__select {
  width: 360px;
  max-width: 100%;
}

.aurora-checkout__hint {
  margin: 12px 0 0;
  font-size: 13px;
  color: #909399;
}

.aurora-checkout__summary {
  display: flex;
  justify-content: flex-end;
  align-items: center;
  gap: 16px;
  margin-top: 20px;
}

.aurora-checkout__summary strong {
  color: #f56c6c;
  font-size: 20px;
}

.aurora-checkout__discount {
  margin-left: 12px;
  font-size: 13px;
  color: #67c23a;
}

.aurora-checkout__results {
  margin-top: 16px;
}

.aurora-checkout__go {
  margin-top: 12px;
}
</style>
