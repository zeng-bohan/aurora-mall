<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { computed, onMounted, ref } from 'vue'

import { adminTradeApi, type AdminUser } from '@/api/dtc'
import { formatDateTime } from '@/utils/datetime'
import { describeError } from '@/utils/errors'

const users = ref<AdminUser[]>([])
const loading = ref(true)
const error = ref('')
const keyword = ref('')

const filtered = computed(() => {
  const value = keyword.value.trim().toLowerCase()
  if (!value) {
    return users.value
  }
  return users.value.filter(
    (user) =>
      user.username.toLowerCase().includes(value) ||
      (user.nickname ?? '').toLowerCase().includes(value),
  )
})

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    users.value = await adminTradeApi.users()
  } catch (e) {
    error.value = describeError(e)
    ElMessage.error(error.value)
  } finally {
    loading.value = false
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
        <h1>用户列表</h1>
        <p>只读账号视图，订单数与最近下单时间来自交易库汇总。</p>
      </div>
      <div class="admin-actions">
        <button class="btn btn--line btn--sm" type="button" @click="load">刷新</button>
      </div>
    </div>

    <section class="admin-card">
      <div class="admin-toolbar">
        <input v-model="keyword" class="input" style="width: 240px" placeholder="搜索用户名或昵称" />
        <span class="admin-toolbar__spacer" />
        <span class="tiny">共 {{ filtered.length }} 个账号</span>
      </div>
      <div v-if="loading" class="admin-empty">正在读取用户…</div>
      <div v-else-if="error" class="error-banner" style="margin: 16px">{{ error }}</div>
      <div v-else class="table-wrap">
        <table class="data-table">
          <thead>
            <tr>
              <th>ID</th>
              <th>用户名</th>
              <th>昵称</th>
              <th>角色</th>
              <th>订单数</th>
              <th>最近下单</th>
              <th>注册时间</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="user in filtered" :key="user.id">
              <td>#{{ user.id }}</td>
              <td>{{ user.username }}</td>
              <td>{{ user.nickname || '—' }}</td>
              <td>
                <span class="tag" :class="user.role === 'ADMIN' ? 'tag--accent' : ''">{{ user.role }}</span>
              </td>
              <td>{{ user.orderCount }}</td>
              <td class="tiny">{{ formatDateTime(user.lastOrderAt) }}</td>
              <td class="tiny">{{ formatDateTime(user.createdAt) }}</td>
            </tr>
            <tr v-if="!filtered.length">
              <td colspan="7" class="admin-empty">没有匹配的账号</td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
  </div>
</template>
