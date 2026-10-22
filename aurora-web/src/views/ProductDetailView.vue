<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { productApi } from '@/api'
import type { Sku } from '@/api/product'
import { SKU_ON_SALE } from '@/api/product'
import { ApiError, ErrorCode } from '@/api/types'
import ProductFigure from '@/components/ProductFigure.vue'
import { useReveal } from '@/composables/useReveal'
import { useAuthStore } from '@/stores/auth'
import { useCartStore } from '@/stores/cart'
import { productKindLabel } from '@/utils/catalog'
import { formatDateTime } from '@/utils/datetime'
import { describeError } from '@/utils/errors'
import { formatYuan } from '@/utils/money'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const cart = useCartStore()

const sku = ref<Sku | null>(null)
const loading = ref(true)
const notFound = ref(false)
const quantity = ref(1)
const adding = ref(false)

const skuId = computed(() => Number(route.params.id))
const sellable = computed(
  () => sku.value !== null && sku.value.status === SKU_ON_SALE && sku.value.stock > 0
)
const maxQuantity = computed(() => Math.min(sku.value?.stock ?? 1, 999))

const availability = computed(() => {
  if (!sku.value) {
    return ''
  }
  if (sku.value.status !== SKU_ON_SALE) {
    return '已下架'
  }
  if (sku.value.stock <= 0) {
    return '参考库存为 0'
  }
  return sku.value.stock <= 10 ? `参考库存仅剩 ${sku.value.stock} 件` : '参考库存充足'
})

useReveal()

function step(delta: number): void {
  quantity.value = Math.min(Math.max(quantity.value + delta, 1), maxQuantity.value)
}

async function load(): Promise<void> {
  loading.value = true
  notFound.value = false
  sku.value = null
  try {
    sku.value = await productApi.detail(skuId.value)
    quantity.value = 1
  } catch (error) {
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
  await router.push({
    name: 'checkout',
    query: { skuId: String(sku.value.id), quantity: String(quantity.value) }
  })
}

onMounted(load)
watch(skuId, load)
</script>

<template>
  <div class="store-product">
    <div class="container">
      <RouterLink class="back-link" to="/products">← 返回目录</RouterLink>

      <div v-if="loading" class="store-skeleton" aria-busy="true" aria-label="正在加载商品" />

      <div v-else-if="notFound" class="store-empty">
        <h1>商品不存在</h1>
        <p>它可能已经下架，或链接已经失效。</p>
        <RouterLink class="btn" to="/products">回到目录</RouterLink>
      </div>

      <template v-else-if="sku">
        <div class="product-layout">
          <div class="product-visual" data-reveal>
            <ProductFigure :title="sku.title" :label="`${sku.title} 的目录图形`" />
            <p class="product-visual__caption">
              目录图形按商品类型绘制，不是商品实拍。所选商品：{{ sku.title }}。
            </p>
          </div>

          <div class="product-copy" data-reveal style="--reveal-delay: 100ms">
            <h1>{{ sku.title }}</h1>
            <p class="product-price num">{{ formatYuan(sku.price) }}</p>
            <p class="product-status">{{ productKindLabel(sku.title) }} · {{ availability }}</p>

            <dl class="product-specs">
              <div>
                <dt>商品编号</dt>
                <dd>{{ sku.id }}</dd>
              </div>
              <div>
                <dt>目录库存</dt>
                <dd>{{ sku.stock }} 件</dd>
              </div>
              <div>
                <dt>更新时间</dt>
                <dd>{{ formatDateTime(sku.updatedAt) }}</dd>
              </div>
              <div>
                <dt>状态</dt>
                <dd>{{ sku.status === SKU_ON_SALE ? '在售' : '已下架' }}</dd>
              </div>
            </dl>

            <div class="buy-panel">
              <div class="quantity" role="group" aria-label="购买数量">
                <button type="button" :disabled="!sellable || quantity <= 1" @click="step(-1)">−</button>
                <output aria-live="polite">{{ quantity }}</output>
                <button type="button" :disabled="!sellable || quantity >= maxQuantity" @click="step(1)">
                  +
                </button>
              </div>

              <div class="buy-actions">
                <button
                  type="button"
                  class="btn"
                  :disabled="!sellable || adding"
                  @click="addToCart"
                >
                  {{ adding ? '加入中…' : '加入购物车' }}
                </button>
                <button type="button" class="btn btn--signal" :disabled="!sellable" @click="buyNow">
                  立即购买
                </button>
              </div>

              <p v-if="!sellable" class="notice notice--warning">
                该商品当前不可购买。{{ sku.status !== SKU_ON_SALE ? '它已经下架。' : '参考库存不足。' }}
              </p>

              <p class="product-note">
                目录库存是商品表的参考值，不等于库存服务的可售库存。下单时会由服务端再次校验；如果库存不足，订单页会给出明确拒绝原因。
              </p>
            </div>
          </div>
        </div>

        <section class="product-pipeline" aria-labelledby="what-next">
          <h2 id="what-next">下单后会发生什么</h2>
          <p>每一步都有服务端状态，不靠前端动画制造“已完成”的错觉。</p>
          <ol class="product-steps">
            <li>
              <strong>订单创建</strong>
              <span>订单与事务消息同时写入，重复提交由幂等键拦截。</span>
            </li>
            <li>
              <strong>库存预留</strong>
              <span>Redis 原子预扣，数据库扣减由消费者完成。</span>
            </li>
            <li>
              <strong>支付确认</strong>
              <span>模拟渠道回调必须通过 HMAC 验签，金额与订单一致才生效。</span>
            </li>
            <li>
              <strong>超时关单</strong>
              <span>30 分钟未支付自动关闭，库存与优惠券按订单状态回退。</span>
            </li>
          </ol>
        </section>
      </template>
    </div>
  </div>
</template>

<style scoped>
.product-steps {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 0;
  border-top: 1px solid var(--line);
}

.product-steps li {
  display: grid;
  gap: 6px;
  padding: 18px 20px 18px 0;
  border-bottom: 1px solid var(--line);
}

.product-steps li + li {
  padding-left: 20px;
  border-left: 1px solid var(--line);
}

.product-steps strong {
  font-size: var(--step-3);
}

.product-steps span {
  font-size: var(--step-2);
  color: var(--muted);
  line-height: 1.55;
}

@media (max-width: 900px) {
  .product-steps {
    grid-template-columns: 1fr 1fr;
  }

  .product-steps li:nth-child(3) {
    padding-left: 0;
    border-left: 0;
  }
}

@media (max-width: 560px) {
  .product-steps {
    grid-template-columns: 1fr;
  }

  .product-steps li + li {
    padding-left: 0;
    border-left: 0;
  }
}
</style>
