import { http } from './client'
import { postSse, type SseHandlers } from './sse'
import type { Page } from './knowledge'

/**
 * Conversations, messages and the question stream (spec §11, §12).
 *
 * Three contracts here are not obvious from the urls:
 *  - a conversation locks the agent version at creation, so `agentVersion` in the sidebar row is
 *    informational — editing the agent never changes an existing thread;
 *  - `GET /conversations/{id}/messages` returns lightweight rows **without** steps or citations;
 *    the full record of one answer is `GET /messages/{id}`, which is also the reconnect path;
 *  - `ask` is a POST that answers with `text/event-stream`, hence {@link postSse} instead of
 *    {@link http}: the browser's `EventSource` cannot send an Authorization header or a body.
 */

export type MessageRole = 'USER' | 'ASSISTANT'
export type MessageStatus = 'PENDING' | 'STREAMING' | 'COMPLETED' | 'FAILED' | 'TRUNCATED'
export type Phase = 'PLAN' | 'RETRIEVE' | 'REFLECT' | 'ANSWER'

export interface Conversation {
  id: number
  agentId: number
  agentName: string
  agentVersion: number
  /** Owner. An administrator's list is unfiltered, so this is what marks a thread read-only. */
  userId: number
  title: string | null
  createdAt: string
  updatedAt: string
}

export interface MessageSummary {
  id: number
  conversationId: number
  role: MessageRole
  status: MessageStatus
  content: string | null
  citationCount: number
  createdAt: string
}

/** One citation; `kbId` / `nodeId` are absent on the live SSE frame, present on the persisted row. */
export interface Citation {
  chunkId: number
  documentId: number
  kbId?: number
  nodeId?: number | null
  docName: string
  nodeName: string | null
  content: string
  score: number
}

export interface ReasoningStepRecord {
  round: number
  phase: Phase
  summary: string | null
  payload: string
  latencyMs: number
  createdAt: string
}

export interface TokenUsage {
  promptTokens: number
  completionTokens: number
}

export interface MessageDetail {
  id: number
  conversationId: number
  role: MessageRole
  status: MessageStatus
  content: string | null
  citations: Citation[]
  steps: ReasoningStepRecord[]
  tokenUsage: TokenUsage | null
  latencyMs: number | null
  createdAt: string
}

export function listConversations(page = 0, size = 50): Promise<Page<Conversation>> {
  return http.get(`/api/conversations?page=${page}&size=${size}`)
}

export function createConversation(agentId: number, title?: string): Promise<Conversation> {
  return http.post('/api/conversations', { agentId, title: title ?? null })
}

export function listMessages(conversationId: number): Promise<MessageSummary[]> {
  return http.get(`/api/conversations/${conversationId}/messages`)
}

export function getMessage(messageId: number): Promise<MessageDetail> {
  return http.get(`/api/messages/${messageId}`)
}

/**
 * Asks a question and streams the run. The returned controller aborts the connection only; the
 * server finishes the answer and persists it, so an abort is never data loss.
 */
export function ask(
  conversationId: number,
  content: string,
  handlers: SseHandlers
): AbortController {
  return postSse(`/api/conversations/${conversationId}/messages`, { content }, handlers)
}
