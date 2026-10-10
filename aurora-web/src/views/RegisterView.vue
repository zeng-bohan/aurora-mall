<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'

import { userApi } from '@/api'

import { describeError } from '@/utils/errors'

const router = useRouter()

const form = reactive({ username: '', password: '', nickname: '' })
const submitting = ref(false)

async function onSubmit(): Promise<void> {
  // 后端约束：用户名 4-32、密码 6-64。先本地挡一遍，省一次往返和一条红字。
  if (form.username.length < 4 || form.username.length > 32) {
    ElMessage.warning('用户名需 4-32 个字符')
    return
  }
  if (form.password.length < 6 || form.password.length > 64) {
    ElMessage.warning('密码需 6-64 个字符')
    return
  }
  submitting.value = true
  try {
    await userApi.register({
      username: form.username,
      password: form.password,
      // 空昵称不发：后端 @Size(max=64) 允许缺省，但空串会占位
      nickname: form.nickname.trim() || undefined
    })
    ElMessage.success('注册成功，请登录')
    await router.push({ name: 'login' })
  } catch (error) {
    ElMessage.error(describeError(error))
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="aurora-page aurora-auth">
    <el-card class="aurora-auth__card">
      <h2 class="aurora-auth__title">注册</h2>
      <el-form label-position="top" @submit.prevent="onSubmit">
        <el-form-item label="用户名">
          <el-input v-model="form.username" placeholder="4-32 个字符" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input
            v-model="form.password"
            type="password"
            show-password
            placeholder="6-64 个字符"
          />
        </el-form-item>
        <el-form-item label="昵称（可选）">
          <el-input v-model="form.nickname" placeholder="不超过 64 个字符" />
        </el-form-item>
        <el-alert
          type="info"
          :closable="false"
          show-icon
          title="新账号固定为普通用户"
          description="管理员只能由运维在库里指定，注册不出管理员账号。"
          class="aurora-auth__note"
        />
        <el-button type="primary" :loading="submitting" class="aurora-auth__submit" @click="onSubmit">
          注册
        </el-button>
      </el-form>
      <p class="aurora-auth__hint">已有账号？<RouterLink to="/login">去登录</RouterLink></p>
    </el-card>
  </div>
</template>

<style scoped>
.aurora-auth {
  display: flex;
  justify-content: center;
  padding-top: 64px;
}

.aurora-auth__card {
  width: 380px;
}

.aurora-auth__title {
  margin: 0 0 16px;
  font-size: 20px;
}

.aurora-auth__note {
  margin-bottom: 16px;
}

.aurora-auth__submit {
  width: 100%;
}

.aurora-auth__hint {
  margin: 16px 0 0;
  font-size: 13px;
  color: #909399;
  text-align: center;
}
</style>
