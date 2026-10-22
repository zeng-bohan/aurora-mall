import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

import { authApi, type UserView } from '@/api/dtc'
import { clearTokens, loadTokens, saveTokens } from '@/api/session'
import type { Tokens } from '@/api/types'

export const useAuthStore = defineStore('auth', () => {
  const tokens = ref<Tokens | null>(loadTokens())
  const user = ref<UserView | null>(null)

  const isLoggedIn = computed(() => tokens.value !== null)
  const isAdmin = computed(() => user.value?.role === 'ADMIN')
  const displayName = computed(() => user.value?.nickname || user.value?.username || '账户')

  async function login(username: string, password: string): Promise<void> {
    const response = await authApi.login({ username, password })
    saveTokens({ accessToken: response.accessToken, refreshToken: response.refreshToken })
    tokens.value = { accessToken: response.accessToken, refreshToken: response.refreshToken }
    await loadIdentity()
  }

  async function loadIdentity(): Promise<void> {
    if (!tokens.value) {
      user.value = null
      return
    }
    try {
      user.value = await authApi.me()
    } catch {
      user.value = null
    }
  }

  async function logout(): Promise<void> {
    try {
      await authApi.logout()
    } catch {
      // 本地登出不能被服务端失败阻塞
    }
    forget()
  }

  function forget(): void {
    clearTokens()
    tokens.value = null
    user.value = null
  }

  return { tokens, user, isLoggedIn, isAdmin, displayName, login, loadIdentity, logout, forget }
})
