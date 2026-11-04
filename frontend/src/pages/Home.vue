<script setup lang="ts">
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
</script>

<template>
  <el-card shadow="never">
    <h2 class="title">欢迎，{{ auth.user?.username }}</h2>
    <p class="hint">
      从「对话」开始提问；知识库、Agent 配置、会话审计与用户管理在左侧导航中。
      管理员可审计全部会话，但只能只读查看他人会话。
    </p>
    <el-descriptions :column="2" border class="details">
      <el-descriptions-item label="角色">
        {{ auth.isAdmin ? '管理员' : '普通用户' }}
      </el-descriptions-item>
      <el-descriptions-item label="账号">{{ auth.user?.username }}</el-descriptions-item>
    </el-descriptions>
  </el-card>
</template>

<style scoped>
.title {
  margin: 0 0 8px;
  font-size: 18px;
}

.hint {
  margin: 0 0 16px;
  color: #909399;
  font-size: 13px;
}

.details {
  max-width: 520px;
}
</style>
