<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { listAgents, type Agent } from '@/api/agents'
import { useChatStore, type ChatMessage } from '@/stores/chat'
import { useAuthStore } from '@/stores/auth'
import type { Citation } from '@/api/chat'
import MessageBubble from '@/components/MessageBubble.vue'
import ReasoningPanel from '@/components/ReasoningPanel.vue'
import CitationDrawer from '@/components/CitationDrawer.vue'

/**
 * The chat page: threads on the left, the conversation in the middle, the reasoning trace of the
 * answer being read on the right.
 *
 * The middle column is optimistic — {@link useChatStore.send} inserts the question and an empty
 * answer before the first frame — while the right column is authoritative: it renders the steps the
 * store has for the focused answer, whether they arrived live or were read back through
 * `GET /messages/{id}`. An interrupted run therefore never leaves an empty panel.
 *
 * An administrator's thread list is unfiltered (spec §8), so a thread owned by somebody else opens
 * read-only: the composer is disabled because the server refuses to answer on another user's
 * behalf. Reading is the whole point of that view.
 */
const store = useChatStore()
const auth = useAuthStore()

const agents = ref<Agent[]>([])
const draft = ref('')
const panelOpen = ref(true)
const pickerOpen = ref(false)
const pickedAgentId = ref<number | null>(null)
const drawerOpen = ref(false)
const activeCitation = ref<Citation | null>(null)
const activeCitationIndex = ref(0)
const scroller = ref<HTMLElement | null>(null)

const enabledAgents = computed(() => agents.value.filter((agent) => agent.enabled))

const focused = computed(() => store.focusedMessage)

/** Administrator looking at someone else's thread: readable, never answerable. */
const readOnly = computed(() => {
  const active = store.activeConversation
  return active !== null && auth.user !== null && active.userId !== auth.user.id
})

onMounted(async () => {
  try {
    const page = await listAgents(0, 50)
    agents.value = page.content
  } catch {
    agents.value = []
  }
  try {
    await store.loadConversations()
    if (store.activeId === null && store.conversations.length > 0) {
      await store.openConversation(store.conversations[0].id)
    }
  } catch {
    // the interceptor already surfaced the failure; an empty sidebar is the honest state
  }
})

watch(
  () => [store.messages.length, store.messages[store.messages.length - 1]?.content],
  async () => {
    await nextTick()
    if (scroller.value) {
      scroller.value.scrollTop = scroller.value.scrollHeight
    }
  }
)

async function select(id: number): Promise<void> {
  if (id === store.activeId) {
    return
  }
  try {
    await store.openConversation(id)
  } catch {
    ElMessage.error('加载会话失败')
  }
}

function openPicker(): void {
  pickedAgentId.value = enabledAgents.value[0]?.id ?? null
  pickerOpen.value = true
}

async function createThread(): Promise<void> {
  if (pickedAgentId.value === null) {
    ElMessage.warning('请选择一个已启用的 Agent')
    return
  }
  try {
    await store.startConversation(pickedAgentId.value)
    pickerOpen.value = false
  } catch {
    ElMessage.error('创建会话失败')
  }
}

async function send(): Promise<void> {
  const question = draft.value.trim()
  if (!question || readOnly.value) {
    return
  }
  if (store.activeId === null) {
    ElMessage.warning('请先新建一个会话')
    return
  }
  draft.value = ''
  await store.send(question)
}

function openCitation(message: ChatMessage, index: number): void {
  const citation = message.citations[index]
  if (!citation) {
    return
  }
  activeCitation.value = citation
  activeCitationIndex.value = index
  drawerOpen.value = true
}
</script>

<template>
  <div class="chat">
    <aside class="threads">
      <div class="threads-head">
        <span class="threads-title">会话</span>
        <el-button type="primary" size="small" data-test="new-conversation" @click="openPicker">
          新对话
        </el-button>
      </div>
      <div class="thread-list">
        <div
          v-for="conversation in store.conversations"
          :key="conversation.id"
          class="thread-item"
          :class="{ active: conversation.id === store.activeId }"
          @click="select(conversation.id)"
        >
          <div class="thread-name">{{ conversation.title || '未命名会话' }}</div>
          <div class="thread-meta">
            {{ conversation.agentName }} · v{{ conversation.agentVersion }}
          </div>
        </div>
        <div v-if="store.conversations.length === 0" class="thread-empty">还没有会话</div>
      </div>
    </aside>

    <section class="thread">
      <header class="thread-head">
        <div v-if="store.activeConversation" class="thread-title">
          {{ store.activeConversation.agentName }}
          <el-tag size="small" type="info">v{{ store.activeConversation.agentVersion }}</el-tag>
          <el-tag v-if="readOnly" size="small" type="warning" data-test="read-only">只读</el-tag>
        </div>
        <div v-else class="thread-title">对话</div>
        <div class="thread-actions">
          <el-button v-if="store.streaming" size="small" @click="store.stop()">中断</el-button>
          <el-button link type="primary" size="small" @click="panelOpen = !panelOpen">
            {{ panelOpen ? '收起推理' : '展开推理' }}
          </el-button>
        </div>
      </header>

      <div ref="scroller" class="messages">
        <el-empty
          v-if="store.messages.length === 0"
          description="新建会话后，向 Agent 提问即可看到答案与推理过程"
        />
        <MessageBubble
          v-for="message in store.messages"
          :key="message.key"
          :message="message"
          @cite="(index: number) => openCitation(message, index)"
          @reasoning="store.focus(message.key)"
        />
      </div>

      <el-alert
        v-if="store.error"
        :title="store.error"
        type="error"
        :closable="false"
        show-icon
        class="error"
      />

      <div class="composer">
        <el-input
          v-model="draft"
          type="textarea"
          :rows="3"
          maxlength="8000"
          show-word-limit
          resize="none"
          :disabled="readOnly"
          :placeholder="
            readOnly
              ? '这是其他用户的会话，管理员只能查看'
              : '输入问题，Enter 发送，Shift + Enter 换行'
          "
          data-test="question"
          @keydown.enter.exact.prevent="send"
        />
        <el-button
          type="primary"
          :loading="store.streaming"
          :disabled="store.activeId === null || readOnly"
          data-test="send"
          @click="send"
        >
          发送
        </el-button>
      </div>
    </section>

    <aside v-if="panelOpen" class="panel">
      <ReasoningPanel
        :steps="focused?.steps ?? []"
        :streaming="store.streaming"
        :token-usage="focused?.tokenUsage ?? null"
        :latency-ms="focused?.latencyMs ?? null"
      />
    </aside>

    <CitationDrawer
      v-model="drawerOpen"
      :citation="activeCitation"
      :index="activeCitationIndex"
    />

    <el-dialog v-model="pickerOpen" title="新建会话" width="420px">
      <el-select v-model="pickedAgentId" placeholder="选择 Agent" class="picker" data-test="agent-picker">
        <el-option
          v-for="agent in enabledAgents"
          :key="agent.id"
          :label="`${agent.name}（v${agent.currentVersion}）`"
          :value="agent.id"
        />
      </el-select>
      <p class="hint">会话会锁定所选 Agent 当前版本，之后修改 Agent 不影响已有会话。</p>
      <template #footer>
        <el-button @click="pickerOpen = false">取消</el-button>
        <el-button type="primary" data-test="create-conversation" @click="createThread">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.chat {
  display: flex;
  height: calc(100vh - 88px);
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  overflow: hidden;
}

.threads {
  width: 220px;
  border-right: 1px solid #e4e7ed;
  display: flex;
  flex-direction: column;
  background: #fafbfc;
}

.threads-head {
  height: 48px;
  padding: 0 12px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid #e4e7ed;
}

.threads-title {
  font-size: 13px;
  font-weight: 600;
  color: #303133;
}

.thread-list {
  flex: 1;
  overflow-y: auto;
}

.thread-item {
  padding: 10px 12px;
  border-bottom: 1px solid #f0f2f5;
  cursor: pointer;
}

.thread-item:hover {
  background: #f0f2f5;
}

.thread-item.active {
  background: #ecf5ff;
}

.thread-name {
  font-size: 13px;
  color: #303133;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.thread-meta {
  margin-top: 2px;
  font-size: 12px;
  color: #909399;
}

.thread-empty {
  padding: 16px 12px;
  font-size: 12px;
  color: #c0c4cc;
}

.thread {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.thread-head {
  height: 48px;
  padding: 0 16px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid #e4e7ed;
}

.thread-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  font-weight: 600;
  color: #303133;
}

.thread-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.messages {
  flex: 1;
  overflow-y: auto;
  padding: 16px;
  background: #f5f7fa;
}

.error {
  margin: 0 16px 8px;
}

.composer {
  display: flex;
  align-items: flex-end;
  gap: 8px;
  padding: 12px 16px;
  border-top: 1px solid #e4e7ed;
}

.composer :deep(.el-textarea) {
  flex: 1;
}

.panel {
  width: 300px;
  border-left: 1px solid #e4e7ed;
  padding: 12px;
  overflow-y: auto;
}

.picker {
  width: 100%;
}

.hint {
  margin: 8px 0 0;
  font-size: 12px;
  color: #909399;
}
</style>
