<script setup lang="ts">
import { computed } from 'vue'
import type { ReasoningStepView } from '@/stores/chat'
import type { TokenUsage } from '@/api/chat'

/**
 * The reasoning trace of one answer, grouped by round.
 *
 * Grouping is the point: the orchestrator runs PLAN once and then RETRIEVE → REFLECT per round, so a
 * flat list of eight steps hides the loop an operator is trying to read. `status: 'started'` steps
 * are the live stream's "still working" marker; persisted steps always arrive finished with a
 * latency, which is why the two shapes are merged into one view model in the store.
 */
const props = defineProps<{
  steps: ReasoningStepView[]
  streaming: boolean
  tokenUsage?: TokenUsage | null
  latencyMs?: number | null
}>()

const PHASE_TYPE: Record<string, 'info' | 'success' | 'warning' | 'danger'> = {
  PLAN: 'info',
  RETRIEVE: 'warning',
  REFLECT: 'success',
  ANSWER: 'danger'
}

const rounds = computed(() => {
  const grouped = new Map<number, ReasoningStepView[]>()
  for (const step of props.steps) {
    const list = grouped.get(step.round) ?? []
    list.push(step)
    grouped.set(step.round, list)
  }
  return [...grouped.entries()].sort((a, b) => a[0] - b[0])
})

const totalTokens = computed(() => {
  const usage = props.tokenUsage
  return usage ? usage.promptTokens + usage.completionTokens : 0
})
</script>

<template>
  <div class="panel">
    <div class="panel-head">
      <span class="panel-title">推理过程</span>
      <span v-if="latencyMs !== null && latencyMs !== undefined" class="meta">{{ latencyMs }} ms</span>
      <span v-if="totalTokens > 0" class="meta">{{ totalTokens }} tokens</span>
    </div>

    <el-empty v-if="steps.length === 0" description="暂无推理步骤" :image-size="60" />

    <section v-for="[round, roundSteps] in rounds" :key="round" class="round" data-test="round">
      <div class="round-title" data-test="round-title">第 {{ round }} 轮</div>
      <div
        v-for="step in roundSteps"
        :key="`${step.round}-${step.phase}`"
        class="step"
        :class="{ running: step.status === 'started' }"
        data-test="step"
      >
        <el-tag :type="PHASE_TYPE[step.phase] ?? 'info'" size="small" data-test="step-phase">
          {{ step.phase }}
        </el-tag>
        <span class="summary">
          {{ step.summary ?? (step.status === 'started' ? '进行中…' : '') }}
        </span>
        <span v-if="step.latencyMs !== null" class="latency">{{ step.latencyMs }} ms</span>
      </div>
    </section>

    <div v-if="streaming" class="live">
      <span class="dot" />正在生成回答
    </div>
  </div>
</template>

<style scoped>
.panel {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.panel-head {
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.panel-title {
  font-weight: 600;
  font-size: 14px;
  color: #303133;
}

.meta {
  color: #909399;
  font-size: 12px;
}

.round-title {
  font-size: 12px;
  color: #909399;
  margin-bottom: 4px;
}

.step {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 6px 8px;
  border-radius: 4px;
  background: #fafbfc;
  margin-bottom: 4px;
}

.step.running {
  background: #fdf6ec;
}

.summary {
  flex: 1;
  font-size: 12px;
  line-height: 1.6;
  color: #606266;
  word-break: break-word;
}

.latency {
  color: #909399;
  font-size: 12px;
  white-space: nowrap;
}

.live {
  display: flex;
  align-items: center;
  gap: 6px;
  color: #e6a23c;
  font-size: 12px;
}

.dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #e6a23c;
  animation: pulse 1.2s ease-in-out infinite;
}

@keyframes pulse {
  0%,
  100% {
    opacity: 0.3;
  }
  50% {
    opacity: 1;
  }
}
</style>
