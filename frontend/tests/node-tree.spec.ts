import { beforeEach, describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import NodeTree from '../src/components/NodeTree.vue'
import type { KnowledgeNode } from '../src/api/knowledge'

/**
 * The node tree is the only place the knowledge base hierarchy is drawn, and `readonly` is the only
 * switch that separates an administrator from a USER. Both are asserted here: the tree must nest
 * children under their parent (a flat list would silently lose the hierarchy), and a USER must not
 * be offered a single write control.
 */

function node(partial: Partial<KnowledgeNode> & { id: number; name: string }): KnowledgeNode {
  return {
    kbId: 1,
    parentId: null,
    path: `${partial.id}`,
    sortOrder: 0,
    ...partial
  }
}

const NODES: KnowledgeNode[] = [
  node({ id: 3, name: '第三章', sortOrder: 2 }),
  node({ id: 1, name: '第一章', sortOrder: 0 }),
  node({ id: 2, name: '1.1 背景', parentId: 1, sortOrder: 0 }),
  node({ id: 4, name: '1.1.1 现状', parentId: 2, sortOrder: 0 })
]

/** jsdom has no ResizeObserver; Element Plus' scroll containers expect one to exist. */
class ResizeObserverStub {
  observe(): void {}
  unobserve(): void {}
  disconnect(): void {}
}

function mountTree(props: Record<string, unknown> = {}) {
  return mount(NodeTree, {
    props: { nodes: NODES, selectedId: null, readonly: false, ...props },
    global: { plugins: [ElementPlus] }
  })
}

describe('NodeTree', () => {
  beforeEach(() => {
    globalThis.ResizeObserver = ResizeObserverStub as unknown as typeof ResizeObserver
  })

  it('renders nested nodes in sort order', () => {
    const wrapper = mountTree()

    const names = wrapper.findAll('.node-name').map((item) => item.text())
    expect(names).toEqual(['第一章', '1.1 背景', '1.1.1 现状', '第三章'])

    // Depth is the real contract: a child must live inside its parent's node, not next to it.
    const first = wrapper.findAll('.el-tree-node').at(0)!
    expect(first.text()).toContain('1.1 背景')
    const child = first.findAll('.el-tree-node')[0]
    expect(child.text()).toContain('1.1.1 现状')
  })

  it('hides every write control for a USER', () => {
    const wrapper = mountTree({ readonly: true })

    expect(wrapper.find('[data-test="tree-create-root"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="node-add"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="node-rename"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="node-remove"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('只读')
  })

  it('shows write controls and enables dragging for an ADMIN', async () => {
    const wrapper = mountTree({ readonly: false })

    expect(wrapper.find('[data-test="tree-create-root"]').exists()).toBe(true)
    expect(wrapper.findAll('[data-test="node-remove"]')).toHaveLength(NODES.length)
    expect(wrapper.findComponent({ name: 'ElTree' }).props('draggable')).toBe(true)

    await wrapper.find('[data-test="tree-create-root"]').trigger('click')
    expect(wrapper.emitted('create')).toEqual([[null]])
  })

  it('emits the parent id when adding a child and the node when removing', async () => {
    const wrapper = mountTree()

    const addButtons = wrapper.findAll('[data-test="node-add"]')
    await addButtons[0].trigger('click')
    expect(wrapper.emitted('create')).toEqual([[1]])

    await wrapper.findAll('[data-test="node-remove"]')[0].trigger('click')
    // The confirmation dialog is a user gate: nothing is emitted until it is accepted.
    expect(wrapper.emitted('remove')).toBeUndefined()
  })

  it('emits the selected node on click', async () => {
    const wrapper = mountTree()
    await wrapper.findAll('.el-tree-node__content')[0].trigger('click')

    const emitted = wrapper.emitted('select')
    expect(emitted).toBeTruthy()
    expect((emitted![0][0] as KnowledgeNode).id).toBe(1)
  })
})
