<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { testRunAgent, type AgentTestRun, type AgentTestStep } from '@/api/agents'
import { ApiError } from '@/api/client'

/**
 * Trial run drawer: asks a real question against a saved agent **without** writing a conversation.
 *
 * The trace is rendered by round rather than as a flat list because the orchestrator's loop is what
 * an operator is tuning: PLAN runs once, then RETRIEVE → REFLECT repeat until the evidence is judged
 * sufficient. A flat list of eight steps hides that shape.
 *
 * `truncated` is shown as a warning, not an error: the run still produced an answer, it just stopped
 * early (step timeout, or two unparsable reflections in a row).
 */
const props = defineProps<{
  modelValue: boolean
  agentId: number | null
  agentName: string
}>()

const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()

const question = ref('')
const running = ref(false)
const result = ref<AgentTestRun | null>(null)
const error = ref('')

const open = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value)
})

const PHASE_TAG: Record<string, 'info' | 'success' | 'warning' | 'danger'> = {
  PLAN: 'info',
  RETRIEVE: 'warning',
  REFLECT: 'success',
  ANSWER: 'danger'
}

const rounds = computed(() => {
  const grouped = new Map<number, AgentTestStep[]>()
  for (const step of result.value?.steps ?? []) {
    const list = grouped.get(step.round) ?? []
    list.push(step)
    grouped.set(step.round, list)
  }
  return [...grouped.entries()].sort((a, b) => a[0] - b[0])
})

watch(
  () => props.modelValue,
  (visible) => {
    if (!visible) {
      result.value = null
      error.value = ''
    }
  }
)

watch(
  () => props.agentId,
  () => {
    result.value = null
    error.value = ''
  }
)

async function run(): Promise<void> {
  if (props.agentId === null) {
    return
  }
  if (!question.value.trim()) {
    ElMessage.warning('请输入试运行问题')
    return
  }
  running.value = true
  error.value = ''
  try {
    result.value = await testRunAgent(props.agentId, { question: question.value.trim() })
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '试运行失败'
    result.value = null
  } finally {
    running.value = false
  }
}

function prettyPayload(payload: string): string {
  try {
    return JSON.stringify(JSON.parse(payload), null, 2)
  } catch {
    return payload
  }
}
</script>

<template>
  <el-drawer v-model="open" :title="`试运行 · ${agentName}`" size="720px">
    <div class="prompt">
      <el-input
        v-model="question"
        type="textarea"
        :rows="3"
        maxlength="8000"
        show-word-limit
        placeholder="输入一个真实问题，用当前版本配置跑一遍完整链路（不会写入会话记录）"
        data-test="test-question"
      />
      <div class="actions">
        <el-button type="primary" :loading="running" data-test="test-run" @click="run">运行</el-button>
        <span v-if="result" class="meta">
          版本 v{{ result.version }} · {{ result.latencyMs }} ms · {{ result.citations.length }} 条引用
        </span>
      </div>
    </div>

    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon class="alert" />
    <el-alert
      v-if="result?.truncated"
      title="本次运行提前结束（超时或连续解析失败），答案基于已有证据"
      type="warning"
      :closable="false"
      show-icon
      class="alert"
    />

    <el-card v-if="result" shadow="never" class="answer">
      <template #header>回答</template>
      <div class="answer-body">{{ result.answer }}</div>
    </el-card>

    <div v-for="[round, steps] in rounds" :key="round" class="round">
      <div class="round-title">第 {{ round }} 轮</div>
      <el-timeline>
        <el-timeline-item
          v-for="(step, index) in steps"
          :key="`${step.round}-${step.phase}-${index}`"
          :timestamp="`${step.latencyMs} ms`"
          placement="top"
        >
          <el-tag :type="PHASE_TAG[step.phase] ?? 'info'" size="small">{{ step.phase }}</el-tag>
          <span class="summary">{{ step.summary }}</span>
          <el-collapse v-if="step.payload && step.payload !== '{}'" class="payload">
            <el-collapse-item title="查看载荷">
              <pre>{{ prettyPayload(step.payload) }}</pre>
            </el-collapse-item>
          </el-collapse>
        </el-timeline-item>
      </el-timeline>
    </div>

    <el-card v-if="result && result.citations.length > 0" shadow="never" class="answer">
      <template #header>引用（{{ result.citations.length }}）</template>
      <div v-for="(citation, index) in result.citations" :key="citation.chunkId" class="citation">
        <div class="citation-head">
          <span class="index">[{{ index + 1 }}]</span>
          <span class="doc">{{ citation.docName }}</span>
          <el-tag v-if="citation.nodeName" size="small" type="info">{{ citation.nodeName }}</el-tag>
          <span class="score">{{ citation.score.toFixed(3) }}</span>
        </div>
        <div class="snippet">{{ citation.content }}</div>
      </div>
    </el-card>
  </el-drawer>
</template>

<style scoped>
.prompt {
  margin-bottom: 12px;
}

.actions {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 8px;
}

.meta {
  color: #909399;
  font-size: 12px;
}

.alert {
  margin-bottom: 12px;
}

.answer {
  margin-bottom: 12px;
}

.answer-body {
  white-space: pre-wrap;
  line-height: 1.7;
  color: #303133;
}

.round {
  margin-bottom: 8px;
}

.round-title {
  font-weight: 600;
  font-size: 13px;
  color: #606266;
  margin-bottom: 6px;
}

.summary {
  margin-left: 8px;
  color: #606266;
  font-size: 13px;
}

.payload {
  margin-top: 6px;
}

.payload pre {
  margin: 0;
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-word;
  color: #909399;
}

.citation {
  border-bottom: 1px solid #f0f2f5;
  padding: 6px 0;
}

.citation:last-child {
  border-bottom: none;
}

.citation-head {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
}

.index {
  font-weight: 600;
}

.doc {
  color: #303133;
}

.score {
  margin-left: auto;
  color: #909399;
  font-size: 12px;
}

.snippet {
  margin-top: 4px;
  color: #909399;
  font-size: 12px;
  line-height: 1.6;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
</style>
