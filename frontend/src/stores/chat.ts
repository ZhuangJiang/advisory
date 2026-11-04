import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { ApiError } from '@/api/client'
import {
  ask as askStream,
  createConversation,
  getMessage,
  listConversations,
  listMessages,
  type Citation,
  type Conversation,
  type MessageDetail,
  type MessageRole,
  type MessageStatus,
  type Phase,
  type TokenUsage
} from '@/api/chat'

/**
 * Chat state for one open thread plus the reasoning trace of the answer being read.
 *
 * The store exists because a stream and a record have to end up as the same object: frames arrive
 * incrementally (`step`, `delta`, `citations`) while the database holds the authoritative copy. The
 * rule is that the live frames only ever *fill in* the placeholder created by {@link send}, and
 * {@link restore} overwrites that placeholder from `GET /messages/{id}` when the connection died
 * before `done` — the plan's reconnect path, not a client-side replay.
 *
 * Steps are kept as a view model (`ReasoningStepView`) rather than the persisted record: the live
 * frame has no latency and the persisted one has no "started" state, and the panel needs both
 * shapes side by side.
 */

export interface ReasoningStepView {
  round: number
  phase: Phase
  status: 'started' | 'finished'
  summary: string | null
  latencyMs: number | null
}

export interface ChatMessage {
  /** Stable local key: `id` is null until `meta` arrives, and list rendering needs a key now. */
  key: string
  id: number | null
  role: MessageRole
  status: MessageStatus
  content: string
  citations: Citation[]
  steps: ReasoningStepView[]
  /** `true` once steps/citations were fetched from the database (or produced by a live run). */
  detailLoaded: boolean
  tokenUsage: TokenUsage | null
  latencyMs: number | null
  createdAt: string | null
}

let keySeq = 0
function nextKey(): string {
  keySeq += 1
  return `local-${keySeq}`
}

function fromDetail(detail: MessageDetail): Partial<ChatMessage> {
  return {
    status: detail.status,
    content: detail.content ?? '',
    citations: detail.citations ?? [],
    steps: (detail.steps ?? []).map((step) => ({
      round: step.round,
      phase: step.phase,
      status: 'finished' as const,
      summary: step.summary,
      latencyMs: step.latencyMs
    })),
    detailLoaded: true,
    tokenUsage: detail.tokenUsage,
    latencyMs: detail.latencyMs,
    createdAt: detail.createdAt
  }
}

export const useChatStore = defineStore('chat', () => {
  const conversations = ref<Conversation[]>([])
  const activeId = ref<number | null>(null)
  const messages = ref<ChatMessage[]>([])
  const streaming = ref(false)
  const loading = ref(false)
  const error = ref('')
  /** Which assistant message the right-hand panel is showing; null = the latest one. */
  const focusedKey = ref<string | null>(null)

  let controller: AbortController | null = null

  const activeConversation = computed(
    () => conversations.value.find((item) => item.id === activeId.value) ?? null
  )

  const focusedMessage = computed<ChatMessage | null>(() => {
    if (focusedKey.value) {
      const explicit = messages.value.find((message) => message.key === focusedKey.value)
      if (explicit) {
        return explicit
      }
    }
    for (let i = messages.value.length - 1; i >= 0; i -= 1) {
      if (messages.value[i].role === 'ASSISTANT') {
        return messages.value[i]
      }
    }
    return null
  })

  function find(key: string): ChatMessage | undefined {
    return messages.value.find((message) => message.key === key)
  }

  function mergeStep(message: ChatMessage, incoming: ReasoningStepView): void {
    const existing = message.steps.find(
      (step) => step.round === incoming.round && step.phase === incoming.phase
    )
    if (existing) {
      existing.status = incoming.status
      existing.summary = incoming.summary ?? existing.summary
      existing.latencyMs = incoming.latencyMs ?? existing.latencyMs
      return
    }
    message.steps.push({ ...incoming })
  }

  async function loadConversations(): Promise<void> {
    const page = await listConversations()
    conversations.value = page.content
  }

  /** Loads a thread, then the full record of its last answer so the panel is never empty. */
  async function openConversation(id: number): Promise<void> {
    stop()
    activeId.value = id
    focusedKey.value = null
    error.value = ''
    loading.value = true
    try {
      const rows = await listMessages(id)
      messages.value = rows.map((row) => ({
        key: `saved-${row.id}`,
        id: row.id,
        role: row.role,
        status: row.status,
        content: row.content ?? '',
        citations: [],
        steps: [],
        detailLoaded: false,
        tokenUsage: null,
        latencyMs: null,
        createdAt: row.createdAt
      }))
      const lastAnswer = [...messages.value].reverse().find((message) => message.role === 'ASSISTANT')
      if (lastAnswer) {
        focusedKey.value = lastAnswer.key
        await loadDetail(lastAnswer.key)
      }
    } finally {
      loading.value = false
    }
  }

  /** Fetches steps and citations of one message; a no-op once they are already local. */
  async function loadDetail(key: string): Promise<void> {
    const message = find(key)
    if (!message || message.id === null || message.detailLoaded) {
      return
    }
    try {
      const detail = await getMessage(message.id)
      Object.assign(message, fromDetail(detail))
    } catch (e) {
      error.value = e instanceof ApiError ? e.message : '加载推理过程失败'
    }
  }

  async function startConversation(agentId: number): Promise<void> {
    const created = await createConversation(agentId)
    conversations.value = [created, ...conversations.value]
    activeId.value = created.id
    messages.value = []
    focusedKey.value = null
    error.value = ''
  }

  /**
   * Sends one question. The user row and an empty assistant row are inserted immediately so the
   * screen reacts before the first frame; every handler then mutates that same assistant row.
   */
  async function send(content: string): Promise<void> {
    const question = content.trim()
    if (!question || activeId.value === null || streaming.value) {
      return
    }
    const conversationId = activeId.value
    error.value = ''

    messages.value.push({
      key: nextKey(),
      id: null,
      role: 'USER',
      status: 'COMPLETED',
      content: question,
      citations: [],
      steps: [],
      detailLoaded: true,
      tokenUsage: null,
      latencyMs: null,
      createdAt: new Date().toISOString()
    })
    const answer: ChatMessage = {
      key: nextKey(),
      id: null,
      role: 'ASSISTANT',
      status: 'PENDING',
      content: '',
      citations: [],
      steps: [],
      detailLoaded: true,
      tokenUsage: null,
      latencyMs: null,
      createdAt: new Date().toISOString()
    }
    messages.value.push(answer)
    // The array stores a reactive proxy of the object, so the handlers must mutate that proxy —
    // writing to the raw object would change the data without notifying the renderer.
    const target = messages.value[messages.value.length - 1]
    focusedKey.value = answer.key
    streaming.value = true

    const started = askStream(conversationId, question, {
      onMeta(meta) {
        target.id = meta.messageId
      },
      onStep(step) {
        mergeStep(target, {
          round: step.round,
          phase: step.phase,
          status: step.status,
          summary: step.summary ?? null,
          latencyMs: null
        })
      },
      onDelta(delta) {
        target.content += delta.text
        target.status = 'STREAMING'
      },
      onCitations(payload) {
        target.citations = payload.items.map((item) => ({
          chunkId: item.chunkId,
          documentId: item.documentId,
          docName: item.docName,
          nodeName: item.nodeName,
          content: item.snippet,
          score: item.score
        }))
      },
      onDone(done) {
        target.status = done.status
        target.tokenUsage = done.tokenUsage ?? null
        target.latencyMs = done.latencyMs ?? null
        streaming.value = false
        controller = null
        touch(conversationId)
      },
      onError(failure) {
        target.status = 'FAILED'
        target.content = target.content || failure.message
        streaming.value = false
        controller = null
        error.value = failure.message
        void recover(target)
      }
    })
    // A run that finished synchronously (or a mock) must not leave a stale abort handle behind.
    controller = streaming.value ? started : null
  }

  /**
   * After a broken connection the local answer is a prefix of the truth. `GET /messages/{id}` is
   * authoritative, so it replaces the placeholder — unless the run is still going (PENDING with no
   * content yet), in which case the row is left alone and the caller can reload later.
   */
  async function recover(message: ChatMessage): Promise<void> {
    if (message.id === null) {
      return
    }
    try {
      const detail = await getMessage(message.id)
      Object.assign(message, fromDetail(detail))
      if (detail.status === 'PENDING' || detail.status === 'STREAMING') {
        error.value = '回答仍在生成，可稍后刷新该会话查看完整结果'
      }
    } catch {
      error.value = '连接中断，且未能取回已生成的答案'
    }
  }

  /** Aborts the client side only; the server keeps running and persists the answer. */
  function stop(): void {
    if (controller) {
      controller.abort()
      controller = null
    }
    streaming.value = false
  }

  function focus(key: string): void {
    focusedKey.value = key
    void loadDetail(key)
  }

  function touch(conversationId: number): void {
    const index = conversations.value.findIndex((item) => item.id === conversationId)
    if (index < 0) {
      return
    }
    const [conversation] = conversations.value.splice(index, 1)
    conversations.value = [{ ...conversation, updatedAt: new Date().toISOString() }, ...conversations.value]
  }

  function reset(): void {
    stop()
    conversations.value = []
    activeId.value = null
    messages.value = []
    focusedKey.value = null
    error.value = ''
  }

  return {
    conversations,
    activeId,
    messages,
    streaming,
    loading,
    error,
    focusedKey,
    activeConversation,
    focusedMessage,
    loadConversations,
    openConversation,
    loadDetail,
    startConversation,
    send,
    stop,
    focus,
    reset
  }
})
