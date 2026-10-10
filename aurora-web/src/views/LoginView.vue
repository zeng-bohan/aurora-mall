<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { useAuthStore } from '@/stores/auth'
import { useCartStore } from '@/stores/cart'
import { useMyOrdersStore } from '@/stores/myOrders'
import { describeError } from '@/utils/errors'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const cart = useCartStore()
const myOrders = useMyOrdersStore()

const form = reactive({ username: '', password: '' })
const submitting = ref(false)

async function onSubmit(): Promise<void> {
  if (!form.username || !form.password) {
    ElMessage.warning('请填写用户名与密码')
    return
  }
  submitting.value = true
  try {
    await auth.login(form.username, form.password)
    // 登录后立刻按新身份装载：本地订单索引按 userId 分桶，必须先拿到 userId
    myOrders.load()
    await cart.load().catch(() => undefined)
    ElMessage.success('登录成功')
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/products'
    await router.replace(redirect)
  } catch (error) {
    ElMessage.error(describeError(error))
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="aurora-page aurora-auth">
    <el-card class="aurora-auth__card">
      <h2 class="aurora-auth__title">登录</h2>
      <el-form label-position="top" @submit.prevent="onSubmit">
        <el-form-item label="用户名">
          <el-input v-model="form.username" autocomplete="username" placeholder="4-32 个字符" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input
            v-model="form.password"
            type="password"
            show-password
            autocomplete="current-password"
            placeholder="至少 6 位"
            @keyup.enter="onSubmit"
          />
        </el-form-item>
        <el-button type="primary" :loading="submitting" class="aurora-auth__submit" @click="onSubmit">
          登录
        </el-button>
      </el-form>
      <p class="aurora-auth__hint">
        还没有账号？<RouterLink to="/register">去注册</RouterLink>
      </p>
    </el-card>
  </div>
</template>

<style scoped>
.aurora-auth {
  display: flex;
  justify-content: center;
  padding-top: 64px;
}

.aurora-auth__card {
  width: 380px;
}

.aurora-auth__title {
  margin: 0 0 16px;
  font-size: 20px;
}

.aurora-auth__submit {
  width: 100%;
}

.aurora-auth__hint {
  margin: 16px 0 0;
  font-size: 13px;
  color: #909399;
  text-align: center;
}
</style>
