<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import { catalogApi, seckillApi, type Category, type SeckillActivity, type SpuCard } from '@/api/dtc'
import ProductCard from '@/components/ProductCard.vue'
import { useAuthStore } from '@/stores/auth'
import { formatCountdown } from '@/utils/datetime'
import { formatYuan } from '@/utils/money'
import { describeError } from '@/utils/errors'

const router = useRouter()
const auth = useAuthStore()

const categories = ref<Category[]>([])
const featured = ref<SpuCard[]>([])
const seckill = ref<SeckillActivity | null>(null)
const loading = ref(true)
const error = ref('')
const now = ref(Date.now())
let tick: number | undefined

const categoryImages: Record<number, string> = {
  1: '/products/p01-1.webp',
  2: '/products/p05-1.webp',
  3: '/products/p08-1.webp',
  4: '/products/p11-1.webp',
  5: '/products/p12-1.webp',
  6: '/products/p15-1.webp',
  7: '/products/p18-1.webp',
}

const seckillCountdown = computed(() => {
  if (!seckill.value) {
    return ''
  }
  if (seckill.value.phase === 'RUNNING') {
    return formatCountdown(seckill.value.endAt, now.value) ?? ''
  }
  if (seckill.value.phase === 'NOT_STARTED') {
    return formatCountdown(seckill.value.startAt, now.value) ?? ''
  }
  return ''
})

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const [cats, page] = await Promise.all([
      catalogApi.categories(),
      catalogApi.page({ size: 6, sort: 'recommended' }),
    ])
    categories.value = cats
    featured.value = page.records
    if (auth.isLoggedIn) {
      try {
        const activities = await seckillApi.list()
        seckill.value = activities.find((item) => item.phase === 'RUNNING')
          ?? activities.find((item) => item.phase === 'NOT_STARTED')
          ?? null
      } catch {
        seckill.value = null
      }
    }
  } catch (e) {
    error.value = describeError(e)
  } finally {
    loading.value = false
  }
}

function goCategory(categoryId: number): void {
  void router.push({ name: 'products', query: { categoryId: String(categoryId) } })
}

function goSeckill(): void {
  if (auth.isLoggedIn) {
    void router.push({ name: 'seckill' })
  } else {
    void router.push({ name: 'login', query: { redirect: '/seckill' } })
  }
}

onMounted(() => {
  void load()
  tick = window.setInterval(() => {
    now.value = Date.now()
  }, 1000)
})

onUnmounted(() => {
  if (tick) {
    window.clearInterval(tick)
  }
})
</script>

<template>
  <div>
    <section class="hero">
      <div class="hero__media">
        <img src="/scenes/hero.webp" alt="AURORA 桌面场景" />
      </div>
      <div class="hero__veil" />
      <div class="container hero__inner">
        <div class="hero__content">
          <span class="eyebrow">AURORA / DESK &amp; POCKET</span>
          <h1 class="display">把桌面，<br />留给真正重要的事。</h1>
          <p class="hero__copy">
            从 65W 氮化镓充电器到 75% 机械键盘，AURORA 把每天使用的科技做得更安静、更顺手。
          </p>
          <div class="hero__actions">
            <RouterLink class="btn btn--primary" to="/products">浏览全部商品</RouterLink>
            <button class="btn btn--line" type="button" @click="goSeckill">限时秒杀</button>
          </div>
        </div>
      </div>
      <div class="container hero__meta">
        <span>18 个产品系列 · 45 种可售规格</span>
        <span>概念渲染 · 演示项目</span>
      </div>
    </section>

    <section class="spec-strip">
      <div class="container spec-strip__grid">
        <div class="spec-item">
          <strong>18</strong>
          <span>个产品系列</span>
        </div>
        <div class="spec-item">
          <strong>45</strong>
          <span>种 SKU 规格</span>
        </div>
        <div class="spec-item">
          <strong>¥299</strong>
          <span>券后满额免运费</span>
        </div>
        <div class="spec-item">
          <strong>7 天</strong>
          <span>发货后自动确认收货</span>
        </div>
      </div>
    </section>

    <section class="section">
      <div class="container">
        <div class="section-head">
          <div class="section-head__copy">
            <span class="eyebrow">按使用场景</span>
            <h2 class="display">从这里开始，整理你的桌面。</h2>
          </div>
          <RouterLink class="link-arrow section-head__aside" to="/products">查看全部 →</RouterLink>
        </div>

        <div v-if="error" class="error-banner">{{ error }}</div>
        <div v-else-if="loading" class="category-rail">
          <div v-for="i in 7" :key="i" class="skeleton" style="aspect-ratio: 1" />
        </div>
        <div v-else class="category-rail">
          <button
            v-for="category in categories"
            :key="category.id"
            class="category-card"
            type="button"
            @click="goCategory(category.id)"
          >
            <div class="category-card__media">
              <img :src="categoryImages[category.id]" :alt="category.name" />
            </div>
            <div class="category-card__name">{{ category.name }}</div>
            <div class="category-card__meta">系列入口</div>
          </button>
        </div>
      </div>
    </section>

    <section class="section section--tight">
      <div class="container">
        <div class="section-head">
          <div class="section-head__copy">
            <span class="eyebrow">精选</span>
            <h2 class="display">每天都用得上的几件。</h2>
          </div>
          <RouterLink class="link-arrow section-head__aside" to="/products">全部商品 →</RouterLink>
        </div>
        <div v-if="loading" class="product-grid">
          <div v-for="i in 6" :key="i" class="skeleton" style="aspect-ratio: 1" />
        </div>
        <div v-else class="product-grid">
          <ProductCard v-for="product in featured" :key="product.id" :product="product" />
        </div>
      </div>
    </section>

    <section class="section">
      <div class="container editorial">
        <div class="editorial__media">
          <img src="/scenes/desk.webp" alt="晨光下的桌面键盘与触控板" />
        </div>
        <div class="editorial__copy">
          <span class="eyebrow">01 / DESK</span>
          <h2 class="display">桌面不是堆叠，是秩序。</h2>
          <p>
            键盘、挂灯、音箱各就各位。你只需要把手放上去，剩下的交给这些安静的工具。
          </p>
          <RouterLink class="link-arrow" :to="{ name: 'products', query: { categoryId: '2' } }">
            从桌面输入开始 →
          </RouterLink>
        </div>
      </div>
    </section>

    <section class="section section--tight">
      <div class="container scene-grid">
        <RouterLink class="scene-card" :to="{ name: 'products', query: { categoryId: '6' } }">
          <img src="/scenes/travel.webp" alt="旅行收纳与充电装备" />
          <div class="scene-card__veil" />
          <div class="scene-card__copy">
            <span class="eyebrow" style="color: rgb(255 255 255 / 72%)">02 / CARRY</span>
            <h3 class="display">出门，只带真正需要的。</h3>
            <p>充电宝、线材、收纳包。一个包解决一天的续航。</p>
            <span class="link-arrow">查看随身系列 →</span>
          </div>
        </RouterLink>
        <RouterLink class="scene-card" :to="{ name: 'products', query: { categoryId: '1' } }">
          <img src="/scenes/night.webp" alt="夜间床头的磁吸充电座" />
          <div class="scene-card__veil" />
          <div class="scene-card__copy">
            <span class="eyebrow" style="color: rgb(255 255 255 / 72%)">03 / NIGHT</span>
            <h3 class="display">夜里，把电留到明天。</h3>
            <p>手机、手表、耳机落在同一块底座上，早上醒来都是满电。</p>
            <span class="link-arrow">查看充电系列 →</span>
          </div>
        </RouterLink>
      </div>
    </section>

    <section class="section section--tight">
      <div class="container">
        <div v-if="seckill" class="seckill-band">
          <div class="seckill-band__media">
            <img :src="seckill.productImage" :alt="seckill.productTitle" />
          </div>
          <div class="seckill-band__body">
            <span class="eyebrow" style="color: #b9b1a6">Limited Drop</span>
            <h2 class="display">{{ seckill.title }}</h2>
            <p>{{ seckill.productTitle }} · 每人限购 1 件，抢完即止。</p>
            <div class="seckill-band__price">
              <strong>{{ formatYuan(seckill.seckillPrice) }}</strong>
              <span v-if="seckill.originalPrice > seckill.seckillPrice">
                日常价 {{ formatYuan(seckill.originalPrice) }}
              </span>
            </div>
            <div class="seckill-band__meta">
              <span>{{ seckill.phase === 'RUNNING' ? '距结束' : '距开始' }} {{ seckillCountdown }}</span>
              <span>剩余 {{ seckill.availableStock }} / {{ seckill.totalStock }}</span>
            </div>
            <button class="btn btn--accent" type="button" @click="goSeckill">
              {{ seckill.phase === 'RUNNING' ? '进入秒杀' : '查看活动' }}
            </button>
          </div>
        </div>
        <div v-else class="seckill-band">
          <div class="seckill-band__media">
            <img src="/products/p01-1.webp" alt="AURORA 65W 充电器" />
          </div>
          <div class="seckill-band__body">
            <span class="eyebrow" style="color: #b9b1a6">Limited Drop</span>
            <h2 class="display">每周一次，限量价格。</h2>
            <p>秒杀库存独立分配。中签后生成待支付订单，30 分钟内完成支付。</p>
            <button class="btn btn--accent" type="button" @click="goSeckill">
              {{ auth.isLoggedIn ? '查看秒杀活动' : '登录后查看' }}
            </button>
          </div>
        </div>
      </div>
    </section>

    <section class="section section--tight">
      <div class="container">
        <div class="promise-grid">
          <div class="promise">
            <strong>满 ¥299 免运费</strong>
            <p>券后商品金额达到门槛即免运费，未满收取 ¥12。</p>
          </div>
          <div class="promise">
            <strong>发货前可退款</strong>
            <p>支持部分子单退款；剩余金额低于券门槛时需整单退款。</p>
          </div>
          <div class="promise">
            <strong>一个父订单，一次支付</strong>
            <p>多件商品合并结算，统一支付、统一发货。</p>
          </div>
          <div class="promise">
            <strong>模拟物流可追踪</strong>
            <p>运营填写承运商与运单号，用户可确认收货或等待自动完成。</p>
          </div>
        </div>
      </div>
    </section>
  </div>
</template>
