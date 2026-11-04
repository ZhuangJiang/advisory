import { http } from './client'
import type { Page } from './knowledge'
import type { MessageDetail } from './chat'

/**
 * Administrator audit (spec §12 `audit`).
 *
 * The list is denormalised on the server (user name, agent name, version, message count), so the
 * table needs exactly one request — there is no endpoint to join users and agents from the client.
 * `messageCount`/`lastMessageAt` are computed in SQL, which is why a thread without any answer still
 * appears with `lastMessageAt === null`.
 */

export interface AuditConversationRow {
  id: number
  userId: number
  userName: string
  agentId: number
  agentName: string
  agentVersion: number
  title: string | null
  messageCount: number
  createdAt: string
  lastMessageAt: string | null
}

export interface AuditConversationDetail {
  conversation: AuditConversationRow
  messages: MessageDetail[]
}

export interface AuditFilters {
  userId: number | null
  agentId: number | null
  /** `YYYY-MM-DD`, inclusive on the server. */
  from: string | null
  /** `YYYY-MM-DD`; a date-only value covers the whole day. */
  to: string | null
}

export interface AuditQuery extends Partial<AuditFilters> {
  page?: number
  size?: number
}

/**
 * Builds the query string. Empty filters are omitted rather than sent as `null`/blank: the backend
 * treats a missing parameter as "no filter", but a blank string would fail to parse as a date.
 */
function hasValue(value: unknown): boolean {
  return value !== null && value !== undefined && String(value).trim() !== ''
}

function toQueryString(query: AuditQuery): string {
  const params = new URLSearchParams()
  // A cleared `el-input` yields an empty string, not null: sending `userId=` would fail Long parsing.
  if (hasValue(query.userId)) {
    params.set('userId', String(query.userId))
  }
  if (hasValue(query.agentId)) {
    params.set('agentId', String(query.agentId))
  }
  if (query.from) {
    params.set('from', query.from)
  }
  if (query.to) {
    params.set('to', query.to)
  }
  params.set('page', String(query.page ?? 0))
  params.set('size', String(query.size ?? 20))
  return params.toString()
}

export function listAuditConversations(query: AuditQuery = {}): Promise<Page<AuditConversationRow>> {
  return http.get(`/api/admin/conversations?${toQueryString(query)}`)
}

/** The read-only thread: every message with its persisted steps and citations. */
export function getAuditConversation(id: number): Promise<AuditConversationDetail> {
  return http.get(`/api/admin/conversations/${id}`)
}

/** The default window: today and the six days before it. */
export function defaultRange(today = new Date()): [string, string] {
  const end = new Date(today.getFullYear(), today.getMonth(), today.getDate())
  const start = new Date(end)
  start.setDate(start.getDate() - 6)
  return [formatDate(start), formatDate(end)]
}

export function formatDate(date: Date): string {
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${date.getFullYear()}-${month}-${day}`
}
