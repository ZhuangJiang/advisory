import { http } from './client'
import type { KnowledgeBase, Page } from './knowledge'

/**
 * Agent configuration, versions and trial runs (spec §9, §12).
 *
 * `embeddingModelId` is never sent: the backend derives it from the referenced knowledge bases, so a
 * client cannot make the stored value disagree with them. That derivation is also why
 * {@link embeddingConflict} exists — if two selected knowledge bases were vectorised by different
 * models, retrieval would compare vectors from different spaces, and the backend rejects the save.
 * The form checks it first so the operator sees why before a round trip.
 */

export interface Agent {
  id: number
  name: string
  description: string | null
  systemPrompt: string
  chatModelId: number | null
  embeddingModelId: number | null
  kbIds: number[]
  nodeScope: Record<string, number[]>
  maxRounds: number
  topK: number
  temperature: number
  enabled: boolean
  currentVersion: number
  createdAt: string
  updatedAt: string
}

export interface AgentSnapshot {
  name: string
  systemPrompt: string
  chatModelId: number | null
  embeddingModelId: number | null
  kbIds: number[]
  nodeScope: Record<string, number[]>
  maxRounds: number
  topK: number
  temperature: number
}

export interface AgentVersion {
  version: number
  snapshot: AgentSnapshot
  createdAt: string
}

export interface AgentTestStep {
  round: number
  phase: 'PLAN' | 'RETRIEVE' | 'REFLECT' | 'ANSWER'
  summary: string
  payload: string
  latencyMs: number
}

export interface AgentTestCitation {
  chunkId: number
  documentId: number
  kbId: number
  nodeId: number | null
  docName: string
  nodeName: string | null
  content: string
  score: number
}

export interface AgentTestRun {
  version: number
  answer: string
  steps: AgentTestStep[]
  citations: AgentTestCitation[]
  truncated: boolean
  latencyMs: number
}

export interface AgentInput {
  name: string
  description?: string | null
  systemPrompt: string
  chatModelId: number
  kbIds: number[]
  nodeScope: Record<number, number[]>
  maxRounds: number
  topK: number
  temperature: number
}

/**
 * 分页列出 Agent。
 *
 * `enabled` 缺省时不过滤（管理页要看到已下线的条目才能重新上线）；对话页传 `true`，
 * 由服务端与分页一起过滤——只取一页再本地筛会漏掉排在后面的在线 Agent。
 *
 * @param page 页码，从 0 开始
 * @param size 每页条数
 * @param enabled 是否只取在线 Agent
 */
export function listAgents(page = 0, size = 20, enabled?: boolean): Promise<Page<Agent>> {
  const filter = enabled === undefined ? '' : `&enabled=${enabled}`
  return http.get(`/api/agents?page=${page}&size=${size}${filter}`)
}

export function getAgent(id: number): Promise<Agent> {
  return http.get(`/api/agents/${id}`)
}

export function createAgent(body: AgentInput): Promise<Agent> {
  return http.post('/api/agents', body)
}

export function updateAgent(id: number, body: Partial<AgentInput>): Promise<Agent> {
  return http.patch(`/api/agents/${id}`, body)
}

/** ADMIN-only: online/offline only flips state, so the version history stays untouched. */
export function onlineAgent(id: number): Promise<Agent> {
  return http.post(`/api/agents/${id}/online`)
}

export function offlineAgent(id: number): Promise<Agent> {
  return http.post(`/api/agents/${id}/offline`)
}

export function deleteAgent(id: number): Promise<void> {
  return http.delete(`/api/agents/${id}`)
}

/** ADMIN-only: the version list carries every historical snapshot verbatim. */
export function listAgentVersions(id: number): Promise<AgentVersion[]> {
  return http.get(`/api/agents/${id}/versions`)
}

export function testRunAgent(
  id: number,
  body: { question: string; version?: number }
): Promise<AgentTestRun> {
  return http.post(`/api/agents/${id}/test`, body)
}

/**
 * The knowledge bases an agent may read must share one embedding model.
 *
 * Returns the offending knowledge bases so the form can name them, or an empty list when the
 * selection is consistent (or has fewer than two members).
 */
export function embeddingConflict(kbs: KnowledgeBase[], kbIds: number[]): KnowledgeBase[] {
  const selected = kbs.filter((kb) => kbIds.includes(kb.id))
  if (selected.length < 2) {
    return []
  }
  const models = new Set(selected.map((kb) => kb.embeddingModelId ?? -1))
  return models.size > 1 ? selected : []
}
