<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import {
  adminCatalogApi,
  type Attribute,
  type Category,
  type SaveSpu,
  type Spec,
} from '@/api/dtc'
import { describeError } from '@/utils/errors'

interface SkuForm {
  id?: number
  skuCode: string
  price: number
  imageUrl: string
  status: number
  attributes: Record<number, number>
  title?: string
}

const route = useRoute()
const router = useRouter()
const id = Number(route.params.id)
const isEdit = Boolean(id)

const categories = ref<Category[]>([])
const attributeDefs = ref<Attribute[]>([])
const loading = ref(true)
const saving = ref(false)
const error = ref('')

const form = reactive({
  categoryId: 0,
  title: '',
  subtitle: '',
  description: '',
  status: 1,
  attributeIds: [] as number[],
})
const specs = ref<Spec[]>([])
const spuValues = reactive<Record<number, number>>({})
const skus = ref<SkuForm[]>([])

const selectedAttributes = computed(() =>
  attributeDefs.value.filter((attribute) => form.attributeIds.includes(attribute.id)),
)
const variantAttributes = computed(() => selectedAttributes.value.filter((attribute) => attribute.variant))
const nonVariantAttributes = computed(() =>
  selectedAttributes.value.filter((attribute) => !attribute.variant && attribute.values.length > 0),
)

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const [categoryList, attributeList] = await Promise.all([
      adminCatalogApi.categories(),
      adminCatalogApi.attributes(),
    ])
    categories.value = categoryList
    attributeDefs.value = attributeList
    if (!isEdit) {
      form.categoryId = categoryList[0]?.id ?? 0
      return
    }
    const detail = await adminCatalogApi.detail(id)
    form.categoryId = detail.categoryId
    form.title = detail.title
    form.subtitle = detail.subtitle
    form.description = detail.description
    form.status = detail.status
    form.attributeIds = detail.attributes.map((attribute) => attribute.id)
    specs.value = detail.specs.map((spec) => ({ ...spec }))
    for (const attribute of detail.attributes) {
      if (!attribute.variant && attribute.values[0]) {
        spuValues[attribute.id] = attribute.values[0].id
      }
    }
    skus.value = detail.skus.map((sku) => ({
      id: sku.id,
      skuCode: sku.skuCode,
      price: sku.price,
      imageUrl: sku.imageUrl,
      status: sku.status,
      attributes: Object.fromEntries(sku.attributes.map((item) => [item.attributeId, item.valueId])),
      title: sku.title,
    }))
  } catch (e) {
    error.value = describeError(e)
  } finally {
    loading.value = false
  }
}

function toggleAttribute(attribute: Attribute): void {
  if (form.attributeIds.includes(attribute.id)) {
    form.attributeIds = form.attributeIds.filter((item) => item !== attribute.id)
    delete spuValues[attribute.id]
  } else {
    form.attributeIds = [...form.attributeIds, attribute.id]
  }
}

function addSku(): void {
  const attributes: Record<number, number> = {}
  for (const attribute of variantAttributes.value) {
    const first = attribute.values[0]
    if (first) {
      attributes[attribute.id] = first.id
    }
  }
  skus.value.push({
    skuCode: `AUR-${Date.now().toString(36).toUpperCase()}`,
    price: 0,
    imageUrl: '/products/placeholder.webp',
    status: 1,
    attributes,
  })
}

function removeSku(index: number): void {
  skus.value.splice(index, 1)
}

function addSpec(): void {
  specs.value.push({ name: '', value: '' })
}

function removeSpec(index: number): void {
  specs.value.splice(index, 1)
}

async function save(): Promise<void> {
  error.value = ''
  if (!form.categoryId) {
    error.value = '请选择分类'
    return
  }
  if (!form.title.trim() || !form.subtitle.trim() || !form.description.trim()) {
    error.value = '请填写标题、副标题和描述'
    return
  }
  if (!skus.value.length) {
    error.value = '至少添加一个 SKU'
    return
  }
  for (const sku of skus.value) {
    if (!sku.skuCode.trim()) {
      error.value = 'SKU 编码不能为空'
      return
    }
    if (!Number.isFinite(sku.price) || sku.price <= 0) {
      error.value = 'SKU 价格必须大于 0'
      return
    }
    for (const attribute of variantAttributes.value) {
      if (!sku.attributes[attribute.id]) {
        error.value = `请为每个 SKU 选择${attribute.name}`
        return
      }
    }
  }
  const attributeValues: Record<number, number> = {}
  for (const attribute of nonVariantAttributes.value) {
    if (spuValues[attribute.id]) {
      attributeValues[attribute.id] = spuValues[attribute.id]
    }
  }
  const variantIds = new Set(variantAttributes.value.map((attribute) => attribute.id))
  const payload: SaveSpu = {
    categoryId: form.categoryId,
    title: form.title.trim(),
    subtitle: form.subtitle.trim(),
    description: form.description.trim(),
    attributeIds: form.attributeIds,
    attributeValues,
    specs: specs.value.filter((spec) => spec.name.trim() && spec.value.trim()),
    skus: skus.value.map((sku) => ({
      id: sku.id,
      skuCode: sku.skuCode.trim(),
      price: sku.price,
      imageUrl: sku.imageUrl.trim() || '/products/placeholder.webp',
      status: sku.status,
      title: sku.title,
      attributes: Object.fromEntries(
        Object.entries(sku.attributes)
          .filter(([attributeId]) => variantIds.has(Number(attributeId)))
          .map(([attributeId, valueId]) => [Number(attributeId), Number(valueId)]),
      ),
    })),
    status: form.status,
  }
  saving.value = true
  try {
    if (isEdit) {
      await adminCatalogApi.update(id, payload)
    } else {
      await adminCatalogApi.create(payload)
    }
    ElMessage.success('商品已保存')
    await router.push({ name: 'admin-products' })
  } catch (e) {
    error.value = describeError(e)
  } finally {
    saving.value = false
  }
}

onMounted(() => {
  void load()
})
</script>

<template>
  <div>
    <div class="admin-page-head">
      <div>
        <button class="btn btn--ghost btn--sm" type="button" style="margin-bottom: 8px" @click="router.push({ name: 'admin-products' })">
          ← 返回商品列表
        </button>
        <h1>{{ isEdit ? '编辑商品' : '新建商品' }}</h1>
        <p>SPU 信息、属性绑定和 SKU 矩阵。库存请在库存页调整。</p>
      </div>
      <div class="admin-actions">
        <button class="btn btn--primary btn--sm" type="button" :disabled="saving || loading" @click="save">
          {{ saving ? '保存中…' : '保存商品' }}
        </button>
      </div>
    </div>

    <div v-if="loading" class="admin-empty">正在读取商品数据…</div>
    <div v-else-if="error && !form.title" class="error-banner">{{ error }}</div>
    <div v-else class="admin-stack">
      <section class="admin-card">
        <div class="admin-card__head"><h2>基本信息</h2></div>
        <div class="admin-card__body form-section">
          <div class="form-two">
            <label class="field">
              <span class="field__label">分类</span>
              <select v-model.number="form.categoryId" class="select">
                <option v-for="category in categories" :key="category.id" :value="category.id">
                  {{ category.name }}
                </option>
              </select>
            </label>
            <label class="field">
              <span class="field__label">状态</span>
              <select v-model.number="form.status" class="select">
                <option :value="1">在售</option>
                <option :value="0">下架</option>
              </select>
            </label>
          </div>
          <label class="field">
            <span class="field__label">标题</span>
            <input v-model="form.title" class="input" maxlength="96" />
          </label>
          <label class="field">
            <span class="field__label">副标题</span>
            <input v-model="form.subtitle" class="input" maxlength="160" />
          </label>
          <label class="field">
            <span class="field__label">描述</span>
            <textarea v-model="form.description" class="textarea" rows="4" />
          </label>
        </div>
      </section>

      <section class="admin-card">
        <div class="admin-card__head"><h2>属性绑定</h2></div>
        <div class="admin-card__body">
          <div style="display: flex; flex-wrap: wrap; gap: 8px">
            <button
              v-for="attribute in attributeDefs"
              :key="attribute.id"
              class="chip-toggle"
              :class="{ 'is-active': form.attributeIds.includes(attribute.id) }"
              type="button"
              @click="toggleAttribute(attribute)"
            >
              {{ attribute.name }}
              <span v-if="attribute.variant" class="tag tag--accent">变体</span>
            </button>
          </div>
          <div v-if="nonVariantAttributes.length" class="form-three" style="margin-top: 18px">
            <label v-for="attribute in nonVariantAttributes" :key="attribute.id" class="field">
              <span class="field__label">{{ attribute.name }}</span>
              <select v-model.number="spuValues[attribute.id]" class="select">
                <option :value="undefined">请选择</option>
                <option v-for="value in attribute.values" :key="value.id" :value="value.id">
                  {{ value.value }}
                </option>
              </select>
            </label>
          </div>
          <p class="hint" style="margin-top: 12px">
            变体属性用于生成 SKU 组合；非变体属性用于商品参数和筛选。
          </p>
        </div>
      </section>

      <section class="admin-card">
        <div class="admin-card__head">
          <h2>商品参数</h2>
          <button class="btn btn--line btn--sm" type="button" @click="addSpec">添加参数</button>
        </div>
        <div class="admin-card__body form-section">
          <div v-for="(spec, index) in specs" :key="index" class="form-two">
            <input v-model="spec.name" class="input" placeholder="参数名，如 输出" />
            <div style="display: flex; gap: 6px">
              <input v-model="spec.value" class="input" placeholder="参数值，如 65W" />
              <button class="btn btn--ghost btn--sm" type="button" @click="removeSpec(index)">删除</button>
            </div>
          </div>
          <p v-if="!specs.length" class="hint">还没有参数。建议补充输出、接口、尺寸等。</p>
        </div>
      </section>

      <section class="admin-card">
        <div class="admin-card__head">
          <h2>SKU 矩阵</h2>
          <button class="btn btn--primary btn--sm" type="button" @click="addSku">添加 SKU</button>
        </div>
        <div class="admin-card__body">
          <p v-if="!variantAttributes.length" class="hint" style="margin-bottom: 12px">
            当前没有绑定变体属性。每个 SKU 组合为空也可以保存，但建议至少绑定颜色或尺寸。
          </p>
          <div class="table-wrap">
            <table class="sku-editor">
              <thead>
                <tr>
                  <th>SKU 编码</th>
                  <th>价格</th>
                  <th>图片 URL</th>
                  <th v-for="attribute in variantAttributes" :key="attribute.id">{{ attribute.name }}</th>
                  <th>状态</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="(sku, index) in skus" :key="index">
                  <td><input v-model="sku.skuCode" class="input" style="width: 150px" /></td>
                  <td><input v-model.number="sku.price" type="number" min="0.01" step="0.01" class="input" style="width: 100px" /></td>
                  <td><input v-model="sku.imageUrl" class="input" style="width: 200px" /></td>
                  <td v-for="attribute in variantAttributes" :key="attribute.id">
                    <select v-model.number="sku.attributes[attribute.id]" class="select" style="width: 130px">
                      <option v-for="value in attribute.values" :key="value.id" :value="value.id">
                        {{ value.value }}
                      </option>
                    </select>
                  </td>
                  <td>
                    <select v-model.number="sku.status" class="select" style="width: 90px">
                      <option :value="1">在售</option>
                      <option :value="0">下架</option>
                    </select>
                  </td>
                  <td>
                    <button class="btn btn--ghost btn--sm" type="button" @click="removeSku(index)">删除</button>
                  </td>
                </tr>
                <tr v-if="!skus.length">
                  <td :colspan="5 + variantAttributes.length" class="admin-empty">还没有 SKU，点击“添加 SKU”</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
      </section>

      <p v-if="error" class="error-text">{{ error }}</p>
    </div>
  </div>
</template>
