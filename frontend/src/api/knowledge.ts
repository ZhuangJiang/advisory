import { ApiError, http } from './client'

/**
 * Knowledge base, node, document and chunk calls.
 *
 * The backend contracts this file mirrors:
 *  - nodes arrive flat (`parentId`), the tree is assembled in the UI;
 *  - `PATCH /api/nodes/{id}` distinguishes "no move" (field absent) from "move to root" (`null`),
 *    which is why {@link patchNode} only copies keys the caller actually passed — `JSON.stringify`
 *    drops `undefined`, so an omitted `parentId` stays omitted;
 *  - upload is presign → PUT (straight to MinIO, no Authorization header) → create. The PUT must not
 *    carry our bearer token: MinIO validates the signature, and an extra `Authorization` header
 *    fails the request;
 *  - a soft-deleted or unknown document answers 404 on both `GET /documents/{id}` and its chunk list.
 */

export interface Page<T> {
  content: T[]
  page: number
  size: number
  total: number
  totalPages: number
}

export interface KnowledgeBase {
  id: number
  name: string
  description: string | null
  embeddingModelId: number | null
  chunkConfig: string
  reindexStatus: 'READY' | 'REINDEXING' | 'FAILED'
  createdAt: string
  documentCount: number
}

export interface KnowledgeNode {
  id: number
  kbId: number
  parentId: number | null
  name: string
  path: string
  sortOrder: number
}

export type DocumentStatus =
  | 'PENDING'
  | 'PARSING'
  | 'CHUNKING'
  | 'EMBEDDING'
  | 'READY'
  | 'FAILED'
  | 'DELETED'

export interface DocumentRow {
  id: number
  kbId: number
  nodeId: number | null
  filename: string
  objectKey: string
  mime: string | null
  size: number
  checksum: string | null
  status: DocumentStatus
  errorMsg: string | null
  /** 仅 `PENDING` 时有值：前面还有几篇文档在裁切队列里排着（从 1 开始）。 */
  queuePosition?: number | null
  createdAt: string
  updatedAt: string
}

export interface Chunk {
  id: number
  seq: number
  headingPath: string
  content: string
  tokenCount: number | null
}

export interface PresignResult {
  uploadUrl: string
  objectKey: string
  expiresInSeconds: number
}

export interface ModelSummary {
  id: number
  providerId: number
  modelName: string
  capability: string
  dimension: number | null
  enabled: boolean
}

interface ProviderSummary {
  id: number
  name: string
}

// --------------------------------------------------------------------------- knowledge bases

export function listKnowledgeBases(page = 0, size = 20): Promise<Page<KnowledgeBase>> {
  return http.get(`/api/knowledge-bases?page=${page}&size=${size}`)
}

export interface KnowledgeBaseInput {
  name: string
  description?: string | null
  embeddingModelId?: number | null
  chunkConfig?: string
}

export function createKnowledgeBase(body: KnowledgeBaseInput): Promise<KnowledgeBase> {
  return http.post('/api/knowledge-bases', body)
}

export function getKnowledgeBase(id: number): Promise<KnowledgeBase> {
  return http.get(`/api/knowledge-bases/${id}`)
}

export function updateKnowledgeBase(id: number, body: Partial<KnowledgeBaseInput>): Promise<KnowledgeBase> {
  return http.patch(`/api/knowledge-bases/${id}`, body)
}

export function reindexKnowledgeBase(id: number): Promise<void> {
  return http.post(`/api/knowledge-bases/${id}/reindex`)
}

export function deleteKnowledgeBase(id: number): Promise<void> {
  return http.delete(`/api/knowledge-bases/${id}`)
}

// --------------------------------------------------------------------------- nodes

export function listNodes(kbId: number): Promise<KnowledgeNode[]> {
  return http.get(`/api/knowledge-bases/${kbId}/nodes`)
}

export function createNode(kbId: number, parentId: number | null, name: string): Promise<KnowledgeNode> {
  return http.post(`/api/knowledge-bases/${kbId}/nodes`, { parentId, name })
}

/** `parentId` absent = rename only; `parentId: null` = move to root. */
export function patchNode(
  id: number,
  patch: { name?: string; parentId?: number | null }
): Promise<KnowledgeNode> {
  const body: Record<string, unknown> = {}
  if (patch.name !== undefined) {
    body.name = patch.name
  }
  if (patch.parentId !== undefined) {
    body.parentId = patch.parentId
  }
  return http.patch(`/api/nodes/${id}`, body)
}

export function deleteNode(id: number): Promise<void> {
  return http.delete(`/api/nodes/${id}`)
}

// --------------------------------------------------------------------------- documents

export function listDocuments(
  params: { kbId?: number; nodeId?: number | null; page?: number; size?: number }
): Promise<Page<DocumentRow>> {
  const query = new URLSearchParams()
  if (params.kbId !== undefined) {
    query.set('kbId', String(params.kbId))
  }
  if (params.nodeId !== undefined && params.nodeId !== null) {
    query.set('nodeId', String(params.nodeId))
  }
  query.set('page', String(params.page ?? 0))
  query.set('size', String(params.size ?? 50))
  return http.get(`/api/documents?${query.toString()}`)
}

export function presignUpload(body: {
  kbId: number
  nodeId: number | null
  filename: string
  size: number
}): Promise<PresignResult> {
  return http.post('/api/documents/presign', body)
}

export function registerDocument(body: {
  kbId: number
  nodeId: number | null
  filename: string
  objectKey: string
  size: number
}): Promise<DocumentRow> {
  return http.post('/api/documents', body)
}

/**
 * The three-step upload, as the spec requires it: the browser PUTs the bytes to MinIO directly, so a
 * 50 MB file never passes through the API process.
 */
export async function uploadDocument(input: {
  kbId: number
  nodeId: number | null
  file: File
}): Promise<DocumentRow> {
  const presigned = await presignUpload({
    kbId: input.kbId,
    nodeId: input.nodeId,
    filename: input.file.name,
    size: input.file.size
  })

  const put = await fetch(presigned.uploadUrl, { method: 'PUT', body: input.file })
  if (!put.ok) {
    throw new ApiError(put.status * 100, `上传到对象存储失败（HTTP ${put.status}）`, put.status)
  }

  return registerDocument({
    kbId: input.kbId,
    nodeId: input.nodeId,
    filename: input.file.name,
    objectKey: presigned.objectKey,
    size: input.file.size
  })
}

export function retryDocument(id: number): Promise<DocumentRow> {
  return http.post(`/api/documents/${id}/retry`)
}

/**
 * 重新裁切单篇文档：复用后端已保存的解析文本，按知识库当前的切片配置重新切片并向量化。
 *
 * 和 `retryDocument` 的区别：重试只服务于 `FAILED`（会重新解析原文件），重新裁切对 `READY` 也有意义，
 * 调完切片参数后不必整库重建。
 */
export function rechunkDocument(id: number): Promise<DocumentRow> {
  return http.post(`/api/documents/${id}/rechunk`)
}

export interface RechunkSkip {
  id: number
  reason: string
}

export interface RechunkResult {
  requested: number
  accepted: number
  skipped: RechunkSkip[]
}

/** 批量重新裁切：后端逐篇提交，不满足条件的只被跳过并附原因，不会整批失败。 */
export function rechunkDocuments(documentIds: number[]): Promise<RechunkResult> {
  return http.post('/api/documents/rechunk', { documentIds })
}

export interface UploadFailure {
  filename: string
  message: string
}

export interface UploadBatchResult {
  uploaded: DocumentRow[]
  failed: UploadFailure[]
}

/**
 * 多文件上传：逐个串行跑「presign → PUT → 登记」三步。
 *
 * 串行是刻意的。上传本身可以并发，但每个文件登记后都会进后端同一条裁切队列；串行提交让队列顺序等于
 * 用户勾选顺序，也避免几十个大的 PUT 同时打满浏览器连接数。单个文件失败只记进 `failed`，其余继续——
 * 批量上传时「一个坏文件拖垮整批」是最让人恼火的失败方式。
 */
export async function uploadDocuments(input: {
  kbId: number
  nodeId: number | null
  files: File[]
  onProgress?: (done: number, total: number, filename: string) => void
}): Promise<UploadBatchResult> {
  const uploaded: DocumentRow[] = []
  const failed: UploadFailure[] = []
  const total = input.files.length

  for (let index = 0; index < total; index++) {
    const file = input.files[index]
    input.onProgress?.(index, total, file.name)
    try {
      uploaded.push(await uploadDocument({ kbId: input.kbId, nodeId: input.nodeId, file }))
    } catch (e) {
      failed.push({ filename: file.name, message: describeKnowledgeError(e) })
    }
  }
  input.onProgress?.(total, total, '')

  return { uploaded, failed }
}

export function deleteDocument(id: number): Promise<void> {
  return http.delete(`/api/documents/${id}`)
}

export function listChunks(documentId: number, page = 0, size = 20): Promise<Page<Chunk>> {
  return http.get(`/api/documents/${documentId}/chunks?page=${page}&size=${size}`)
}

// --------------------------------------------------------------------------- models (read-only)

/**
 * EMBEDDING models, for the knowledge-base form and the "embedding 模型" column.
 *
 * Providers and their local model rows are readable by any authenticated user (only writes and the
 * remote probe are ADMIN-only), so the label works for a USER without a second endpoint.
 */
export async function listEmbeddingModels(): Promise<ModelSummary[]> {
  const providers = await http.get<Page<ProviderSummary>>('/api/providers?page=0&size=100')
  const perProvider = await Promise.all(
    providers.content.map((provider) =>
      http
        .get<ModelSummary[]>(`/api/providers/${provider.id}/models`)
        .catch(() => [] as ModelSummary[])
    )
  )
  return perProvider.flat().filter((model) => model.capability === 'EMBEDDING')
}

// --------------------------------------------------------------------------- error copy

/**
 * The messages the backend deliberately leaves generic. Everything else is shown as-is: the Java
 * services already phrase their validation errors for humans.
 */
const KNOWLEDGE_ERRORS: Record<number, string> = {
  40901: '同级下已存在同名节点',
  40902: '请先清空子节点或文档',
  40903: '知识库已被 Agent 引用，无法删除',
  40905: '重建索引已在进行中'
}

export function describeKnowledgeError(error: unknown): string {
  if (error instanceof ApiError) {
    return KNOWLEDGE_ERRORS[error.code] ?? error.message
  }
  return error instanceof Error ? error.message : '操作失败'
}
