<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { createAgent, getAgent, listAgentVersions, updateAgent, type AgentVersion } from '@/api/agents'
import { listKnowledgeBases, describeKnowledgeError, type KnowledgeBase } from '@/api/knowledge'
import { listChatModels, type Model } from '@/api/providers'
import KbScopePicker from '@/components/KbScopePicker.vue'

/**
 * Agent create/edit form, one field per `AgentSnapshot` member.
 *
 * Three rules are enforced in the UI because they are the ones an operator gets wrong:
 *  - `embeddingModelId` is never edited — it is derived from the selected knowledge bases;
 *  - the selected knowledge bases must share one embedding model, otherwise saving is disabled
 *    (the picker reports it through `validity-change`);
 *  - `maxRounds` stays inside 1–6 and `topK` inside 1–100, the ranges the backend accepts.
 *
 * Saving always produces a new version, so the page reloads the agent and the version list instead of
 * patching its local copy: `currentVersion` must come from the server, not from a guess.
 */
const route = useRoute()
const router = useRouter()

const agentId = computed(() => (route.name === 'agent-edit' ? Number(route.params.id) : null))
const isEdit = computed(() => agentId.value !== null)

const kbs = ref<KnowledgeBase[]>([])
const chatModels = ref<Model[]>([])
const versions = ref<AgentVersion[]>([])
const currentVersion = ref<number | null>(null)
const loading = ref(false)
const saving = ref(false)
const loadError = ref('')
const scopeValid = ref(true)
const snapshotVisible = ref(false)
const snapshotVersion = ref<AgentVersion | null>(null)

const form = reactive({
  name: '',
  description: '',
  systemPrompt: '',
  chatModelId: null as number | null,
  kbIds: [] as number[],
  nodeScope: {} as Record<number, number[]>,
  maxRounds: 3,
  topK: 20,
  temperature: 0.7
})

const canSave = computed(
  () =>
    scopeValid.value &&
    form.name.trim().length > 0 &&
    form.systemPrompt.trim().length > 0 &&
    form.chatModelId !== null &&
    form.kbIds.length > 0
)

function applyAgent(agent: Awaited<ReturnType<typeof getAgent>>): void {
  form.name = agent.name
  form.description = agent.description ?? ''
  form.systemPrompt = agent.systemPrompt
  form.chatModelId = agent.chatModelId
  form.kbIds = [...agent.kbIds]
  form.nodeScope = Object.fromEntries(
    Object.entries(agent.nodeScope ?? {}).map(([kbId, ids]) => [Number(kbId), ids])
  )
  form.maxRounds = agent.maxRounds
  form.topK = agent.topK
  form.temperature = Number(agent.temperature)
  currentVersion.value = agent.currentVersion
}

async function load(): Promise<void> {
  loading.value = true
  loadError.value = ''
  try {
    const [kbPage, models] = await Promise.all([listKnowledgeBases(0, 100), listChatModels()])
    kbs.value = kbPage.content
    chatModels.value = models

    if (agentId.value !== null) {
      applyAgent(await getAgent(agentId.value))
      versions.value = await listAgentVersions(agentId.value)
    }
  } catch (e) {
    loadError.value = describeKnowledgeError(e)
  } finally {
    loading.value = false
  }
}

onMounted(load)

async function save(): Promise<void> {
  if (!canSave.value || form.chatModelId === null) {
    return
  }
  saving.value = true
  try {
    const body = {
      name: form.name.trim(),
      description: form.description.trim() || null,
      systemPrompt: form.systemPrompt,
      chatModelId: form.chatModelId,
      kbIds: form.kbIds,
      nodeScope: form.nodeScope,
      maxRounds: form.maxRounds,
      topK: form.topK,
      temperature: form.temperature
    }
    if (agentId.value === null) {
      const created = await createAgent(body)
      ElMessage.success(`已创建 v${created.currentVersion}`)
      await router.replace({ name: 'agent-edit', params: { id: created.id } })
      await load()
    } else {
      const updated = await updateAgent(agentId.value, body)
      ElMessage.success(`已保存为 v${updated.currentVersion}`)
      applyAgent(updated)
      versions.value = await listAgentVersions(agentId.value)
    }
  } catch (e) {
    ElMessage.error(describeKnowledgeError(e))
  } finally {
    saving.value = false
  }
}

function showSnapshot(version: AgentVersion): void {
  snapshotVersion.value = version
  snapshotVisible.value = true
}

function back(): void {
  void router.push({ name: 'agents' })
}

function formatTime(value: string): string {
  return new Date(value).toLocaleString('zh-CN', { hour12: false })
}
</script>

<template>
  <div v-loading="loading">
    <el-page-header class="header" @back="back">
      <template #content>
        <span class="name">{{ isEdit ? form.name || 'Agent' : '新建 Agent' }}</span>
        <el-tag v-if="currentVersion !== null" size="small" type="info" class="version">
          当前 v{{ currentVersion }}
        </el-tag>
      </template>
    </el-page-header>

    <el-alert v-if="loadError" :title="loadError" type="error" :closable="false" show-icon class="alert" />

    <el-row v-if="!loadError" :gutter="12">
      <el-col :span="15">
        <el-card shadow="never">
          <template #header>基础配置</template>
          <el-form label-width="120px">
            <el-form-item label="名称" required>
              <el-input v-model="form.name" maxlength="128" show-word-limit data-test="agent-name" />
            </el-form-item>
            <el-form-item label="描述">
              <el-input v-model="form.description" type="textarea" :rows="2" maxlength="500" />
            </el-form-item>
            <el-form-item label="System Prompt" required>
              <el-input
                v-model="form.systemPrompt"
                type="textarea"
                :rows="8"
                placeholder="描述角色、语气与回答边界。引用规则由系统追加，冲突时以引用规则为准。"
                data-test="agent-prompt"
              />
            </el-form-item>
            <el-form-item label="对话模型" required>
              <el-select v-model="form.chatModelId" placeholder="选择 CHAT 模型" class="full">
                <el-option
                  v-for="model in chatModels"
                  :key="model.id"
                  :label="`${model.modelName}（#${model.providerId}）`"
                  :value="model.id"
                  :disabled="!model.enabled"
                />
              </el-select>
              <div v-if="chatModels.length === 0" class="field-hint">
                还没有 CHAT 模型，请先在「提供商与模型」中添加。
              </div>
            </el-form-item>
            <el-form-item label="知识库范围" required>
              <KbScopePicker
                :kbs="kbs"
                :kb-ids="form.kbIds"
                :node-scope="form.nodeScope"
                @update:kb-ids="(value) => (form.kbIds = value)"
                @update:node-scope="(value) => (form.nodeScope = value)"
                @validity-change="(valid) => (scopeValid = valid)"
              />
            </el-form-item>
            <el-form-item label="最大轮次">
              <el-slider v-model="form.maxRounds" :min="1" :max="6" show-stops :marks="{ 1: '1', 3: '3', 6: '6' }" />
            </el-form-item>
            <el-form-item label="召回条数">
              <el-input-number v-model="form.topK" :min="1" :max="100" />
            </el-form-item>
            <el-form-item label="温度">
              <el-slider v-model="form.temperature" :min="0" :max="2" :step="0.05" show-input />
            </el-form-item>
            <el-form-item>
              <el-button
                type="primary"
                :loading="saving"
                :disabled="!canSave"
                data-test="agent-save"
                @click="save"
              >
                {{ isEdit ? '保存为新版本' : '创建' }}
              </el-button>
              <span v-if="!scopeValid" class="blocked">知识库 embedding 模型不一致，无法保存</span>
            </el-form-item>
          </el-form>
        </el-card>
      </el-col>

      <el-col :span="9">
        <el-card shadow="never">
          <template #header>版本历史</template>
          <el-empty v-if="!isEdit" description="保存后可查看版本历史" :image-size="60" />
          <el-table v-else :data="versions" size="small" empty-text="还没有版本">
            <el-table-column label="版本" width="72">
              <template #default="{ row }">v{{ row.version }}</template>
            </el-table-column>
            <el-table-column label="时间" min-width="130">
              <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
            </el-table-column>
            <el-table-column label="" width="70">
              <template #default="{ row }">
                <el-button link size="small" data-test="version-view" @click="showSnapshot(row)">
                  查看
                </el-button>
              </template>
            </el-table-column>
          </el-table>
          <div class="hint">版本只读；回滚 = 把旧值填回表单并保存成新版本。</div>
        </el-card>
      </el-col>
    </el-row>

    <el-dialog v-model="snapshotVisible" :title="`v${snapshotVersion?.version ?? ''} 快照`" width="640px">
      <pre class="snapshot">{{ JSON.stringify(snapshotVersion?.snapshot ?? {}, null, 2) }}</pre>
    </el-dialog>
  </div>
</template>

<style scoped>
.header {
  margin-bottom: 12px;
}

.name {
  font-size: 16px;
  font-weight: 600;
  margin-right: 8px;
}

.version {
  margin-left: 4px;
}

.alert {
  margin-bottom: 12px;
}

.full {
  width: 100%;
}

.field-hint {
  color: #909399;
  font-size: 12px;
  line-height: 1.5;
  margin-top: 2px;
}

.field-hint.inline {
  margin-left: 8px;
  margin-top: 0;
}

.hint {
  color: #909399;
  font-size: 12px;
  margin-top: 8px;
  line-height: 1.5;
}

.blocked {
  color: #f56c6c;
  font-size: 12px;
  margin-left: 12px;
}

.snapshot {
  margin: 0;
  max-height: 60vh;
  overflow: auto;
  font-size: 12px;
  line-height: 1.5;
}
</style>
