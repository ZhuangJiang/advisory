import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { http } from '@/api/client'
import {
  clearTokens,
  getAccessToken,
  getRefreshToken,
  setTokens,
  tokenRevision,
  type TokenPair
} from '@/api/session'

export type Role = 'ADMIN' | 'USER'

export interface Me {
  id: number
  username: string
  role: Role
  mustChangePassword: boolean
}

/**
 * Session state. The tokens live in `localStorage` (see `api/session`) so a reload keeps the user
 * signed in; this store mirrors them plus the `/auth/me` projection that the router guard and the
 * layout read.
 *
 * `mustChangePassword` is not cosmetic: `main.ts` wires the 403 `PASSWORD_CHANGE_REQUIRED` response
 * to a redirect, and the guard refuses every other route until the password is changed (spec §8).
 */
export const useAuthStore = defineStore('auth', () => {
  // Derived from storage rather than copied into a ref: the HTTP client can rotate the token pair on
  // a silent refresh, and a copy would silently keep the old token until the next reload.
  const accessToken = computed(() => {
    tokenRevision.value
    return getAccessToken()
  })
  const refreshToken = computed(() => {
    tokenRevision.value
    return getRefreshToken()
  })
  const user = ref<Me | null>(null)
  const loading = ref(false)

  const isAuthenticated = computed(() => accessToken.value !== null)
  const isAdmin = computed(() => user.value?.role === 'ADMIN')
  const mustChangePassword = computed(() => user.value?.mustChangePassword === true)

  function apply(pair: TokenPair): void {
    setTokens(pair)
  }

  function clear(): void {
    clearTokens()
    user.value = null
  }

  async function loadMe(): Promise<Me> {
    const me = await http.get<Me>('/api/auth/me')
    user.value = me
    return me
  }

  async function login(username: string, password: string): Promise<Me> {
    loading.value = true
    try {
      apply(await http.post<TokenPair>('/api/auth/login', { username, password }))
      return await loadMe()
    } catch (error) {
      clear()
      throw error
    } finally {
      loading.value = false
    }
  }

  /** Swaps the token pair: the backend revokes every refresh token on a password change. */
  async function changePassword(oldPassword: string, newPassword: string): Promise<Me> {
    const pair = await http.post<TokenPair>('/api/auth/change-password', { oldPassword, newPassword })
    apply(pair)
    return loadMe()
  }

  async function logout(): Promise<void> {
    const token = refreshToken.value
    try {
      if (token) {
        await http.post<void>('/api/auth/logout', { refreshToken: token })
      }
    } catch {
      // A failed logout call must not keep the user signed in locally.
    } finally {
      clear()
    }
  }

  return {
    accessToken,
    refreshToken,
    user,
    loading,
    isAuthenticated,
    isAdmin,
    mustChangePassword,
    apply,
    clear,
    loadMe,
    login,
    changePassword,
    logout
  }
})
