<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'

import { userApi } from '@/api'
import { describeError } from '@/utils/errors'

const router = useRouter()

const form = reactive({ username: '', password: '', nickname: '' })
const errors = reactive({ username: '', password: '', nickname: '' })
const serverError = ref('')
const submitting = ref(false)

function validate(): boolean {
  const username = form.username.trim()
  errors.username =
    username.length >= 4 && username.length <= 32 ? '' : '用户名需 4-32 个字符'
  errors.password =
    form.password.length >= 6 && form.password.length <= 64 ? '' : '密码需 6-64 个字符'
  errors.nickname = form.nickname.length <= 64 ? '' : '昵称不能超过 64 个字符'
  return !errors.username && !errors.password && !errors.nickname
}

async function onSubmit(): Promise<void> {
  serverError.value = ''
  if (!validate()) {
    return
  }
  submitting.value = true
  try {
    await userApi.register({
      username: form.username.trim(),
      password: form.password,
      nickname: form.nickname.trim() || undefined
    })
    ElMessage.success('注册成功，请登录')
    await router.push({ name: 'login' })
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
      <p>注册一个目录账户。新账户固定是普通用户；管理员由运维在数据库中指定。</p>
      <p class="auth-brand__foot">价格由服务端计算 · 库存由服务端校验</p>
    </section>

    <div class="auth-form-wrap">
      <form class="auth-form" novalidate @submit.prevent="onSubmit">
        <h1>注册</h1>
        <p class="auth-form__lead">创建一个账户，用来保存购物车、订单和优惠券。</p>

        <div v-if="serverError" class="notice notice--danger" role="alert">
          {{ serverError }}
        </div>

        <div class="field">
          <label for="register-username">用户名</label>
          <input
            id="register-username"
            v-model="form.username"
            name="username"
            autocomplete="username"
            placeholder="4-32 个字符"
            :aria-invalid="Boolean(errors.username)"
            :aria-describedby="errors.username ? 'register-username-error' : undefined"
          />
          <p v-if="errors.username" id="register-username-error" class="field__error">
            {{ errors.username }}
          </p>
        </div>

        <div class="field">
          <label for="register-password">密码</label>
          <input
            id="register-password"
            v-model="form.password"
            name="password"
            type="password"
            autocomplete="new-password"
            placeholder="6-64 个字符"
            :aria-invalid="Boolean(errors.password)"
            :aria-describedby="errors.password ? 'register-password-error' : undefined"
          />
          <p v-if="errors.password" id="register-password-error" class="field__error">
            {{ errors.password }}
          </p>
        </div>

        <div class="field">
          <label for="register-nickname">昵称（可选）</label>
          <input
            id="register-nickname"
            v-model="form.nickname"
            name="nickname"
            autocomplete="nickname"
            placeholder="不超过 64 个字符"
            :aria-invalid="Boolean(errors.nickname)"
            :aria-describedby="errors.nickname ? 'register-nickname-error' : undefined"
          />
          <p v-if="errors.nickname" id="register-nickname-error" class="field__error">
            {{ errors.nickname }}
          </p>
        </div>

        <p class="auth-note">
          注册不会创建管理员账户。管理员权限由运维在数据库中指定，前端不提供提权入口。
        </p>

        <button type="submit" class="btn" :disabled="submitting">
          {{ submitting ? '注册中…' : '注册' }}
        </button>

        <p class="auth-alt">
          已有账户？<RouterLink to="/login">去登录</RouterLink>
        </p>
      </form>
    </div>
  </div>
</template>
