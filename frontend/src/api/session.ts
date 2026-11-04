import { ref } from 'vue'

/**
 * Token persistence, separated from both the store and the HTTP client so neither has to import the
 * other: the store owns *policy* (when to log in or out), this module owns *storage*.
 *
 * `localStorage` is deliberate — a page reload must not log the user out, and the SPA has no
 * server-side session to fall back on.
 *
 * `tokenRevision` exists because the HTTP client can replace the token pair on its own (silent
 * refresh on 401). A write bumps the revision, and the store derives its `accessToken` /
 * `refreshToken` from a computed that reads it — so a refresh performed deep inside `apiFetch` still
 * updates every reactive consumer without a callback registry.
 */
const ACCESS_KEY = 'advisory.accessToken'
const REFRESH_KEY = 'advisory.refreshToken'

export const tokenRevision = ref(0)

export interface TokenPair {
  accessToken: string
  refreshToken: string
}

export function getAccessToken(): string | null {
  return localStorage.getItem(ACCESS_KEY)
}

export function getRefreshToken(): string | null {
  return localStorage.getItem(REFRESH_KEY)
}

export function setTokens(pair: TokenPair): void {
  localStorage.setItem(ACCESS_KEY, pair.accessToken)
  localStorage.setItem(REFRESH_KEY, pair.refreshToken)
  tokenRevision.value++
}

export function setAccessToken(token: string): void {
  localStorage.setItem(ACCESS_KEY, token)
  tokenRevision.value++
}

export function clearTokens(): void {
  localStorage.removeItem(ACCESS_KEY)
  localStorage.removeItem(REFRESH_KEY)
  tokenRevision.value++
}
