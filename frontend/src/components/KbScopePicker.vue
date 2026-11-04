<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { listNodes, type KnowledgeBase, type KnowledgeNode } from '@/api/knowledge'
import { embeddingConflict } from '@/api/agents'

/**
 * Knowledge base + node scope picker.
 *
 * Scope semantics come from the backend: a knowledge base with **no** node ids is "the whole
 * knowledge base". So an unchecked tree is not an error, it is the widest possible selection — the
 * hint says so, because a blank tree otherwise reads like "nothing selected".
 *
 * Every selected knowledge base must have been vectorised by the same embedding model; mixing them
 * would compare vectors from different spaces. The rule is evaluated here (naming the offenders) and
 * reported through `validity-change`, which the form uses to disable saving — the backend enforces it
 * too, this just avoids a pointless round trip.
 */
const props = defineProps<{
  kbs: KnowledgeBase[]
  kbIds: number[]
  nodeScope: Record<number, number[]>
  disabled?: boolean
}>()

const emit = defineEmits<{
  'update:kbIds': [value: number[]]
  'update:nodeScope': [value: Record<number, number[]>]
  'validity-change': [valid: boolean]
}>()

const nodesByKb = reactive<Record<number, KnowledgeNode[]>>({})
const loadingKb = ref<number | null>(null)
const loadFailed = ref<number[]>([])

const conflict = computed(() => embeddingConflict(props.kbs, props.kbIds))

const conflictText = computed(() => {
  if (conflict.value.length === 0) {
    return ''
  }
  const names = conflict.value.map((kb) => `${kb.name}（#${kb.embeddingModelId ?? '未配置'}）`)
  return `所选知识库使用了不同的 embedding 模型：${names.join('、')}。请只选择同一模型向量化的知识库。`
})

watch(
  conflict,
  (list) => emit('validity-change', list.length === 0),
  { immediate: true }
)

async function ensureNodes(kbId: number): Promise<void> {
  if (nodesByKb[kbId] !== undefined || loadingKb.value === kbId || loadFailed.value.includes(kbId)) {
    return
  }
  loadingKb.value = kbId
  try {
    nodesByKb[kbId] = await listNodes(kbId)
  } catch {
    // A tree that cannot load must not block saving the rest of the form.
    loadFailed.value = [...loadFailed.value, kbId]
  } finally {
    loadingKb.value = null
  }
}

watch(
  () => props.kbIds,
  (ids) => {
    ids.forEach((id) => void ensureNodes(id))
    // Drop scope entries whose knowledge base is no longer selected: keeping them would send node
    // ids for a knowledge base the backend does not consider part of the agent.
    const kept: Record<number, number[]> = {}
    ids.forEach((id) => {
      const current = props.nodeScope[id]
      if (current !== undefined) {
        kept[id] = current
      }
    })
    if (Object.keys(kept).length !== Object.keys(props.nodeScope).length) {
      emit('update:nodeScope', kept)
    }
  },
  { immediate: true, deep: true }
)

interface UiNode extends KnowledgeNode {
  children: UiNode[]
}

function treeFor(kbId: number): UiNode[] {
  const flat = nodesByKb[kbId] ?? []
  const byId = new Map<number, UiNode>()
  flat.forEach((node) => byId.set(node.id, { ...node, children: [] }))
  const roots: UiNode[] = []
  flat.forEach((node) => {
    const current = byId.get(node.id)!
    const parent = node.parentId === null ? undefined : byId.get(node.parentId)
    if (parent) {
      parent.children.push(current)
    } else {
      roots.push(current)
    }
  })
  const sort = (list: UiNode[]): void => {
    list.sort((a, b) => a.sortOrder - b.sortOrder || a.id - b.id)
    list.forEach((item) => sort(item.children))
  }
  sort(roots)
  return roots
}

function onKbChange(value: number[]): void {
  emit('update:kbIds', value)
}

function onCheck(kbId: number, _node: unknown, info: { checkedKeys: Array<string | number> }): void {
  // Checked parents are kept as well as their children: retrieval scopes with `path <@`, so a parent
  // id already covers its subtree and the duplicates are harmless — whereas dropping parents would
  // silently narrow a "select whole folder" gesture to the leaves that happen to exist today.
  const keys = info.checkedKeys.map((key) => Number(key))
  emit('update:nodeScope', { ...props.nodeScope, [kbId]: keys })
}

function scopeLabel(kbId: number): string {
  const ids = props.nodeScope[kbId] ?? []
  return ids.length === 0 ? '整库' : `${ids.length} 个节点`
}
</script>

<template>
  <div class="kb-scope">
    <el-select
      :model-value="kbIds"
      multiple
      filterable
      placeholder="选择知识库"
      class="kb-select"
      :disabled="disabled"
      data-test="kb-select"
      @update:model-value="onKbChange"
    >
      <el-option v-for="kb in kbs" :key="kb.id" :label="kb.name" :value="kb.id" />
    </el-select>

    <el-alert
      v-if="conflictText"
      :title="conflictText"
      type="error"
      :closable="false"
      show-icon
      class="alert"
      data-test="scope-conflict"
    />

    <el-empty v-if="kbIds.length === 0" description="至少选择一个知识库" :image-size="60" />

    <el-card v-for="kbId in kbIds" :key="kbId" shadow="never" class="kb-card">
      <template #header>
        <div class="kb-head">
          <span class="kb-name">{{ kbs.find((item) => item.id === kbId)?.name ?? `#${kbId}` }}</span>
          <el-tag size="small" type="info">{{ scopeLabel(kbId) }}</el-tag>
        </div>
      </template>

      <el-alert
        v-if="loadFailed.includes(kbId)"
        title="节点加载失败，将按整库检索"
        type="warning"
        :closable="false"
      />
      <div v-else v-loading="loadingKb === kbId">
        <el-tree
          :data="treeFor(kbId)"
          node-key="id"
          show-checkbox
          default-expand-all
          :props="{ label: 'name', children: 'children' }"
          :default-checked-keys="nodeScope[kbId] ?? []"
          empty-text="该知识库还没有节点"
          @check="onCheck.bind(null, kbId)"
        />
        <div class="hint">不勾选任何节点 = 整库检索</div>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.kb-scope {
  width: 100%;
}

.kb-select {
  width: 100%;
}

.alert {
  margin-top: 8px;
}

.kb-card {
  margin-top: 8px;
}

.kb-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.kb-name {
  font-weight: 600;
}

.hint {
  color: #909399;
  font-size: 12px;
  margin-top: 4px;
}
</style>
