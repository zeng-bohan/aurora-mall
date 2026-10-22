<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { onMounted, reactive, ref } from 'vue'

import {
  adminCatalogApi,
  type Attribute,
  type AttributeValue,
  type Category,
  type SaveAttribute,
  type SaveAttributeValue,
  type SaveCategory,
} from '@/api/dtc'
import { describeError } from '@/utils/errors'

const categories = ref<Category[]>([])
const attributes = ref<Attribute[]>([])
const loading = ref(true)
const error = ref('')

const categoryOpen = ref(false)
const editingCategory = ref<Category | null>(null)
const categoryForm = reactive<SaveCategory>({ name: '', slug: '', sort: 0, status: 1 })

const attrOpen = ref(false)
const editingAttr = ref<Attribute | null>(null)
const attrForm = reactive<SaveAttribute>({
  name: '',
  code: '',
  type: 'TEXT',
  unit: '',
  filterable: false,
  variant: false,
  required: false,
  display: true,
  sort: 0,
  status: 1,
})

const valueOpen = ref(false)
const valueAttributeId = ref(0)
const editingValue = ref<AttributeValue | null>(null)
const valueForm = reactive<SaveAttributeValue>({ value: '', colorHex: '', sort: 0, status: 1 })
const saving = ref(false)

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const [categoryList, attributeList] = await Promise.all([
      adminCatalogApi.categories(),
      adminCatalogApi.attributes(),
    ])
    categories.value = categoryList
    attributes.value = attributeList
  } catch (e) {
    error.value = describeError(e)
  } finally {
    loading.value = false
  }
}

function openCategory(category?: Category): void {
  editingCategory.value = category ?? null
  Object.assign(categoryForm, {
    name: category?.name ?? '',
    slug: category?.slug ?? '',
    sort: category?.sort ?? 0,
    status: category?.status ?? 1,
  })
  categoryOpen.value = true
}

async function saveCategory(): Promise<void> {
  if (!categoryForm.name.trim() || !categoryForm.slug.trim()) {
    ElMessage.warning('请填写分类名称和标识')
    return
  }
  saving.value = true
  try {
    if (editingCategory.value) {
      await adminCatalogApi.updateCategory(editingCategory.value.id, categoryForm)
    } else {
      await adminCatalogApi.createCategory(categoryForm)
    }
    ElMessage.success('分类已保存')
    categoryOpen.value = false
    await load()
  } catch (e) {
    ElMessage.error(describeError(e))
  } finally {
    saving.value = false
  }
}

function openAttribute(attribute?: Attribute): void {
  editingAttr.value = attribute ?? null
  Object.assign(attrForm, {
    name: attribute?.name ?? '',
    code: attribute?.code ?? '',
    type: attribute?.type ?? 'TEXT',
    unit: attribute?.unit ?? '',
    filterable: attribute?.filterable ?? false,
    variant: attribute?.variant ?? false,
    required: attribute?.required ?? false,
    display: attribute?.display ?? true,
    sort: attribute?.sort ?? 0,
    status: attribute?.status ?? 1,
  })
  attrOpen.value = true
}

async function saveAttribute(): Promise<void> {
  if (!attrForm.name.trim() || !attrForm.code.trim()) {
    ElMessage.warning('请填写属性名称和编码')
    return
  }
  saving.value = true
  try {
    if (editingAttr.value) {
      await adminCatalogApi.updateAttribute(editingAttr.value.id, attrForm)
    } else {
      await adminCatalogApi.createAttribute(attrForm)
    }
    ElMessage.success('属性已保存')
    attrOpen.value = false
    await load()
  } catch (e) {
    ElMessage.error(describeError(e))
  } finally {
    saving.value = false
  }
}

function openValue(attributeId: number, value?: AttributeValue): void {
  valueAttributeId.value = attributeId
  editingValue.value = value ?? null
  Object.assign(valueForm, {
    value: value?.value ?? '',
    colorHex: value?.colorHex ?? '',
    sort: value?.sort ?? 0,
    status: value?.status ?? 1,
  })
  valueOpen.value = true
}

async function saveValue(): Promise<void> {
  if (!valueForm.value.trim()) {
    ElMessage.warning('请填写属性值')
    return
  }
  saving.value = true
  try {
    if (editingValue.value) {
      await adminCatalogApi.updateValue(editingValue.value.id, valueForm)
    } else {
      await adminCatalogApi.addValue(valueAttributeId.value, valueForm)
    }
    ElMessage.success('属性值已保存')
    valueOpen.value = false
    await load()
  } catch (e) {
    ElMessage.error(describeError(e))
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
        <h1>分类与属性</h1>
        <p>全局 EAV 字典：属性值可以被多个 SPU 复用，SKU 组合由变体属性生成。</p>
      </div>
      <div class="admin-actions">
        <button class="btn btn--line btn--sm" type="button" @click="load">刷新</button>
      </div>
    </div>

    <div v-if="loading" class="admin-empty">正在读取分类和属性…</div>
    <div v-else-if="error" class="error-banner">{{ error }}</div>
    <template v-else>
      <section class="admin-card" style="margin-bottom: 16px">
        <div class="admin-card__head">
          <h2>分类</h2>
          <button class="btn btn--primary btn--sm" type="button" @click="openCategory()">新建分类</button>
        </div>
        <div class="table-wrap">
          <table class="data-table">
            <thead>
              <tr>
                <th>ID</th>
                <th>名称</th>
                <th>标识</th>
                <th>排序</th>
                <th>状态</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="category in categories" :key="category.id">
                <td>#{{ category.id }}</td>
                <td>{{ category.name }}</td>
                <td class="mono">{{ category.slug }}</td>
                <td>{{ category.sort }}</td>
                <td>{{ category.status === 1 ? '显示' : '隐藏' }}</td>
                <td>
                  <div class="table-actions">
                    <button class="btn btn--ghost btn--sm" type="button" @click="openCategory(category)">编辑</button>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <section class="admin-card">
        <div class="admin-card__head">
          <h2>属性字典</h2>
          <button class="btn btn--primary btn--sm" type="button" @click="openAttribute()">新建属性</button>
        </div>
        <div class="admin-card__body" style="display: grid; gap: 14px">
          <article v-for="attribute in attributes" :key="attribute.id" class="surface" style="padding: 16px">
            <div style="display: flex; justify-content: space-between; gap: 16px; flex-wrap: wrap">
              <div>
                <strong>{{ attribute.name }}</strong>
                <span class="mono tiny" style="margin-left: 8px">{{ attribute.code }}</span>
                <div class="tiny" style="margin-top: 4px">
                  {{ attribute.type }}
                  <span v-if="attribute.unit"> · {{ attribute.unit }}</span>
                  <span v-if="attribute.variant" class="tag tag--accent" style="margin-left: 6px">变体</span>
                  <span v-if="attribute.filterable" class="tag" style="margin-left: 6px">可筛选</span>
                  <span v-if="attribute.required" class="tag tag--warning" style="margin-left: 6px">必填</span>
                </div>
              </div>
              <div style="display: flex; gap: 6px; align-items: flex-start">
                <button class="btn btn--ghost btn--sm" type="button" @click="openAttribute(attribute)">编辑属性</button>
                <button class="btn btn--line btn--sm" type="button" @click="openValue(attribute.id)">新增值</button>
              </div>
            </div>
            <div style="display: flex; flex-wrap: wrap; gap: 8px; margin-top: 12px">
              <button
                v-for="value in attribute.values"
                :key="value.id"
                class="chip-toggle is-active"
                type="button"
                @click="openValue(attribute.id, value)"
              >
                <span v-if="value.colorHex" class="swatch-dot" :style="{ background: value.colorHex }" />
                {{ value.value }}
              </button>
              <span v-if="!attribute.values.length" class="tiny">还没有属性值</span>
            </div>
          </article>
        </div>
      </section>
    </template>

    <el-dialog v-model="categoryOpen" :title="editingCategory ? '编辑分类' : '新建分类'" width="440px">
      <div class="form-section">
        <label class="field">
          <span class="field__label">名称</span>
          <input v-model="categoryForm.name" class="input" maxlength="32" />
        </label>
        <label class="field">
          <span class="field__label">标识（小写字母、数字、连字符）</span>
          <input v-model="categoryForm.slug" class="input" maxlength="48" />
        </label>
        <div class="form-two">
          <label class="field">
            <span class="field__label">排序</span>
            <input v-model.number="categoryForm.sort" type="number" class="input" />
          </label>
          <label class="field">
            <span class="field__label">状态</span>
            <select v-model.number="categoryForm.status" class="select">
              <option :value="1">显示</option>
              <option :value="0">隐藏</option>
            </select>
          </label>
        </div>
      </div>
      <div class="dialog-actions">
        <button class="btn btn--ghost" type="button" @click="categoryOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="saving" @click="saveCategory">保存</button>
      </div>
    </el-dialog>

    <el-dialog v-model="attrOpen" :title="editingAttr ? '编辑属性' : '新建属性'" width="480px">
      <div class="form-section">
        <div class="form-two">
          <label class="field">
            <span class="field__label">名称</span>
            <input v-model="attrForm.name" class="input" maxlength="32" />
          </label>
          <label class="field">
            <span class="field__label">编码</span>
            <input v-model="attrForm.code" class="input" maxlength="48" />
          </label>
        </div>
        <div class="form-two">
          <label class="field">
            <span class="field__label">类型</span>
            <select v-model="attrForm.type" class="select">
              <option value="TEXT">TEXT</option>
              <option value="COLOR">COLOR</option>
              <option value="NUMBER">NUMBER</option>
            </select>
          </label>
          <label class="field">
            <span class="field__label">单位（可选）</span>
            <input v-model="attrForm.unit" class="input" maxlength="16" />
          </label>
        </div>
        <div style="display: flex; flex-wrap: wrap; gap: 14px">
          <label class="radio-line"><input v-model="attrForm.filterable" type="checkbox" /> 可筛选</label>
          <label class="radio-line"><input v-model="attrForm.variant" type="checkbox" /> 变体属性</label>
          <label class="radio-line"><input v-model="attrForm.required" type="checkbox" /> 必填</label>
          <label class="radio-line"><input v-model="attrForm.display" type="checkbox" /> 展示</label>
        </div>
        <div class="form-two">
          <label class="field">
            <span class="field__label">排序</span>
            <input v-model.number="attrForm.sort" type="number" class="input" />
          </label>
          <label class="field">
            <span class="field__label">状态</span>
            <select v-model.number="attrForm.status" class="select">
              <option :value="1">启用</option>
              <option :value="0">停用</option>
            </select>
          </label>
        </div>
      </div>
      <div class="dialog-actions">
        <button class="btn btn--ghost" type="button" @click="attrOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="saving" @click="saveAttribute">保存</button>
      </div>
    </el-dialog>

    <el-dialog v-model="valueOpen" :title="editingValue ? '编辑属性值' : '新增属性值'" width="420px">
      <div class="form-section">
        <label class="field">
          <span class="field__label">属性值</span>
          <input v-model="valueForm.value" class="input" maxlength="64" />
        </label>
        <label class="field">
          <span class="field__label">颜色（可选，如 #2F3033）</span>
          <input v-model="valueForm.colorHex" class="input" maxlength="16" />
        </label>
        <div class="form-two">
          <label class="field">
            <span class="field__label">排序</span>
            <input v-model.number="valueForm.sort" type="number" class="input" />
          </label>
          <label class="field">
            <span class="field__label">状态</span>
            <select v-model.number="valueForm.status" class="select">
              <option :value="1">启用</option>
              <option :value="0">停用</option>
            </select>
          </label>
        </div>
      </div>
      <div class="dialog-actions">
        <button class="btn btn--ghost" type="button" @click="valueOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="saving" @click="saveValue">保存</button>
      </div>
    </el-dialog>
  </div>
</template>
