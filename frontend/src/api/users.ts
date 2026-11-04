import { http } from './client'
import type { Page } from './knowledge'

/**
 * User administration (spec §12 `admin`, ADMIN only).
 *
 * Two server-side rules shape the UI: a created user always starts with `mustChangePassword = true`
 * (the administrator chooses the initial password, so the user must replace it before anything
 * else), and a disable or a reset revokes every outstanding refresh token of that user — the person
 * is signed out on their next refresh, not on their next reload.
 */

export type UserRole = 'ADMIN' | 'USER'

export interface UserSummary {
  id: number
  username: string
  role: UserRole
  enabled: boolean
  mustChangePassword: boolean
}

export function listUsers(page = 0, size = 20): Promise<Page<UserSummary>> {
  return http.get(`/api/users?page=${page}&size=${size}`)
}

export function createUser(body: {
  username: string
  password: string
  role: UserRole
}): Promise<UserSummary> {
  return http.post('/api/users', body)
}

/** `enabled` and `password` are both optional; omitting one leaves it unchanged. */
export function patchUser(
  id: number,
  body: { enabled?: boolean; password?: string }
): Promise<UserSummary> {
  return http.patch(`/api/users/${id}`, body)
}
