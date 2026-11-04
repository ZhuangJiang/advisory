import { describe, expect, it } from 'vitest'
import { consumeSse, type SseHandlers } from '../src/api/sse'

/**
 * The SSE parser is the one piece of the frontend with a real protocol to honour: Spring's
 * `SseEmitter` writes `event:name\ndata:{json}\n\n`, a frame can be split across network chunks, and
 * `ping` frames arrive every 15s. Parsing is therefore tested against byte-level chunks, not against
 * a convenient already-parsed string.
 */

function streamOf(chunks: string[]): ReadableStream<Uint8Array> {
  const encoder = new TextEncoder()
  return new ReadableStream<Uint8Array>({
    start(controller) {
      for (const chunk of chunks) {
        controller.enqueue(encoder.encode(chunk))
      }
      controller.close()
    }
  })
}

function frame(event: string, data: unknown): string {
  return `event:${event}\ndata:${JSON.stringify(data)}\n\n`
}

describe('consumeSse', () => {
  it('dispatches meta, step, delta, citations and done in order', async () => {
    const seen: string[] = []
    const payloads: Record<string, unknown> = {}
    const handlers: SseHandlers = {
      onMeta: (payload) => {
        seen.push('meta')
        payloads.meta = payload
      },
      onStep: (payload) => {
        seen.push('step')
        payloads.step = payload
      },
      onDelta: () => seen.push('delta'),
      onCitations: () => seen.push('citations'),
      onDone: (payload) => {
        seen.push('done')
        payloads.done = payload
      }
    }

    await consumeSse(
      streamOf([
        frame('meta', { messageId: 11, conversationId: 3 }),
        frame('step', { round: 1, phase: 'PLAN', status: 'started' }),
        frame('delta', { phase: 'ANSWER', text: '答案 [1]' }),
        frame('citations', { items: [{ chunkId: 1, docName: 'a.pdf' }] }),
        frame('done', { messageId: 11, status: 'COMPLETED' })
      ]),
      handlers
    )

    expect(seen).toEqual(['meta', 'step', 'delta', 'citations', 'done'])
    expect(payloads.meta).toEqual({ messageId: 11, conversationId: 3 })
    expect(payloads.step).toMatchObject({ phase: 'PLAN', status: 'started' })
    expect(payloads.done).toMatchObject({ status: 'COMPLETED' })
  })

  it('reassembles a frame split across chunks and survives CRLF', async () => {
    const events: string[] = []
    const texts: string[] = []
    const raw = 'event:meta\r\ndata:{"messageId":7}\r\n\r\nevent:delta\r\ndata:{"text":"半句"}\r\n\r\n'

    await consumeSse(
      streamOf([raw.slice(0, 12), raw.slice(12, 30), raw.slice(30)]),
      {
        onMeta: () => events.push('meta'),
        onDelta: (payload) => {
          events.push('delta')
          texts.push((payload as { text: string }).text)
        }
      }
    )

    expect(events).toEqual(['meta', 'delta'])
    expect(texts).toEqual(['半句'])
  })

  it('ignores ping frames and unknown events', async () => {
    const events: string[] = []
    await consumeSse(
      streamOf([frame('ping', {}), frame('heartbeat', { x: 1 }), frame('meta', { messageId: 1 })]),
      { onMeta: () => events.push('meta'), onDone: () => events.push('done') }
    )
    expect(events).toEqual(['meta'])
  })

  it('routes an error frame to onError and stops reading', async () => {
    const events: string[] = []
    const errors: unknown[] = []

    await consumeSse(
      streamOf([
        frame('meta', { messageId: 1 }),
        frame('error', { code: 50000, message: 'provider down' }),
        frame('done', { messageId: 1 })
      ]),
      {
        onMeta: () => events.push('meta'),
        onDone: () => events.push('done'),
        onError: (payload) => {
          events.push('error')
          errors.push(payload)
        }
      }
    )

    expect(events).toEqual(['meta', 'error'])
    expect(errors[0]).toMatchObject({ code: 50000, message: 'provider down' })
  })

  it('does not choke on a partial trailing frame', async () => {
    const events: string[] = []
    await consumeSse(streamOf([frame('meta', { messageId: 1 }), 'event:step\ndata:{"rou']), {
      onMeta: () => events.push('meta'),
      onStep: () => events.push('step')
    })
    expect(events).toEqual(['meta'])
  })
})
