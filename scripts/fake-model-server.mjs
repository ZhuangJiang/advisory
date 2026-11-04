#!/usr/bin/env node
/**
 * Minimal OpenAI-compatible server for local and CI integration runs. No dependencies.
 *
 * Why it exists: the production images contain no stub model gateway, so `scripts/smoke.ps1` needs a
 * real HTTP endpoint. This one speaks just enough of the OpenAI API for the platform to exercise the
 * *whole* pipeline - provider probe, ingestion/embedding, PLAN/RETRIEVE/REFLECT/ANSWER, SSE,
 * citations, audit - without a GPU or an API key. The answers are fixtures, not a model.
 *
 * Endpoints:
 *   GET  /v1/models             provider probe and remote model import
 *   POST /v1/chat/completions   PLAN/REFLECT JSON, otherwise a plain cited answer
 *   POST /v1/embeddings         1024-dimension vectors (the fixed pgvector dimension)
 *
 * Run it on the compose network so the backend can resolve it by name:
 *
 *   docker run -d --rm --name advisory-fake-model --network advisory_default \
 *     -v "%CD%/scripts:/scripts:ro" node:20-alpine node /scripts/fake-model-server.mjs
 *
 * Then point the smoke run at http://advisory-fake-model:8099.
 */

import { createHash } from 'node:crypto'
import { createServer } from 'node:http'

const DIMENSION = 1024
const PORT = Number(process.env.PORT ?? 8099)

const PLAN_ANSWER = JSON.stringify({ queries: ['报销所需材料', '报销提交时限'] })
const REFLECT_ANSWER = JSON.stringify({
  sufficient: true,
  gaps: [],
  nextQueries: [],
  draftAnswer: '报销需提供发票原件与审批单，并在 30 天内提交。'
})
const FINAL_ANSWER =
  '报销需要提供发票原件、审批单与费用明细清单[1]。' +
  '报销须在费用发生之日起 30 个自然日内提交；逾期需财务总监书面批准[1]。'

/** Deterministic pseudo-vector: same text -> same vector, different text -> different vector. */
function embeddingFor(text) {
  const digest = createHash('sha256').update(text).digest()
  let seed = digest.readBigUInt64BE(0)
  const values = new Array(DIMENSION)
  let norm = 0
  for (let i = 0; i < DIMENSION; i += 1) {
    // xorshift64 keeps the stub dependency-free and reproducible across Node versions.
    seed ^= seed << 13n
    seed ^= seed >> 7n
    seed ^= seed << 17n
    seed &= (1n << 64n) - 1n
    const value = Number(seed % 2001n) / 1000 - 1
    values[i] = value
    norm += value * value
  }
  const scale = norm > 0 ? Math.sqrt(norm) : 1
  return values.map((value) => value / scale)
}

function pickAnswer(messages) {
  const system = messages
    .filter((message) => message.role === 'system')
    .map((message) => String(message.content ?? ''))
    .join(' ')
  if (system.includes('检索规划助手')) {
    return PLAN_ANSWER
  }
  if (system.includes('判断已召回的片段')) {
    return REFLECT_ANSWER
  }
  return FINAL_ANSWER
}

function sendJson(response, status, payload) {
  const body = Buffer.from(JSON.stringify(payload), 'utf8')
  response.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': body.length
  })
  response.end(body)
}

function readJson(request) {
  return new Promise((resolve, reject) => {
    const chunks = []
    request.on('data', (chunk) => chunks.push(chunk))
    request.on('end', () => {
      if (chunks.length === 0) {
        resolve({})
        return
      }
      try {
        resolve(JSON.parse(Buffer.concat(chunks).toString('utf8')))
      } catch (error) {
        reject(error)
      }
    })
    request.on('error', reject)
  })
}

const server = createServer(async (request, response) => {
  const path = (request.url ?? '/').split('?')[0].replace(/\/+$/, '')
  try {
    if (request.method === 'GET' && (path === '/v1/models' || path === '/models')) {
      sendJson(response, 200, {
        object: 'list',
        data: [
          { id: 'fake-chat', object: 'model', owned_by: 'fake' },
          { id: 'fake-embed', object: 'model', owned_by: 'fake' }
        ]
      })
      return
    }
    if (request.method === 'GET' && (path === '/' || path === '/health')) {
      sendJson(response, 200, { status: 'ok' })
      return
    }
    if (request.method === 'POST' && path.endsWith('/chat/completions')) {
      const body = await readJson(request)
      sendJson(response, 200, {
        id: 'chatcmpl-fake',
        object: 'chat.completion',
        created: Math.floor(Date.now() / 1000),
        model: body.model ?? 'fake-chat',
        choices: [
          {
            index: 0,
            message: { role: 'assistant', content: pickAnswer(body.messages ?? []) },
            finish_reason: 'stop'
          }
        ],
        usage: { prompt_tokens: 0, completion_tokens: 0, total_tokens: 0 }
      })
      return
    }
    if (request.method === 'POST' && path.endsWith('/embeddings')) {
      const body = await readJson(request)
      const raw = body.input
      const inputs = typeof raw === 'string' ? [raw] : Array.isArray(raw) ? raw : []
      sendJson(response, 200, {
        object: 'list',
        data: inputs.map((text, index) => ({
          object: 'embedding',
          index,
          embedding: embeddingFor(String(text))
        })),
        model: body.model ?? 'fake-embed',
        usage: { prompt_tokens: 0, total_tokens: 0 }
      })
      return
    }
    sendJson(response, 404, { error: { message: `not found: ${request.method} ${path}` } })
  } catch (error) {
    sendJson(response, 400, { error: { message: String(error) } })
  }
})

server.listen(PORT, '0.0.0.0', () => {
  console.log(`fake model server listening on :${PORT} (dimension=${DIMENSION})`)
})
