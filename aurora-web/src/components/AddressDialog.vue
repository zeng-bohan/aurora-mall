<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { computed, reactive, ref, watch } from 'vue'

import { addressApi, type Address, type AddressInput } from '@/api/dtc'
import { describeError } from '@/utils/errors'

const props = defineProps<{ modelValue: boolean; address?: Address | null }>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  saved: []
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
})

const form = reactive<AddressInput>({
  receiverName: '',
  phone: '',
  province: '',
  city: '',
  district: '',
  detail: '',
  postalCode: '',
  isDefault: false,
})
const saving = ref(false)
const error = ref('')

const title = computed(() => (props.address ? '编辑地址' : '新增地址'))

watch(
  () => [props.modelValue, props.address] as const,
  () => {
    if (!props.modelValue) {
      return
    }
    error.value = ''
    Object.assign(form, {
      receiverName: props.address?.receiverName ?? '',
      phone: props.address?.phone ?? '',
      province: props.address?.province ?? '',
      city: props.address?.city ?? '',
      district: props.address?.district ?? '',
      detail: props.address?.detail ?? '',
      postalCode: props.address?.postalCode ?? '',
      isDefault: props.address?.isDefault ?? false,
    })
  },
  { immediate: true },
)

async function save(): Promise<void> {
  error.value = ''
  if (!form.receiverName.trim()) {
    error.value = '请填写收货人'
    return
  }
  if (!/^1\d{10}$/.test(form.phone)) {
    error.value = '请填写 11 位手机号'
    return
  }
  if (!form.province.trim() || !form.city.trim() || !form.district.trim()) {
    error.value = '请填写省、市、区'
    return
  }
  if (!form.detail.trim()) {
    error.value = '请填写详细地址'
    return
  }
  saving.value = true
  try {
    if (props.address) {
      await addressApi.update(props.address.id, form)
    } else {
      await addressApi.create(form)
    }
    ElMessage.success('地址已保存')
    emit('saved')
    visible.value = false
  } catch (e) {
    error.value = describeError(e)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-dialog v-model="visible" :title="title" width="560px" append-to-body>
    <div class="form-grid">
      <label class="field">
        <span class="field__label">收货人</span>
        <input v-model="form.receiverName" class="input" maxlength="32" placeholder="姓名" />
      </label>
      <label class="field">
        <span class="field__label">手机号</span>
        <input v-model="form.phone" class="input" maxlength="11" placeholder="11 位手机号" />
      </label>
      <label class="field">
        <span class="field__label">省</span>
        <input v-model="form.province" class="input" maxlength="32" placeholder="如 浙江省" />
      </label>
      <label class="field">
        <span class="field__label">市</span>
        <input v-model="form.city" class="input" maxlength="32" placeholder="如 杭州市" />
      </label>
      <label class="field">
        <span class="field__label">区 / 县</span>
        <input v-model="form.district" class="input" maxlength="32" placeholder="如 西湖区" />
      </label>
      <label class="field">
        <span class="field__label">邮编（可选）</span>
        <input v-model="form.postalCode" class="input" maxlength="12" placeholder="310012" />
      </label>
      <label class="field field--full">
        <span class="field__label">详细地址</span>
        <input v-model="form.detail" class="input" maxlength="200" placeholder="街道、门牌号、楼层房间" />
      </label>
      <label class="field field--full" style="display: flex; align-items: center; gap: 8px">
        <input v-model="form.isDefault" type="checkbox" class="checkbox" />
        <span class="hint">设为默认地址</span>
      </label>
    </div>
    <p v-if="error" class="error-text" style="margin-top: 12px">{{ error }}</p>
    <div class="dialog-actions">
      <button class="btn btn--ghost" type="button" @click="visible = false">取消</button>
      <button class="btn btn--primary" type="button" :disabled="saving" @click="save">
        {{ saving ? '保存中…' : '保存地址' }}
      </button>
    </div>
  </el-dialog>
</template>
