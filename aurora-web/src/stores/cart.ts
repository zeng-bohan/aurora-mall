import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

import { cartApi, type CartItem } from '@/api/dtc'

export const useCartStore = defineStore('cart', () => {
  const items = ref<CartItem[]>([])
  const loading = ref(false)

  const totalQuantity = computed(() =>
    items.value.reduce((sum, item) => sum + item.quantity, 0),
  )
  const activeItems = computed(() => items.value.filter((item) => item.status === 1))
  const subtotal = computed(() =>
    activeItems.value.reduce((sum, item) => sum + item.price * item.quantity, 0),
  )

  async function load(): Promise<void> {
    loading.value = true
    try {
      items.value = await cartApi.list()
    } finally {
      loading.value = false
    }
  }

  async function add(skuId: number, quantity = 1): Promise<void> {
    await cartApi.add(skuId, quantity)
    await load()
  }

  async function updateQuantity(skuId: number, quantity: number): Promise<void> {
    await cartApi.update(skuId, quantity)
    await load()
  }

  async function remove(skuId: number): Promise<void> {
    await cartApi.remove(skuId)
    await load()
  }

  async function clear(): Promise<void> {
    await cartApi.clear()
    items.value = []
  }

  function reset(): void {
    items.value = []
  }

  return {
    items,
    loading,
    totalQuantity,
    activeItems,
    subtotal,
    load,
    add,
    updateQuantity,
    remove,
    clear,
    reset,
  }
})
