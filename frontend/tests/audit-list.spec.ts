import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'

/**
 * The audit page is the administrator's read-only window into other people's threads.
 *
 * Two things are pinned here: the filters actually reach the endpoint as query parameters (a filter
 * that only exists in the UI would silently return everyone's data), and the list renders the
 * denormalised row — user, agent, version, message count — without a second request per row.
 */

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' }
  })
}

function ok<T>(data: T): Response {
  return json({ code: 0, message: 'ok', data })
}

const agents = [
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
  }
]

const rows = [
  {
    id: 11,
    userId: 2,
    userName: 'zhang',
    agentId: 5,
    agentName: '知识助手',
    agentVersion: 3,
    title: '报销流程',
    messageCount: 4,
    createdAt: '2026-11-01T02:00:00Z',
    lastMessageAt: '2026-11-01T02:05:00Z'
  }
]

let fetchMock: ReturnType<typeof vi.fn>

beforeEach(() => {
  localStorage.clear()
  fetchMock = vi.fn((input: string) => {
    if (input.startsWith('/api/agents')) {
      return Promise.resolve(ok({ content: agents, page: 0, size: 50, total: 1, totalPages: 1 }))
    }
    if (input.startsWith('/api/admin/conversations')) {
      return Promise.resolve(ok({ content: rows, page: 0, size: 20, total: 1, totalPages: 1 }))
    }
    return Promise.resolve(ok(null))
  })
  vi.stubGlobal('fetch', fetchMock)
})

function auditCalls(): string[] {
  return fetchMock.mock.calls
    .map((call) => String(call[0]))
    .filter((url) => url.startsWith('/api/admin/conversations'))
}

describe('audit api', () => {
  it('sends userId and agentId filters to the audit endpoint', async () => {
    const { listAuditConversations } = await import('../src/api/audit')

    await listAuditConversations({ userId: 7, agentId: 5, from: '2026-10-28', to: '2026-11-04' })

    const [url] = auditCalls()
    expect(url).toContain('userId=7')
    expect(url).toContain('agentId=5')
    expect(url).toContain('from=2026-10-28')
    expect(url).toContain('to=2026-11-04')
  })

  it('omits empty filters instead of sending blanks', async () => {
    const { listAuditConversations } = await import('../src/api/audit')

    await listAuditConversations({ userId: null, agentId: null, from: null, to: null })

    const [url] = auditCalls()
    expect(url).not.toContain('userId')
    expect(url).not.toContain('agentId')
    expect(url).toContain('page=0')
  })
})

describe('audit list page', () => {
  it('shows the row and re-queries with the typed user filter', async () => {
    const module = await import('../src/pages/audit/AuditList.vue')
    const wrapper = mount(module.default, {
      global: { plugins: [ElementPlus, createPinia()] }
    })
    await flushPromises()
    await flushPromises()

    expect(wrapper.text()).toContain('zhang')
    expect(wrapper.text()).toContain('知识助手')
    expect(wrapper.text()).toContain('v3')

    // the first query already carries the default seven-day window
    const [first] = auditCalls()
    expect(first).toContain('from=')
    expect(first).toContain('to=')

    await wrapper.find('[data-test="filter-user"]').setValue('7')
    await wrapper.find('[data-test="audit-search"]').trigger('click')
    await flushPromises()

    const calls = auditCalls()
    expect(calls[calls.length - 1]).toContain('userId=7')
  })
})

describe('user admin api', () => {
  it('creates a user and disables it through PATCH', async () => {
    const { createUser, patchUser } = await import('../src/api/users')

    await createUser({ username: 'zhang', password: 'init-1234', role: 'USER' })
    expect(fetchMock.mock.calls[0][0]).toBe('/api/users')
    expect(fetchMock.mock.calls[0][1]).toEqual(
      expect.objectContaining({ method: 'POST', body: expect.stringContaining('init-1234') })
    )

    await patchUser(3, { enabled: false })
    expect(fetchMock.mock.calls[1][0]).toBe('/api/users/3')
    expect(fetchMock.mock.calls[1][1]).toEqual(
      expect.objectContaining({ method: 'PATCH', body: '{"enabled":false}' })
    )
  })
})
