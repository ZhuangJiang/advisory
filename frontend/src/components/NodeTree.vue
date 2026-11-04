<script setup lang="ts">
import { computed } from 'vue'
import { ElMessageBox } from 'element-plus'
import type { KnowledgeNode } from '@/api/knowledge'

/**
 * The knowledge base node tree.
 *
 * The component owns layout and interaction only: it never calls the API. Every mutation is emitted
 * as an intent (`create` / `rename` / `move` / `remove`) and the page performs the request and
 * reloads the list, so the tree always renders server state — after a rejected move the refresh puts
 * the node back where the backend says it is.
 *
 * `readonly` is the single switch for the USER role: it hides every write control and turns off
 * drag-and-drop (the backend would answer 403 anyway; hiding them avoids a dead UI).
 */
const props = defineProps<{
  nodes: KnowledgeNode[]
  selectedId: number | null
  readonly?: boolean
  loading?: boolean
}>()

const emit = defineEmits<{
  select: [node: KnowledgeNode | null]
  create: [parentId: number | null]
  rename: [node: KnowledgeNode, name: string]
  move: [node: KnowledgeNode, parentId: number | null]
  remove: [node: KnowledgeNode]
}>()

interface TreeNode extends KnowledgeNode {
  children: TreeNode[]
}

const treeData = computed<TreeNode[]>(() => {
  const byId = new Map<number, TreeNode>()
  for (const node of props.nodes) {
    byId.set(node.id, { ...node, children: [] })
  }
  const roots: TreeNode[] = []
  for (const node of props.nodes) {
    const current = byId.get(node.id)!
    const parent = node.parentId === null ? undefined : byId.get(node.parentId)
    if (parent) {
      parent.children.push(current)
    } else {
      roots.push(current)
    }
  }
  const sortRecursive = (list: TreeNode[]): void => {
    list.sort((a, b) => a.sortOrder - b.sortOrder || a.id - b.id)
    list.forEach((item) => sortRecursive(item.children))
  }
  sortRecursive(roots)
  return roots
})

function onNodeClick(data: TreeNode): void {
  emit('select', data)
}

/** `inner` = become a child of the drop target; before/after = become its sibling. */
function onNodeDrop(
  dragging: { data: TreeNode },
  drop: { data: TreeNode },
  dropType: 'before' | 'after' | 'inner'
): void {
  const parentId = dropType === 'inner' ? drop.data.id : drop.data.parentId
  emit('move', dragging.data, parentId)
}

async function renameNode(node: TreeNode): Promise<void> {
  let name: string
  try {
    const result = await ElMessageBox.prompt('请输入新的节点名称', '重命名节点', {
      inputValue: node.name,
      inputValidator: (value: string) => (value && value.trim() ? true : '名称不能为空'),
      confirmButtonText: '保存',
      cancelButtonText: '取消'
    })
    name = result.value.trim()
  } catch {
    return
  }
  if (name !== node.name) {
    emit('rename', node, name)
  }
}

async function removeNode(node: TreeNode): Promise<void> {
  try {
    await ElMessageBox.confirm(`确认删除节点「${node.name}」？`, '删除节点', { type: 'warning' })
  } catch {
    return
  }
  emit('remove', node)
}

function addChild(node: TreeNode): void {
  emit('create', node.id)
}

/** Exposed so the page can surface "select a node first" without duplicating the check. */
defineExpose({ treeData })
</script>

<template>
  <div v-loading="loading" class="node-tree">
    <div class="toolbar">
      <el-button
        v-if="!readonly"
        size="small"
        type="primary"
        data-test="tree-create-root"
        @click="emit('create', null)"
      >
        新建根节点
      </el-button>
      <span v-if="readonly" class="readonly-hint">只读</span>
    </div>

    <el-tree
      :data="treeData"
      node-key="id"
      :props="{ label: 'name', children: 'children' }"
      :draggable="!readonly"
      :highlight-current="true"
      :current-node-key="selectedId ?? undefined"
      :expand-on-click-node="false"
      default-expand-all
      empty-text="暂无节点，先新建一个根节点"
      @node-click="onNodeClick"
      @node-drop="onNodeDrop"
    >
      <template #default="{ data }">
        <span class="node-row">
          <span class="node-name">{{ data.name }}</span>
          <span v-if="!readonly" class="node-actions">
            <el-button link size="small" data-test="node-add" @click.stop="addChild(data)">子节点</el-button>
            <el-button link size="small" data-test="node-rename" @click.stop="renameNode(data)">重命名</el-button>
            <el-button link size="small" type="danger" data-test="node-remove" @click.stop="removeNode(data)">
              删除
            </el-button>
          </span>
        </span>
      </template>
    </el-tree>
  </div>
</template>

<style scoped>
.node-tree {
  min-height: 320px;
}

.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.readonly-hint {
  color: #909399;
  font-size: 12px;
}

.node-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  padding-right: 8px;
  gap: 8px;
}

.node-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.node-actions {
  flex: none;
  opacity: 0;
  transition: opacity 0.15s;
}

.node-row:hover .node-actions {
  opacity: 1;
}

/* Keyboard and touch users never hover: keep the actions reachable. */
.node-row:focus-within .node-actions {
  opacity: 1;
}
</style>
