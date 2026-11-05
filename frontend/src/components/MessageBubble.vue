<script setup lang="ts">
import { computed } from 'vue'
import type { ChatMessage } from '@/stores/chat'
import { renderMarkdown } from '@/utils/markdown'

/**
 * One message row. Two jobs:
 *
 *  - render the answer as Markdown, with `[n]` turned into a clickable superscript. Only indices
 *    that actually have a citation become markers; a stray `[9]` in the text stays literal.
 *  - say out loud when an answer is incomplete. `TRUNCATED` is a real outcome (round limit or
 *    timeout) and a `FAILED` row carries the provider's reason, so neither is rendered as if it
 *    were a normal answer.
 *
 * 渲染成 HTML 之后就没有 Vue 的事件绑定了，引用角标改用事件委托：一个监听器挂在正文容器上，
 * 点击落在 `sup.cite` 时再按 `data-cite` 派发。流式期间每次增量都会重渲染，逐个绑定监听器
 * 既费事又会在重渲染时丢失。
 */
const props = defineProps<{ message: ChatMessage }>()

const emit = defineEmits<{ cite: [index: number]; reasoning: [] }>()

const html = computed(() =>
  renderMarkdown(
    props.message.content ?? '',
    props.message.citations.length,
    props.message.citations.map((citation) => citation.docName)
  )
)

function onBodyClick(event: MouseEvent): void {
  const target = event.target
  if (!(target instanceof Element)) {
    return
  }
  const marker = target.closest('sup.cite')
  const raw = marker?.getAttribute('data-cite')
  if (raw === null || raw === undefined) {
    return
  }
  const index = Number(raw)
  if (Number.isInteger(index) && index >= 0 && index < props.message.citations.length) {
    emit('cite', index)
  }
}

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
        <!-- 流式光标交给头部的「生成中」标签表达：正文渲染成块级元素后，行内光标只会掉到下一行 -->
        <!-- eslint-disable-next-line vue/no-v-html -- 已过 DOMPurify 消毒，见 utils/markdown.ts -->
        <div class="markdown" data-test="markdown" @click="onBodyClick" v-html="html" />
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
  line-height: 1.75;
  font-size: 14px;
  color: #303133;
  word-break: break-word;
}

/* Markdown 正文：只做「读得舒服」需要的那几类元素，不引入主题包 */
.markdown {
  overflow-x: auto;
}

.markdown :deep(> :first-child) {
  margin-top: 0;
}

.markdown :deep(> :last-child) {
  margin-bottom: 0;
}

.markdown :deep(p) {
  margin: 0 0 8px;
}

.markdown :deep(h1),
.markdown :deep(h2),
.markdown :deep(h3),
.markdown :deep(h4),
.markdown :deep(h5),
.markdown :deep(h6) {
  margin: 14px 0 6px;
  line-height: 1.4;
  font-weight: 600;
  color: #1f2329;
}

.markdown :deep(h1) {
  font-size: 18px;
}

.markdown :deep(h2) {
  font-size: 16px;
}

.markdown :deep(h3) {
  font-size: 15px;
}

.markdown :deep(h4),
.markdown :deep(h5),
.markdown :deep(h6) {
  font-size: 14px;
}

.markdown :deep(ul),
.markdown :deep(ol) {
  margin: 0 0 8px;
  padding-left: 22px;
}

.markdown :deep(li) {
  margin: 2px 0;
}

.markdown :deep(li > p) {
  margin: 0;
}

.markdown :deep(blockquote) {
  margin: 8px 0;
  padding: 4px 10px;
  border-left: 3px solid #dcdfe6;
  color: #606266;
  background: #fafbfc;
}

.markdown :deep(code) {
  padding: 1px 4px;
  border-radius: 3px;
  background: #f2f3f5;
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 12.5px;
}

.markdown :deep(pre) {
  margin: 8px 0;
  padding: 10px 12px;
  border-radius: 6px;
  background: #f6f8fa;
  overflow-x: auto;
}

.markdown :deep(pre code) {
  padding: 0;
  background: transparent;
}

.markdown :deep(table) {
  margin: 8px 0;
  border-collapse: collapse;
  font-size: 13px;
}

.markdown :deep(th),
.markdown :deep(td) {
  border: 1px solid #e4e7ed;
  padding: 5px 8px;
  text-align: left;
}

.markdown :deep(th) {
  background: #fafbfc;
  font-weight: 600;
}

.markdown :deep(hr) {
  margin: 12px 0;
  border: none;
  border-top: 1px solid #ebeef5;
}

.markdown :deep(a) {
  color: #409eff;
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

.foot {
  display: flex;
  gap: 12px;
  margin-top: 6px;
  color: #909399;
  font-size: 12px;
}
</style>
