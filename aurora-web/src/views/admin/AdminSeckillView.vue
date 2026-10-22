<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'

import { adminCatalogApi, seckillApi, type AdminSku, type SaveSeckill, type SeckillActivity } from '@/api/dtc'
import StatusBadge from '@/components/StatusBadge.vue'
import { formatDateTime } from '@/utils/datetime'
import { describeError } from '@/utils/errors'
import { formatYuan } from '@/utils/money'

const router = useRouter()

const rows = ref<SeckillActivity[]>([])
const skus = ref<AdminSku[]>([])
const loading = ref(true)
const error = ref('')
const createOpen = ref(false)
const saving = ref(false)
const preheating = ref<number | null>(null)

function localInput(date: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`
}

function defaults(): SaveSeckill {
  const now = new Date()
  return {
    title: '',
    skuId: 0,
    seckillPrice: 0,
    totalStock: 10,
    perUserLimit: 1,
    startAt: localInput(new Date(now.getTime() - 5 * 60000)),
    endAt: localInput(new Date(now.getTime() + 2 * 3600000)),
  }
}

const form = reactive<SaveSeckill>(defaults())

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const [activities, skuList] = await Promise.all([seckillApi.adminList(), adminCatalogApi.skus()])
    rows.value = activities
    skus.value = skuList.filter((sku) => sku.status === 1)
  } catch (e) {
    error.value = describeError(e)
  } finally {
    loading.value = false
  }
}

function openCreate(): void {
  Object.assign(form, defaults())
  form.skuId = skus.value[0]?.id ?? 0
  const sku = skus.value[0]
  if (sku) {
    form.seckillPrice = Math.max(1, Math.round(sku.price * 0.75))
  }
  createOpen.value = true
}

async function save(): Promise<void> {
  if (!form.title.trim()) {
    ElMessage.warning('请填写活动标题')
    return
  }
  if (!form.skuId) {
    ElMessage.warning('请选择 SKU')
    return
  }
  if (form.seckillPrice <= 0) {
    ElMessage.warning('活动价必须大于 0')
    return
  }
  saving.value = true
  try {
    await seckillApi.adminCreate({ ...form, title: form.title.trim() })
    ElMessage.success('活动已创建，请点击预热写入 Redis')
    createOpen.value = false
    await load()
  } catch (e) {
    ElMessage.error(describeError(e))
  } finally {
    saving.value = false
  }
}

async function preheat(activity: SeckillActivity): Promise<void> {
  preheating.value = activity.id
  try {
    const result = await seckillApi.preheat(activity.id)
    ElMessage.success(`已预热，当前库存 ${result.stock}`)
    await load()
  } catch (e) {
    ElMessage.error(describeError(e))
  } finally {
    preheating.value = null
  }
}

function view(activity: SeckillActivity): void {
  void router.push({ name: 'seckill-detail', params: { id: activity.id } })
}

onMounted(() => {
  void load()
})
</script>

<template>
  <div>
    <div class="admin-page-head">
      <div>
        <h1>秒杀活动</h1>
        <p>活动库存独立分配。创建后必须预热，用户才能抢购。</p>
      </div>
      <div class="admin-actions">
        <button class="btn btn--primary btn--sm" type="button" @click="openCreate">创建活动</button>
        <button class="btn btn--line btn--sm" type="button" @click="load">刷新</button>
      </div>
    </div>

    <section class="admin-card">
      <div v-if="loading" class="admin-empty">正在读取活动…</div>
      <div v-else-if="error" class="error-banner" style="margin: 16px">{{ error }}</div>
      <div v-else class="table-wrap">
        <table class="data-table">
          <thead>
            <tr>
              <th>活动</th>
              <th>商品</th>
              <th>活动价</th>
              <th>库存</th>
              <th>阶段</th>
              <th>时间窗</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in rows" :key="row.id">
              <td>
                <strong>{{ row.title }}</strong>
                <div class="tiny">#{{ row.id }}</div>
              </td>
              <td>
                <div class="table-product">
                  <img :src="row.productImage" :alt="row.productTitle" />
                  <div class="table-product__title">{{ row.productTitle }}</div>
                </div>
              </td>
              <td class="money">
                {{ formatYuan(row.seckillPrice) }}
                <div class="tiny" style="text-decoration: line-through">{{ formatYuan(row.originalPrice) }}</div>
              </td>
              <td>{{ row.availableStock }} / {{ row.totalStock }}</td>
              <td><StatusBadge :status="row.phase" /></td>
              <td class="tiny">{{ formatDateTime(row.startAt) }} – {{ formatDateTime(row.endAt) }}</td>
              <td>
                <div class="table-actions">
                  <button class="btn btn--primary btn--sm" type="button" :disabled="preheating === row.id" @click="preheat(row)">
                    {{ preheating === row.id ? '预热中' : '预热' }}
                  </button>
                  <button class="btn btn--ghost btn--sm" type="button" @click="view(row)">查看</button>
                </div>
              </td>
            </tr>
            <tr v-if="!rows.length">
              <td colspan="7" class="admin-empty">还没有秒杀活动</td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <el-dialog v-model="createOpen" title="创建秒杀活动" width="560px">
      <div class="form-grid">
        <label class="field field--full">
          <span class="field__label">活动标题</span>
          <input v-model="form.title" class="input" maxlength="64" placeholder="如 Arc 65W 限时秒杀" />
        </label>
        <label class="field field--full">
          <span class="field__label">商品 SKU</span>
          <select v-model.number="form.skuId" class="select">
            <option v-for="sku in skus" :key="sku.id" :value="sku.id">
              {{ sku.title }} · {{ formatYuan(sku.price) }}
            </option>
          </select>
        </label>
        <label class="field">
          <span class="field__label">活动价</span>
          <input v-model.number="form.seckillPrice" type="number" min="1" class="input" />
        </label>
        <label class="field">
          <span class="field__label">活动总量</span>
          <input v-model.number="form.totalStock" type="number" min="1" class="input" />
        </label>
        <label class="field">
          <span class="field__label">开始时间</span>
          <input v-model="form.startAt" type="datetime-local" class="input" />
        </label>
        <label class="field">
          <span class="field__label">结束时间</span>
          <input v-model="form.endAt" type="datetime-local" class="input" />
        </label>
      </div>
      <p class="hint" style="margin-top: 12px">当前只支持每人限购 1 件。创建后点击列表中的“预热”。</p>
      <div class="dialog-actions">
        <button class="btn btn--ghost" type="button" @click="createOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="saving" @click="save">
          {{ saving ? '创建中…' : '创建活动' }}
        </button>
      </div>
    </el-dialog>
  </div>
</template>
