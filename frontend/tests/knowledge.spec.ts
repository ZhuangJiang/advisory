import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  createNode,
  describeKnowledgeError,
  patchNode,
  rechunkDocument,
  rechunkDocuments,
  uploadDocument,
  uploadDocuments
} from '../src/api/knowledge'
import { setErrorHandlers, ApiError } from '../src/api/client'
import { setTokens } from '../src/api/session'

/**
 * The knowledge API module is the only place that knows the upload is three steps and that a node
 * PATCH distinguishes "field absent" from "null". Both are easy to break subtly, so they are pinned
 * here instead of through a page.
 */

function ok<T>(data: T, status = 200): Response {
  return new Response(JSON.stringify({ code: 0, message: 'ok', data }), {
    status,
    headers: { 'Content-Type': 'application/json' }
  })
}

function failure(code: number, message: string, status: number): Response {
  return new Response(JSON.stringify({ code, message }), {
    status,
    headers: { 'Content-Type': 'application/json' }
  })
}

describe('knowledge api', () => {
  let fetchMock: ReturnType<typeof vi.fn>

  beforeEach(() => {
    localStorage.clear()
    setTokens({ accessToken: 'access', refreshToken: 'refresh' })
    fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    setErrorHandlers({ onUnauthorized: () => {}, onPasswordChangeRequired: () => {} })
  })

  it('uploads in three steps and never sends the bearer token to object storage', async () => {
    const file = new File(['hello advisory'], 'guide.txt', { type: 'text/plain' })
    fetchMock
      .mockResolvedValueOnce(
        ok({ uploadUrl: 'http://minio.local/bucket/guide.txt?sig=1', objectKey: 'kb/7/guide.txt', expiresInSeconds: 600 })
      )
      .mockResolvedValueOnce(new Response('', { status: 200 }))
      .mockResolvedValueOnce(ok({ id: 42, status: 'PENDING' }, 201))

    const document = await uploadDocument({ kbId: 7, nodeId: 3, file })

    expect(document.id).toBe(42)
    expect(fetchMock).toHaveBeenCalledTimes(3)

    const [presignUrl, presignInit] = fetchMock.mock.calls[0]
    expect(presignUrl).toBe('/api/documents/presign')
    expect(JSON.parse(presignInit.body as string)).toEqual({
      kbId: 7,
      nodeId: 3,
      filename: 'guide.txt',
      size: file.size
    })

    const [putUrl, putInit] = fetchMock.mock.calls[1]
    expect(putUrl).toBe('http://minio.local/bucket/guide.txt?sig=1')
    expect(putInit.method).toBe('PUT')
    expect(putInit.body).toBe(file)
    // MinIO validates the signature: an extra Authorization header would fail the request.
    expect(putInit.headers).toBeUndefined()

    const [registerUrl, registerInit] = fetchMock.mock.calls[2]
    expect(registerUrl).toBe('/api/documents')
    expect(JSON.parse(registerInit.body as string)).toMatchObject({
      kbId: 7,
      nodeId: 3,
      filename: 'guide.txt',
      objectKey: 'kb/7/guide.txt'
    })
  })

  it('omits parentId on rename and sends null when moving to root', async () => {
    // A fresh Response per call: a body can only be read once, so a shared mock value would fail
    // on the second request.
    fetchMock.mockImplementation(() => Promise.resolve(ok({ id: 2, name: '改名', parentId: null })))

    await patchNode(2, { name: '改名' })
    expect(JSON.parse(fetchMock.mock.calls[0][1].body as string)).toEqual({ name: '改名' })

    await patchNode(2, { parentId: null })
    expect(JSON.parse(fetchMock.mock.calls[1][1].body as string)).toEqual({ parentId: null })
  })

  it('fails the upload when object storage rejects the PUT', async () => {
    const file = new File(['x'], 'guide.txt')
    fetchMock
      .mockResolvedValueOnce(ok({ uploadUrl: 'http://minio.local/x', objectKey: 'kb/7/x', expiresInSeconds: 600 }))
      .mockResolvedValueOnce(new Response('denied', { status: 403 }))

    await expect(uploadDocument({ kbId: 7, nodeId: null, file })).rejects.toBeInstanceOf(ApiError)
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('surfaces the backend business code instead of only the HTTP status', async () => {
    fetchMock.mockImplementation(() =>
      Promise.resolve(failure(40901, 'Sibling node name duplicated', 409))
    )

    await expect(createNode(1, null, 'dup')).rejects.toMatchObject({ code: 40901, status: 409 })
  })

  it('translates the node-not-empty conflict into actionable copy', () => {
    expect(describeKnowledgeError(new ApiError(40902, 'Node has children', 409))).toBe(
      '请先清空子节点或文档'
    )
    expect(describeKnowledgeError(new ApiError(40903, 'Knowledge base is in use', 409))).toBe(
      '知识库已被 Agent 引用，无法删除'
    )
    // Unknown codes keep the backend's own message: it is already written for humans.
    expect(describeKnowledgeError(new ApiError(40000, 'name must not be blank', 400))).toBe(
      'name must not be blank'
    )
  })

  it('uploads a batch one file at a time and keeps going after a failure', async () => {
    const first = new File(['one'], 'one.md')
    const second = new File(['two'], 'two.md')
    const third = new File(['three'], 'three.md')

    fetchMock
      // one.md: presign → PUT → register
      .mockResolvedValueOnce(
        ok({ uploadUrl: 'http://minio.local/one', objectKey: 'kb/7/one.md', expiresInSeconds: 600 })
      )
      .mockResolvedValueOnce(new Response('', { status: 200 }))
      .mockResolvedValueOnce(ok({ id: 1, filename: 'one.md', status: 'PENDING' }, 201))
      // two.md: presign rejected (too large)
      .mockResolvedValueOnce(failure(40000, '文件大小不能超过 50 MB', 400))
      // three.md still runs
      .mockResolvedValueOnce(
        ok({ uploadUrl: 'http://minio.local/three', objectKey: 'kb/7/three.md', expiresInSeconds: 600 })
      )
      .mockResolvedValueOnce(new Response('', { status: 200 }))
      .mockResolvedValueOnce(ok({ id: 3, filename: 'three.md', status: 'PENDING' }, 201))

    const progress: number[] = []
    const result = await uploadDocuments({
      kbId: 7,
      nodeId: 4,
      files: [first, second, third],
      onProgress: (done) => progress.push(done)
    })

    expect(result.uploaded.map((doc) => doc.id)).toEqual([1, 3])
    expect(result.failed).toEqual([{ filename: 'two.md', message: '文件大小不能超过 50 MB' }])
    // Sequential, in the order the operator picked them: one → two → three.
    const registered = fetchMock.mock.calls
      .filter((call) => call[0] === '/api/documents')
      .map((call) => JSON.parse(call[1].body as string).filename)
    expect(registered).toEqual(['one.md', 'three.md'])
    expect(progress).toEqual([0, 1, 2, 3])
  })

  it('rechunks a single document and a batch through their own endpoints', async () => {
    fetchMock.mockImplementation(() => Promise.resolve(ok({ requested: 2, accepted: 2, skipped: [] })))

    await rechunkDocument(9)
    expect(fetchMock.mock.calls[0][0]).toBe('/api/documents/9/rechunk')

    await rechunkDocuments([9, 10])
    expect(fetchMock.mock.calls[1][0]).toBe('/api/documents/rechunk')
    expect(JSON.parse(fetchMock.mock.calls[1][1].body as string)).toEqual({ documentIds: [9, 10] })
  })
})
