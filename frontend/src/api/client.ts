import {
  clearTokens,
  getAccessToken,
  getRefreshToken,
  setAccessToken,
  setTokens,
  type TokenPair
} from './session'

/**
 * The one HTTP entry point. Everything the app sends goes through {@link apiFetch}, which means the
 * `Authorization` header, the envelope unwrapping, the error type and the refresh-once rule are
 * defined in exactly one place.
 *
 * Refresh handling: a 401 triggers at most one refresh, shared by all concurrent callers (otherwise
 * a page firing three requests with an expired token would burn three refresh tokens). If the
 * refresh fails the session is dropped and the router is told to go to the login page.
 */

export class ApiError extends Error {
  constructor(
    readonly code: number,
    message: string,
    readonly status: number
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

interface Envelope<T> {
  code: number
  message: string
  data?: T
}

export interface ErrorHandlers {
  /** Session is gone: clear the store and navigate to /login. */
  onUnauthorized: () => void
  /** Backend answered 403 / 40301: the user must change the password before anything else. */
  onPasswordChangeRequired: () => void
}

let handlers: ErrorHandlers = {
  onUnauthorized: () => {
    window.location.assign('/login')
  },
  onPasswordChangeRequired: () => {
    window.location.assign('/change-password')
  }
}

/** Wired in `main.ts` so the client stays usable (and testable) without a router instance. */
export function setErrorHandlers(next: Partial<ErrorHandlers>): void {
  handlers = { ...handlers, ...next }
}

let refreshInFlight: Promise<boolean> | null = null

async function refreshSession(): Promise<boolean> {
  if (refreshInFlight) {
    return refreshInFlight
  }
  const refreshToken = getRefreshToken()
  if (!refreshToken) {
    return false
  }
  refreshInFlight = (async () => {
    try {
      const response = await fetch('/api/auth/refresh', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ refreshToken })
      })
      if (!response.ok) {
        return false
      }
      const envelope = (await response.json()) as Envelope<TokenPair>
      if (envelope.code !== 0 || !envelope.data) {
        return false
      }
      setTokens(envelope.data)
      return true
    } catch {
      return false
    } finally {
      refreshInFlight = null
    }
  })()
  return refreshInFlight
}

async function readEnvelope<T>(response: Response): Promise<Envelope<T> | null> {
  const text = await response.text()
  if (!text) {
    return null
  }
  try {
    return JSON.parse(text) as Envelope<T>
  } catch {
    return null
  }
}

function expired(message: string): ApiError {
  clearTokens()
  handlers.onUnauthorized()
  return new ApiError(40100, message || '登录已过期，请重新登录', 401)
}

/**
 * Sends one request and returns the unwrapped `data`. A 401 is retried exactly once after a
 * successful refresh; a business error (`code !== 0`) is thrown as {@link ApiError} so callers can
 * branch on `code` instead of on message strings.
 */
export async function apiFetch<T>(path: string, init: RequestInit = {}, retry = true): Promise<T> {
  const headers = new Headers(init.headers)
  if (init.body !== undefined && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }
  const token = getAccessToken()
  if (token) {
    headers.set('Authorization', `Bearer ${token}`)
  }

  const response = await fetch(path, { ...init, headers })

  if (response.status === 401) {
    if (retry && (await refreshSession())) {
      return apiFetch<T>(path, init, false)
    }
    throw expired('')
  }

  const envelope = await readEnvelope<T>(response)

  if (response.status === 403 && envelope?.code === 40301) {
    handlers.onPasswordChangeRequired()
    throw new ApiError(envelope.code, envelope.message ?? '请先修改初始密码', 403)
  }

  if (!response.ok) {
    throw new ApiError(
      envelope?.code ?? response.status * 100,
      envelope?.message ?? `请求失败（HTTP ${response.status}）`,
      response.status
    )
  }

  if (!envelope) {
    // 204 / empty body: the caller asked for a side effect, not a value.
    return undefined as T
  }
  if (envelope.code !== 0) {
    throw new ApiError(envelope.code, envelope.message ?? '请求失败', response.status)
  }
  return envelope.data as T
}

export const http = {
  get: <T>(path: string) => apiFetch<T>(path),
  post: <T>(path: string, body?: unknown) =>
    apiFetch<T>(path, { method: 'POST', body: body === undefined ? undefined : JSON.stringify(body) }),
  patch: <T>(path: string, body: unknown) =>
    apiFetch<T>(path, { method: 'PATCH', body: JSON.stringify(body) }),
  delete: <T>(path: string) => apiFetch<T>(path, { method: 'DELETE' })
}

/** Exposed for the SSE client, which needs a token but must not go through `apiFetch`. */
export { getAccessToken, setAccessToken, getRefreshToken }
