<script setup lang="ts">
import { ElMessage, ElMessageBox } from 'element-plus'
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import { adminCatalogApi, type SpuCard } from '@/api/dtc'
import StatusBadge from '@/components/StatusBadge.vue'
import { formatYuan } from '@/utils/money'
import { describeError } from '@/utils/errors'

const router = useRouter()

const rows = ref<SpuCard[]>([])
const total = ref(0)
const pages = ref(0)
const current = ref(1)
const size = 20
const keyword = ref('')
const loading = ref(true)
const error = ref('')
const acting = ref<number | null>(null)

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const page = await adminCatalogApi.page({
      keyword: keyword.value.trim() || undefined,
      current: current.value,
      size,
    })
    rows.value = page.records
    total.value = page.total
    pages.value = page.pages
  } catch (e) {
    error.value = describeError(e)
  } finally {
    loading.value = false
  }
}

function search(): void {
  current.value = 1
  void load()
}

function edit(row: SpuCard): void {
  void router.push({ name: 'admin-product-edit', params: { id: row.id } })
}

async function toggleStatus(row: SpuCard): Promise<void> {
  const next = row.status === 1 ? 0 : 1
  try {
    await ElMessageBox.confirm(
      next === 1 ? '上架后商品会重新出现在商城目录。' : '下架后商品会从商城目录隐藏，已有订单不受影响。',
      next === 1 ? '上架商品' : '下架商品',
      { confirmButtonText: next === 1 ? '上架' : '下架', cancelButtonText: '取消', type: 'warning' },
    )
    acting.value = row.id
    await adminCatalogApi.status(row.id, next)
    ElMessage.success(next === 1 ? '已上架' : '已下架')
    await load()
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error(describeError(e))
    }
  } finally {
    acting.value = null
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
        <h1>商品管理</h1>
        <p>维护 SPU、属性绑定和 SKU 价格。库存单独在库存页调整。</p>
      </div>
      <div class="admin-actions">
        <RouterLink class="btn btn--primary btn--sm" to="/admin/products/new">新建商品</RouterLink>
        <button class="btn btn--line btn--sm" type="button" @click="load">刷新</button>
      </div>
    </div>

    <section class="admin-card">
      <div class="admin-toolbar">
        <input v-model="keyword" class="input" style="width: 260px" placeholder="搜索商品标题" @keyup.enter="search" />
        <button class="btn btn--primary btn--sm" type="button" @click="search">查询</button>
        <span class="admin-toolbar__spacer" />
        <span class="tiny">共 {{ total }} 个 SPU</span>
      </div>

      <div v-if="loading" class="admin-empty">正在读取商品…</div>
      <div v-else-if="error" class="error-banner" style="margin: 16px">{{ error }}</div>
      <div v-else class="table-wrap">
        <table class="data-table">
          <thead>
            <tr>
              <th>商品</th>
              <th>分类</th>
              <th>价格</th>
              <th>SKU</th>
              <th>状态</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in rows" :key="row.id">
              <td>
                <div class="table-product">
                  <img :src="row.imageUrl" :alt="row.title" />
                  <div>
                    <div class="table-product__title">{{ row.title }}</div>
                    <div class="table-product__meta">#{{ row.id }} · {{ row.subtitle }}</div>
                  </div>
                </div>
              </td>
              <td>{{ row.categoryName }}</td>
              <td class="money">{{ formatYuan(row.minPrice) }} – {{ formatYuan(row.maxPrice) }}</td>
              <td>{{ row.skuCount }}</td>
              <td><StatusBadge :status="row.status === 1 ? 'ON_SALE' : 'OFF_SHELF'" /></td>
              <td>
                <div class="table-actions">
                  <button class="btn btn--ghost btn--sm" type="button" @click="edit(row)">编辑</button>
                  <button class="btn btn--line btn--sm" type="button" :disabled="acting === row.id" @click="toggleStatus(row)">
                    {{ row.status === 1 ? '下架' : '上架' }}
                  </button>
                </div>
              </td>
            </tr>
            <tr v-if="!rows.length">
              <td colspan="6" class="admin-empty">没有匹配的商品</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div v-if="pages > 1" class="admin-pagination">
        <el-pagination
          background
          layout="prev, pager, next"
          :total="total"
          :page-size="size"
          :current-page="current"
          @current-change="(page: number) => { current = page; load() }"
        />
      </div>
    </section>
  </div>
</template>
