<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ApiError } from '@/api/client'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const router = useRouter()

const form = reactive({ oldPassword: '', newPassword: '', confirm: '' })
const error = ref('')
const submitting = ref(false)

const MIN_LENGTH = 8

async function submit(): Promise<void> {
  if (!form.oldPassword || !form.newPassword) {
    error.value = '请填写原密码与新密码'
    return
  }
  if (form.newPassword.length < MIN_LENGTH) {
    error.value = `新密码至少 ${MIN_LENGTH} 位`
    return
  }
  if (form.newPassword !== form.confirm) {
    error.value = '两次输入的新密码不一致'
    return
  }
  error.value = ''
  submitting.value = true
  try {
    await auth.changePassword(form.oldPassword, form.newPassword)
    ElMessage.success('密码已更新')
    await router.replace({ name: 'home' })
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '修改失败，请稍后重试'
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="change-page">
    <el-card class="change-card" shadow="always">
      <h1 class="title">修改密码</h1>
      <p class="subtitle">
        {{ auth.user?.username }}，首次登录需要修改初始密码后才能使用平台。
      </p>

      <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" class="error" />

      <el-form label-position="top" @submit.prevent="submit">
        <el-form-item label="原密码">
          <el-input v-model="form.oldPassword" type="password" show-password autocomplete="current-password" />
        </el-form-item>
        <el-form-item :label="`新密码（至少 ${MIN_LENGTH} 位）`">
          <el-input v-model="form.newPassword" type="password" show-password autocomplete="new-password" />
        </el-form-item>
        <el-form-item label="确认新密码">
          <el-input
            v-model="form.confirm"
            type="password"
            show-password
            autocomplete="new-password"
            @keyup.enter="submit"
          />
        </el-form-item>
        <el-button type="primary" class="submit" :loading="submitting" @click="submit">
          保存并进入
        </el-button>
      </el-form>
    </el-card>
  </div>
</template>

<style scoped>
.change-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #f5f7fa;
}

.change-card {
  width: 420px;
  padding: 8px 12px 20px;
}

.title {
  margin: 8px 0 4px;
  font-size: 20px;
  text-align: center;
}

.subtitle {
  margin: 0 0 20px;
  color: #909399;
  font-size: 13px;
  line-height: 1.5;
  text-align: center;
}

.error {
  margin-bottom: 12px;
}

.submit {
  width: 100%;
  margin-top: 4px;
}
</style>
