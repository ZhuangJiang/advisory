import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'

/**
 * Agent lifecycle actions are driven from the list, not the edit form.
 *
 * The backend refuses to delete an agent that is still online, so the button is disabled rather
 * than letting the operator hit a 409. Taking an agent down and back up uses dedicated endpoints
 * that do not create a configuration version.
 */

vi.mock('vue-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('vue-router')>()
  return { ...actual, useRouter: () => ({ push: vi.fn() }) }
})

const agents = [
  {
    id: 1,
    name: '在线助手',
    description: null,
    systemPrompt: 'p',
    chatModelId: 9,
    embeddingModelId: 8,
    kbIds: [1],
    nodeScope: {},
    maxRounds: 3,
    topK: 20,
    temperature: 0.7,
    enabled: true,
    currentVersion: 1,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z'
  },
  {
    id: 2,
    name: '已下线助手',
    description: null,
    systemPrompt: 'p',
    chatModelId: 9,
    embeddingModelId: 8,
    kbIds: [1],
    nodeScope: {},
    maxRounds: 3,
    topK: 20,
    temperature: 0.7,
    enabled: false,
    currentVersion: 1,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z'
  }
]

vi.mock('../src/api/agents', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../src/api/agents')>()
  return {
    ...actual,
    listAgents: vi.fn(() =>
      Promise.resolve({ content: agents, page: 0, size: 10, total: 2, totalPages: 1 })
    ),
    offlineAgent: vi.fn(() => Promise.resolve(agents[0])),
    onlineAgent: vi.fn(() => Promise.resolve(agents[1])),
    deleteAgent: vi.fn(() => Promise.resolve())
  }
})

async function mountList() {
  const module = await import('../src/pages/agents/AgentList.vue')
  const wrapper = mount(module.default, { global: { plugins: [ElementPlus] } })
  await flushPromises()
  await flushPromises()
  return wrapper
}

describe('agent lifecycle actions', () => {
  beforeEach(() => {
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
  })

  it('disables delete for an online agent and enables it once offline', async () => {
    const wrapper = await mountList()
    const deleteButtons = wrapper.findAll('[data-test="agent-delete"]')
    const toggleButtons = wrapper.findAll('[data-test="agent-toggle"]')

    expect(deleteButtons[0].attributes('disabled')).toBeDefined()
    expect(deleteButtons[1].attributes('disabled')).toBeUndefined()
    expect(toggleButtons[0].text()).toBe('下线')
    expect(toggleButtons[1].text()).toBe('上线')
  })

  it('calls offlineAgent when taking an online agent down', async () => {
    const api = await import('../src/api/agents')
    const wrapper = await mountList()

    await wrapper.findAll('[data-test="agent-toggle"]')[0].trigger('click')
    await flushPromises()

    expect(api.offlineAgent).toHaveBeenCalledWith(1)
  })

  it('deletes an offline agent after confirmation', async () => {
    const api = await import('../src/api/agents')
    const wrapper = await mountList()

    await wrapper.findAll('[data-test="agent-delete"]')[1].trigger('click')
    await flushPromises()

    expect(api.deleteAgent).toHaveBeenCalledWith(2)
  })
})
