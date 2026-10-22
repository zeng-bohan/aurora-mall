<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { useAuthStore } from '@/stores/auth'
import { useCartStore } from '@/stores/cart'
import { describeError } from '@/utils/errors'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const cart = useCartStore()

const form = reactive({ username: '', password: '' })
const errors = reactive({ username: '', password: '' })
const serverError = ref('')
const submitting = ref(false)

function safeRedirect(): string {
  const value = route.query.redirect
  if (typeof value === 'string' && value.startsWith('/') && !value.startsWith('//')) {
    return value
  }
  return '/products'
}

function validate(): boolean {
  errors.username = form.username.trim() ? '' : '请输入用户名'
  errors.password = form.password ? '' : '请输入密码'
  return !errors.username && !errors.password
}

async function onSubmit(): Promise<void> {
  serverError.value = ''
  if (!validate()) {
    return
  }
  submitting.value = true
  try {
    await auth.login(form.username.trim(), form.password)
    await cart.load().catch(() => undefined)
    ElMessage.success('登录成功')
    await router.replace(safeRedirect())
  } catch (error) {
    serverError.value = describeError(error)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="auth-page">
    <section class="auth-brand" aria-label="AURORA 极光选品">
      <p class="display auth-brand__word">AURORA</p>
      <p>一份可以逐笔核对的选品目录。登录后，购物车、订单、优惠券和秒杀排期都从这里继续。</p>
      <p class="auth-brand__foot">Java 21 · Spring Boot 3.5 · 模拟支付链路</p>
    </section>

    <div class="auth-form-wrap">
      <form class="auth-form" novalidate @submit.prevent="onSubmit">
        <h1>登录</h1>
        <p class="auth-form__lead">使用你的目录账户继续。令牌只保存在本机浏览器。</p>

        <div v-if="serverError" class="notice notice--danger" role="alert">
          {{ serverError }}
        </div>

        <div class="field">
          <label for="login-username">用户名</label>
          <input
            id="login-username"
            v-model="form.username"
            name="username"
            autocomplete="username"
            placeholder="4-32 个字符"
            :aria-invalid="Boolean(errors.username)"
            :aria-describedby="errors.username ? 'login-username-error' : undefined"
          />
          <p v-if="errors.username" id="login-username-error" class="field__error">
            {{ errors.username }}
          </p>
        </div>

        <div class="field">
          <label for="login-password">密码</label>
          <input
            id="login-password"
            v-model="form.password"
            name="password"
            type="password"
            autocomplete="current-password"
            placeholder="至少 6 位"
            :aria-invalid="Boolean(errors.password)"
            :aria-describedby="errors.password ? 'login-password-error' : undefined"
          />
          <p v-if="errors.password" id="login-password-error" class="field__error">
            {{ errors.password }}
          </p>
        </div>

        <button type="submit" class="btn" :disabled="submitting">
          {{ submitting ? '登录中…' : '登录' }}
        </button>

        <p class="auth-alt">
          还没有账户？<RouterLink to="/register">去注册</RouterLink>
        </p>
      </form>
    </div>
  </div>
</template>
