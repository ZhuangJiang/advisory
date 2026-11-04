<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { listChunks, describeKnowledgeError, type Chunk } from '@/api/knowledge'

/**
 * Read-only chunk inspector: proves what the pipeline actually stored for one document.
 *
 * Chunks are paged by `seq`, so the drawer reads the document in order instead of in whatever order
 * the rows happen to come back. `headingPath` is the section trail the chunker recorded; it is empty
 * for documents without headings, which is why it renders as a tag only when present.
 */
const props = defineProps<{
  modelValue: boolean
  documentId: number | null
  filename: string
}>()

const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()

const chunks = ref<Chunk[]>([])
const total = ref(0)
const page = ref(0)
const size = 10
const loading = ref(false)
const error = ref('')

const open = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value)
})

async function load(): Promise<void> {
  if (props.documentId === null) {
    return
  }
  loading.value = true
  error.value = ''
  try {
    const result = await listChunks(props.documentId, page.value, size)
    chunks.value = result.content
    total.value = result.total
  } catch (e) {
    error.value = describeKnowledgeError(e)
    chunks.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

watch(
  () => [props.modelValue, props.documentId, page.value] as const,
  ([visible]) => {
    if (visible) {
      void load()
    }
  },
  { immediate: true }
)

watch(
  () => props.documentId,
  () => {
    page.value = 0
  }
)
</script>

<template>
  <el-drawer v-model="open" :title="`片段预览 · ${filename}`" size="620px">
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon class="alert" />
    <div v-loading="loading" class="list">
      <el-empty v-if="!loading && chunks.length === 0" description="该文档还没有片段" />
      <el-card v-for="chunk in chunks" :key="chunk.id" shadow="never" class="chunk">
        <div class="head">
          <span class="seq">#{{ chunk.seq + 1 }}</span>
          <el-tag v-if="chunk.headingPath" size="small" type="info">{{ chunk.headingPath }}</el-tag>
          <span v-else class="no-heading">无标题</span>
          <span v-if="chunk.tokenCount" class="tokens">{{ chunk.tokenCount }} tokens</span>
        </div>
        <pre class="content">{{ chunk.content }}</pre>
      </el-card>
    </div>

    <el-pagination
      v-if="total > size"
      v-model:current-page="page"
      :page-size="size"
      :total="total"
      layout="prev, pager, next"
      class="pager"
    />
  </el-drawer>
</template>

<style scoped>
.alert {
  margin-bottom: 12px;
}

.list {
  min-height: 200px;
}

.chunk {
  margin-bottom: 8px;
}

.head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}

.seq {
  font-weight: 600;
  color: #303133;
}

.no-heading {
  color: #c0c4cc;
  font-size: 12px;
}

.tokens {
  margin-left: auto;
  color: #909399;
  font-size: 12px;
}

.content {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  font-family: inherit;
  font-size: 13px;
  line-height: 1.6;
  color: #606266;
}

.pager {
  margin-top: 12px;
  justify-content: center;
}
</style>
