import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { setTokens } from '../src/api/session'
import { router } from '../src/router'
import { useAuthStore } from '../src/stores/auth'

/**
 * The guard is where spec §8's two hard rules live: anonymous visitors never see a business page,
 * and a user with `mustChangePassword` cannot reach one either. Both are asserted by navigating,
 * because the rule is a redirect, not a rendering detail.
 */
describe('router guard', () => {
  beforeEach(async () => {
    localStorage.clear()
    setActivePinia(createPinia())
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => new Response('{"code":0,"message":"ok","data":null}', { status: 200 }))
    )
    // reset the shared router instance to a known place without running guard assertions
    await router.replace('/login')
  })

  it('sends an anonymous visitor to /login', async () => {
    await router.push('/')
    expect(router.currentRoute.value.name).toBe('login')
  })

  it('keeps a signed-in user away from /login', async () => {
    setTokens({ accessToken: 'access', refreshToken: 'refresh' })
    const auth = useAuthStore()
    auth.user = { id: 1, username: 'u', role: 'USER', mustChangePassword: false }

    // reach the shell first: pushing '/login' from '/login' would be a duplicated navigation
    await router.push('/')
    expect(router.currentRoute.value.name).toBe('home')

    await router.push('/login')
    expect(router.currentRoute.value.name).toBe('home')
  })

  it('forces /change-password while mustChangePassword is set', async () => {
    setTokens({ accessToken: 'access', refreshToken: 'refresh' })
    const auth = useAuthStore()
    auth.user = { id: 1, username: 'u', role: 'ADMIN', mustChangePassword: true }

    await router.push('/')
    expect(router.currentRoute.value.name).toBe('change-password')

    await router.push('/change-password')
    expect(router.currentRoute.value.name).toBe('change-password')
  })

  it('lets a user with a changed password reach the app shell', async () => {
    setTokens({ accessToken: 'access', refreshToken: 'refresh' })
    const auth = useAuthStore()
    auth.user = { id: 1, username: 'u', role: 'USER', mustChangePassword: false }

    await router.push('/')
    expect(router.currentRoute.value.name).toBe('home')
  })
})
