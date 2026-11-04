<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  createModel,
  createProvider,
  deleteModel,
  deleteProvider,
  describeProbeFailure,
  importModels,
  listModels,
  listProviders,
  listRemoteModels,
  probeProvider,
  updateModel,
  updateProvider,
  type Model,
  type ModelCapability,
  type Provider,
  type ProviderType
} from '@/api/providers'
import { describeKnowledgeError } from '@/api/knowledge'

/**
 * Providers and their model rows.
 *
 * Two things the UI must not hide:
 *  - the API never returns a key, only a mask. An "empty key" field therefore means "leave it alone",
 *    and clearing a stored key is an explicit action, not a side effect of saving;
 *  - a model row is what makes a model usable at all: agents reference `models.id`, so a provider that
 *    probes fine but owns no rows cannot be selected anywhere.
 */
const providers = ref<Provider[]>([])
const loading = ref(false)
const error = ref('')

const providerDialog = ref(false)
const providerSaving = ref(false)
const editingProviderId = ref<number | null>(null)
const providerForm = reactive({
  name: '',
  type: 'OPENAI' as ProviderType,
  baseUrl: '',
  apiKey: ''
})

const modelsDialog = ref(false)
const modelsProvider = ref<Provider | null>(null)
const models = ref<Model[]>([])
const modelsLoading = ref(false)
const modelForm = reactive({
  modelName: '',
  capability: 'CHAT' as ModelCapability,
  dimension: 1024 as number | null
})
const modelSaving = ref(false)

const remoteNames = ref<string[]>([])
const remoteSelected = ref<string[]>([])
const remoteLoading = ref(false)
const remoteError = ref('')
const importCapability = ref<ModelCapability>('CHAT')
const importDimension = ref<number | null>(1024)

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    providers.value = (await listProviders()).content
  } catch (e) {
    error.value = describeKnowledgeError(e)
    providers.value = []
  } finally {
    loading.value = false
  }
}

onMounted(load)

function openCreateProvider(): void {
  editingProviderId.value = null
  providerForm.name = ''
  providerForm.type = 'OPENAI'
  providerForm.baseUrl = ''
  providerForm.apiKey = ''
  providerDialog.value = true
}

function openEditProvider(row: Provider): void {
  editingProviderId.value = row.id
  providerForm.name = row.name
  providerForm.type = row.type
  providerForm.baseUrl = row.baseUrl
  providerForm.apiKey = ''
  providerDialog.value = true
}

async function saveProvider(): Promise<void> {
  if (!providerForm.name.trim() || !providerForm.baseUrl.trim()) {
    ElMessage.warning('请填写名称与地址')
    return
  }
  providerSaving.value = true
  try {
    if (editingProviderId.value === null) {
      await createProvider({
        name: providerForm.name.trim(),
        type: providerForm.type,
        baseUrl: providerForm.baseUrl.trim(),
        ...(providerForm.apiKey ? { apiKey: providerForm.apiKey } : {})
      })
      ElMessage.success('已创建')
    } else {
      // An empty field means "unchanged": the backend would treat "" as "clear the key".
      await updateProvider(editingProviderId.value, {
        name: providerForm.name.trim(),
        type: providerForm.type,
        baseUrl: providerForm.baseUrl.trim(),
        ...(providerForm.apiKey ? { apiKey: providerForm.apiKey } : {})
      })
      ElMessage.success('已保存')
    }
    providerDialog.value = false
    await load()
  } catch (e) {
    ElMessage.error(describeKnowledgeError(e))
  } finally {
    providerSaving.value = false
  }
}

async function clearKey(row: Provider): Promise<void> {
  try {
    await ElMessageBox.confirm(`确认清除「${row.name}」的 API Key？`, '清除密钥', { type: 'warning' })
  } catch {
    return
  }
  try {
    await updateProvider(row.id, { apiKey: '' })
    ElMessage.success('已清除')
    await load()
  } catch (e) {
    ElMessage.error(describeKnowledgeError(e))
  }
}

async function probe(row: Provider): Promise<void> {
  try {
    const result = await probeProvider(row.id)
    if (result.ok) {
      ElMessage.success(`连接正常，远端 ${result.remoteModels.length} 个模型`)
    } else {
      ElMessage.error(describeProbeFailure(result.errorCode))
    }
  } catch (e) {
    ElMessage.error(describeKnowledgeError(e))
  }
}

async function removeProvider(row: Provider): Promise<void> {
  try {
    await ElMessageBox.confirm(`确认删除「${row.name}」及其模型？被引用的模型会阻止删除。`, '删除提供商', {
      type: 'warning'
    })
  } catch {
    return
  }
  try {
    await deleteProvider(row.id)
    ElMessage.success('已删除')
    await load()
  } catch (e) {
    ElMessage.error(describeKnowledgeError(e))
  }
}

// --------------------------------------------------------------------------- models

async function openModels(row: Provider): Promise<void> {
  modelsProvider.value = row
  modelsDialog.value = true
  remoteNames.value = []
  remoteSelected.value = []
  remoteError.value = ''
  modelForm.modelName = ''
  modelForm.capability = 'CHAT'
  modelForm.dimension = 1024
  await loadModels()
}

async function loadModels(): Promise<void> {
  if (modelsProvider.value === null) {
    return
  }
  modelsLoading.value = true
  try {
    models.value = await listModels(modelsProvider.value.id)
  } catch (e) {
    ElMessage.error(describeKnowledgeError(e))
    models.value = []
  } finally {
    modelsLoading.value = false
  }
}

async function addModel(): Promise<void> {
  if (modelsProvider.value === null || !modelForm.modelName.trim()) {
    ElMessage.warning('请填写模型名称')
    return
  }
  modelSaving.value = true
  try {
    await createModel(modelsProvider.value.id, {
      modelName: modelForm.modelName.trim(),
      capability: modelForm.capability,
      dimension: modelForm.capability === 'EMBEDDING' ? modelForm.dimension : null
    })
    modelForm.modelName = ''
    ElMessage.success('已添加')
    await Promise.all([loadModels(), load()])
  } catch (e) {
    ElMessage.error(describeKnowledgeError(e))
  } finally {
    modelSaving.value = false
  }
}

async function toggleModel(row: Model, value: unknown): Promise<void> {
  try {
    await updateModel(row.id, { enabled: Boolean(value) })
  } catch (e) {
    ElMessage.error(describeKnowledgeError(e))
    await loadModels()
  }
}

async function removeModel(row: Model): Promise<void> {
  try {
    await ElMessageBox.confirm(`确认删除模型「${row.modelName}」？`, '删除模型', { type: 'warning' })
  } catch {
    return
  }
  try {
    await deleteModel(row.id)
    await Promise.all([loadModels(), load()])
  } catch (e) {
    ElMessage.error(describeKnowledgeError(e))
  }
}

async function loadRemote(): Promise<void> {
  if (modelsProvider.value === null) {
    return
  }
  remoteLoading.value = true
  remoteError.value = ''
  try {
    const result = await listRemoteModels(modelsProvider.value.id)
    if (!result.ok) {
      remoteError.value = describeProbeFailure(result.errorCode)
      remoteNames.value = []
      return
    }
    remoteNames.value = result.models
    remoteSelected.value = []
  } catch (e) {
    remoteError.value = describeKnowledgeError(e)
  } finally {
    remoteLoading.value = false
  }
}

async function runImport(): Promise<void> {
  if (modelsProvider.value === null || remoteSelected.value.length === 0) {
    ElMessage.warning('请选择要导入的模型')
    return
  }
  try {
    await importModels(
      modelsProvider.value.id,
      remoteSelected.value.map((modelName) => ({
        modelName,
        capability: importCapability.value,
        dimension: importCapability.value === 'EMBEDDING' ? importDimension.value : null
      }))
    )
    ElMessage.success(`已导入 ${remoteSelected.value.length} 个模型`)
    remoteSelected.value = []
    await Promise.all([loadModels(), load()])
  } catch (e) {
    ElMessage.error(describeKnowledgeError(e))
  }
}
</script>

<template>
  <el-card shadow="never">
    <div class="toolbar">
      <div>
        <h2 class="title">提供商与模型</h2>
        <p class="hint">Agent 只能引用这里的模型行；embedding 模型必须是 1024 维。</p>
      </div>
      <el-button type="primary" data-test="provider-create" @click="openCreateProvider">
        新建提供商
      </el-button>
    </div>

    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon class="alert" />

    <el-table v-loading="loading" :data="providers" empty-text="还没有提供商">
      <el-table-column prop="name" label="名称" min-width="150" />
      <el-table-column prop="type" label="类型" width="100" />
      <el-table-column prop="baseUrl" label="地址" min-width="220" show-overflow-tooltip />
      <el-table-column label="API Key" width="140">
        <template #default="{ row }">
          <el-tag v-if="row.apiKeyMasked" size="small" type="info">{{ row.apiKeyMasked }}</el-tag>
          <span v-else class="muted">未配置</span>
        </template>
      </el-table-column>
      <el-table-column prop="modelCount" label="模型" width="80" />
      <el-table-column label="状态" width="86">
        <template #default="{ row }">
          <el-tag :type="row.enabled ? 'success' : 'info'" size="small">
            {{ row.enabled ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="250" fixed="right">
        <template #default="{ row }">
          <el-button link size="small" data-test="provider-probe" @click="probe(row)">测试</el-button>
          <el-button link size="small" data-test="provider-models" @click="openModels(row)">模型</el-button>
          <el-button link size="small" @click="openEditProvider(row)">编辑</el-button>
          <el-button v-if="row.apiKeyMasked" link size="small" type="warning" @click="clearKey(row)">
            清钥
          </el-button>
          <el-button link size="small" type="danger" @click="removeProvider(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog
      v-model="providerDialog"
      :title="editingProviderId === null ? '新建提供商' : '编辑提供商'"
      width="520px"
    >
      <el-form label-width="100px">
        <el-form-item label="名称" required>
          <el-input v-model="providerForm.name" maxlength="128" />
        </el-form-item>
        <el-form-item label="类型" required>
          <el-radio-group v-model="providerForm.type">
            <el-radio value="OPENAI">OpenAI 兼容</el-radio>
            <el-radio value="OLLAMA">Ollama</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="地址" required>
          <el-input v-model="providerForm.baseUrl" placeholder="https://api.openai.com 或 http://host:11434" />
        </el-form-item>
        <el-form-item label="API Key">
          <el-input
            v-model="providerForm.apiKey"
            type="password"
            show-password
            :placeholder="editingProviderId === null ? '可留空' : '留空表示不修改'"
          />
          <div class="field-hint">保存后只返回掩码；清除密钥请用列表里的「清钥」。</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="providerDialog = false">取消</el-button>
        <el-button type="primary" :loading="providerSaving" data-test="provider-save" @click="saveProvider">
          保存
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="modelsDialog" :title="`模型 · ${modelsProvider?.name ?? ''}`" width="720px">
      <el-table v-loading="modelsLoading" :data="models" size="small" empty-text="还没有模型">
        <el-table-column prop="modelName" label="模型名" min-width="180" show-overflow-tooltip />
        <el-table-column prop="capability" label="能力" width="110" />
        <el-table-column label="维度" width="90">
          <template #default="{ row }">{{ row.dimension ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="启用" width="80">
          <template #default="{ row }">
            <el-switch
              :model-value="row.enabled"
              size="small"
              @update:model-value="toggleModel(row, $event)"
            />
          </template>
        </el-table-column>
        <el-table-column label="" width="70">
          <template #default="{ row }">
            <el-button link size="small" type="danger" @click="removeModel(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-divider content-position="left">添加模型行</el-divider>
      <div class="inline-form">
        <el-input v-model="modelForm.modelName" placeholder="模型名，如 gpt-4o-mini" class="grow" />
        <el-select v-model="modelForm.capability" class="capability">
          <el-option label="CHAT" value="CHAT" />
          <el-option label="EMBEDDING" value="EMBEDDING" />
        </el-select>
        <el-input-number
          v-if="modelForm.capability === 'EMBEDDING'"
          v-model="modelForm.dimension"
          :min="1"
          :max="8192"
          controls-position="right"
          class="dimension"
        />
        <el-button type="primary" :loading="modelSaving" data-test="model-add" @click="addModel">
          添加
        </el-button>
      </div>

      <el-divider content-position="left">从远端导入</el-divider>
      <div class="remote">
        <el-button size="small" :loading="remoteLoading" data-test="remote-load" @click="loadRemote">
          读取远端模型
        </el-button>
        <span v-if="remoteError" class="remote-error">{{ remoteError }}</span>
      </div>
      <template v-if="remoteNames.length > 0">
        <el-select v-model="remoteSelected" multiple filterable class="full" placeholder="选择要导入的模型">
          <el-option v-for="name in remoteNames" :key="name" :label="name" :value="name" />
        </el-select>
        <div class="inline-form import-row">
          <el-select v-model="importCapability" class="capability">
            <el-option label="CHAT" value="CHAT" />
            <el-option label="EMBEDDING" value="EMBEDDING" />
          </el-select>
          <el-input-number
            v-if="importCapability === 'EMBEDDING'"
            v-model="importDimension"
            :min="1"
            :max="8192"
            controls-position="right"
            class="dimension"
          />
          <el-button size="small" type="primary" data-test="remote-import" @click="runImport">
            导入所选
          </el-button>
        </div>
        <div class="field-hint">导入以模型名幂等：重复导入同名模型不会产生重复行。</div>
      </template>
    </el-dialog>
  </el-card>
</template>

<style scoped>
.toolbar {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: 12px;
  gap: 16px;
}

.title {
  margin: 0 0 4px;
  font-size: 18px;
}

.hint {
  margin: 0;
  color: #909399;
  font-size: 13px;
}

.muted {
  color: #c0c4cc;
  font-size: 12px;
}

.alert {
  margin-bottom: 12px;
}

.field-hint {
  color: #909399;
  font-size: 12px;
  line-height: 1.5;
  margin-top: 2px;
}

.inline-form {
  display: flex;
  align-items: center;
  gap: 8px;
}

.grow {
  flex: 1;
}

.capability {
  width: 130px;
}

.dimension {
  width: 130px;
}

.full {
  width: 100%;
}

.import-row {
  margin-top: 8px;
}

.remote {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.remote-error {
  color: #f56c6c;
  font-size: 12px;
}
</style>
