import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { setErrorHandlers } from '../src/api/client'
import { setAccessToken, setTokens } from '../src/api/session'
import { useAuthStore } from '../src/stores/auth'

/**
 * The auth store is the only place that owns the session: tokens in `localStorage`, the current user
 * from `GET /api/auth/me`, and the `mustChangePassword` flag the router guard depends on. `fetch` is
 * stubbed so these tests never touch a backend.
 */

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' }
  })
}

function ok<T>(data: T): Response {
  return json({ code: 0, message: 'ok', data })
}

describe('auth store', () => {
  let fetchMock: ReturnType<typeof vi.fn>

  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    setErrorHandlers({ onUnauthorized: () => {}, onPasswordChangeRequired: () => {} })
  })

  it('stores both tokens and the current user after login', async () => {
    fetchMock
      .mockResolvedValueOnce(ok({ accessToken: 'access-1', refreshToken: 'refresh-1' }))
      .mockResolvedValueOnce(ok({ id: 1, username: 'admin', role: 'ADMIN', mustChangePassword: true }))

    const auth = useAuthStore()
    await auth.login('admin', 'secret')

    expect(fetchMock).toHaveBeenNthCalledWith(1, '/api/auth/login', expect.objectContaining({ method: 'POST' }))
    expect(auth.accessToken).toBe('access-1')
    expect(auth.refreshToken).toBe('refresh-1')
    expect(auth.user?.username).toBe('admin')
    expect(auth.isAdmin).toBe(true)
    expect(auth.mustChangePassword).toBe(true)
    expect(localStorage.getItem('advisory.accessToken')).toBe('access-1')
    expect(localStorage.getItem('advisory.refreshToken')).toBe('refresh-1')
  })

  it('sends the access token on later requests', async () => {
    setAccessToken('access-1')
    fetchMock.mockResolvedValueOnce(ok({ id: 2, username: 'user', role: 'USER', mustChangePassword: false }))

    const auth = useAuthStore()
    await auth.loadMe()

    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(new Headers(init.headers).get('Authorization')).toBe('Bearer access-1')
    expect(auth.mustChangePassword).toBe(false)
    expect(auth.isAdmin).toBe(false)
  })

  it('logout clears local state even when the server call fails', async () => {
    setTokens({ accessToken: 'access-1', refreshToken: 'refresh-1' })
    fetchMock.mockResolvedValueOnce(json({ code: 40100, message: 'expired' }, 401))

    const auth = useAuthStore()
    await auth.logout()

    expect(auth.accessToken).toBeNull()
    expect(auth.refreshToken).toBeNull()
    expect(auth.user).toBeNull()
    expect(localStorage.getItem('advisory.accessToken')).toBeNull()
    expect(localStorage.getItem('advisory.refreshToken')).toBeNull()
  })

  it('refreshes once on 401 and replays the original request', async () => {
    setTokens({ accessToken: 'stale', refreshToken: 'refresh-1' })
    fetchMock
      .mockResolvedValueOnce(json({ code: 40100, message: 'expired' }, 401))
      .mockResolvedValueOnce(ok({ accessToken: 'fresh', refreshToken: 'refresh-2' }))
      .mockResolvedValueOnce(ok({ id: 1, username: 'admin', role: 'ADMIN', mustChangePassword: false }))

    const auth = useAuthStore()
    await auth.loadMe()

    expect(auth.accessToken).toBe('fresh')
    expect(localStorage.getItem('advisory.accessToken')).toBe('fresh')
    expect(auth.user?.username).toBe('admin')
    expect(fetchMock).toHaveBeenCalledTimes(3)
  })

  it('clears the session when the refresh also fails', async () => {
    setTokens({ accessToken: 'stale', refreshToken: 'refresh-1' })
    const onUnauthorized = vi.fn()
    setErrorHandlers({ onUnauthorized, onPasswordChangeRequired: () => {} })
    fetchMock
      .mockResolvedValueOnce(json({ code: 40100, message: 'expired' }, 401))
      .mockResolvedValueOnce(json({ code: 40100, message: 'invalid refresh' }, 401))

    const auth = useAuthStore()
    await expect(auth.loadMe()).rejects.toMatchObject({ code: 40100 })

    expect(onUnauthorized).toHaveBeenCalledTimes(1)
    expect(localStorage.getItem('advisory.accessToken')).toBeNull()
  })

  it('changePassword swaps the token pair so the guard re-evaluates', async () => {
    setTokens({ accessToken: 'access-1', refreshToken: 'refresh-1' })
    fetchMock
      .mockResolvedValueOnce(ok({ accessToken: 'access-2', refreshToken: 'refresh-2' }))
      .mockResolvedValueOnce(ok({ id: 1, username: 'admin', role: 'ADMIN', mustChangePassword: false }))

    const auth = useAuthStore()
    await auth.changePassword('old-pass', 'new-pass')

    expect(localStorage.getItem('advisory.accessToken')).toBe('access-2')
    expect(auth.mustChangePassword).toBe(false)
    const [path, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(path).toBe('/api/auth/change-password')
    expect(JSON.parse(String(init.body))).toEqual({ oldPassword: 'old-pass', newPassword: 'new-pass' })
  })
})
