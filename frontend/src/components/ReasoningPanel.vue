<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { Phase, TokenUsage } from '@/api/chat'
import type { ReasoningStepView } from '@/stores/chat'
import { parseStepDetail, type StepDetail } from '@/utils/reasoning'

/**
 * 一次回答的推理过程，按「轮」分组并支持折叠。
 *
 * <p>分组是这块面板的意义所在：编排器只会 PLAN 一次，然后每轮 RETRIEVE → REFLECT，一条平铺的
 * 八行列表看不出这个循环。折叠是第二层需求——一轮里的四条步骤在排查问题时有用，平时只想看
 * 「第几轮、干了什么、花了多久」，所以每轮默认只展开**最后一轮**，其余收起。</p>
 *
 * <p>每一步还能再展开一层「详情」：那里是后端 `payload` 解析出来的结构化内容（检索查询、证据缺口、
 * 引用片段数）。摘要里塞不下这些，硬塞就会变成一大段读不下去的文字——这也正是原先 ANSWER 那行
 * 被整段 queries JSON 撑爆的原因。</p>
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

const PHASE_LABEL: Record<Phase, string> = {
  PLAN: '规划',
  RETRIEVE: '检索',
  REFLECT: '反思',
  ANSWER: '作答'
}

interface StepView {
  key: string
  step: ReasoningStepView
  detail: StepDetail | null
  open: boolean
}

interface RoundView {
  round: number
  steps: StepView[]
  summary: string
  latency: number
  open: boolean
}

/** 展开状态用 Set 存，改动时整体替换，保证 Vue 能感知。 */
const openRounds = ref<Set<number>>(new Set())
const openSteps = ref<Set<string>>(new Set())

const rounds = computed(() => {
  const grouped = new Map<number, ReasoningStepView[]>()
  for (const step of props.steps) {
    const list = grouped.get(step.round) ?? []
    list.push(step)
    grouped.set(step.round, list)
  }
  return [...grouped.entries()].sort((a, b) => a[0] - b[0])
})

const roundViews = computed<RoundView[]>(() =>
  rounds.value.map(([round, steps]) => ({
    round,
    open: openRounds.value.has(round),
    summary: steps
      .map((step) => step.summary)
      .filter((summary): summary is string => !!summary)
      .join(' · '),
    latency: steps.reduce((sum, step) => sum + (step.latencyMs ?? 0), 0),
    steps: steps.map((step) => {
      const key = `${step.round}-${step.phase}`
      return {
        key,
        step,
        detail: parseStepDetail(step.phase, step.payload),
        open: openSteps.value.has(key)
      }
    })
  }))
)

// 默认展开最后一轮；运行中新的一轮出现时也自动展开，用户手动收起的旧轮次保持收起。
watch(
  () => rounds.value.length,
  () => {
    const last = rounds.value[rounds.value.length - 1]
    if (last) {
      const next = new Set(openRounds.value)
      next.add(last[0])
      openRounds.value = next
    }
  },
  { immediate: true }
)

const allOpen = computed(() => roundViews.value.length > 0 && roundViews.value.every((view) => view.open))

const totalTokens = computed(() => {
  const usage = props.tokenUsage
  return usage ? usage.promptTokens + usage.completionTokens : 0
})

function toggleRound(round: number): void {
  const next = new Set(openRounds.value)
  if (next.has(round)) {
    next.delete(round)
  } else {
    next.add(round)
  }
  openRounds.value = next
}

function toggleAll(): void {
  openRounds.value = allOpen.value
    ? new Set()
    : new Set(roundViews.value.map((view) => view.round))
}

function toggleStep(key: string): void {
  const next = new Set(openSteps.value)
  if (next.has(key)) {
    next.delete(key)
  } else {
    next.add(key)
  }
  openSteps.value = next
}
</script>

<template>
  <div class="panel">
    <div class="panel-head">
      <span class="panel-title">推理过程</span>
      <span v-if="roundViews.length > 0" class="meta">{{ roundViews.length }} 轮</span>
      <span v-if="totalTokens > 0" class="meta">{{ totalTokens }} tokens</span>
      <span v-if="latencyMs !== null && latencyMs !== undefined" class="meta">{{ latencyMs }} ms</span>
      <el-button
        v-if="roundViews.length > 1"
        link
        type="primary"
        size="small"
        class="toggle-all"
        data-test="toggle-all"
        @click="toggleAll"
      >
        {{ allOpen ? '全部收起' : '全部展开' }}
      </el-button>
    </div>

    <el-empty v-if="steps.length === 0" description="暂无推理步骤" :image-size="60" />

    <section v-for="view in roundViews" :key="view.round" class="round" data-test="round">
      <button
        type="button"
        class="round-head"
        data-test="round-toggle"
        :aria-expanded="view.open"
        @click="toggleRound(view.round)"
      >
        <span class="caret" :class="{ open: view.open }">▸</span>
        <span class="round-title" data-test="round-title">第 {{ view.round }} 轮</span>
        <span class="round-summary" :title="view.summary">{{ view.summary }}</span>
        <span v-if="view.latency > 0" class="round-latency">{{ view.latency }} ms</span>
      </button>

      <div v-show="view.open" class="round-body" data-test="round-body">
        <div
          v-for="item in view.steps"
          :key="item.key"
          class="step"
          :class="{ running: item.step.status === 'started' }"
          data-test="step"
        >
          <el-tag
            :type="PHASE_TYPE[item.step.phase] ?? 'info'"
            size="small"
            class="phase"
            :title="PHASE_LABEL[item.step.phase]"
            data-test="step-phase"
          >
            {{ item.step.phase }}
          </el-tag>

          <div class="step-main">
            <div class="step-line">
              <span class="summary">
                {{ item.step.summary ?? (item.step.status === 'started' ? '进行中…' : '') }}
              </span>
              <span v-if="item.step.latencyMs !== null" class="latency">{{ item.step.latencyMs }} ms</span>
              <el-button
                v-if="item.detail"
                link
                type="primary"
                size="small"
                class="detail-toggle"
                data-test="step-detail-toggle"
                :aria-expanded="item.open"
                @click="toggleStep(item.key)"
              >
                {{ item.open ? '收起' : '详情' }}
              </el-button>
            </div>

            <div v-if="item.detail && item.open" class="step-detail" data-test="step-detail">
              <template v-if="item.detail.kind === 'plan' || item.detail.kind === 'retrieve'">
                <div class="detail-label">
                  {{ item.detail.kind === 'plan' ? '检索查询' : '本轮查询' }}（{{ item.detail.queries.length }}）
                </div>
                <ol class="detail-list">
                  <li v-for="(query, index) in item.detail.queries" :key="index">{{ query }}</li>
                </ol>
              </template>

              <template v-else-if="item.detail.kind === 'reflect'">
                <div class="detail-label">
                  证据判定：
                  <span v-if="item.detail.sufficient === true" class="ok">充分</span>
                  <span v-else-if="item.detail.sufficient === false" class="lack">不足</span>
                  <span v-else class="unknown">未解析</span>
                </div>
                <template v-if="item.detail.gaps.length > 0">
                  <div class="detail-label">缺口（{{ item.detail.gaps.length }}）</div>
                  <ul class="detail-list">
                    <li v-for="(gap, index) in item.detail.gaps" :key="index">{{ gap }}</li>
                  </ul>
                </template>
                <template v-if="item.detail.nextQueries.length > 0">
                  <div class="detail-label">下一轮查询（{{ item.detail.nextQueries.length }}）</div>
                  <ol class="detail-list">
                    <li v-for="(query, index) in item.detail.nextQueries" :key="index">{{ query }}</li>
                  </ol>
                </template>
              </template>

              <template v-else>
                <div class="detail-label">引用片段：{{ item.detail.citations }} 条</div>
              </template>
            </div>
          </div>
        </div>
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
  flex-wrap: wrap;
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

.toggle-all {
  margin-left: auto;
}

.round {
  border: 1px solid #ebeef5;
  border-radius: 6px;
  overflow: hidden;
  background: #fff;
}

.round-head {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 7px 8px;
  border: none;
  background: #fafbfc;
  cursor: pointer;
  text-align: left;
  font: inherit;
}

.round-head:hover {
  background: #f2f6fc;
}

.caret {
  font-size: 10px;
  color: #909399;
  transition: transform 0.15s ease;
}

.caret.open {
  transform: rotate(90deg);
}

.round-title {
  font-size: 12px;
  color: #303133;
  font-weight: 600;
  white-space: nowrap;
}

.round-summary {
  flex: 1;
  min-width: 0;
  font-size: 12px;
  color: #909399;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.round-latency {
  font-size: 12px;
  color: #c0c4cc;
  white-space: nowrap;
}

.round-body {
  padding: 6px;
}

.step {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  padding: 6px;
  border-radius: 4px;
  background: #fafbfc;
}

.step + .step {
  margin-top: 4px;
}

.step.running {
  background: #fdf6ec;
}

.phase {
  flex: none;
  margin-top: 1px;
}

.step-main {
  flex: 1;
  min-width: 0;
}

.step-line {
  display: flex;
  align-items: baseline;
  gap: 6px;
}

.summary {
  flex: 1;
  min-width: 0;
  font-size: 12px;
  line-height: 1.6;
  color: #606266;
  word-break: break-word;
}

.latency {
  color: #c0c4cc;
  font-size: 12px;
  white-space: nowrap;
}

.detail-toggle {
  flex: none;
}

.step-detail {
  margin-top: 6px;
  padding: 8px;
  border-radius: 4px;
  background: #fff;
  border: 1px solid #ebeef5;
  max-height: 240px;
  overflow-y: auto;
}

.detail-label {
  font-size: 12px;
  color: #909399;
}

.detail-label + .detail-list,
.detail-list + .detail-label {
  margin-top: 6px;
}

.detail-list {
  margin: 0;
  padding-left: 18px;
  font-size: 12px;
  line-height: 1.65;
  color: #606266;
  word-break: break-word;
}

.detail-list li {
  margin: 2px 0;
}

.ok {
  color: #67c23a;
}

.lack {
  color: #e6a23c;
}

.unknown {
  color: #909399;
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
