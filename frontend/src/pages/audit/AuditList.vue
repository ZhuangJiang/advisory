<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { listAgents, type Agent } from '@/api/agents'
import {
  defaultRange,
  getAuditConversation,
  listAuditConversations,
  type AuditConversationDetail,
  type AuditConversationRow
} from '@/api/audit'
import { toChatMessage, type ChatMessage } from '@/stores/chat'
import type { Citation } from '@/api/chat'
import MessageBubble from '@/components/MessageBubble.vue'
import ReasoningPanel from '@/components/ReasoningPanel.vue'
import CitationDrawer from '@/components/CitationDrawer.vue'

/**
 * Administrator audit list (spec §8, §13).
 *
 * Everything here is read-only by design — the audit module has no write endpoint — so the page is a
 * filter, a table and a drawer that reuses the chat components. The reason the detail is a drawer
 * rather than a route is that an administrator usually compares threads: keeping the filtered list
 * on screen behind the drawer is the point.
 *
 * The date range defaults to the last seven days and is sent as `YYYY-MM-DD`; the backend interprets
 * a date-only `to` as the whole day, so the picker does not need to add a time component.
 */
const agents = ref<Agent[]>([])
const rows = ref<AuditConversationRow[]>([])
const total = ref(0)
const page = ref(0)
const size = ref(20)
const loading = ref(false)

const [defaultFrom, defaultTo] = defaultRange()
const userId = ref<number | null>(null)
const agentId = ref<number | null>(null)
const range = ref<[string, string] | null>([defaultFrom, defaultTo])

const drawerOpen = ref(false)
const detail = ref<AuditConversationDetail | null>(null)
const detailLoading = ref(false)
const focusedIndex = ref(0)
const citationOpen = ref(false)
const activeCitation = ref<Citation | null>(null)
const activeCitationIndex = ref(0)

const messages = computed<ChatMessage[]>(() =>
  (detail.value?.messages ?? []).map((message) => toChatMessage(message, `audit-${message.id}`))
)

const focused = computed<ChatMessage | null>(() => messages.value[focusedIndex.value] ?? null)

const detailTitle = computed(() => {
  const conversation = detail.value?.conversation
  return conversation ? `${conversation.userName} · ${conversation.title || '未命名会话'}` : '会话详情'
})

onMounted(async () => {
  try {
    const agentPage = await listAgents(0, 100)
    agents.value = agentPage.content
  } catch {
    agents.value = []
  }
  await load()
})

async function load(): Promise<void> {
  loading.value = true
  try {
    const result = await listAuditConversations({
      // `.number` leaves an empty string behind when the field is cleared; only real numbers pass.
      userId: typeof userId.value === 'number' && Number.isFinite(userId.value) ? userId.value : null,
      agentId: agentId.value,
      from: range.value?.[0] ?? null,
      to: range.value?.[1] ?? null,
      page: page.value,
      size: size.value
    })
    rows.value = result.content
    total.value = result.total
  } catch {
    ElMessage.error('加载审计列表失败')
  } finally {
    loading.value = false
  }
}

async function search(): Promise<void> {
  page.value = 0
  await load()
}

async function reset(): Promise<void> {
  userId.value = null
  agentId.value = null
  const [from, to] = defaultRange()
  range.value = [from, to]
  page.value = 0
  await load()
}

async function openDetail(row: AuditConversationRow): Promise<void> {
  drawerOpen.value = true
  detailLoading.value = true
  detail.value = null
  focusedIndex.value = 0
  try {
    const loaded = await getAuditConversation(row.id)
    detail.value = loaded
    const lastAnswer = loaded.messages.map((m) => m.role).lastIndexOf('ASSISTANT')
    focusedIndex.value = lastAnswer >= 0 ? lastAnswer : 0
  } catch {
    ElMessage.error('加载会话详情失败')
    drawerOpen.value = false
  } finally {
    detailLoading.value = false
  }
}

function openCitation(message: ChatMessage, index: number): void {
  const citation = message.citations[index]
  if (!citation) {
    return
  }
  activeCitation.value = citation
  activeCitationIndex.value = index
  citationOpen.value = true
}
</script>

<template>
  <div class="audit">
    <el-card shadow="never" class="filters">
      <div class="filter-row">
        <span class="label">用户 ID</span>
        <el-input
          v-model.number="userId"
          placeholder="全部用户"
          clearable
          class="w-140"
          data-test="filter-user"
        />
        <span class="label">Agent</span>
        <el-select v-model="agentId" placeholder="全部 Agent" clearable class="w-200" data-test="filter-agent">
          <el-option v-for="agent in agents" :key="agent.id" :label="agent.name" :value="agent.id" />
        </el-select>
        <span class="label">时间</span>
        <el-date-picker
          v-model="range"
          type="daterange"
          value-format="YYYY-MM-DD"
          start-placeholder="开始日期"
          end-placeholder="结束日期"
          class="w-260"
          data-test="filter-range"
        />
        <el-button type="primary" data-test="audit-search" @click="search">查询</el-button>
        <el-button @click="reset">重置</el-button>
      </div>
    </el-card>

    <el-card shadow="never">
      <el-table
        v-loading="loading"
        :data="rows"
        row-key="id"
        class="table"
        data-test="audit-table"
        @row-click="openDetail"
      >
        <el-table-column prop="userName" label="用户" width="140" />
        <el-table-column prop="agentName" label="Agent" min-width="140" />
        <el-table-column label="版本" width="80">
          <template #default="{ row }">v{{ row.agentVersion }}</template>
        </el-table-column>
        <el-table-column prop="title" label="会话" min-width="180">
          <template #default="{ row }">{{ row.title || '未命名会话' }}</template>
        </el-table-column>
        <el-table-column prop="messageCount" label="消息数" width="90" />
        <el-table-column label="创建时间" width="180">
          <template #default="{ row }">{{ new Date(row.createdAt).toLocaleString() }}</template>
        </el-table-column>
        <el-table-column label="最近消息" width="180">
          <template #default="{ row }">
            {{ row.lastMessageAt ? new Date(row.lastMessageAt).toLocaleString() : '—' }}
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
    </el-card>

    <el-drawer v-model="drawerOpen" :title="detailTitle" size="980px">
      <div v-loading="detailLoading" class="detail">
        <div v-if="detail" class="detail-messages">
          <div class="detail-head">
            {{ detail.conversation.agentName }} · v{{ detail.conversation.agentVersion }} ·
            {{ detail.conversation.messageCount }} 条消息
          </div>
          <div
            v-for="(message, index) in messages"
            :key="message.key"
            class="detail-message"
            :class="{ focused: index === focusedIndex && message.role === 'ASSISTANT' }"
            @click="message.role === 'ASSISTANT' && (focusedIndex = index)"
          >
            <MessageBubble
              :message="message"
              @cite="(citationIndex: number) => openCitation(message, citationIndex)"
            />
          </div>
        </div>
        <div class="detail-panel">
          <ReasoningPanel
            :steps="focused?.steps ?? []"
            :streaming="false"
            :token-usage="focused?.tokenUsage ?? null"
            :latency-ms="focused?.latencyMs ?? null"
          />
        </div>
      </div>
    </el-drawer>

    <CitationDrawer v-model="citationOpen" :citation="activeCitation" :index="activeCitationIndex" />
  </div>
</template>

<style scoped>
.audit {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.filter-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.label {
  font-size: 13px;
  color: #606266;
}

.w-140 {
  width: 140px;
}

.w-200 {
  width: 200px;
}

.w-260 {
  width: 260px;
}

.table :deep(.el-table__row) {
  cursor: pointer;
}

.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}

.detail {
  display: flex;
  gap: 16px;
  min-height: 320px;
}

.detail-messages {
  flex: 1;
  min-width: 0;
}

.detail-head {
  font-size: 12px;
  color: #909399;
  margin-bottom: 12px;
}

.detail-message {
  border-radius: 6px;
}

.detail-message.focused {
  background: #f5f7fa;
}

.detail-panel {
  width: 300px;
  border-left: 1px solid #e4e7ed;
  padding-left: 16px;
}
</style>
