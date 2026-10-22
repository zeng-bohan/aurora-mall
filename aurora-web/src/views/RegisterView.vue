<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'

import { authApi } from '@/api/dtc'
import { useAuthStore } from '@/stores/auth'
import { describeError } from '@/utils/errors'

const router = useRouter()
const auth = useAuthStore()

const username = ref('')
const nickname = ref('')
const password = ref('')
const confirmPassword = ref('')
const error = ref('')
const loading = ref(false)

async function submit(): Promise<void> {
  error.value = ''
  if (username.value.trim().length < 3) {
    error.value = '用户名至少 3 个字符'
    return
  }
  if (password.value.length < 8) {
    error.value = '密码至少 8 个字符'
    return
  }
  if (password.value !== confirmPassword.value) {
    error.value = '两次输入的密码不一致'
    return
  }
  loading.value = true
  try {
    await authApi.register({
      username: username.value.trim(),
      password: password.value,
      nickname: nickname.value.trim() || undefined,
    })
    await auth.login(username.value.trim(), password.value)
    await router.replace('/')
  } catch (e) {
    error.value = describeError(e)
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="auth-shell">
    <div class="auth-visual">
      <RouterLink class="brand auth-back" to="/" style="color: #fff">
        <span class="brand__mark" aria-hidden="true" />
        <span class="brand__word" style="color: #fff">AURORA</span>
      </RouterLink>
      <img src="/scenes/night.webp" alt="AURORA 夜间充电场景" />
      <div class="auth-visual__veil" />
      <div class="auth-visual__copy">
        <span class="eyebrow" style="color: rgb(255 255 255 / 72%)">Join Aurora</span>
        <h2 class="display">从一件每天都会用的工具开始。</h2>
        <p>注册后可以保存地址、查看订单和退款进度。</p>
      </div>
    </div>

    <div class="auth-panel">
      <div class="auth-panel__inner">
        <span class="eyebrow">Create account</span>
        <h1 class="display">注册。</h1>
        <p class="auth-panel__copy">只需要用户名和密码，昵称可以之后补充。</p>

        <form class="auth-form" @submit.prevent="submit">
          <label class="field">
            <span class="field__label">用户名</span>
            <input v-model="username" class="input" autocomplete="username" placeholder="至少 3 个字符" />
          </label>
          <label class="field">
            <span class="field__label">昵称（可选）</span>
            <input v-model="nickname" class="input" autocomplete="nickname" placeholder="显示在个人中心" />
          </label>
          <label class="field">
            <span class="field__label">密码</span>
            <input
              v-model="password"
              class="input"
              type="password"
              autocomplete="new-password"
              placeholder="至少 8 个字符"
            />
          </label>
          <label class="field">
            <span class="field__label">确认密码</span>
            <input
              v-model="confirmPassword"
              class="input"
              type="password"
              autocomplete="new-password"
              placeholder="再输入一次"
            />
          </label>
          <p v-if="error" class="error-text">{{ error }}</p>
          <button class="btn btn--primary btn--block" type="submit" :disabled="loading">
            {{ loading ? '创建中…' : '创建账号' }}
          </button>
        </form>

        <p class="auth-switch">
          已经有账号？<RouterLink to="/login">直接登录</RouterLink>
        </p>
      </div>
    </div>
  </div>
</template>
