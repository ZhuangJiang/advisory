import { fetchEventSource } from '@microsoft/fetch-event-source'
import { getAccessToken } from './session'

/**
 * SSE client for one question.
 *
 * `consumeSse` is a pure parser over a `ReadableStream` and knows nothing about HTTP, which is why
 * the protocol can be unit-tested byte by byte. `postSse` is the transport: it uses
 * `fetch-event-source` rather than `EventSource` because the browser's built-in implementation
 * cannot send an `Authorization` header nor a POST body.
 *
 * Frame contract (spec §11, produced by `SseStepSink`):
 *   meta      {messageId, conversationId}   — first frame, the reconnect handle
 *   step      {round, phase, status, summary}
 *   delta     {phase, text}
 *   citations {items: [{chunkId, documentId, docName, nodeName, snippet, score}]}
 *   done      {messageId, status, tokenUsage, latencyMs} — terminal
 *   error     {code, message, recoverable}  — terminal
 *   ping      {}                            — every 15s, ignored here
 */

export interface SseMeta {
  messageId: number
  conversationId: number
}

export interface SseStep {
  round: number
  phase: 'PLAN' | 'RETRIEVE' | 'REFLECT' | 'ANSWER'
  status: 'started' | 'finished'
  summary?: string | null
}

export interface SseDelta {
  phase: string
  text: string
}

export interface SseCitation {
  chunkId: number
  documentId: number
  docName: string
  nodeName: string | null
  snippet: string
  score: number
}

export interface SseCitations {
  items: SseCitation[]
}

export interface SseDone {
  messageId: number
  status: 'COMPLETED' | 'TRUNCATED' | 'FAILED'
  tokenUsage?: { promptTokens: number; completionTokens: number; totalTokens: number }
  latencyMs?: number
}

export interface SseError {
  code: number
  message: string
  recoverable?: boolean
}

export interface SseHandlers {
  onMeta?: (payload: SseMeta) => void
  onStep?: (payload: SseStep) => void
  onDelta?: (payload: SseDelta) => void
  onCitations?: (payload: SseCitations) => void
  onDone?: (payload: SseDone) => void
  onError?: (payload: SseError) => void
}

function parseJson(raw: string): unknown {
  try {
    return JSON.parse(raw)
  } catch {
    return {}
  }
}

/**
 * Dispatches one frame. Returns true when the frame terminates the stream (`done` / `error`), which
 * is what stops `consumeSse` from waiting for more bytes after the server finished.
 */
function dispatch(event: string, data: string, handlers: SseHandlers): boolean {
  const payload = data ? parseJson(data) : {}
  switch (event) {
    case 'meta':
      handlers.onMeta?.(payload as SseMeta)
      return false
    case 'step':
      handlers.onStep?.(payload as SseStep)
      return false
    case 'delta':
      handlers.onDelta?.(payload as SseDelta)
      return false
    case 'citations':
      handlers.onCitations?.(payload as SseCitations)
      return false
    case 'done':
      handlers.onDone?.(payload as SseDone)
      return true
    case 'error':
      handlers.onError?.(payload as SseError)
      return true
    default:
      // `ping` and anything unknown: heartbeats are not application events.
      return false
  }
}

function parseFrame(raw: string): { event: string; data: string } | null {
  let event = 'message'
  const data: string[] = []
  let sawField = false
  for (const line of raw.split('\n')) {
    if (line.startsWith(':')) {
      sawField = true // comment / keep-alive
      continue
    }
    const colon = line.indexOf(':')
    if (colon < 0) {
      continue
    }
    const field = line.slice(0, colon)
    const value = line.slice(colon + 1).replace(/^ /, '')
    if (field === 'event') {
      event = value.trim()
      sawField = true
    } else if (field === 'data') {
      data.push(value)
      sawField = true
    }
  }
  return sawField ? { event, data: data.join('\n') } : null
}

/**
 * Reads an SSE stream until `done`, `error`, or the stream closes.
 *
 * A frame split across chunks is reassembled; `\r\n` is normalised. A trailing partial frame (no
 * blank-line terminator) is discarded rather than dispatched with a half-parsed payload: the server
 * always terminates frames, so a partial one means the connection died.
 */
export async function consumeSse(
  stream: ReadableStream<Uint8Array>,
  handlers: SseHandlers
): Promise<void> {
  const reader = stream.getReader()
  const decoder = new TextDecoder()
  let buffer = ''

  try {
    for (;;) {
      const { value, done } = await reader.read()
      if (done) {
        return
      }
      buffer = (buffer + decoder.decode(value, { stream: true })).replace(/\r\n/g, '\n')

      let boundary = buffer.indexOf('\n\n')
      while (boundary >= 0) {
        const raw = buffer.slice(0, boundary)
        buffer = buffer.slice(boundary + 2)
        const frame = parseFrame(raw)
        if (frame && dispatch(frame.event, frame.data, handlers)) {
          return
        }
        boundary = buffer.indexOf('\n\n')
      }
    }
  } finally {
    try {
      await reader.cancel()
    } catch {
      // already closed
    }
    reader.releaseLock()
  }
}

/** HTTP statuses that mean "nothing was streamed" and carry a JSON envelope instead. */
export class SseHttpError extends Error {
  constructor(
    readonly status: number,
    readonly code: number,
    message: string
  ) {
    super(message)
    this.name = 'SseHttpError'
  }
}

/**
 * POSTs a question and feeds the frames to `handlers`. The returned controller aborts the stream —
 * the caller uses it when the component unmounts. The server keeps the run going and persists the
 * answer either way (spec §9), so aborting is never data loss.
 */
export function postSse(path: string, body: unknown, handlers: SseHandlers): AbortController {
  const controller = new AbortController()
  const token = getAccessToken()

  void fetchEventSource(path, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
      ...(token ? { Authorization: `Bearer ${token}` } : {})
    },
    body: JSON.stringify(body),
    signal: controller.signal,
    // A background tab must not abort an answer that is still being generated.
    openWhenHidden: true,
    async onopen(response) {
      if (response.ok) {
        return
      }
      const text = await response.text()
      let code = response.status * 100
      let message = `请求失败（HTTP ${response.status}）`
      try {
        const envelope = JSON.parse(text) as { code?: number; message?: string }
        code = envelope.code ?? code
        message = envelope.message ?? message
      } catch {
        // not an envelope: keep the generic message
      }
      throw new SseHttpError(response.status, code, message)
    },
    onmessage(message) {
      if (message.event) {
        dispatch(message.event, message.data, handlers)
      }
    },
    onclose() {
      // The server closed without `done`: nothing to retry, the caller reloads via GET /messages.
    },
    onerror(error) {
      // Rethrowing stops fetch-event-source's retry loop; a retry would re-ask the question.
      throw error
    }
  }).catch((error: unknown) => {
    if (controller.signal.aborted) {
      return
    }
    if (error instanceof SseHttpError) {
      handlers.onError?.({ code: error.code, message: error.message, recoverable: false })
      return
    }
    handlers.onError?.({
      code: 50000,
      message: error instanceof Error ? error.message : '连接中断，请刷新重试',
      recoverable: true
    })
  })

  return controller
}
