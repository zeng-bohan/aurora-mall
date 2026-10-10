<script setup lang="ts">
import { ElMessage, ElMessageBox } from 'element-plus'
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import { SKU_ON_SALE } from '@/api/product'
import { useCartStore } from '@/stores/cart'
import { describeError } from '@/utils/errors'
import { formatYuan } from '@/utils/money'

const router = useRouter()
const cart = useCartStore()
const busySkuId = ref<number | null>(null)

async function changeQuantity(skuId: number, quantity: number): Promise<void> {
  busySkuId.value = skuId
  try {
    await cart.updateQuantity(skuId, quantity)
  } catch (error) {
    ElMessage.error(describeError(error))
    // 改失败就把视图拉回服务端的真实值，别让界面停在用户以为改成了的数量上
    await cart.load().catch(() => undefined)
  } finally {
    busySkuId.value = null
  }
}

async function remove(skuId: number): Promise<void> {
  try {
    await ElMessageBox.confirm('确定从购物车移除这件商品吗？', '移除', { type: 'warning' })
  } catch {
    return // 用户取消
  }
  busySkuId.value = skuId
  try {
    await cart.remove(skuId)
  } catch (error) {
    ElMessage.error(describeError(error))
  } finally {
    busySkuId.value = null
  }
}

async function clearAll(): Promise<void> {
  try {
    await ElMessageBox.confirm('确定清空购物车吗？', '清空', { type: 'warning' })
  } catch {
    return
  }
  try {
    await cart.clear()
  } catch (error) {
    ElMessage.error(describeError(error))
  }
}

onMounted(() => {
  void cart.load().catch((error: unknown) => ElMessage.error(describeError(error)))
})
</script>

<template>
  <div class="aurora-page">
    <h2 class="aurora-title">购物车</h2>

    <el-table v-loading="cart.loading" :data="cart.items" empty-text="购物车是空的">
      <el-table-column label="商品" min-width="220">
        <template #default="{ row }">
          <RouterLink :to="{ name: 'product-detail', params: { id: row.skuId } }">
            {{ row.title }}
          </RouterLink>
          <el-tag v-if="row.status !== SKU_ON_SALE" type="info" size="small" class="aurora-tag">
            已下架
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="单价" width="120" align="right">
        <template #default="{ row }">
          <span class="aurora-amount">{{ formatYuan(row.price) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="数量" width="180">
        <template #default="{ row }">
          <el-input-number
            :model-value="row.quantity"
            :min="1"
            :max="Math.min(row.stock > 0 ? row.stock : 1, 999)"
            size="small"
            :disabled="busySkuId === row.skuId"
            @change="(value: number | undefined) => value && changeQuantity(row.skuId, value)"
          />
        </template>
      </el-table-column>
      <el-table-column label="小计" width="140" align="right">
        <template #default="{ row }">
          <span class="aurora-amount">{{ formatYuan(row.price * row.quantity) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="100">
        <template #default="{ row }">
          <el-button
            type="danger"
            text
            size="small"
            :disabled="busySkuId === row.skuId"
            @click="remove(row.skuId)"
          >
            移除
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="aurora-cart__footer">
      <el-button text :disabled="cart.items.length === 0" @click="clearAll">清空购物车</el-button>
      <div class="aurora-cart__summary">
        <span>合计（仅可购买商品）：</span>
        <strong class="aurora-amount">{{ formatYuan(cart.estimatedTotal) }}</strong>
        <el-button
          type="primary"
          :disabled="cart.checkoutItems.length === 0"
          @click="router.push({ name: 'checkout' })"
        >
          去结算
        </el-button>
      </div>
    </div>

    <el-alert
      v-if="cart.items.length > cart.checkoutItems.length"
      type="warning"
      :closable="false"
      show-icon
      title="有商品已下架，不会被结算"
      class="aurora-cart__alert"
    />
  </div>
</template>

<style scoped>
.aurora-title {
  font-size: 20px;
  margin: 8px 0 16px;
}

.aurora-tag {
  margin-left: 8px;
}

.aurora-cart__footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 16px;
}

.aurora-cart__summary {
  display: flex;
  align-items: center;
  gap: 12px;
}

.aurora-cart__summary strong {
  color: #f56c6c;
  font-size: 18px;
}

.aurora-cart__alert {
  margin-top: 16px;
}
</style>
