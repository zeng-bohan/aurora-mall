<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { useAuthStore } from '@/stores/auth'
import { describeError } from '@/utils/errors'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const username = ref('')
const password = ref('')
const error = ref('')
const loading = ref(false)

async function submit(): Promise<void> {
  error.value = ''
  if (!username.value.trim() || !password.value) {
    error.value = '请输入用户名和密码'
    return
  }
  loading.value = true
  try {
    await auth.login(username.value.trim(), password.value)
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/'
    await router.replace(redirect.startsWith('/') ? redirect : '/')
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
      <img src="/scenes/hero.webp" alt="AURORA 桌面场景" />
      <div class="auth-visual__veil" />
      <div class="auth-visual__copy">
        <span class="eyebrow" style="color: rgb(255 255 255 / 72%)">Desk &amp; Pocket</span>
        <h2 class="display">让桌面安静下来，<br />让工具顺手起来。</h2>
        <p>登录后可以保存购物车、地址和订单，也可以参与限时秒杀。</p>
      </div>
    </div>

    <div class="auth-panel">
      <div class="auth-panel__inner">
        <span class="eyebrow">Welcome back</span>
        <h1 class="display">登录。</h1>
        <p class="auth-panel__copy">使用你的 AURORA 账号继续。</p>

        <form class="auth-form" @submit.prevent="submit">
          <label class="field">
            <span class="field__label">用户名</span>
            <input v-model="username" class="input" autocomplete="username" placeholder="输入用户名" />
          </label>
          <label class="field">
            <span class="field__label">密码</span>
            <input
              v-model="password"
              class="input"
              type="password"
              autocomplete="current-password"
              placeholder="输入密码"
            />
          </label>
          <p v-if="error" class="error-text">{{ error }}</p>
          <button class="btn btn--primary btn--block" type="submit" :disabled="loading">
            {{ loading ? '登录中…' : '登录' }}
          </button>
        </form>

        <p class="auth-switch">
          还没有账号？<RouterLink to="/register">注册一个</RouterLink>
        </p>

        <div class="demo-account">
          <strong>演示账号</strong><br />
          顾客：<span class="mono">linyi / Aurora123!</span><br />
          运营：<span class="mono">admin / Aurora123!</span>
        </div>
      </div>
    </div>
  </div>
</template>
