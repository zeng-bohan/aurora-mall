import { defineStore } from 'pinia'
import { ref } from 'vue'

import { useAuthStore } from './auth'

/**
 * 本地记住下过的订单 id。
 *
 * 存在的唯一原因：**后端没有「我的订单列表」接口**（只有 `GET /orders/{id}`）。
 * 用户下单后我们能拿到 id，就把它留在本地，于是可以列出"我下过的单"——
 * 代价是换浏览器/清缓存后列表就空了，而且列表只有 id，金额状态还得逐单去查。
 *
 * 这是 M6 暴露出来的后端缺口，正解是在 order 服务加一个按 userId 分页查的端点，
 * 而不是把列表长期托管在浏览器里。
 */
function storageKey(userId: string): string {
  return `aurora.orderIds.${userId}`
}

function read(userId: string): number[] {
  try {
    const raw = localStorage.getItem(storageKey(userId))
    if (!raw) {
      return []
    }
    const parsed: unknown = JSON.parse(raw)
    if (!Array.isArray(parsed)) {
      return []
    }
    return parsed.filter((value): value is number => typeof value === 'number')
  } catch {
    // 存储被写坏不该让页面白屏：当作没有记录
    return []
  }
}

function write(userId: string, orderIds: number[]): void {
  localStorage.setItem(storageKey(userId), JSON.stringify(orderIds))
}

export const useMyOrdersStore = defineStore('myOrders', () => {
  const auth = useAuthStore()
  const orderIds = ref<number[]>([])

  /** 按当前登录用户装载；未登录则清空。 */
  function load(): void {
    orderIds.value = auth.userId ? read(auth.userId) : []
  }

  /** 新订单插到最前：列表按最近下单排序。 */
  function remember(orderId: number): void {
    const userId = auth.userId
    if (!userId) {
      return
    }
    const next = [orderId, ...orderIds.value.filter((id) => id !== orderId)]
    orderIds.value = next
    write(userId, next)
  }

  function forgetAll(): void {
    const userId = auth.userId
    if (userId) {
      localStorage.removeItem(storageKey(userId))
    }
    orderIds.value = []
  }

  return { orderIds, load, remember, forgetAll }
})
