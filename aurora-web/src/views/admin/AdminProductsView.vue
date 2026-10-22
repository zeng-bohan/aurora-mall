<script setup lang="ts">
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onMounted, reactive, ref } from 'vue'

import { inventoryApi, productApi } from '@/api'
import type { Sku } from '@/api/product'
import { SKU_ON_SALE } from '@/api/product'
import EmptyState from '@/components/EmptyState.vue'
import PageHead from '@/components/PageHead.vue'
import StatusPill from '@/components/StatusPill.vue'
import { describeError } from '@/utils/errors'
import { formatDateTime } from '@/utils/datetime'
import { formatYuan } from '@/utils/money'

const records = ref<Sku[]>([])
const total = ref(0)
const current = ref(1)
const size = ref(10)
const loading = ref(true)
const loadError = ref('')
const saving = ref(false)

const dialogVisible = ref(false)
const editingId = ref<number | null>(null)
const form = reactive({ title: '', price: 0.01, stock: 0 })

const stockDialogVisible = ref(false)
const stockTarget = ref<Sku | null>(null)
const stockValue = ref(0)
const stockCurrent = ref<number | null>(null)
const stockLoading = ref(false)
const stockFailed = ref(false)
const stockBySku = ref<Record<number, number | null | undefined>>({})

const pageOnSale = computed(() => records.value.filter((row) => row.status === SKU_ON_SALE).length)
const pageOffShelf = computed(() => records.value.length - pageOnSale.value)

async function load(): Promise<void> {
  loading.value = true
  loadError.value = ''
  try {
    const page = await productApi.adminPage(current.value, size.value)
    records.value = page.records
    total.value = page.total
    stockBySku.value = await loadStockFor(page.records)
  } catch (error) {
    loadError.value = describeError(error)
    ElMessage.error(loadError.value)
  } finally {
    loading.value = false
  }
}

async function loadStockFor(rows: Sku[]): Promise<Record<number, number | null | undefined>> {
  const entries = await Promise.all(
    rows.map(async (sku) => {
      try {
        const stock = await inventoryApi.getStock(sku.id)
        return [sku.id, stock.available] as const
      } catch {
        return [sku.id, undefined] as const
      }
    })
  )
  return Object.fromEntries(entries)
}

function stockLabel(skuId: number): string {
  const value = stockBySku.value[skuId]
  if (value === undefined) {
    return '读取失败'
  }
  return value === null ? '未开通' : String(value)
}

function openCreate(): void {
  editingId.value = null
  form.title = ''
  form.price = 0.01
  form.stock = 0
  dialogVisible.value = true
}

function openEdit(sku: Sku): void {
  editingId.value = sku.id
  form.title = sku.title
  form.price = sku.price
  form.stock = sku.stock
  dialogVisible.value = true
}

async function save(): Promise<void> {
  if (!form.title.trim()) {
    ElMessage.warning('请填写商品名')
    return
  }
  if (form.price <= 0) {
    ElMessage.warning('价格必须大于 0')
    return
  }
  saving.value = true
  try {
    if (editingId.value === null) {
      await productApi.adminCreate({ title: form.title, price: form.price, stock: form.stock })
      ElMessage.success('已创建，记得再去开可售库存')
    } else {
      await productApi.adminUpdate(editingId.value, {
        title: form.title,
        price: form.price,
        stock: form.stock
      })
      ElMessage.success('已更新（展示库存未改动）')
    }
    dialogVisible.value = false
    await load()
  } catch (error) {
    ElMessage.error(describeError(error))
  } finally {
    saving.value = false
  }
}

async function offShelf(sku: Sku): Promise<void> {
  try {
    await ElMessageBox.confirm(`确定下架「${sku.title}」吗？下架后游客不再可见。`, '下架', {
      type: 'warning'
    })
  } catch {
    return
  }
  try {
    await productApi.adminOffShelf(sku.id)
    ElMessage.success('已下架')
    await load()
  } catch (error) {
    ElMessage.error(describeError(error))
  }
}

async function openStock(sku: Sku): Promise<void> {
  stockTarget.value = sku
  stockCurrent.value = null
  stockValue.value = 0
  stockFailed.value = false
  stockDialogVisible.value = true
  await readStock(sku.id)
}

async function readStock(skuId: number): Promise<void> {
  stockLoading.value = true
  stockFailed.value = false
  try {
    const view = await inventoryApi.getStock(skuId)
    stockCurrent.value = view.available
    stockValue.value = view.available ?? 0
  } catch (error) {
    stockFailed.value = true
    ElMessage.error(describeError(error))
  } finally {
    stockLoading.value = false
  }
}

async function saveStock(): Promise<void> {
  if (!stockTarget.value || stockFailed.value) {
    return
  }
  if (stockValue.value < 0) {
    ElMessage.warning('库存不能为负')
    return
  }
  stockLoading.value = true
  try {
    await inventoryApi.setStock(stockTarget.value.id, stockValue.value)
    stockBySku.value[stockTarget.value.id] = stockValue.value
    ElMessage.success('可售库存已更新')
    stockDialogVisible.value = false
  } catch (error) {
    ElMessage.error(describeError(error))
  } finally {
    stockLoading.value = false
  }
}

function onChangePage(next: number): void {
  current.value = next
  void load()
}

onMounted(load)
</script>

<template>
  <div class="app-page">
    <PageHead title="商品管理" meta="维护商品资料、价格与上下架状态；能否成交由可售库存单独决定。">
      <template #actions>
        <button type="button" class="btn" @click="openCreate">新建商品</button>
      </template>
    </PageHead>

    <dl class="summary-strip" aria-label="商品概况">
      <div>
        <dt>商品总数</dt>
        <dd>{{ total }} <span>件</span></dd>
      </div>
      <div>
        <dt>本页在售</dt>
        <dd>{{ pageOnSale }} <span>件</span></dd>
      </div>
      <div>
        <dt>本页已下架</dt>
        <dd>{{ pageOffShelf }} <span>件</span></dd>
      </div>
    </dl>

    <p class="admin-hint">
      「展示库存」是商品表的展示字段；「可售库存」由库存服务维护，决定是否还能下单。新建商品后需在行内「库存」中开通可售。
    </p>

    <div v-if="loading && records.length === 0" aria-busy="true" aria-label="正在加载商品">
      <div class="skeleton-block skeleton-row" />
      <div class="skeleton-block skeleton-row" />
      <div class="skeleton-block skeleton-row" />
    </div>

    <EmptyState
      v-else-if="loadError"
      title="商品加载失败"
      :hint="loadError"
    >
      <button type="button" class="btn" @click="load">重新加载</button>
    </EmptyState>

    <EmptyState
      v-else-if="records.length === 0"
      title="还没有商品"
      hint="新建商品后，再去库存弹窗里设置可售库存。"
    >
      <button type="button" class="btn" @click="openCreate">新建商品</button>
    </EmptyState>

    <section v-else class="panel">
      <div class="table-wrap">
        <table class="data-table">
          <thead>
            <tr>
              <th>商品</th>
              <th class="num">价格</th>
              <th class="num">展示库存</th>
              <th class="num">可售库存</th>
              <th>状态</th>
              <th class="col-updated">更新时间</th>
              <th aria-label="操作" />
            </tr>
          </thead>
          <tbody>
            <tr v-for="sku in records" :key="sku.id">
              <td class="identity">
                <span class="row-title">{{ sku.title }}</span>
                <span class="row-sub">编号 {{ sku.id }}</span>
              </td>
              <td class="num">{{ formatYuan(sku.price) }}</td>
              <td class="num">{{ sku.stock }}</td>
              <td class="num" :class="{ 'cell-muted': stockBySku[sku.id] == null }">
                {{ stockLabel(sku.id) }}
              </td>
              <td>
                <StatusPill
                  :tone="sku.status === SKU_ON_SALE ? 'paid' : 'closed'"
                  :label="sku.status === SKU_ON_SALE ? '在售' : '已下架'"
                />
              </td>
              <td class="col-updated">{{ formatDateTime(sku.updatedAt) }}</td>
              <td>
                <div class="row-actions">
                  <button type="button" class="btn btn--outline btn--sm" @click="openStock(sku)">
                    库存
                  </button>
                  <button type="button" class="btn btn--outline btn--sm" @click="openEdit(sku)">
                    编辑
                  </button>
                  <button
                    v-if="sku.status === SKU_ON_SALE"
                    type="button"
                    class="link-danger"
                    @click="offShelf(sku)"
                  >
                    下架
                  </button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <p class="table-scroll-hint">表格可左右滑动，操作列在右侧。</p>
    </section>

    <div v-if="total > size" class="catalog-pager">
      <el-pagination
        layout="prev, pager, next, total"
        :total="total"
        :page-size="size"
        :current-page="current"
        @current-change="onChangePage"
      />
    </div>

    <el-dialog v-model="dialogVisible" :title="editingId === null ? '新建商品' : '编辑商品'" width="460px">
      <el-form label-position="top">
        <el-form-item label="商品名">
          <el-input v-model="form.title" placeholder="不超过 64 个字符" />
        </el-form-item>
        <el-form-item label="价格">
          <el-input-number v-model="form.price" :min="0.01" :precision="2" :step="1" style="width: 100%" />
        </el-form-item>
        <el-form-item label="展示库存">
          <el-input-number
            v-model="form.stock"
            :min="0"
            :disabled="editingId !== null"
            style="width: 100%"
          />
          <p v-if="editingId !== null" class="form-hint">编辑时不改展示库存；可售库存请用「库存」按钮设置。</p>
        </el-form-item>
      </el-form>
      <template #footer>
        <button type="button" class="btn btn--outline" @click="dialogVisible = false">取消</button>
        <button type="button" class="btn" :disabled="saving" @click="save">
          {{ saving ? '保存中…' : '保存' }}
        </button>
      </template>
    </el-dialog>

    <el-dialog v-model="stockDialogVisible" title="可售库存" width="440px">
      <p class="form-hint" style="margin-bottom: 8px">{{ stockTarget?.title }}（编号 {{ stockTarget?.id }}）</p>
      <div v-if="stockFailed" class="notice notice--danger" style="margin-bottom: 12px">
        <strong>未能读取当前可售库存</strong>
        <p>为了避免用错误的基数覆盖库存，保存已禁用。请重试读取。</p>
        <button
          v-if="stockTarget"
          type="button"
          class="btn btn--outline btn--sm"
          style="margin-top: 8px"
          @click="readStock(stockTarget.id)"
        >
          重新读取
        </button>
      </div>
      <p v-else class="form-hint" style="margin-bottom: 12px">
        当前可售：<strong>{{ stockCurrent === null ? '未开通' : stockCurrent }}</strong>
      </p>
      <el-form label-position="top">
        <el-form-item label="设为">
          <el-input-number v-model="stockValue" :min="0" :disabled="stockFailed || stockLoading" style="width: 100%" />
        </el-form-item>
      </el-form>
      <p class="admin-hint admin-hint--tight">
        填写可售总量，不是增量。已有在途未支付的预占仍然有效。
      </p>
      <template #footer>
        <button type="button" class="btn btn--outline" @click="stockDialogVisible = false">取消</button>
        <button
          type="button"
          class="btn"
          :disabled="stockLoading || stockFailed"
          @click="saveStock"
        >
          {{ stockLoading ? '处理中…' : '保存' }}
        </button>
      </template>
    </el-dialog>
  </div>
</template>
