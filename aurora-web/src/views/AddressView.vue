<script setup lang="ts">
import { ElMessage, ElMessageBox } from 'element-plus'
import { onMounted, ref } from 'vue'

import { addressApi, type Address } from '@/api/dtc'
import AddressDialog from '@/components/AddressDialog.vue'
import EmptyState from '@/components/EmptyState.vue'
import { describeError } from '@/utils/errors'

const addresses = ref<Address[]>([])
const loading = ref(true)
const dialogOpen = ref(false)
const editing = ref<Address | null>(null)

async function load(): Promise<void> {
  loading.value = true
  try {
    addresses.value = await addressApi.list()
  } catch (e) {
    ElMessage.error(describeError(e))
  } finally {
    loading.value = false
  }
}

function openAdd(): void {
  editing.value = null
  dialogOpen.value = true
}

function openEdit(address: Address): void {
  editing.value = address
  dialogOpen.value = true
}

async function remove(address: Address): Promise<void> {
  try {
    await ElMessageBox.confirm('删除后历史订单不受影响。确认删除这个地址？', '删除地址', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning',
    })
    await addressApi.remove(address.id)
    ElMessage.success('地址已删除')
    await load()
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error(describeError(e))
    }
  }
}

async function makeDefault(address: Address): Promise<void> {
  try {
    await addressApi.setDefault(address.id)
    ElMessage.success('已设为默认地址')
    await load()
  } catch (e) {
    ElMessage.error(describeError(e))
  }
}

onMounted(() => {
  void load()
})
</script>

<template>
  <div>
    <div class="container page-head">
      <div class="page-head__row">
        <div>
          <span class="eyebrow">Address Book</span>
          <h1 class="display">收货地址。</h1>
          <p>下单时会保存地址快照。之后修改或删除地址，不会改变历史订单。</p>
        </div>
        <button class="btn btn--primary" type="button" @click="openAdd">新增地址</button>
      </div>
    </div>

    <div class="container" style="padding-block: 32px 72px">
      <div v-if="loading" class="page-loading">正在读取地址…</div>
      <EmptyState
        v-else-if="!addresses.length"
        title="还没有收货地址"
        description="添加一个常用地址，结算时可以直接选择。"
      >
        <button class="btn btn--primary" type="button" @click="openAdd">添加地址</button>
      </EmptyState>
      <div v-else class="address-grid">
        <article
          v-for="address in addresses"
          :key="address.id"
          class="address-card"
          :class="{ 'is-default': address.isDefault }"
        >
          <div class="address-card__name">
            {{ address.receiverName }}
            <span class="tag" v-if="address.isDefault">默认</span>
          </div>
          <div class="address-card__line">{{ address.phone }}</div>
          <div class="address-card__line">
            {{ address.province }} {{ address.city }} {{ address.district }}<br />
            {{ address.detail }}
            <span v-if="address.postalCode"><br />{{ address.postalCode }}</span>
          </div>
          <div class="address-card__actions">
            <button type="button" @click="openEdit(address)">编辑</button>
            <button v-if="!address.isDefault" type="button" @click="makeDefault(address)">设为默认</button>
            <button type="button" @click="remove(address)">删除</button>
          </div>
        </article>
      </div>
    </div>

    <AddressDialog v-model="dialogOpen" :address="editing" @saved="load" />
  </div>
</template>
