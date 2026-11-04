import { http } from './client'
import type { Page } from './knowledge'

/**
 * Provider and model calls (spec §9).
 *
 * Two behaviours in this file are contracts, not conveniences:
 *  - `apiKeyMasked` is the only key material the API ever returns. It is omitted for a provider with
 *    no key, so the UI must render "未配置" rather than an empty string;
 *  - `PATCH /api/providers/{id}` reads `apiKey` three ways: absent = unchanged, `""` = clear,
 *    anything else = replace. {@link updateProvider} therefore only copies keys the caller passed —
 *    sending an empty `apiKey` by accident would wipe a working key.
 */

export type ProviderType = 'OPENAI' | 'OLLAMA'
export type ModelCapability = 'CHAT' | 'EMBEDDING'

export interface Provider {
  id: number
  name: string
  type: ProviderType
  baseUrl: string
  enabled: boolean
  createdAt: string
  modelCount: number
  apiKeyMasked?: string
}

export interface Model {
  id: number
  providerId: number
  modelName: string
  capability: ModelCapability
  dimension: number | null
  params: string
  enabled: boolean
  createdAt: string
}

export interface ProbeResult {
  ok: boolean
  errorCode: string | null
  remoteModels: string[]
}

export interface RemoteModels {
  ok: boolean
  errorCode: string | null
  models: string[]
}

export interface ProviderInput {
  name: string
  type: ProviderType
  baseUrl: string
  apiKey?: string
}

export interface ModelInput {
  modelName: string
  capability: ModelCapability
  dimension?: number | null
}

const PROBE_MESSAGES: Record<string, string> = {
  NETWORK_UNREACHABLE: '无法连接到提供商地址',
  AUTH_FAILED: 'API Key 无效或权限不足',
  MODEL_NOT_FOUND: '提供商上找不到该模型',
  TIMEOUT: '连接超时'
}

export function describeProbeFailure(errorCode: string | null): string {
  if (!errorCode) {
    return '连通性测试失败'
  }
  return PROBE_MESSAGES[errorCode] ?? errorCode
}

// --------------------------------------------------------------------------- providers

export function listProviders(page = 0, size = 100): Promise<Page<Provider>> {
  return http.get(`/api/providers?page=${page}&size=${size}`)
}

export function createProvider(body: ProviderInput): Promise<Provider> {
  return http.post('/api/providers', body)
}

/** Absent keys stay absent — see the file header for the `apiKey` semantics. */
export function updateProvider(id: number, patch: Partial<ProviderInput & { enabled: boolean }>): Promise<Provider> {
  const body: Record<string, unknown> = {}
  for (const key of ['name', 'type', 'baseUrl', 'apiKey', 'enabled'] as const) {
    if (patch[key] !== undefined) {
      body[key] = patch[key]
    }
  }
  return http.patch(`/api/providers/${id}`, body)
}

export function deleteProvider(id: number): Promise<void> {
  return http.delete(`/api/providers/${id}`)
}

export function probeProvider(id: number, modelName?: string): Promise<ProbeResult> {
  return http.post(`/api/providers/${id}/test`, modelName ? { modelName } : {})
}

// --------------------------------------------------------------------------- models

export function listModels(providerId: number): Promise<Model[]> {
  return http.get(`/api/providers/${providerId}/models`)
}

export function createModel(providerId: number, body: ModelInput): Promise<Model> {
  return http.post(`/api/providers/${providerId}/models`, body)
}

export function updateModel(
  id: number,
  patch: { modelName?: string; dimension?: number | null; enabled?: boolean }
): Promise<Model> {
  const body: Record<string, unknown> = {}
  for (const key of ['modelName', 'dimension', 'enabled'] as const) {
    if (patch[key] !== undefined) {
      body[key] = patch[key]
    }
  }
  return http.patch(`/api/models/${id}`, body)
}

export function deleteModel(id: number): Promise<void> {
  return http.delete(`/api/models/${id}`)
}

export function listRemoteModels(providerId: number): Promise<RemoteModels> {
  return http.get(`/api/providers/${providerId}/models?remote=true`)
}

export function importModels(
  providerId: number,
  models: { modelName: string; capability: ModelCapability; dimension?: number | null }[]
): Promise<Model[]> {
  return http.post(`/api/providers/${providerId}/models/import`, { models })
}

/** CHAT models across every provider, for the agent form. Errors per provider are skipped. */
export async function listChatModels(): Promise<Model[]> {
  const providers = await listProviders()
  const perProvider = await Promise.all(
    providers.content.map((provider) => listModels(provider.id).catch(() => [] as Model[]))
  )
  return perProvider.flat().filter((model) => model.capability === 'CHAT')
}
