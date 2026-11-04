<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { useAuthStore } from '@/stores/auth'

interface NavItem {
  name: string
  label: string
  adminOnly?: boolean
}

/**
 * The menu is derived from the router, not hard-coded: an entry appears only once its route exists,
 * so the shell works while the pages are still being built (Task 15–18 register the routes) and an
 * administrator-only page never shows up for a USER.
 */
const NAV: NavItem[] = [
  { name: 'chat', label: '对话' },
  { name: 'knowledge-bases', label: '知识库' },
  { name: 'agents', label: 'Agent 配置', adminOnly: true },
  { name: 'providers', label: '提供商与模型', adminOnly: true },
  { name: 'audit', label: '会话审计', adminOnly: true },
  { name: 'users', label: '用户管理', adminOnly: true }
]

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()

const items = computed(() =>
  NAV.filter((item) => router.hasRoute(item.name) && (!item.adminOnly || auth.isAdmin))
)

const active = computed(() => {
  const nav = route.meta.nav as string | undefined
  if (nav) {
    return nav
  }
  return route.name ? String(route.name) : 'home'
})

async function logout(): Promise<void> {
  try {
    await ElMessageBox.confirm('确认退出登录？', '退出', { type: 'warning' })
  } catch {
    return
  }
  await auth.logout()
  await router.replace({ name: 'login' })
}
</script>

<template>
  <el-container class="shell">
    <el-aside width="208px" class="aside">
      <div class="brand">智能咨询平台</div>
      <el-menu :default-active="active" router class="menu">
        <el-menu-item v-for="item in items" :key="item.name" :index="item.name" :route="{ name: item.name }">
          {{ item.label }}
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="header">
        <div class="who">
          <el-tag :type="auth.isAdmin ? 'danger' : 'info'" size="small">
            {{ auth.isAdmin ? '管理员' : '普通用户' }}
          </el-tag>
          <span class="username">{{ auth.user?.username }}</span>
        </div>
        <el-button link type="primary" @click="logout">退出登录</el-button>
      </el-header>

      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.shell {
  height: 100vh;
}

.aside {
  border-right: 1px solid #e4e7ed;
  background: #fafbfc;
  display: flex;
  flex-direction: column;
}

.brand {
  height: 56px;
  line-height: 56px;
  padding: 0 20px;
  font-weight: 600;
  font-size: 15px;
  color: #303133;
  border-bottom: 1px solid #e4e7ed;
}

.menu {
  border-right: none;
  background: transparent;
}

.header {
  height: 56px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid #e4e7ed;
  background: #fff;
}

.who {
  display: flex;
  align-items: center;
  gap: 8px;
}

.username {
  color: #303133;
  font-size: 14px;
}

.main {
  background: #f5f7fa;
  padding: 16px;
}
</style>
