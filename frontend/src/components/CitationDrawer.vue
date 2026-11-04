<script setup lang="ts">
import { computed } from 'vue'
import type { Citation } from '@/api/chat'

/**
 * The full text behind one `[n]` marker: the snippet the answer was grounded in, plus where it came
 * from. The drawer only reads — a citation is evidence, not something the reader edits.
 */
const props = defineProps<{
  modelValue: boolean
  citation: Citation | null
  index: number
}>()

const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()

const open = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value)
})
</script>

<template>
  <el-drawer v-model="open" :title="`引用 [${index + 1}]`" size="520px">
    <template v-if="citation">
      <el-descriptions :column="1" border size="small">
        <el-descriptions-item label="文档">{{ citation.docName }}</el-descriptions-item>
        <el-descriptions-item v-if="citation.nodeName" label="节点">
          {{ citation.nodeName }}
        </el-descriptions-item>
        <el-descriptions-item label="相关度">{{ citation.score.toFixed(3) }}</el-descriptions-item>
        <el-descriptions-item label="片段 ID">{{ citation.chunkId }}</el-descriptions-item>
      </el-descriptions>
      <div class="content" data-test="citation-content">{{ citation.content }}</div>
    </template>
  </el-drawer>
</template>

<style scoped>
.content {
  margin-top: 12px;
  padding: 12px;
  background: #fafbfc;
  border-radius: 6px;
  line-height: 1.8;
  font-size: 13px;
  color: #303133;
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
