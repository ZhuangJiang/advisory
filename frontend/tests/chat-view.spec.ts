import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ElementPlus from 'element-plus'
import { ElOption } from 'element-plus'
import type { Citation, MessageDetail, MessageSummary } from '../src/api/chat'

/**
 * The chat page is the only place where a live stream and the persisted record have to agree.
 *
 * Three things are pinned here:
 *  1. the reasoning panel groups by round — the orchestrator's loop (PLAN once, then
 *     RETRIEVE → REFLECT per round) is invisible in a flat list;
 *  2. a `TRUNCATED` answer is shown with a banner, never as a silent success;
 *  3. the store turns `delta` / `step` / `citations` frames into exactly one assistant message, so
 *     a partial stream is still readable.
 */

const conversation = {
  id: 11,
  agentId: 5,
  agentName: '知识助手',
  agentVersion: 3,
  userId: 1,
  title: '报销流程',
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z'
}

const summaries: MessageSummary[] = [
  {
    id: 1,
    conversationId: 11,
    role: 'USER',
    status: 'COMPLETED',
    content: '报销需要哪些材料？',
    citationCount: 0,
    createdAt: '2026-01-01T00:00:00Z'
  },
  {
    id: 2,
    conversationId: 11,
    role: 'ASSISTANT',
    status: 'TRUNCATED',
    content: '需要发票原件与审批单[1]。',
    citationCount: 1,
    createdAt: '2026-01-01T00:00:05Z'
  }
]

const citations: Citation[] = [
  {
    chunkId: 7,
    documentId: 3,
    kbId: 1,
    nodeId: null,
    docName: '报销制度.pdf',
    nodeName: '第三章 报销材料',
    content: '报销需提供发票原件、审批单与费用明细。',
    score: 0.87
  },
  {
    chunkId: 8,
    documentId: 3,
    kbId: 1,
    nodeId: null,
    docName: '报销制度.pdf',
    nodeName: '第四章 附件',
    content: '费用明细需逐条列明金额。',
    score: 0.72
  }
]

const detail: MessageDetail = {
  id: 2,
  conversationId: 11,
  role: 'ASSISTANT',
  status: 'TRUNCATED',
  content: '需要发票原件与审批单[1]。',
  citations,
  steps: [
    { round: 1, phase: 'PLAN', summary: '拆解为 2 个查询', payload: '{}', latencyMs: 120, createdAt: '2026-01-01T00:00:01Z' },
    { round: 1, phase: 'RETRIEVE', summary: '召回 3 个片段', payload: '{}', latencyMs: 260, createdAt: '2026-01-01T00:00:02Z' },
    { round: 1, phase: 'REFLECT', summary: '缺少审批单证据', payload: '{}', latencyMs: 180, createdAt: '2026-01-01T00:00:03Z' },
    { round: 2, phase: 'RETRIEVE', summary: '补充召回 1 个片段', payload: '{}', latencyMs: 150, createdAt: '2026-01-01T00:00:04Z' },
    { round: 2, phase: 'ANSWER', summary: '已生成', payload: '{}', latencyMs: 300, createdAt: '2026-01-01T00:00:05Z' }
  ],
  tokenUsage: { promptTokens: 0, completionTokens: 0 },
  latencyMs: 1010,
  createdAt: '2026-01-01T00:00:05Z'
}

vi.mock('../src/api/chat', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../src/api/chat')>()
  return {
    ...actual,
    listConversations: vi.fn(() =>
      Promise.resolve({ content: [conversation], page: 0, size: 50, total: 1, totalPages: 1 })
    ),
    listMessages: vi.fn(() => Promise.resolve(summaries)),
    getMessage: vi.fn(() => Promise.resolve(detail)),
    createConversation: vi.fn(() => Promise.resolve(conversation)),
    ask: vi.fn(() => new AbortController())
  }
})

vi.mock('../src/api/agents', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../src/api/agents')>()
  return {
    ...actual,
    listAgents: vi.fn(() =>
      Promise.resolve({
        content: [
          {
            id: 5,
            name: '知识助手',
            description: null,
            systemPrompt: '只根据资料回答',
            chatModelId: 9,
            embeddingModelId: 1,
            kbIds: [1],
            nodeScope: {},
            maxRounds: 3,
            topK: 20,
            temperature: 0.3,
            enabled: true,
            currentVersion: 3,
            createdAt: '2026-01-01T00:00:00Z',
            updatedAt: '2026-01-01T00:00:00Z'
          },
          {
            id: 6,
            name: '已下线的助手',
            description: null,
            systemPrompt: '旧的提示词',
            chatModelId: 9,
            embeddingModelId: 1,
            kbIds: [1],
            nodeScope: {},
            maxRounds: 3,
            topK: 20,
            temperature: 0.3,
            enabled: false,
            currentVersion: 1,
            createdAt: '2026-01-01T00:00:00Z',
            updatedAt: '2026-01-01T00:00:00Z'
          }
        ],
        page: 0,
        size: 50,
        total: 2,
        totalPages: 1
      })
    )
  }
})

class ResizeObserverStub {
  observe(): void {}
  unobserve(): void {}
  disconnect(): void {}
}

async function mountChatView(options: { userId?: number } = {}) {
  const pinia = createPinia()
  setActivePinia(pinia)
  if (options.userId !== undefined) {
    const { useAuthStore } = await import('../src/stores/auth')
    useAuthStore().user = {
      id: options.userId,
      username: 'admin',
      role: 'ADMIN',
      mustChangePassword: false
    }
  }
  const module = await import('../src/pages/chat/ChatView.vue')
  const wrapper = mount(module.default, {
    global: { plugins: [ElementPlus, pinia] }
  })
  await flushPromises()
  await flushPromises()
  await flushPromises()
  return wrapper
}

describe('reasoning panel', () => {
  it('groups steps by round and keeps the phase order inside a round', async () => {
    const module = await import('../src/components/ReasoningPanel.vue')
    const wrapper = mount(module.default, {
      props: {
        steps: detail.steps.map((step) => ({
          round: step.round,
          phase: step.phase,
          status: 'finished' as const,
          summary: step.summary,
          latencyMs: step.latencyMs
        })),
        streaming: false
      },
      global: { plugins: [ElementPlus] }
    })

    const rounds = wrapper.findAll('[data-test="round"]')
    expect(rounds.map((round) => round.find('[data-test="round-title"]').text())).toEqual([
      '第 1 轮',
      '第 2 轮'
    ])
    expect(rounds[0].findAll('[data-test="step-phase"]').map((tag) => tag.text())).toEqual([
      'PLAN',
      'RETRIEVE',
      'REFLECT'
    ])
    expect(rounds[1].findAll('[data-test="step-phase"]').map((tag) => tag.text())).toEqual([
      'RETRIEVE',
      'ANSWER'
    ])
  })
})

describe('message bubble', () => {
  it('renders [n] as a clickable citation marker', async () => {
    const module = await import('../src/components/MessageBubble.vue')
    const wrapper = mount(module.default, {
      props: {
        message: {
          key: 'm2',
          id: 2,
          role: 'ASSISTANT',
          status: 'COMPLETED',
          content: '先看制度[1]，再看附件[2]。',
          citations,
          steps: [],
          detailLoaded: true,
          tokenUsage: null,
          latencyMs: null,
          createdAt: null
        }
      },
      global: { plugins: [ElementPlus] }
    })

    const markers = wrapper.findAll('[data-test="cite"]')
    expect(markers.map((marker) => marker.text())).toEqual(['1', '2'])
    await markers[0].trigger('click')
    expect(wrapper.emitted('cite')?.[0]).toEqual([0])
  })
})

describe('chat view', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    globalThis.ResizeObserver = ResizeObserverStub as unknown as typeof ResizeObserver
  })

  it('shows the truncation banner and the persisted reasoning of the latest answer', async () => {
    const wrapper = await mountChatView()

    expect(wrapper.text()).toContain('报销需要哪些材料？')
    expect(wrapper.text()).toContain('需要发票原件与审批单')
    expect(wrapper.text()).toContain('已达最大轮数')
    expect(wrapper.findAll('[data-test="round"]')).toHaveLength(2)
  })

  it('opens another user\'s thread read-only for an administrator', async () => {
    const wrapper = await mountChatView({ userId: 99 })

    expect(wrapper.find('[data-test="read-only"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="send"]').attributes('disabled')).toBeDefined()
    expect(wrapper.find('[data-test="question"]').attributes('disabled')).toBeDefined()
  })

  it('asks the server for online agents only and never offers an offline one', async () => {
    const { listAgents } = await import('../src/api/agents')
    const wrapper = await mountChatView()

    // 过滤必须交给服务端与分页一起做，不能只在本地筛一页
    expect(vi.mocked(listAgents)).toHaveBeenCalledWith(0, 50, true)

    // 对话框是惰性渲染的，先打开再检查选项
    await wrapper.find('[data-test="new-conversation"]').trigger('click')
    await flushPromises()

    const options = wrapper.findAllComponents(ElOption)
    expect(options.map((option) => option.props('label'))).toEqual(['知识助手（v3）'])
  })
})

describe('chat store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('folds meta, step, delta and citations frames into one assistant message', async () => {
    const chat = await import('../src/api/chat')
    vi.mocked(chat.ask).mockImplementation((_id, _content, handlers) => {
      handlers.onMeta?.({ messageId: 42, conversationId: 11 })
      handlers.onStep?.({ round: 1, phase: 'PLAN', status: 'started' })
      handlers.onStep?.({ round: 1, phase: 'PLAN', status: 'finished', summary: '拆解问题' })
      handlers.onDelta?.({ phase: 'ANSWER', text: '第一段。' })
      handlers.onDelta?.({ phase: 'ANSWER', text: '第二段。' })
      handlers.onCitations?.({ items: [{ chunkId: 7, documentId: 3, docName: '报销制度.pdf', nodeName: null, snippet: '发票', score: 0.9 }] })
      handlers.onDone?.({ messageId: 42, status: 'COMPLETED', latencyMs: 800 })
      return new AbortController()
    })

    const { useChatStore } = await import('../src/stores/chat')
    const store = useChatStore()
    await store.loadConversations()
    await store.openConversation(11)
    await store.send('需要哪些材料？')

    const assistant = store.messages[store.messages.length - 1]
    expect(assistant.id).toBe(42)
    expect(assistant.content).toBe('第一段。第二段。')
    expect(assistant.status).toBe('COMPLETED')
    expect(assistant.steps.map((step) => `${step.round}:${step.phase}:${step.status}`)).toEqual([
      '1:PLAN:finished'
    ])
    expect(assistant.citations).toHaveLength(1)
    expect(store.streaming).toBe(false)
  })
})
