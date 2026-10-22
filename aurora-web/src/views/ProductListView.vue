<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import { productApi } from '@/api'
import type { Sku } from '@/api/product'
import { SKU_ON_SALE } from '@/api/product'
import ProductFigure from '@/components/ProductFigure.vue'
import { useReveal } from '@/composables/useReveal'
import { useAuthStore } from '@/stores/auth'
import { useCartStore } from '@/stores/cart'
import { groupByBand, productKindLabel } from '@/utils/catalog'
import { describeError } from '@/utils/errors'
import { formatYuan } from '@/utils/money'

const router = useRouter()
const auth = useAuthStore()
const cart = useCartStore()

const records = ref<Sku[]>([])
const total = ref(0)
const current = ref(1)
const size = ref(24)
const loading = ref(true)
const loadError = ref('')
const addingId = ref<number | null>(null)
const preview = ref<Sku | null>(null)
const viewMode = ref<'catalog' | 'grid'>('catalog')

const groups = computed(() => groupByBand(records.value))
const previewItem = computed(() => preview.value ?? records.value[0] ?? null)

useReveal()

function availability(sku: Sku): string {
  if (sku.status !== SKU_ON_SALE) {
    return '已下架'
  }
  if (sku.stock <= 0) {
    return '缺货'
  }
  return sku.stock <= 10 ? `仅剩 ${sku.stock} 件` : '现货'
}

function selectPreview(sku: Sku): void {
  preview.value = sku
}

async function load(): Promise<void> {
  loading.value = true
  loadError.value = ''
  try {
    const page = await productApi.page(current.value, size.value)
    records.value = page.records
    total.value = page.total
    preview.value = page.records[0] ?? null
  } catch (error) {
    loadError.value = describeError(error)
    ElMessage.error(loadError.value)
  } finally {
    loading.value = false
  }
}

function onChangePage(next: number): void {
  current.value = next
  void load().then(() => {
    document.getElementById('catalog')?.scrollIntoView({ block: 'start' })
  })
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

onMounted(load)
</script>

<template>
  <div class="store-home">
    <section class="store-hero" aria-labelledby="store-title">
      <div class="container hero-inner">
        <div class="hero-copy" data-reveal>
          <h1 id="store-title" class="display hero-word">AURORA</h1>
          <p class="hero-lead">
            极光选品：把库存、订单与秒杀的真实状态摆上货架。这里没有猜谜的“仅剩一件”，只有能逐笔核对的交易。
          </p>
          <div class="hero-actions">
            <a class="btn btn--signal" href="#catalog">浏览本期目录</a>
            <RouterLink
              class="btn btn--paper"
              :to="auth.isLoggedIn ? '/seckill' : { name: 'login', query: { redirect: '/seckill' } }"
            >
              查看秒杀排期
            </RouterLink>
          </div>
        </div>

        <div class="folder-stack" aria-label="目录分区">
          <div class="folder folder--1" data-reveal style="--reveal-delay: 80ms">
            <a class="folder-link" href="#catalog">目录</a>
          </div>
          <div class="folder folder--2" data-reveal style="--reveal-delay: 140ms">
            <a class="folder-link" href="#pipeline">交易</a>
          </div>
          <div class="folder folder--3" data-reveal style="--reveal-delay: 200ms">
            <a class="folder-link" href="#gallery">现场</a>
          </div>
          <div class="folder folder--4" data-reveal style="--reveal-delay: 260ms">
            <a class="folder-link" href="#guarantee">保障</a>
          </div>
          <div class="folder-front" data-reveal style="--reveal-delay: 320ms">
            <div>
              <p class="folder-front__title">本期目录，按价格带编排。</p>
              <p class="folder-front__note">
                共 {{ total || '—' }} 件选品。选中一行可以在左侧看到它的目录图形；价格与库存都是服务端当前值。
              </p>
            </div>
            <div class="folder-front__actions">
              <a class="btn" href="#catalog">开始挑选</a>
              <RouterLink class="btn btn--ghost" to="/orders">查订单</RouterLink>
            </div>
          </div>
        </div>
      </div>
    </section>

    <section id="catalog" class="catalog-band" aria-labelledby="catalog-title">
      <div class="container catalog-layout" :class="{ 'catalog-layout--grid': viewMode === 'grid' }">
        <aside class="catalog-aside">
          <h2 id="catalog-title">本期目录</h2>
          <p class="catalog-aside__lead">
            一张按价格带排好的选品单。悬停或聚焦某一行，左侧会换成对应商品的目录图形。
          </p>
          <div v-if="previewItem" class="catalog-preview" aria-live="polite">
            <ProductFigure :title="previewItem.title" :label="`${previewItem.title} 的目录图形`" />
            <p class="catalog-preview__caption">
              {{ previewItem.title }} · {{ productKindLabel(previewItem.title) }}。目录图形按商品类型绘制，不是商品实拍。
            </p>
          </div>
        </aside>

        <div class="catalog-sheet" data-reveal>
          <div class="catalog-sheet__head">
            <h3>选品与价格</h3>
            <div class="catalog-sheet__tools">
              <span class="catalog-sheet__count num">第 {{ current }} 页 · 共 {{ total }} 件</span>
              <div class="view-toggle" role="group" aria-label="商品呈现方式">
                <button
                  type="button"
                  :class="{ 'is-active': viewMode === 'catalog' }"
                  :aria-pressed="viewMode === 'catalog'"
                  @click="viewMode = 'catalog'"
                >
                  目录
                </button>
                <button
                  type="button"
                  :class="{ 'is-active': viewMode === 'grid' }"
                  :aria-pressed="viewMode === 'grid'"
                  @click="viewMode = 'grid'"
                >
                  网格
                </button>
              </div>
            </div>
          </div>

          <div v-if="loading" aria-busy="true" aria-label="正在加载商品">
            <div class="skeleton-block skeleton-row" />
            <div class="skeleton-block skeleton-row" />
            <div class="skeleton-block skeleton-row" />
            <div class="skeleton-block skeleton-row" />
          </div>

          <div v-else-if="loadError" class="catalog-empty">
            <p>{{ loadError }}</p>
            <button type="button" class="btn" @click="load">重新加载</button>
          </div>

          <template v-else-if="viewMode === 'catalog' && groups.length > 0">
            <section
              v-for="group in groups"
              :key="group.band.id"
              class="catalog-band-group"
              data-reveal
            >
              <h3 class="catalog-band-group__name">{{ group.band.label }}</h3>
              <ul class="catalog-rows">
                <li
                  v-for="sku in group.items"
                  :key="sku.id"
                  class="catalog-row"
                  @mouseenter="selectPreview(sku)"
                  @focusin="selectPreview(sku)"
                >
                  <div class="catalog-row__main">
                    <RouterLink
                      class="catalog-row__name"
                      :to="{ name: 'product-detail', params: { id: sku.id } }"
                    >
                      {{ sku.title }}
                    </RouterLink>
                    <span class="catalog-row__note">
                      {{ productKindLabel(sku.title) }} · {{ availability(sku) }} · 编号 {{ sku.id }}
                    </span>
                  </div>
                  <div class="catalog-row__end">
                    <span class="catalog-row__price num">{{ formatYuan(sku.price) }}</span>
                    <button
                      type="button"
                      class="catalog-row__add"
                      :disabled="sku.status !== SKU_ON_SALE || sku.stock <= 0 || addingId === sku.id"
                      @click="addToCart(sku)"
                    >
                      {{ addingId === sku.id ? '加入中' : '加入购物车' }}
                    </button>
                  </div>
                </li>
              </ul>
            </section>
          </template>

          <ul v-else-if="viewMode === 'grid'" class="catalog-grid">
            <li v-for="sku in records" :key="sku.id" class="catalog-card">
              <RouterLink
                class="catalog-card__figure"
                :to="{ name: 'product-detail', params: { id: sku.id } }"
                :aria-label="`查看 ${sku.title}`"
              >
                <ProductFigure :title="sku.title" :label="`${sku.title} 的目录图形`" />
              </RouterLink>
              <div class="catalog-card__body">
                <RouterLink class="catalog-card__name" :to="{ name: 'product-detail', params: { id: sku.id } }">
                  {{ sku.title }}
                </RouterLink>
                <p class="catalog-card__note">{{ productKindLabel(sku.title) }} · {{ availability(sku) }}</p>
                <div class="catalog-card__end">
                  <span class="catalog-card__price num">{{ formatYuan(sku.price) }}</span>
                  <button
                    type="button"
                    class="catalog-row__add"
                    :disabled="sku.status !== SKU_ON_SALE || sku.stock <= 0 || addingId === sku.id"
                    @click="addToCart(sku)"
                  >
                    {{ addingId === sku.id ? '加入中' : '加入购物车' }}
                  </button>
                </div>
              </div>
            </li>
          </ul>

          <p v-else class="catalog-empty">当前没有可展示的商品。</p>

          <div v-if="total > size" class="catalog-pager">
            <el-pagination
              layout="prev, pager, next, total"
              :total="total"
              :page-size="size"
              :current-page="current"
              @current-change="onChangePage"
            />
          </div>

          <p class="catalog-sheet__note">
            目录库存是商品表的参考值；真正决定能否成交的是库存服务的可售库存，下单时以服务端结果为准。
          </p>
        </div>
      </div>
    </section>

    <section id="pipeline" class="pipeline-band" aria-labelledby="pipeline-title">
      <div class="container pipeline-layout">
        <div class="pipeline-copy" data-reveal>
          <h2 id="pipeline-title">交易如何发生</h2>
          <p>
            下单不是一个按钮的动画。订单、库存、支付和关单各自有明确的状态；失败时，系统会告诉你哪一步没有通过。
          </p>
        </div>

        <div class="pipeline-slip" data-reveal style="--reveal-delay: 120ms">
          <div class="pipeline-slip__title">
            <h3>一笔订单的回执</h3>
            <span>本机演示环境</span>
          </div>
          <div class="pipeline-step">
            <div class="pipeline-step__top">
              <strong>下单</strong>
              <span class="pipeline-step__leader" aria-hidden="true" />
              <span class="pipeline-step__value">即时</span>
            </div>
            <p>订单与事务消息写在同一笔本地事务里，随后才允许消息可见。</p>
          </div>
          <div class="pipeline-step">
            <div class="pipeline-step__top">
              <strong>预留库存</strong>
              <span class="pipeline-step__leader" aria-hidden="true" />
              <span class="pipeline-step__value">1–2 秒</span>
            </div>
            <p>Redis 用 Lua 原子预扣，数据库扣减由消息消费者完成。</p>
          </div>
          <div class="pipeline-step">
            <div class="pipeline-step__top">
              <strong>支付</strong>
              <span class="pipeline-step__leader" aria-hidden="true" />
              <span class="pipeline-step__value">模拟回调</span>
            </div>
            <p>渠道回调必须通过服务端 HMAC 验签，金额与订单不一致会被拒绝。</p>
          </div>
          <div class="pipeline-step">
            <div class="pipeline-step__top">
              <strong>自动关单</strong>
              <span class="pipeline-step__leader" aria-hidden="true" />
              <span class="pipeline-step__value">30 分钟</span>
            </div>
            <p>未支付订单由延迟消息关闭，库存与优惠券按订单状态回退。</p>
          </div>
          <p class="pipeline-result">
            你拿到：一笔状态可追溯的订单。每一步都能在订单页核对，而不是只看到一个“成功”提示。
          </p>
        </div>
      </div>
    </section>

    <section id="gallery" class="gallery-band" aria-labelledby="gallery-title">
      <div class="container">
        <div class="gallery-head" data-reveal>
          <h2 id="gallery-title">选品现场</h2>
          <p>这些是场景照片，不是具体商品实拍。它们只负责让你看到这些物件被使用时的光线与手。</p>
        </div>
        <div class="gallery-grid">
          <figure class="gallery-primary" data-reveal>
            <img
              src="/editorial/camera-hands.jpg"
              width="1200"
              height="1500"
              alt="双手在牛仔外套前握着一台老式旁轴相机"
            />
            <figcaption>
              <strong>取景</strong>
              旁轴相机与牛仔布。场景摄影：Kristian Karlsson / Unsplash License。
            </figcaption>
          </figure>
          <div class="gallery-side">
            <figure data-reveal style="--reveal-delay: 80ms">
              <img
                src="/editorial/audio-mixer.jpg"
                width="1200"
                height="1500"
                alt="一只手在调音台上推动推子"
                />
              <figcaption>
                <strong>试听</strong>
                调音台推子。场景摄影：Drew Patrick Miller / Unsplash License。
              </figcaption>
            </figure>
            <figure data-reveal style="--reveal-delay: 140ms">
              <img
                src="/editorial/pour-over.jpg"
                width="1200"
                height="1500"
                alt="手冲咖啡滤杯正在注水"
                />
              <figcaption>
                <strong>手冲</strong>
                滤杯与注水。场景摄影：Karl Fredrickson / Unsplash License。
              </figcaption>
            </figure>
            <figure data-reveal style="--reveal-delay: 200ms">
              <img
                src="/editorial/tools.jpg"
                width="1200"
                height="1500"
                alt="木桌上摆着斧头、锤子、钳子和皮手套"
                />
              <figcaption>
                <strong>工作台</strong>
                工具与皮革。场景摄影：Todd Quackenbush / Unsplash License。
              </figcaption>
            </figure>
          </div>
        </div>
      </div>
    </section>

    <section id="guarantee" class="proof-band" aria-labelledby="guarantee-title">
      <div class="proof-card" data-reveal>
        <h2 id="guarantee-title">写在目录背面的保证</h2>
        <ul class="proof-list">
          <li data-reveal>价格由订单服务按商品快照计算，请求体里不接受金额。</li>
          <li data-reveal style="--reveal-delay: 60ms">库存预扣在 Redis 中原子完成，数据库扣减有消息消费者兜底。</li>
          <li data-reveal style="--reveal-delay: 120ms">支付是模拟链路，回调必须通过 HMAC 验签；本页面不会伪造支付成功。</li>
          <li data-reveal style="--reveal-delay: 180ms">未支付订单 30 分钟后自动关闭，库存与优惠券按订单状态回退。</li>
          <li data-reveal style="--reveal-delay: 240ms">所有页面都直接读取服务端状态，不用前端假数据凑出“热闹”。</li>
        </ul>
        <p class="proof-note">
          这是一个可运行的分布式交易演示，不是生产商铺。下单前请确认本地服务与演示数据已经就绪。
        </p>
      </div>
    </section>
  </div>
</template>

<style scoped>
.catalog-sheet__note {
  margin-top: 24px;
  font-size: var(--step-1);
  color: var(--muted);
  line-height: 1.6;
}
</style>
