<script setup lang="ts">
import { ElMessage, ElMessageBox } from 'element-plus'
import { onMounted, reactive, ref } from 'vue'

import { inventoryApi, productApi } from '@/api'
import type { Sku } from '@/api/product'
import { SKU_ON_SALE } from '@/api/product'
import { describeError } from '@/utils/errors'
import { formatYuan } from '@/utils/money'

const records = ref<Sku[]>([])
const total = ref(0)
const current = ref(1)
const size = ref(10)
const loading = ref(false)
const saving = ref(false)

const dialogVisible = ref(false)
const editingId = ref<number | null>(null)
const form = reactive({ title: '', price: 0.01, stock: 0 })

// ---- 可售库存（与商品表的 stock 不是一个字段）----
const stockDialogVisible = ref(false)
const stockTarget = ref<Sku | null>(null)
const stockValue = ref(0)
const stockCurrent = ref<number | null>(null)
const stockLoading = ref(false)

async function load(): Promise<void> {
  loading.value = true
  try {
    const page = await productApi.adminPage(current.value, size.value)
    records.value = page.records
    total.value = page.total
  } catch (error) {
    ElMessage.error(describeError(error))
  } finally {
    loading.value = false
  }
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
      // 后端忽略 stock：改这里不会影响真实库存，能改的只有标题与价格
      await productApi.adminUpdate(editingId.value, {
        title: form.title,
        price: form.price,
        stock: form.stock
      })
      ElMessage.success('已更新（展示用库存未改动）')
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
  stockValue.value = sku.stock
  stockDialogVisible.value = true
  stockLoading.value = true
  try {
    const view = await inventoryApi.getStock(sku.id)
    stockCurrent.value = view.available
    stockValue.value = view.available ?? 0
  } catch (error) {
    ElMessage.error(describeError(error))
  } finally {
    stockLoading.value = false
  }
}

async function saveStock(): Promise<void> {
  if (!stockTarget.value) {
    return
  }
  if (stockValue.value < 0) {
    ElMessage.warning('库存不能为负')
    return
  }
  stockLoading.value = true
  try {
    await inventoryApi.setStock(stockTarget.value.id, stockValue.value)
    ElMessage.success('可售库存已更新')
    stockDialogVisible.value = false
  } catch (error) {
    ElMessage.error(describeError(error))
  } finally {
    stockLoading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="aurora-page">
    <div class="aurora-head">
      <h2 class="aurora-title">商品管理</h2>
      <el-button type="primary" @click="openCreate">新建商品</el-button>
    </div>

    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="「库存」列与「可售库存」是两回事"
      description="列表里的库存是商品表的展示字段；真正决定能不能卖的是库存服务的可售库存。新建商品后要用每行的「库存」按钮把可售库存开出来，否则下单会因库存服务找不到这个 SKU 而失败。"
      class="aurora-note"
    />

    <el-table v-loading="loading" :data="records" border>
      <el-table-column prop="id" label="编号" width="100" />
      <el-table-column prop="title" label="商品名" min-width="200" />
      <el-table-column label="价格" width="130" align="right">
        <template #default="{ row }">
          <span class="aurora-amount">{{ formatYuan(row.price) }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="stock" label="库存（展示）" width="130" align="center" />
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag :type="row.status === SKU_ON_SALE ? 'success' : 'info'">
            {{ row.status === SKU_ON_SALE ? '在售' : '已下架' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="220">
        <template #default="{ row }">
          <el-button text type="primary" size="small" @click="openStock(row)">库存</el-button>
          <el-button text type="primary" size="small" @click="openEdit(row)">编辑</el-button>
          <el-button
            v-if="row.status === SKU_ON_SALE"
            text
            type="danger"
            size="small"
            @click="offShelf(row)"
          >
            下架
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="aurora-pager">
      <el-pagination
        layout="prev, pager, next, total"
        :total="total"
        :page-size="size"
        :current-page="current"
        @current-change="
          (next: number) => {
            current = next
            load()
          }
        "
      />
    </div>

    <el-dialog v-model="dialogVisible" :title="editingId === null ? '新建商品' : '编辑商品'" width="440px">
      <el-form label-position="top">
        <el-form-item label="商品名">
          <el-input v-model="form.title" placeholder="不超过 64 个字符" />
        </el-form-item>
        <el-form-item label="价格">
          <el-input-number v-model="form.price" :min="0.01" :precision="2" :step="1" />
        </el-form-item>
        <el-form-item label="库存（展示字段）">
          <el-input-number v-model="form.stock" :min="0" :disabled="editingId !== null" />
          <span v-if="editingId !== null" class="aurora-form-hint">编辑时不改库存</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="stockDialogVisible" title="可售库存" width="420px">
      <p class="aurora-stock__target">{{ stockTarget?.title }}（编号 {{ stockTarget?.id }}）</p>
      <p v-loading="stockLoading" class="aurora-stock__current">
        当前可售：
        <strong v-if="stockCurrent === null">未开通</strong>
        <strong v-else>{{ stockCurrent }}</strong>
      </p>
      <el-form label-position="top">
        <el-form-item label="设为">
          <el-input-number v-model="stockValue" :min="0" />
        </el-form-item>
      </el-form>
      <el-alert
        type="warning"
        :closable="false"
        show-icon
        title="设置的是「可售」总量，不是增量"
        description="若该 SKU 有在途未支付的预占，设置后这些预占仍然有效——这里改的是可售那一部分。"
      />
      <template #footer>
        <el-button @click="stockDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="stockLoading" @click="saveStock">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.aurora-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.aurora-title {
  font-size: 20px;
  margin: 8px 0 16px;
}

.aurora-note {
  margin-bottom: 16px;
}

.aurora-pager {
  display: flex;
  justify-content: center;
  margin-top: 20px;
}

.aurora-form-hint {
  margin-left: 12px;
  font-size: 12px;
  color: #909399;
}

.aurora-stock__target {
  margin: 0 0 8px;
  font-weight: 600;
}

.aurora-stock__current {
  margin: 0 0 16px;
  color: #606266;
}

.aurora-stock__current strong {
  color: #f56c6c;
  font-size: 16px;
}
</style>
