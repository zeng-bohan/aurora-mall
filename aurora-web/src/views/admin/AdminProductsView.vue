<script setup lang="ts">
import { ElMessage, ElMessageBox } from 'element-plus'
import { onMounted, reactive, ref } from 'vue'

import { productApi } from '@/api'
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
      ElMessage.success('已创建')
    } else {
      // 后端忽略 stock：改这里不会影响真实库存，能改的只有标题与价格
      await productApi.adminUpdate(editingId.value, {
        title: form.title,
        price: form.price,
        stock: form.stock
      })
      ElMessage.success('已更新（库存未改动，库存归库存服务管）')
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

onMounted(load)
</script>

<template>
  <div class="aurora-page">
    <div class="aurora-head">
      <h2 class="aurora-title">商品管理</h2>
      <el-button type="primary" @click="openCreate">新建商品</el-button>
    </div>

    <el-alert
      type="warning"
      :closable="false"
      show-icon
      title="这里建的商品还不能真正下单"
      description="商品表里的库存只是展示字段，实际能不能卖由库存服务（aurora-inventory）决定。而库存服务的写端点不对外开放（网关注入的用户身份会被它的身份守卫拒绝），所以前端无法初始化可售库存——需要库存侧补一个管理入口，或让建商品时一并开库存。"
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
      <el-table-column label="操作" width="160">
        <template #default="{ row }">
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
</style>
