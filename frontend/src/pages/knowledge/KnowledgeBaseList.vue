<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  createKnowledgeBase,
  deleteKnowledgeBase,
  describeKnowledgeError,
  listEmbeddingModels,
  listKnowledgeBases,
  reindexKnowledgeBase,
  type KnowledgeBase,
  type ModelSummary
} from '@/api/knowledge'
import { useAuthStore } from '@/stores/auth'

/**
 * Knowledge base list.
 *
 * Administrators get the write actions; a USER sees the same rows (they need to know what exists
 * before asking questions) but no "新建" button, and the backend would answer 403 anyway.
 *
 * The embedding model column resolves ids to names through the provider/model read endpoints —
 * `knowledge_bases` only stores the id, and the model row can be renamed, so the name is never
 * denormalised here.
 */
const auth = useAuthStore()
const router = useRouter()

const rows = ref<KnowledgeBase[]>([])
const total = ref(0)
const page = ref(0)
const size = 10
const loading = ref(false)
const error = ref('')
const modelNames = ref<Record<number, string>>({})
const models = ref<ModelSummary[]>([])

const dialogVisible = ref(false)
const saving = ref(false)
const form = reactive({ name: '', description: '', embeddingModelId: null as number | null })

const canWrite = computed(() => auth.isAdmin)

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const result = await listKnowledgeBases(page.value, size)
    rows.value = result.content
    total.value = result.total
  } catch (e) {
    error.value = describeKnowledgeError(e)
    rows.value = []
  } finally {
    loading.value = false
  }
}

/** Names are a nice-to-have: a USER without provider access still gets a usable table. */
async function loadModels(): Promise<void> {
  try {
    models.value = await listEmbeddingModels()
    modelNames.value = Object.fromEntries(models.value.map((model) => [model.id, model.modelName]))
  } catch {
    models.value = []
    modelNames.value = {}
  }
}

onMounted(async () => {
  await Promise.all([load(), loadModels()])
})

function openCreate(): void {
  form.name = ''
  form.description = ''
  form.embeddingModelId = models.value.find((model) => model.enabled)?.id ?? null
  dialogVisible.value = true
}

async function submit(): Promise<void> {
  if (!form.name.trim()) {
    ElMessage.warning('请填写知识库名称')
    return
  }
  if (form.embeddingModelId === null) {
    ElMessage.warning('请选择 embedding 模型')
    return
  }
  saving.value = true
  try {
    await createKnowledgeBase({
      name: form.name.trim(),
      description: form.description.trim() || null,
      embeddingModelId: form.embeddingModelId
    })
    dialogVisible.value = false
    ElMessage.success('已创建')
    await load()
  } catch (e) {
    ElMessage.error(describeKnowledgeError(e))
  } finally {
    saving.value = false
  }
}

async function reindex(row: KnowledgeBase): Promise<void> {
  try {
    await reindexKnowledgeBase(row.id)
    ElMessage.success('已开始重建索引，期间检索会被阻塞')
    await load()
  } catch (e) {
    ElMessage.error(describeKnowledgeError(e))
  }
}

async function remove(row: KnowledgeBase): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确认删除知识库「${row.name}」？其中的节点与文档会一并删除。`,
      '删除知识库',
      { type: 'warning' }
    )
  } catch {
    return
  }
  try {
    await deleteKnowledgeBase(row.id)
    ElMessage.success('已删除')
    await load()
  } catch (e) {
    ElMessage.error(describeKnowledgeError(e))
  }
}

function open(row: KnowledgeBase): void {
  void router.push({ name: 'knowledge-base-detail', params: { id: row.id } })
}

function modelLabel(id: number | null): string {
  if (id === null) {
    return '未配置'
  }
  return modelNames.value[id] ?? `#${id}`
}

function formatTime(value: string): string {
  return new Date(value).toLocaleString('zh-CN', { hour12: false })
}
</script>

<template>
  <el-card shadow="never">
    <div class="toolbar">
      <div>
        <h2 class="title">知识库</h2>
        <p class="hint">左侧节点树决定文档归属，检索按 Agent 配置的节点范围过滤。</p>
      </div>
      <el-button v-if="canWrite" type="primary" data-test="kb-create" @click="openCreate">
        新建知识库
      </el-button>
    </div>

    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon class="alert" />

    <el-table v-loading="loading" :data="rows" empty-text="还没有知识库">
      <el-table-column label="名称" min-width="200">
        <template #default="{ row }">
          <el-link type="primary" @click="open(row)">{{ row.name }}</el-link>
          <div v-if="row.description" class="description">{{ row.description }}</div>
        </template>
      </el-table-column>
      <el-table-column prop="documentCount" label="文档数" width="92" />
      <el-table-column label="状态" width="120">
        <template #default="{ row }">
          <el-tag
            :type="row.reindexStatus === 'REINDEXING' ? 'warning' : row.reindexStatus === 'FAILED' ? 'danger' : 'success'"
            size="small"
          >
            {{ row.reindexStatus === 'REINDEXING' ? '重建中' : row.reindexStatus === 'FAILED' ? '异常' : '就绪' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="embedding 模型" min-width="160">
        <template #default="{ row }">{{ modelLabel(row.embeddingModelId) }}</template>
      </el-table-column>
      <el-table-column label="创建时间" width="170">
        <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" :width="canWrite ? 210 : 90" fixed="right">
        <template #default="{ row }">
          <el-button link size="small" data-test="kb-open" @click="open(row)">管理</el-button>
          <el-button v-if="canWrite" link size="small" data-test="kb-reindex" @click="reindex(row)">
            重建索引
          </el-button>
          <el-button v-if="canWrite" link size="small" type="danger" data-test="kb-delete" @click="remove(row)">
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
      @current-change="load"
    />

    <el-dialog v-model="dialogVisible" title="新建知识库" width="480px">
      <el-form label-width="110px">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" maxlength="128" show-word-limit data-test="kb-name" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="3" maxlength="500" />
        </el-form-item>
        <el-form-item label="embedding 模型" required>
          <el-select v-model="form.embeddingModelId" placeholder="选择模型" class="full">
            <el-option
              v-for="model in models"
              :key="model.id"
              :label="`${model.modelName}${model.dimension ? ` · ${model.dimension} 维` : ''}`"
              :value="model.id"
              :disabled="!model.enabled"
            />
          </el-select>
          <div class="field-hint">
            一个知识库只能用同一个模型向量化；维度必须与平台配置的 1024 维一致。
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" data-test="kb-submit" @click="submit">创建</el-button>
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

.description {
  color: #909399;
  font-size: 12px;
  margin-top: 2px;
}

.alert {
  margin-bottom: 12px;
}

.pager {
  margin-top: 12px;
  justify-content: flex-end;
}

.full {
  width: 100%;
}

.field-hint {
  color: #909399;
  font-size: 12px;
  line-height: 1.5;
  margin-top: 4px;
}
</style>
