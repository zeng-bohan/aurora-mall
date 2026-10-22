<script setup lang="ts">
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onMounted, reactive, ref } from 'vue'

import { adminCatalogApi, inventoryApi, type AdminSku } from '@/api/dtc'
import { describeError } from '@/utils/errors'
import { formatYuan } from '@/utils/money'

const skus = ref<AdminSku[]>([])
const stocks = reactive<Record<number, number | null>>({})
const inputs = reactive<Record<number, number>>({})
const keyword = ref('')
const loading = ref(true)
const error = ref('')
const saving = ref<number | null>(null)
const syncing = ref(false)

const rows = computed(() => {
  const value = keyword.value.trim().toLowerCase()
  if (!value) {
    return skus.value
  }
  return skus.value.filter(
    (sku) =>
      sku.title.toLowerCase().includes(value) ||
      sku.skuCode.toLowerCase().includes(value) ||
      sku.spuTitle.toLowerCase().includes(value),
  )
})

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const [skuList, stockList] = await Promise.all([
      adminCatalogApi.skus(),
      inventoryApi.stocks(),
    ])
    skus.value = skuList
    for (const key of Object.keys(stocks)) {
      delete stocks[Number(key)]
    }
    for (const stock of stockList) {
      stocks[stock.skuId] = stock.available
      if (inputs[stock.skuId] === undefined) {
        inputs[stock.skuId] = stock.available ?? 0
      }
    }
    for (const sku of skuList) {
      if (inputs[sku.id] === undefined) {
        inputs[sku.id] = sku.stock ?? 0
      }
    }
  } catch (e) {
    error.value = describeError(e)
  } finally {
    loading.value = false
  }
}

async function reconcile(): Promise<void> {
  try {
    await ElMessageBox.confirm(
      '将用数据库可售库存覆盖 Redis 中的全部库存键。请在无抢购/下单进行时执行。',
      '重建 Redis 库存',
      { confirmButtonText: '重建', cancelButtonText: '取消', type: 'warning' },
    )
    syncing.value = true
    const repaired = await inventoryApi.reconcile()
    ElMessage.success(`Redis 库存已同步，修复 ${repaired} 个键`)
    await load()
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error(describeError(e))
    }
  } finally {
    syncing.value = false
  }
}

async function save(sku: AdminSku): Promise<void> {
  const value = Number(inputs[sku.id])
  if (!Number.isInteger(value) || value < 0) {
    ElMessage.warning('库存必须是非负整数')
    return
  }
  saving.value = sku.id
  try {
    await inventoryApi.setStock(sku.id, value)
    stocks[sku.id] = value
    sku.stock = value
    ElMessage.success('库存已更新')
  } catch (e) {
    ElMessage.error(describeError(e))
  } finally {
    saving.value = null
  }
}

onMounted(() => {
  void load()
})
</script>

<template>
  <div>
    <div class="admin-page-head">
      <div>
        <h1>库存</h1>
        <p>设置的是可售库存。预占中的数量不会计入可售，支付后转为真实扣减。</p>
      </div>
      <div class="admin-actions">
        <button class="btn btn--line btn--sm" type="button" :disabled="syncing" @click="reconcile">
          {{ syncing ? '同步中…' : '重建 Redis' }}
        </button>
        <button class="btn btn--line btn--sm" type="button" @click="load">刷新</button>
      </div>
    </div>

    <section class="admin-card">
      <div class="admin-toolbar">
        <input v-model="keyword" class="input" style="width: 280px" placeholder="搜索商品 / SKU 编码" />
        <span class="admin-toolbar__spacer" />
        <span class="tiny">{{ rows.length }} 个 SKU</span>
      </div>

      <div v-if="loading" class="admin-empty">正在读取库存…</div>
      <div v-else-if="error" class="error-banner" style="margin: 16px">{{ error }}</div>
      <div v-else class="table-wrap">
        <table class="data-table">
          <thead>
            <tr>
              <th>商品 / SKU</th>
              <th>价格</th>
              <th>状态</th>
              <th>可售库存</th>
              <th>调整</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="sku in rows" :key="sku.id">
              <td>
                <div class="table-product">
                  <img :src="sku.imageUrl" :alt="sku.title" />
                  <div>
                    <div class="table-product__title">{{ sku.title }}</div>
                    <div class="table-product__meta">{{ sku.skuCode }} · {{ sku.spuTitle }}</div>
                  </div>
                </div>
              </td>
              <td class="money">{{ formatYuan(sku.price) }}</td>
              <td>{{ sku.status === 1 ? '在售' : '下架' }}</td>
              <td>
                <strong>{{ stocks[sku.id] === null || stocks[sku.id] === undefined ? '未开通' : stocks[sku.id] }}</strong>
              </td>
              <td>
                <div style="display: flex; gap: 6px; align-items: center">
                  <input v-model.number="inputs[sku.id]" type="number" min="0" class="input" style="width: 100px" />
                  <button class="btn btn--primary btn--sm" type="button" :disabled="saving === sku.id" @click="save(sku)">
                    {{ saving === sku.id ? '保存中' : '保存' }}
                  </button>
                </div>
              </td>
            </tr>
            <tr v-if="!rows.length">
              <td colspan="5" class="admin-empty">没有匹配的 SKU</td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
  </div>
</template>
