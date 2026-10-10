import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

import { cartApi } from '@/api'
import type { CartItem } from '@/api/cart'

export const useCartStore = defineStore('cart', () => {
  const items = ref<CartItem[]>([])
  const loading = ref(false)

  /** 角标用件数之和，而不是行数——用户理解的是"买几件"。 */
  const totalQuantity = computed(() => items.value.reduce((sum, item) => sum + item.quantity, 0))

  /** 仅统计可结算的行：已下架的条目后端会拒绝下单，不该算进金额。 */
  const checkoutItems = computed(() => items.value.filter((item) => item.status === 1))

  const estimatedTotal = computed(() =>
    checkoutItems.value.reduce((sum, item) => sum + item.price * item.quantity, 0)
  )

  async function load(): Promise<void> {
    loading.value = true
    try {
      items.value = await cartApi.list()
    } finally {
      loading.value = false
    }
  }

  async function add(skuId: number, quantity: number): Promise<void> {
    await cartApi.addItem({ skuId, quantity })
    await load()
  }

  async function updateQuantity(skuId: number, quantity: number): Promise<void> {
    await cartApi.updateItem({ skuId, quantity })
    await load()
  }

  async function remove(skuId: number): Promise<void> {
    await cartApi.removeItem(skuId)
    await load()
  }

  async function clear(): Promise<void> {
    await cartApi.clear()
    await load()
  }

  /** 退出登录时丢弃本地视图，避免下个账号看到上个人的购物车。 */
  function reset(): void {
    items.value = []
  }

  return {
    items,
    loading,
    totalQuantity,
    checkoutItems,
    estimatedTotal,
    load,
    add,
    updateQuantity,
    remove,
    clear,
    reset
  }
})
