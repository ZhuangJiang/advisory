<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ApiError } from '@/api/client'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()

const form = reactive({ username: '', password: '' })
const error = ref('')
const submitting = ref(false)

async function submit(): Promise<void> {
  if (!form.username.trim() || !form.password) {
    error.value = '请输入用户名和密码'
    return
  }
  error.value = ''
  submitting.value = true
  try {
    const me = await auth.login(form.username.trim(), form.password)
    ElMessage.success(`欢迎，${me.username}`)
    if (me.mustChangePassword) {
      await router.replace({ name: 'change-password' })
      return
    }
    const redirect = route.query.redirect
    await router.replace(typeof redirect === 'string' ? redirect : { name: 'home' })
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '登录失败，请稍后重试'
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <el-card class="login-card" shadow="always">
      <h1 class="title">内部智能咨询平台</h1>
      <p class="subtitle">使用公司账号登录</p>

      <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" class="error" />

      <el-form label-position="top" @submit.prevent="submit">
        <el-form-item label="用户名">
          <el-input v-model="form.username" autocomplete="username" placeholder="请输入用户名" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input
            v-model="form.password"
            type="password"
            show-password
            autocomplete="current-password"
            placeholder="请输入密码"
            @keyup.enter="submit"
          />
        </el-form-item>
        <el-button type="primary" class="submit" :loading="submitting" @click="submit">
          登录
        </el-button>
      </el-form>
    </el-card>
  </div>
</template>

<style scoped>
.login-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #f5f7fa 0%, #e4ecf7 100%);
}

.login-card {
  width: 380px;
  padding: 8px 12px 20px;
}

.title {
  margin: 8px 0 4px;
  font-size: 22px;
  text-align: center;
}

.subtitle {
  margin: 0 0 20px;
  color: #909399;
  font-size: 13px;
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
