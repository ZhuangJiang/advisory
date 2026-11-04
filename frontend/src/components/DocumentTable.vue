<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  deleteDocument,
  describeKnowledgeError,
  listDocuments,
  retryDocument,
  uploadDocument,
  type DocumentRow,
  type DocumentStatus
} from '@/api/knowledge'
import ChunkDrawer from './ChunkDrawer.vue'

/**
 * Documents of the selected node, with the upload flow and status polling.
 *
 * Polling matters because ingestion is asynchronous: the row is created as `PENDING` and the worker
 * moves it to `READY`/`FAILED` seconds later. Rather than a fixed interval that runs forever, a
 * single 3s timer is rescheduled only while at least one row is still in a transient state — when
 * everything is settled the component goes quiet. The timer is cleared on unmount and whenever the
 * node or knowledge base changes, so a page change can never be overwritten by a stale response.
 */
const props = defineProps<{
  kbId: number
  selectedNodeId: number | null
  readonly?: boolean
}>()

const POLL_INTERVAL_MS = 3000

const TRANSIENT: DocumentStatus[] = ['PENDING', 'PARSING', 'CHUNKING', 'EMBEDDING']

const STATUS_TAG: Record<DocumentStatus, 'success' | 'warning' | 'danger' | 'info'> = {
  PENDING: 'info',
  PARSING: 'warning',
  CHUNKING: 'warning',
  EMBEDDING: 'warning',
  READY: 'success',
  FAILED: 'danger',
  DELETED: 'info'
}

const STATUS_LABEL: Record<DocumentStatus, string> = {
  PENDING: '排队中',
  PARSING: '解析中',
  CHUNKING: '切分中',
  EMBEDDING: '向量化中',
  READY: '就绪',
  FAILED: '失败',
  DELETED: '已删除'
}

const rows = ref<DocumentRow[]>([])
const total = ref(0)
const page = ref(0)
const size = 10
const loading = ref(false)
const uploading = ref(false)
const error = ref('')
const drawerVisible = ref(false)
const drawerDocument = ref<DocumentRow | null>(null)

const fileInput = ref<HTMLInputElement | null>(null)
let pollTimer: ReturnType<typeof setTimeout> | null = null
let requestSeq = 0

const transientRows = computed(() => rows.value.filter((row) => TRANSIENT.includes(row.status)))

function stopPolling(): void {
  if (pollTimer !== null) {
    clearTimeout(pollTimer)
    pollTimer = null
  }
}

function schedulePoll(): void {
  stopPolling()
  if (transientRows.value.length === 0) {
    return
  }
  pollTimer = setTimeout(() => {
    pollTimer = null
    void load(true)
  }, POLL_INTERVAL_MS)
}

async function load(silent = false): Promise<void> {
  const seq = ++requestSeq
  if (!silent) {
    loading.value = true
  }
  error.value = ''
  try {
    const result = await listDocuments({
      kbId: props.kbId,
      nodeId: props.selectedNodeId,
      page: page.value,
      size
    })
    if (seq !== requestSeq) {
      // A newer request (node switched, page changed) already answered: drop this one.
      return
    }
    rows.value = result.content
    total.value = result.total
    schedulePoll()
  } catch (e) {
    if (seq === requestSeq) {
      error.value = describeKnowledgeError(e)
      rows.value = []
    }
  } finally {
    if (seq === requestSeq) {
      loading.value = false
    }
  }
}

watch(
  () => [props.kbId, props.selectedNodeId] as const,
  () => {
    page.value = 0
    stopPolling()
    void load()
  },
  { immediate: true }
)

watch(page, () => {
  void load()
})

onBeforeUnmount(stopPolling)

function pickFile(): void {
  if (props.readonly) {
    return
  }
  if (props.selectedNodeId === null) {
    ElMessage.warning('请先在左侧选择一个节点')
    return
  }
  fileInput.value?.click()
}

async function onFileChosen(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file || props.selectedNodeId === null) {
    return
  }
  if (file.size > 50 * 1024 * 1024) {
    ElMessage.error('单个文件不能超过 50 MB')
    return
  }

  uploading.value = true
  try {
    await uploadDocument({ kbId: props.kbId, nodeId: props.selectedNodeId, file })
    ElMessage.success('已提交，正在后台解析')
    await load(true)
  } catch (e) {
    ElMessage.error(describeKnowledgeError(e))
  } finally {
    uploading.value = false
  }
}

async function retry(row: DocumentRow): Promise<void> {
  try {
    await retryDocument(row.id)
    ElMessage.success('已重新排队')
    await load(true)
  } catch (e) {
    ElMessage.error(describeKnowledgeError(e))
  }
}

async function remove(row: DocumentRow): Promise<void> {
  try {
    await ElMessageBox.confirm(`确认删除文档「${row.filename}」？`, '删除文档', { type: 'warning' })
  } catch {
    return
  }
  try {
    await deleteDocument(row.id)
    ElMessage.success('已删除')
    await load(true)
  } catch (e) {
    ElMessage.error(describeKnowledgeError(e))
  }
}

function openChunks(row: DocumentRow): void {
  drawerDocument.value = row
  drawerVisible.value = true
}

function formatSize(bytes: number): string {
  if (bytes < 1024) {
    return `${bytes} B`
  }
  if (bytes < 1024 * 1024) {
    return `${(bytes / 1024).toFixed(1)} KB`
  }
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

function formatTime(value: string): string {
  return new Date(value).toLocaleString('zh-CN', { hour12: false })
}

defineExpose({ refresh: () => load(true) })
</script>

<template>
  <div class="document-table">
    <div class="toolbar">
      <span class="scope">
        {{ selectedNodeId === null ? '知识库根目录（全部文档）' : '当前节点文档' }}
      </span>
      <div class="actions">
        <el-button size="small" :loading="loading" data-test="docs-refresh" @click="load(false)">
          刷新
        </el-button>
        <el-button
          v-if="!readonly"
          size="small"
          type="primary"
          :loading="uploading"
          data-test="docs-upload"
          @click="pickFile"
        >
          上传文档
        </el-button>
        <input ref="fileInput" type="file" class="file-input" @change="onFileChosen" />
      </div>
    </div>

    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon class="alert" />

    <el-table v-loading="loading" :data="rows" size="small" empty-text="该节点下还没有文档">
      <el-table-column prop="filename" label="文件名" min-width="200" show-overflow-tooltip />
      <el-table-column label="大小" width="92">
        <template #default="{ row }">{{ formatSize(row.size) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="118">
        <template #default="{ row }">
          <el-tooltip :disabled="!row.errorMsg" :content="row.errorMsg || ''" placement="top">
            <el-tag :type="STATUS_TAG[row.status as DocumentStatus]" size="small">
              {{ STATUS_LABEL[row.status as DocumentStatus] }}
            </el-tag>
          </el-tooltip>
        </template>
      </el-table-column>
      <el-table-column label="上传时间" width="170">
        <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="190" fixed="right">
        <template #default="{ row }">
          <el-button link size="small" data-test="docs-chunks" @click="openChunks(row)">片段</el-button>
          <el-button
            v-if="!readonly && row.status === 'FAILED'"
            link
            size="small"
            type="warning"
            data-test="docs-retry"
            @click="retry(row)"
          >
            重试
          </el-button>
          <el-button
            v-if="!readonly"
            link
            size="small"
            type="danger"
            data-test="docs-delete"
            @click="remove(row)"
          >
            删除
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      v-if="total > size"
      v-model:current-page="page"
      :page-size="size"
      :total="total"
      layout="prev, pager, next"
      class="pager"
    />

    <ChunkDrawer
      v-model="drawerVisible"
      :document-id="drawerDocument?.id ?? null"
      :filename="drawerDocument?.filename ?? ''"
    />
  </div>
</template>

<style scoped>
.document-table {
  min-height: 360px;
}

.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
  gap: 12px;
}

.scope {
  color: #909399;
  font-size: 13px;
}

.actions {
  display: flex;
  gap: 8px;
}

.alert {
  margin-bottom: 10px;
}

.file-input {
  display: none;
}

.pager {
  margin-top: 12px;
  justify-content: flex-end;
}
</style>
