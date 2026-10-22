<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import {
  addressApi,
  catalogApi,
  couponApi,
  tradeApi,
  type Address,
  type CartItem,
  type UserCoupon,
} from '@/api/dtc'
import { newIdempotencyKey } from '@/api/idempotency'
import AddressDialog from '@/components/AddressDialog.vue'
import EmptyState from '@/components/EmptyState.vue'
import { useCartStore } from '@/stores/cart'
import { describeError } from '@/utils/errors'
import { formatYuan, toCents } from '@/utils/money'

const route = useRoute()
const router = useRouter()
const cart = useCartStore()

const items = ref<CartItem[]>([])
const addresses = ref<Address[]>([])
const myCoupons = ref<UserCoupon[]>([])
const selectedAddressId = ref<number | null>(null)
const selectedCouponId = ref<number | null>(null)
const loading = ref(true)
const submitting = ref(false)
const error = ref('')
const addressDialog = ref(false)
const idempotencyKey = ref(newIdempotencyKey())

const merchandiseCents = computed(() =>
  items.value.reduce((sum, item) => sum + toCents(item.price) * item.quantity, 0),
)
const merchandise = computed(() => merchandiseCents.value / 100)
const selectedCoupon = computed(
  () => myCoupons.value.find((coupon) => coupon.id === selectedCouponId.value) ?? null,
)
const discountCents = computed(() => {
  if (!selectedCoupon.value) {
    return 0
  }
  return merchandiseCents.value >= toCents(selectedCoupon.value.thresholdAmount)
    ? toCents(selectedCoupon.value.discountAmount)
    : 0
})
const discount = computed(() => discountCents.value / 100)
const shippingCents = computed(() => (merchandiseCents.value - discountCents.value >= toCents(299) ? 0 : toCents(12)))
const shipping = computed(() => shippingCents.value / 100)
const payable = computed(() => Math.max(0, (merchandiseCents.value - discountCents.value + shippingCents.value) / 100))
const availableCoupons = computed(() =>
  myCoupons.value.filter(
    (coupon) => coupon.status === 'UNUSED' && new Date(coupon.expireAt).getTime() > Date.now(),
  ),
)
const hasStockIssue = computed(() =>
  items.value.some((item) => item.stock !== null && item.stock < item.quantity),
)

function first(value: unknown): string {
  if (Array.isArray(value)) {
    return typeof value[0] === 'string' ? value[0] : ''
  }
  return typeof value === 'string' ? value : ''
}

async function loadItems(): Promise<void> {
  const skuId = Number(first(route.query.skuId))
  const skus = first(route.query.skus)
  if (skuId) {
    const quantity = Math.max(1, Number(first(route.query.quantity)) || 1)
    const snapshot = await catalogApi.snapshot(skuId)
    items.value = [{
      skuId: snapshot.id,
      spuId: snapshot.spuId,
      skuCode: snapshot.skuCode,
      quantity,
      title: snapshot.title,
      attributesText: snapshot.attributesText,
      imageUrl: snapshot.imageUrl,
      price: snapshot.price,
      stock: snapshot.stock,
      status: snapshot.status,
    }]
    return
  }
  await cart.load()
  const requested = skus ? new Set(skus.split(',').map(Number)) : null
  items.value = cart.activeItems.filter((item) => !requested || requested.has(item.skuId))
}

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const [, addressList, coupons] = await Promise.all([
      loadItems(),
      addressApi.list(),
      couponApi.mine(),
    ])
    addresses.value = addressList
    myCoupons.value = coupons
    selectedAddressId.value = addressList.find((address) => address.isDefault)?.id ?? addressList[0]?.id ?? null
  } catch (e) {
    error.value = describeError(e)
  } finally {
    loading.value = false
  }
}

async function reloadAddresses(): Promise<void> {
  addresses.value = await addressApi.list()
  selectedAddressId.value = addresses.value.find((address) => address.isDefault)?.id ?? addresses.value[0]?.id ?? null
}

watch(
  () => [selectedAddressId.value, selectedCouponId.value],
  () => {
    idempotencyKey.value = newIdempotencyKey()
  },
)

async function submit(): Promise<void> {
  error.value = ''
  if (!selectedAddressId.value) {
    error.value = '请先选择收货地址'
    return
  }
  if (!items.value.length) {
    error.value = '没有可结算的商品'
    return
  }
  if (hasStockIssue.value) {
    error.value = '部分商品库存不足，请返回购物车调整数量'
    return
  }
  submitting.value = true
  try {
    const tradeId = await tradeApi.place(
      {
        items: items.value.map((item) => ({ skuId: item.skuId, quantity: item.quantity })),
        addressId: selectedAddressId.value,
        couponId: selectedCouponId.value ?? undefined,
      },
      idempotencyKey.value,
    )
    const cartSkuIds = new Set(cart.items.map((item) => item.skuId))
    for (const item of items.value) {
      if (cartSkuIds.has(item.skuId)) {
        await cart.remove(item.skuId).catch(() => undefined)
      }
    }
    ElMessage.success('订单已创建，请完成支付')
    await router.replace({ name: 'order-detail', params: { id: tradeId }, query: { new: '1' } })
  } catch (e) {
    error.value = describeError(e)
  } finally {
    submitting.value = false
  }
}

onMounted(() => {
  void load()
})
</script>

<template>
  <div>
    <div class="container page-head">
      <span class="eyebrow">Checkout</span>
      <h1 class="display">确认订单。</h1>
      <p>一个父订单对应一次支付和一次发货。提交后 30 分钟内完成支付。</p>
    </div>

    <div class="container">
      <div v-if="loading" class="page-loading">正在准备结算信息…</div>
      <EmptyState
        v-else-if="!items.length"
        title="没有可结算的商品"
        description="购物车为空，或所选商品已下架。"
      >
        <RouterLink class="btn btn--primary" to="/products">返回商品目录</RouterLink>
      </EmptyState>

      <div v-else class="checkout-layout">
        <div>
          <section class="checkout-section">
            <div class="checkout-section__head">
              <h3>收货地址</h3>
              <button class="btn btn--ghost btn--sm" type="button" @click="addressDialog = true">新增地址</button>
            </div>
            <EmptyState
              v-if="!addresses.length"
              title="还没有收货地址"
              description="添加一个地址后即可提交订单。"
            >
              <button class="btn btn--line" type="button" @click="addressDialog = true">添加地址</button>
            </EmptyState>
            <label
              v-for="address in addresses"
              v-else
              :key="address.id"
              class="address-option"
              :class="{ 'is-active': selectedAddressId === address.id }"
            >
              <input v-model="selectedAddressId" type="radio" :value="address.id" />
              <div>
                <div class="address-option__name">
                  {{ address.receiverName }} · {{ address.phone }}
                  <span v-if="address.isDefault" class="tag tag--accent" style="margin-left: 6px">默认</span>
                </div>
                <div class="address-option__line">
                  {{ address.province }} {{ address.city }} {{ address.district }} {{ address.detail }}
                </div>
              </div>
            </label>
          </section>

          <section class="checkout-section">
            <div class="checkout-section__head">
              <h3>商品</h3>
              <span class="tiny">共 {{ items.reduce((sum, item) => sum + item.quantity, 0) }} 件</span>
            </div>
            <div class="checkout-items">
              <div v-for="item in items" :key="item.skuId" class="checkout-item">
                <div class="checkout-item__media">
                  <img :src="item.imageUrl" :alt="item.title" />
                </div>
                <div>
                  <div class="checkout-item__title">{{ item.title }}</div>
                  <div class="checkout-item__meta">{{ item.attributesText }} · ×{{ item.quantity }}</div>
                  <div v-if="item.stock !== null && item.stock < item.quantity" class="error-text">
                    库存仅剩 {{ item.stock }} 件
                  </div>
                </div>
                <div class="checkout-item__price">{{ formatYuan(item.price * item.quantity) }}</div>
              </div>
            </div>
          </section>

          <section class="checkout-section">
            <div class="checkout-section__head">
              <h3>优惠券</h3>
              <span class="tiny">仅显示账户中的券</span>
            </div>
            <label class="radio-line">
              <input v-model="selectedCouponId" type="radio" :value="null" />
              <span>不使用优惠券</span>
            </label>
            <label
              v-for="coupon in availableCoupons"
              :key="coupon.id"
              class="radio-line"
              :style="{ opacity: merchandiseCents >= toCents(coupon.thresholdAmount) ? 1 : 0.45 }"
            >
              <input
                v-model="selectedCouponId"
                type="radio"
                :value="coupon.id"
                :disabled="merchandiseCents < toCents(coupon.thresholdAmount)"
              />
              <strong>{{ coupon.title }}</strong>
              <span class="tiny">
                满 {{ formatYuan(coupon.thresholdAmount) }} 可用 · 有效期至
                {{ coupon.expireAt.slice(0, 10) }}
              </span>
              <span v-if="merchandiseCents < toCents(coupon.thresholdAmount)" class="tag">未达门槛</span>
            </label>
            <p v-if="!availableCoupons.length" class="hint">暂无可用优惠券，可在优惠券页面领取。</p>
          </section>
        </div>

        <aside class="summary-card">
          <h3>金额明细</h3>
          <div class="summary-list">
            <div class="summary-row">
              <span>商品金额</span>
              <strong>{{ formatYuan(merchandise) }}</strong>
            </div>
            <div class="summary-row">
              <span>优惠</span>
              <span>{{ discount > 0 ? `− ${formatYuan(discount)}` : '—' }}</span>
            </div>
            <div class="summary-row">
              <span>运费</span>
              <span>{{ shipping === 0 ? '免运费' : formatYuan(shipping) }}</span>
            </div>
          </div>
          <div class="summary-total">
            <span>应付金额</span>
            <strong>{{ formatYuan(payable) }}</strong>
          </div>
          <button class="btn btn--primary btn--block" type="button" :disabled="submitting" @click="submit">
            {{ submitting ? '提交中…' : '提交订单' }}
          </button>
          <p v-if="error" class="error-text" style="margin-top: 10px">{{ error }}</p>
          <p class="summary-note">
            提交即生成一个父订单。支付、发货和退款进度都可以在订单详情查看。
          </p>
        </aside>
      </div>
    </div>

    <AddressDialog v-model="addressDialog" @saved="reloadAddresses" />
  </div>
</template>
