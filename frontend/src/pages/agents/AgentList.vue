<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { deleteAgent, listAgents, type Agent } from '@/api/agents'
import { describeKnowledgeError } from '@/api/knowledge'
import AgentTestDrawer from '@/components/AgentTestDrawer.vue'

/**
 * Agent list. Readable by every signed-in user (a USER needs to know which agents exist before
 * starting a conversation); write actions are ADMIN-only and the backend enforces the same rule.
 */
const router = useRouter()

const rows = ref<Agent[]>([])
const total = ref(0)
const page = ref(0)
const size = 10
const loading = ref(false)
const error = ref('')

const drawerVisible = ref(false)
const testAgentId = ref<number | null>(null)
const testAgentName = ref('')

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const result = await listAgents(page.value, size)
    rows.value = result.content
    total.value = result.total
  } catch (e) {
    error.value = describeKnowledgeError(e)
    rows.value = []
  } finally {
    loading.value = false
  }
}

onMounted(load)

function create(): void {
  void router.push({ name: 'agent-new' })
}

function edit(row: Agent): void {
  void router.push({ name: 'agent-edit', params: { id: row.id } })
}

function test(row: Agent): void {
  testAgentId.value = row.id
  testAgentName.value = row.name
  drawerVisible.value = true
}

async function remove(row: Agent): Promise<void> {
  try {
    await ElMessageBox.confirm(`确认删除 Agent「${row.name}」？`, '删除 Agent', { type: 'warning' })
  } catch {
    return
  }
  try {
    await deleteAgent(row.id)
    ElMessage.success('已删除')
    await load()
  } catch (e) {
    ElMessage.error(describeKnowledgeError(e))
  }
}

function formatTime(value: string): string {
  return new Date(value).toLocaleString('zh-CN', { hour12: false })
}
</script>

<template>
  <el-card shadow="never">
    <div class="toolbar">
      <div>
        <h2 class="title">Agent 配置</h2>
        <p class="hint">每次保存都会生成新版本；已经开始的会话仍按启动时的版本复现。</p>
      </div>
      <el-button type="primary" data-test="agent-create" @click="create">新建 Agent</el-button>
    </div>

    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon class="alert" />

    <el-table v-loading="loading" :data="rows" empty-text="还没有 Agent">
      <el-table-column label="名称" min-width="180">
        <template #default="{ row }">
          <el-link type="primary" @click="edit(row)">{{ row.name }}</el-link>
          <div v-if="row.description" class="description">{{ row.description }}</div>
        </template>
      </el-table-column>
      <el-table-column label="当前版本" width="100">
        <template #default="{ row }">v{{ row.currentVersion }}</template>
      </el-table-column>
      <el-table-column label="关联库" width="96">
        <template #default="{ row }">{{ row.kbIds.length }}</template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.enabled ? 'success' : 'info'" size="small">
            {{ row.enabled ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="更新时间" width="170">
        <template #default="{ row }">{{ formatTime(row.updatedAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="180" fixed="right">
        <template #default="{ row }">
          <el-button link size="small" data-test="agent-edit" @click="edit(row)">编辑</el-button>
          <el-button link size="small" data-test="agent-test" @click="test(row)">试运行</el-button>
          <el-button link size="small" type="danger" data-test="agent-delete" @click="remove(row)">
            删除
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      v-if="total > size"
      v-model:current-page="page"
      :page-size="size"
      :total="total"
      layout="prev, pager, next"
      class="pager"
      @current-change="load"
    />

    <AgentTestDrawer
      v-model="drawerVisible"
      :agent-id="testAgentId"
      :agent-name="testAgentName"
    />
  </el-card>
</template>

<style scoped>
.toolbar {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: 12px;
  gap: 16px;
}

.title {
  margin: 0 0 4px;
  font-size: 18px;
}

.hint {
  margin: 0;
  color: #909399;
  font-size: 13px;
}

.description {
  color: #909399;
  font-size: 12px;
  margin-top: 2px;
}

.alert {
  margin-bottom: 12px;
}

.pager {
  margin-top: 12px;
  justify-content: flex-end;
}
</style>
