import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

/**
 * Routes and the access rules from spec §8.
 *
 * Two rules are absolute and are enforced here rather than in the pages:
 *  - no token → `/login`;
 *  - `mustChangePassword` → `/change-password` and nothing else, until the password is changed.
 *
 * `meta.roles` marks administrator-only pages; a USER who types the URL is sent home instead of
 * seeing an empty shell (the API would refuse the data anyway, this just avoids a broken screen).
 */
const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/pages/Login.vue'),
    meta: { public: true }
  },
  {
    path: '/change-password',
    name: 'change-password',
    component: () => import('@/pages/ChangePassword.vue')
  },
  {
    path: '/',
    component: () => import('@/layouts/AppLayout.vue'),
    children: [
      {
        path: '',
        name: 'home',
        component: () => import('@/pages/Home.vue')
      },
      {
        path: 'knowledge-bases',
        name: 'knowledge-bases',
        component: () => import('@/pages/knowledge/KnowledgeBaseList.vue')
      },
      {
        // `meta.nav` keeps the sidebar entry highlighted while browsing inside a knowledge base.
        path: 'knowledge-bases/:id',
        name: 'knowledge-base-detail',
        component: () => import('@/pages/knowledge/KnowledgeBaseDetail.vue'),
        meta: { nav: 'knowledge-bases' }
      },
      {
        path: 'agents',
        name: 'agents',
        component: () => import('@/pages/agents/AgentList.vue')
      },
      {
        path: 'agents/new',
        name: 'agent-new',
        component: () => import('@/pages/agents/AgentEdit.vue'),
        meta: { roles: ['ADMIN'], nav: 'agents' }
      },
      {
        path: 'agents/:id',
        name: 'agent-edit',
        component: () => import('@/pages/agents/AgentEdit.vue'),
        meta: { roles: ['ADMIN'], nav: 'agents' }
      },
      {
        path: 'providers',
        name: 'providers',
        component: () => import('@/pages/providers/ProviderList.vue'),
        meta: { roles: ['ADMIN'] }
      }
    ]
  },
  {
    path: '/:pathMatch(.*)*',
    redirect: '/'
  }
]

export const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()

  if (to.meta.public) {
    return auth.isAuthenticated && !auth.mustChangePassword ? { name: 'home' } : true
  }

  if (!auth.isAuthenticated) {
    return { name: 'login', query: to.fullPath === '/' ? {} : { redirect: to.fullPath } }
  }

  if (!auth.user) {
    try {
      await auth.loadMe()
    } catch {
      auth.clear()
      return { name: 'login' }
    }
  }

  if (auth.mustChangePassword && to.name !== 'change-password') {
    return { name: 'change-password' }
  }
  if (!auth.mustChangePassword && to.name === 'change-password') {
    return { name: 'home' }
  }

  const roles = to.meta.roles as string[] | undefined
  if (roles && !roles.includes(auth.user!.role)) {
    return { name: 'home' }
  }
  return true
})
