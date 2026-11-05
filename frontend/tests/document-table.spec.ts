import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'

/**
 * The document table is where multi-file upload, batch rechunking and the queue position meet.
 *
 * All three are easy to break silently: a `single` file input drops every file but the first, a
 * missing selection guard sends an empty id list, and a `queuePosition` that never renders leaves
 * the operator staring at an apparently stuck "排队中". The API module is mocked so these tests
 * only describe what the component asks the backend to do.
 */

vi.mock('../src/api/knowledge', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../src/api/knowledge')>()
  return {
    ...actual,
    listDocuments: vi.fn(),
    uploadDocuments: vi.fn(),
    rechunkDocuments: vi.fn(),
    rechunkDocument: vi.fn(),
    retryDocument: vi.fn(),
    deleteDocument: vi.fn()
  }
})

const rows = [
  {
    id: 1,
    kbId: 1,
    nodeId: 2,
    filename: '排队中.md',
    objectKey: 'kb/1/a/排队中.md',
    mime: 'text/markdown',
    size: 120,
    checksum: null,
    status: 'PENDING',
    errorMsg: null,
    queuePosition: 2,
    createdAt: '2026-11-05T02:00:00Z',
    updatedAt: '2026-11-05T02:00:00Z'
  },
  {
    id: 2,
    kbId: 1,
    nodeId: 2,
    filename: '就绪.md',
    objectKey: 'kb/1/b/就绪.md',
    mime: 'text/markdown',
    size: 240,
    checksum: null,
    status: 'READY',
    errorMsg: null,
    queuePosition: null,
    createdAt: '2026-11-05T01:00:00Z',
    updatedAt: '2026-11-05T01:00:00Z'
  },
  {
    id: 3,
    kbId: 1,
    nodeId: 2,
    filename: '处理中.md',
    objectKey: 'kb/1/c/处理中.md',
    mime: 'text/markdown',
    size: 360,
    checksum: null,
    status: 'PARSING',
    errorMsg: null,
    queuePosition: null,
    createdAt: '2026-11-05T00:00:00Z',
    updatedAt: '2026-11-05T00:00:00Z'
  }
]

async function mountTable() {
  const module = await import('../src/components/DocumentTable.vue')
  const wrapper = mount(module.default, {
    props: { kbId: 1, selectedNodeId: 2 },
    global: { plugins: [ElementPlus] }
  })
  await flushPromises()
  await flushPromises()
  return wrapper
}

describe('document table', () => {
  beforeEach(async () => {
    vi.clearAllMocks()
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const api = await import('../src/api/knowledge')
    vi.mocked(api.listDocuments).mockResolvedValue({
      content: rows as never,
      page: 0,
      size: 10,
      total: rows.length,
      totalPages: 1
    })
    vi.mocked(api.uploadDocuments).mockResolvedValue({ uploaded: [], failed: [] })
    vi.mocked(api.rechunkDocuments).mockResolvedValue({ requested: 1, accepted: 1, skipped: [] })
    vi.mocked(api.rechunkDocument).mockResolvedValue(rows[1] as never)
  })

  it('shows where a queued document sits in the chunk queue', async () => {
    const wrapper = await mountTable()
    expect(wrapper.text()).toContain('第 2 位')
  })

  it('sends the selected documents to the batch rechunk endpoint', async () => {
    const api = await import('../src/api/knowledge')
    const wrapper = await mountTable()

    const batchButton = wrapper.find('[data-test="docs-batch-rechunk"]')
    expect(batchButton.attributes('disabled')).toBeDefined()

    const table = wrapper.findComponent({ name: 'ElTable' })
    table.vm.$emit('selection-change', [rows[1], rows[0]])
    await flushPromises()

    expect(wrapper.find('[data-test="docs-batch-rechunk"]').attributes('disabled')).toBeUndefined()
    await wrapper.find('[data-test="docs-batch-rechunk"]').trigger('click')
    await flushPromises()

    // 处理中的文档不可勾选，这里只用来验证顺序按选择结果原样传给后端。
    expect(api.rechunkDocuments).toHaveBeenCalledWith([2, 1])
  })

  it('rechunks a single ready document from its row', async () => {
    const api = await import('../src/api/knowledge')
    const wrapper = await mountTable()

    const buttons = wrapper.findAll('[data-test="docs-rechunk"]')
    expect(buttons.length).toBe(1)
    await buttons[0].trigger('click')
    await flushPromises()

    expect(api.rechunkDocument).toHaveBeenCalledWith(2)
  })

  it('hands every chosen file to the batch upload call', async () => {
    const api = await import('../src/api/knowledge')
    const wrapper = await mountTable()

    const input = wrapper.find('input[type="file"]')
    expect(input.attributes('multiple')).toBeDefined()

    const files = [new File(['a'], 'a.md'), new File(['b'], 'b.md')]
    Object.defineProperty(input.element, 'files', { value: files, configurable: true })
    await input.trigger('change')
    await flushPromises()

    expect(api.uploadDocuments).toHaveBeenCalledTimes(1)
    const call = vi.mocked(api.uploadDocuments).mock.calls[0][0]
    expect(call.files).toHaveLength(2)
    expect(call.kbId).toBe(1)
    expect(call.nodeId).toBe(2)
  })
})
