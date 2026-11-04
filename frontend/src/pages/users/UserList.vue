<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { createUser, listUsers, patchUser, type UserRole, type UserSummary } from '@/api/users'

/**
 * User administration (spec §8, §13).
 *
 * Two consequences of the server rules are surfaced in the UI instead of being silently applied:
 * creating a user or resetting a password sets `mustChangePassword`, so the person is forced through
 * the change-password screen on first login; disabling revokes every refresh token, so the account
 * drops out at its next token refresh. Both are stated in the confirmation/toast, because an
 * administrator who expects "they can still finish what they are doing" needs to know otherwise.
 */
const rows = ref<UserSummary[]>([])
const total = ref(0)
const page = ref(0)
const size = ref(20)
const loading = ref(false)

const createOpen = ref(false)
const createForm = reactive<{ username: string; password: string; role: UserRole }>({
  username: '',
  password: '',
  role: 'USER'
})
const creating = ref(false)

const resetOpen = ref(false)
const resetTarget = ref<UserSummary | null>(null)
const resetPassword = ref('')
const resetting = ref(false)

onMounted(load)

async function load(): Promise<void> {
  loading.value = true
  try {
    const result = await listUsers(page.value, size.value)
    rows.value = result.content
    total.value = result.total
  } catch {
    ElMessage.error('加载用户列表失败')
  } finally {
    loading.value = false
  }
}

function openCreate(): void {
  createForm.username = ''
  createForm.password = ''
  createForm.role = 'USER'
  createOpen.value = true
}

async function submitCreate(): Promise<void> {
  if (!createForm.username.trim() || !createForm.password) {
    ElMessage.warning('用户名与初始密码都不能为空')
    return
  }
  creating.value = true
  try {
    await createUser({
      username: createForm.username.trim(),
      password: createForm.password,
      role: createForm.role
    })
    createOpen.value = false
    ElMessage.success('已创建，该用户首次登录时须修改密码')
    await load()
  } catch {
    ElMessage.error('创建失败：用户名可能已存在')
  } finally {
    creating.value = false
  }
}

async function toggleEnabled(user: UserSummary): Promise<void> {
  const next = !user.enabled
  try {
    await ElMessageBox.confirm(
      next
        ? `确认启用 ${user.username}？`
        : `确认禁用 ${user.username}？禁用后该用户已下线，refresh token 立即失效。`,
      next ? '启用用户' : '禁用用户',
      { type: 'warning' }
    )
  } catch {
    return
  }
  try {
    await patchUser(user.id, { enabled: next })
    ElMessage.success(next ? '已启用' : '该用户已下线，refresh token 已失效')
    await load()
  } catch {
    ElMessage.error('操作失败')
  }
}

function openReset(user: UserSummary): void {
  resetTarget.value = user
  resetPassword.value = ''
  resetOpen.value = true
}

async function submitReset(): Promise<void> {
  const target = resetTarget.value
  if (!target) {
    return
  }
  if (!resetPassword.value) {
    ElMessage.warning('请输入新的初始密码')
    return
  }
  resetting.value = true
  try {
    await patchUser(target.id, { password: resetPassword.value })
    resetOpen.value = false
    ElMessage.success('密码已重置，该用户下次登录须修改密码')
    await load()
  } catch {
    ElMessage.error('重置失败')
  } finally {
    resetting.value = false
  }
}
</script>

<template>
  <el-card shadow="never">
    <div class="head">
      <span class="title">用户</span>
      <el-button type="primary" data-test="user-create" @click="openCreate">新建用户</el-button>
    </div>

    <el-table v-loading="loading" :data="rows" row-key="id" data-test="user-table">
      <el-table-column prop="username" label="用户名" min-width="160" />
      <el-table-column label="角色" width="120">
        <template #default="{ row }">
          <el-tag :type="row.role === 'ADMIN' ? 'danger' : 'info'" size="small">
            {{ row.role === 'ADMIN' ? '管理员' : '普通用户' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="120">
        <template #default="{ row }">
          <el-tag :type="row.enabled ? 'success' : 'info'" size="small">
            {{ row.enabled ? '启用' : '已禁用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="密码" width="160">
        <template #default="{ row }">
          <span v-if="row.mustChangePassword" class="warn">待首次修改</span>
          <span v-else class="muted">已设置</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="220">
        <template #default="{ row }">
          <el-button link type="primary" @click="openReset(row)">重置密码</el-button>
          <el-button link :type="row.enabled ? 'danger' : 'success'" @click="toggleEnabled(row)">
            {{ row.enabled ? '禁用' : '启用' }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="pager">
      <el-pagination
        layout="total, prev, pager, next"
        :total="total"
        :page-size="size"
        :current-page="page + 1"
        @current-change="(value: number) => { page = value - 1; load() }"
      />
    </div>

    <el-dialog v-model="createOpen" title="新建用户" width="420px">
      <el-form label-width="88px">
        <el-form-item label="用户名">
          <el-input v-model="createForm.username" data-test="user-name" />
        </el-form-item>
        <el-form-item label="初始密码">
          <el-input v-model="createForm.password" type="password" show-password data-test="user-password" />
        </el-form-item>
        <el-form-item label="角色">
          <el-select v-model="createForm.role">
            <el-option label="普通用户" value="USER" />
            <el-option label="管理员" value="ADMIN" />
          </el-select>
        </el-form-item>
      </el-form>
      <p class="hint">新建用户首次登录时会被强制修改密码。</p>
      <template #footer>
        <el-button @click="createOpen = false">取消</el-button>
        <el-button type="primary" :loading="creating" data-test="user-create-submit" @click="submitCreate">
          创建
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="resetOpen" :title="`重置密码 · ${resetTarget?.username ?? ''}`" width="420px">
      <el-input v-model="resetPassword" type="password" show-password placeholder="新的初始密码" />
      <p class="hint">重置后该用户全部 refresh token 失效，下次登录须修改密码。</p>
      <template #footer>
        <el-button @click="resetOpen = false">取消</el-button>
        <el-button type="primary" :loading="resetting" @click="submitReset">确认重置</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<style scoped>
.head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.title {
  font-size: 14px;
  font-weight: 600;
  color: #303133;
}

.warn {
  color: #e6a23c;
  font-size: 13px;
}

.muted {
  color: #909399;
  font-size: 13px;
}

.hint {
  margin: 8px 0 0;
  font-size: 12px;
  color: #909399;
}

.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}
</style>
