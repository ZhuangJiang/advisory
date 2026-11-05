<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ElMessage, ElMessageBox, type TableInstance } from 'element-plus'
import {
  deleteDocument,
  describeKnowledgeError,
  listDocuments,
  rechunkDocument,
  rechunkDocuments,
  retryDocument,
  uploadDocuments,
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
 *
 * Uploads accept several files at once and go out one by one (see `uploadDocuments`). The backend
 * chunks documents strictly one at a time, so `queuePosition` on a `PENDING` row tells the operator
 * where their batch actually is instead of leaving them with an indefinite "排队中".
 */
const props = defineProps<{
  kbId: number
  selectedNodeId: number | null
  readonly?: boolean
}>()

const POLL_INTERVAL_MS = 3000
const MAX_FILE_BYTES = 50 * 1024 * 1024
/** 一次批量上传的文件数上限：再多就该分批，避免一个请求打满浏览器连接与后端队列。 */
const MAX_FILES_PER_UPLOAD = 50

const TRANSIENT: DocumentStatus[] = ['PENDING', 'PARSING', 'CHUNKING', 'EMBEDDING']
/** 这两种状态才有「重新裁切」的意义：中间状态正在处理，删除状态不在列表里。 */
const RECHUNKABLE: DocumentStatus[] = ['READY', 'FAILED']

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
const uploadProgress = ref<{ done: number; total: number } | null>(null)
const rechunking = ref(false)
const error = ref('')
const drawerVisible = ref(false)
const drawerDocument = ref<DocumentRow | null>(null)
const selectedRows = ref<DocumentRow[]>([])
const tableRef = ref<TableInstance | null>(null)

const fileInput = ref<HTMLInputElement | null>(null)
let pollTimer: ReturnType<typeof setTimeout> | null = null
let requestSeq = 0

const transientRows = computed(() => rows.value.filter((row) => TRANSIENT.includes(row.status)))
const uploadLabel = computed(() =>
  uploadProgress.value
    ? `上传中 ${uploadProgress.value.done}/${uploadProgress.value.total}`
    : '上传文档'
)

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
    selectedRows.value = []
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

async function onFilesChosen(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement
  const chosen = Array.from(input.files ?? [])
  input.value = ''
  if (chosen.length === 0 || props.selectedNodeId === null) {
    return
  }
  if (chosen.length > MAX_FILES_PER_UPLOAD) {
    ElMessage.error(`一次最多上传 ${MAX_FILES_PER_UPLOAD} 个文件，请分批选择`)
    return
  }

  const oversized = chosen.filter((file) => file.size > MAX_FILE_BYTES)
  const accepted = chosen.filter((file) => file.size <= MAX_FILE_BYTES)
  if (oversized.length > 0) {
    ElMessage.warning(`${oversized.length} 个文件超过 50 MB，已跳过：${oversized[0].name}`)
  }
  if (accepted.length === 0) {
    return
  }

  uploading.value = true
  uploadProgress.value = { done: 0, total: accepted.length }
  try {
    const result = await uploadDocuments({
      kbId: props.kbId,
      nodeId: props.selectedNodeId,
      files: accepted,
      onProgress: (done, total) => {
        uploadProgress.value = { done, total }
      }
    })
    if (result.uploaded.length > 0) {
      ElMessage.success(`已提交 ${result.uploaded.length} 个文档，正在按顺序解析`)
    }
    if (result.failed.length > 0) {
      // 只报第一个失败原因：批量场景下逐条弹窗没有意义，详情在列表里能看到失败状态与原因。
      const first = result.failed[0]
      ElMessage.error(`${result.failed.length} 个文件上传失败：${first.filename}（${first.message}）`)
    }
    // 新文档按创建时间倒序排在首页；留在旧页码上会让人以为上传没生效。
    if (page.value !== 0) {
      page.value = 0
    } else {
      await load(true)
    }
  } finally {
    uploading.value = false
    uploadProgress.value = null
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

async function rechunkOne(row: DocumentRow): Promise<void> {
  try {
    await rechunkDocument(row.id)
    ElMessage.success(`「${row.filename}」已重新排队裁切`)
    await load(true)
  } catch (e) {
    ElMessage.error(describeKnowledgeError(e))
  }
}

async function rechunkSelected(): Promise<void> {
  const ids = selectedRows.value.map((row) => row.id)
  if (ids.length === 0) {
    return
  }
  try {
    await ElMessageBox.confirm(
      `确认重新裁切选中的 ${ids.length} 篇文档？将按知识库当前的切片配置重新切片并重新向量化。`,
      '批量重新裁切',
      { type: 'warning', confirmButtonText: '开始', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  rechunking.value = true
  try {
    const result = await rechunkDocuments(ids)
    if (result.skipped.length > 0) {
      ElMessage.warning(
        `已提交 ${result.accepted} 篇，跳过 ${result.skipped.length} 篇：${result.skipped[0].reason}`
      )
    } else {
      ElMessage.success(`已提交 ${result.accepted} 篇重新裁切`)
    }
    tableRef.value?.clearSelection()
    await load(true)
  } catch (e) {
    ElMessage.error(describeKnowledgeError(e))
  } finally {
    rechunking.value = false
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

function onSelectionChange(selection: DocumentRow[]): void {
  selectedRows.value = selection
}

function selectable(row: DocumentRow): boolean {
  return RECHUNKABLE.includes(row.status)
}

function isRechunkable(row: DocumentRow): boolean {
  return RECHUNKABLE.includes(row.status)
}

function queueHint(row: DocumentRow): string {
  return row.status === 'PENDING' && row.queuePosition ? `排队中 · 第 ${row.queuePosition} 位` : ''
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
        <span v-if="selectedRows.length > 0" class="selected-hint">
          已选 {{ selectedRows.length }} 篇
        </span>
      </span>
      <div class="actions">
        <el-button size="small" :loading="loading" data-test="docs-refresh" @click="load(false)">
          刷新
        </el-button>
        <el-button
          v-if="!readonly"
          size="small"
          :disabled="selectedRows.length === 0"
          :loading="rechunking"
          data-test="docs-batch-rechunk"
          @click="rechunkSelected"
        >
          批量重新裁切
        </el-button>
        <el-button
          v-if="!readonly"
          size="small"
          type="primary"
          :loading="uploading"
          data-test="docs-upload"
          @click="pickFile"
        >
          {{ uploadLabel }}
        </el-button>
        <input ref="fileInput" type="file" multiple class="file-input" @change="onFilesChosen" />
      </div>
    </div>

    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon class="alert" />

    <el-table
      ref="tableRef"
      v-loading="loading"
      :data="rows"
      row-key="id"
      size="small"
      empty-text="该节点下还没有文档"
      @selection-change="onSelectionChange"
    >
      <el-table-column v-if="!readonly" type="selection" width="42" :selectable="selectable" />
      <el-table-column prop="filename" label="文件名" min-width="200" show-overflow-tooltip />
      <el-table-column label="大小" width="92">
        <template #default="{ row }">{{ formatSize(row.size) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="150">
        <template #default="{ row }">
          <el-tooltip :disabled="!row.errorMsg" :content="row.errorMsg || ''" placement="top">
            <span class="status-cell">
              <el-tag :type="STATUS_TAG[row.status as DocumentStatus]" size="small">
                {{ STATUS_LABEL[row.status as DocumentStatus] }}
              </el-tag>
              <span v-if="queueHint(row)" class="queue-hint">{{ queueHint(row) }}</span>
            </span>
          </el-tooltip>
        </template>
      </el-table-column>
      <el-table-column label="上传时间" width="170">
        <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="240" fixed="right">
        <template #default="{ row }">
          <el-button link size="small" data-test="docs-chunks" @click="openChunks(row)">片段</el-button>
          <el-button
            v-if="!readonly && isRechunkable(row)"
            link
            size="small"
            data-test="docs-rechunk"
            @click="rechunkOne(row)"
          >
            重新裁切
          </el-button>
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

.selected-hint {
  margin-left: 8px;
  color: #409eff;
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

.status-cell {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.queue-hint {
  color: #909399;
  font-size: 12px;
}

.pager {
  margin-top: 12px;
  justify-content: flex-end;
}
</style>
