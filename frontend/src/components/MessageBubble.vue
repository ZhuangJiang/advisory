<script setup lang="ts">
import { computed } from 'vue'
import type { ChatMessage } from '@/stores/chat'

/**
 * One message row. Two jobs:
 *
 *  - turn `[n]` into a clickable superscript. Only indices that actually have a citation are
 *    markers; a stray `[9]` in the text would otherwise open an empty drawer.
 *  - say out loud when an answer is incomplete. `TRUNCATED` is a real outcome (round limit or
 *    timeout) and a `FAILED` row carries the provider's reason, so neither is rendered as if it
 *    were a normal answer.
 */
const props = defineProps<{ message: ChatMessage }>()

const emit = defineEmits<{ cite: [index: number]; reasoning: [] }>()

type Segment = { kind: 'text'; text: string } | { kind: 'cite'; index: number }

const segments = computed<Segment[]>(() => {
  const text = props.message.content ?? ''
  const limit = props.message.citations.length
  const out: Segment[] = []
  const pattern = /\[(\d{1,3})\]/g
  let cursor = 0
  for (const match of text.matchAll(pattern)) {
    const index = Number(match[1]) - 1
    if (index < 0 || index >= limit || match.index === undefined) {
      continue
    }
    if (match.index > cursor) {
      out.push({ kind: 'text', text: text.slice(cursor, match.index) })
    }
    out.push({ kind: 'cite', index })
    cursor = match.index + match[0].length
  }
  if (cursor < text.length) {
    out.push({ kind: 'text', text: text.slice(cursor) })
  }
  return out
})

const isUser = computed(() => props.message.role === 'USER')
const statusLabel = computed(() => {
  switch (props.message.status) {
    case 'PENDING':
      return '准备中'
    case 'STREAMING':
      return '生成中'
    case 'COMPLETED':
      return '已完成'
    case 'TRUNCATED':
      return '提前结束'
    default:
      return '失败'
  }
})
</script>

<template>
  <div class="row" :class="isUser ? 'user' : 'assistant'" :data-test="`message-${message.role}`">
    <div class="bubble">
      <div class="head">
        <span class="who">{{ isUser ? '我' : '助手' }}</span>
        <el-tag
          v-if="message.status !== 'COMPLETED'"
          size="small"
          :type="message.status === 'FAILED' ? 'danger' : message.status === 'TRUNCATED' ? 'warning' : 'info'"
        >
          {{ statusLabel }}
        </el-tag>
        <el-button
          v-if="!isUser"
          link
          type="primary"
          size="small"
          data-test="open-reasoning"
          @click="emit('reasoning')"
        >
          推理过程
        </el-button>
      </div>

      <el-alert
        v-if="message.status === 'TRUNCATED'"
        title="已达最大轮数或超时，以下答案基于已有证据"
        type="warning"
        :closable="false"
        show-icon
        class="banner"
        data-test="truncated"
      />
      <el-alert
        v-if="message.status === 'FAILED'"
        title="生成失败"
        :description="message.content || '未能生成回答'"
        type="error"
        :closable="false"
        show-icon
        class="banner"
        data-test="failed"
      />

      <div v-if="message.status !== 'FAILED'" class="body">
        <template v-for="(segment, index) in segments" :key="index">
          <span v-if="segment.kind === 'text'">{{ segment.text }}</span>
          <sup
            v-else
            class="cite"
            data-test="cite"
            :title="message.citations[segment.index]?.docName ?? '引用'"
            @click="emit('cite', segment.index)"
          >
            {{ segment.index + 1 }}
          </sup>
        </template>
        <span v-if="message.status === 'STREAMING'" class="caret">▍</span>
      </div>

      <div v-if="!isUser && (message.citations.length > 0 || message.latencyMs !== null)" class="foot">
        <span v-if="message.citations.length > 0">{{ message.citations.length }} 条引用</span>
        <span v-if="message.latencyMs !== null">{{ message.latencyMs }} ms</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.row {
  display: flex;
  margin-bottom: 12px;
}

.row.user {
  justify-content: flex-end;
}

.bubble {
  max-width: 78%;
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  padding: 10px 12px;
}

.row.user .bubble {
  background: #ecf5ff;
  border-color: #d9ecff;
}

.head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}

.who {
  font-size: 12px;
  color: #909399;
}

.head :deep(.el-button) {
  margin-left: auto;
}

.banner {
  margin-bottom: 8px;
}

.body {
  white-space: pre-wrap;
  line-height: 1.75;
  font-size: 14px;
  color: #303133;
  word-break: break-word;
}

.cite {
  color: #409eff;
  cursor: pointer;
  font-size: 12px;
  padding: 0 1px;
}

.cite:hover {
  text-decoration: underline;
}

.caret {
  color: #409eff;
  animation: blink 1s step-end infinite;
}

@keyframes blink {
  50% {
    opacity: 0;
  }
}

.foot {
  display: flex;
  gap: 12px;
  margin-top: 6px;
  color: #909399;
  font-size: 12px;
}
</style>
