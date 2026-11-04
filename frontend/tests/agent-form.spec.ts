import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { embeddingConflict } from '../src/api/agents'

/**
 * The agent form must refuse to save a selection the backend would reject.
 *
 * Two knowledge bases vectorised by different models cannot be retrieved together (the vectors live
 * in different spaces), so the picker names the offenders and the form disables saving. The rule is
 * also a pure function, which is where the truth table is pinned; the component test proves the
 * wiring (alert visible, button disabled) rather than re-deriving the rule from the DOM.
 */

const kbs = [
  { id: 1, name: '制度库', description: null, embeddingModelId: 100, chunkConfig: '{}', reindexStatus: 'READY', createdAt: '2026-01-01T00:00:00Z', documentCount: 3 },
  { id: 2, name: '产品库', description: null, embeddingModelId: 200, chunkConfig: '{}', reindexStatus: 'READY', createdAt: '2026-01-01T00:00:00Z', documentCount: 1 }
] as const

const agent = {
  id: 5,
  name: '知识助手',
  description: null,
  systemPrompt: '只根据资料回答',
  chatModelId: 900,
  embeddingModelId: 100,
  kbIds: [1, 2],
  nodeScope: {},
  maxRounds: 3,
  topK: 20,
  temperature: 0.7,
  enabled: true,
  currentVersion: 2,
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z'
}

vi.mock('vue-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('vue-router')>()
  return {
    ...actual,
    useRoute: () => ({ name: 'agent-edit', params: { id: '5' } }),
    useRouter: () => ({ push: vi.fn(), replace: vi.fn() })
  }
})

vi.mock('../src/api/knowledge', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../src/api/knowledge')>()
  return {
    ...actual,
    listKnowledgeBases: vi.fn(() => Promise.resolve({ content: [...kbs], page: 0, size: 100, total: 2, totalPages: 1 })),
    listNodes: vi.fn(() => Promise.resolve([]))
  }
})

vi.mock('../src/api/providers', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../src/api/providers')>()
  return {
    ...actual,
    listChatModels: vi.fn(() =>
      Promise.resolve([
        { id: 900, providerId: 1, modelName: 'gpt-4o-mini', capability: 'CHAT', dimension: null, params: '{}', enabled: true, createdAt: '2026-01-01T00:00:00Z' }
      ])
    )
  }
})

vi.mock('../src/api/agents', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../src/api/agents')>()
  return {
    ...actual,
    getAgent: vi.fn(() => Promise.resolve(agent)),
    listAgentVersions: vi.fn(() => Promise.resolve([])),
    createAgent: vi.fn(),
    updateAgent: vi.fn()
  }
})

class ResizeObserverStub {
  observe(): void {}
  unobserve(): void {}
  disconnect(): void {}
}

async function mountAgentEdit() {
  const module = await import('../src/pages/agents/AgentEdit.vue')
  const wrapper = mount(module.default, { global: { plugins: [ElementPlus] } })
  await flushPromises()
  await flushPromises()
  return wrapper
}

describe('agent form scope rule', () => {
  beforeEach(() => {
    globalThis.ResizeObserver = ResizeObserverStub as unknown as typeof ResizeObserver
  })

  it('flags a selection that mixes embedding models', () => {
    expect(embeddingConflict([...kbs], [1]).map((kb) => kb.id)).toEqual([])
    expect(embeddingConflict([...kbs], [1, 2]).map((kb) => kb.id)).toEqual([1, 2])
  })

  it('disables save and explains why when the selected knowledge bases disagree', async () => {
    const wrapper = await mountAgentEdit()

    expect(wrapper.find('[data-test="scope-conflict"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('不同的 embedding 模型')
    expect(wrapper.text()).toContain('制度库')
    expect(wrapper.text()).toContain('产品库')

    const save = wrapper.find('[data-test="agent-save"]')
    expect(save.exists()).toBe(true)
    expect(save.attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('无法保存')
  })

  it('keeps save enabled when every selected knowledge base uses one model', async () => {
    const agents = await import('../src/api/agents')
    vi.mocked(agents.getAgent).mockResolvedValueOnce({ ...agent, kbIds: [1] })

    const wrapper = await mountAgentEdit()

    expect(wrapper.find('[data-test="scope-conflict"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="agent-save"]').attributes('disabled')).toBeUndefined()
  })
})
