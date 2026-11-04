<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  createNode,
  deleteNode,
  describeKnowledgeError,
  getKnowledgeBase,
  listNodes,
  patchNode,
  type KnowledgeBase,
  type KnowledgeNode
} from '@/api/knowledge'
import { useAuthStore } from '@/stores/auth'
import NodeTree from '@/components/NodeTree.vue'
import DocumentTable from '@/components/DocumentTable.vue'

/**
 * One knowledge base: node tree on the left, documents on the right.
 *
 * This page is where node mutations actually happen — {@link NodeTree} only emits intents, so every
 * request, error message and reload lives here. A rejected move is the important case: the tree has
 * already drawn the dragged node in its new place, so a failure must reload the list to put it back
 * rather than leave the UI showing a move the backend refused.
 */
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const kbId = computed(() => Number(route.params.id))
const canWrite = computed(() => auth.isAdmin)

const kb = ref<KnowledgeBase | null>(null)
const nodes = ref<KnowledgeNode[]>([])
const selectedId = ref<number | null>(null)
const loading = ref(false)
const nodesLoading = ref(false)
const loadError = ref('')

const selectedNode = computed(() => nodes.value.find((node) => node.id === selectedId.value) ?? null)

async function loadKb(): Promise<void> {
  loadError.value = ''
  loading.value = true
  try {
    kb.value = await getKnowledgeBase(kbId.value)
  } catch (e) {
    loadError.value = describeKnowledgeError(e)
    kb.value = null
  } finally {
    loading.value = false
  }
}

async function loadNodes(): Promise<void> {
  nodesLoading.value = true
  try {
    nodes.value = await listNodes(kbId.value)
    if (selectedId.value !== null && !nodes.value.some((node) => node.id === selectedId.value)) {
      // The selected node was deleted by this or another session.
      selectedId.value = null
    }
  } catch (e) {
    ElMessage.error(describeKnowledgeError(e))
    nodes.value = []
  } finally {
    nodesLoading.value = false
  }
}

onMounted(async () => {
  await Promise.all([loadKb(), loadNodes()])
})

watch(kbId, async () => {
  selectedId.value = null
  await Promise.all([loadKb(), loadNodes()])
})

async function onCreate(parentId: number | null): Promise<void> {
  let name: string
  try {
    const result = await ElMessageBox.prompt(
      parentId === null ? '新建根节点名称' : '新建子节点名称',
      '新建节点',
      {
        inputValidator: (value: string) => (value && value.trim() ? true : '名称不能为空'),
        confirmButtonText: '创建',
        cancelButtonText: '取消'
      }
    )
    name = result.value.trim()
  } catch {
    return
  }
  try {
    const created = await createNode(kbId.value, parentId, name)
    await loadNodes()
    selectedId.value = created.id
  } catch (e) {
    ElMessage.error(describeKnowledgeError(e))
  }
}

async function onRename(node: KnowledgeNode, name: string): Promise<void> {
  try {
    await patchNode(node.id, { name })
    await loadNodes()
  } catch (e) {
    ElMessage.error(describeKnowledgeError(e))
  }
}

async function onMove(node: KnowledgeNode, parentId: number | null): Promise<void> {
  try {
    await patchNode(node.id, { parentId })
    await loadNodes()
  } catch (e) {
    // The tree already shows the drop; reloading is what undoes it.
    ElMessage.error(describeKnowledgeError(e))
    await loadNodes()
  }
}

async function onRemove(node: KnowledgeNode): Promise<void> {
  try {
    await deleteNode(node.id)
    if (selectedId.value === node.id) {
      selectedId.value = null
    }
    await loadNodes()
    ElMessage.success('已删除节点')
  } catch (e) {
    ElMessage.error(describeKnowledgeError(e))
  }
}

function back(): void {
  void router.push({ name: 'knowledge-bases' })
}
</script>

<template>
  <div v-loading="loading">
    <el-page-header class="header" @back="back">
      <template #content>
        <span class="kb-name">{{ kb?.name ?? '知识库' }}</span>
        <el-tag v-if="kb" size="small" type="info" class="count">
          {{ kb.documentCount }} 个文档
        </el-tag>
        <el-tag v-if="kb?.reindexStatus === 'REINDEXING'" size="small" type="warning">索引重建中</el-tag>
      </template>
    </el-page-header>

    <el-alert v-if="loadError" :title="loadError" type="error" :closable="false" show-icon class="alert" />

    <div v-if="!loadError" class="body">
      <el-card shadow="never" class="tree-card">
        <template #header>
          <div class="card-header">
            <span>节点树</span>
            <span v-if="selectedNode" class="selected">已选：{{ selectedNode.name }}</span>
          </div>
        </template>
        <NodeTree
          :nodes="nodes"
          :selected-id="selectedId"
          :readonly="!canWrite"
          :loading="nodesLoading"
          @select="(node) => (selectedId = node ? node.id : null)"
          @create="onCreate"
          @rename="onRename"
          @move="onMove"
          @remove="onRemove"
        />
      </el-card>

      <el-card shadow="never" class="docs-card">
        <template #header>
          <div class="card-header">
            <span>文档</span>
            <span class="selected">按所选节点过滤，未选节点时显示全部</span>
          </div>
        </template>
        <DocumentTable :kb-id="kbId" :selected-node-id="selectedId" :readonly="!canWrite" />
      </el-card>
    </div>
  </div>
</template>

<style scoped>
.header {
  margin-bottom: 12px;
}

.kb-name {
  font-size: 16px;
  font-weight: 600;
  margin-right: 8px;
}

.count {
  margin-right: 6px;
}

.alert {
  margin-bottom: 12px;
}

.body {
  display: grid;
  grid-template-columns: 300px minmax(0, 1fr);
  gap: 12px;
  align-items: start;
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.selected {
  color: #909399;
  font-size: 12px;
  font-weight: 400;
}

@media (max-width: 1100px) {
  .body {
    grid-template-columns: 1fr;
  }
}
</style>
