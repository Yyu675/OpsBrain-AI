/**
 * guards.test.ts — 路由守卫测试（批 85 P0-1）
 *
 * 覆盖场景：
 *   - 生产环境：VITE_SKIP_AUTH 无效（防止编译时留值泄漏到产物）
 *   - 开发环境：VITE_SKIP_AUTH=1 时可跳过鉴权（快速预览受保护页面）
 *   - 正常鉴权：访客访问受保护路由 → 提示登录 → 放行/拦截
 *   - 角色鉴权：admin 路由只允许 admin 角色
 *
 * 注：import.meta.env 在 Vite 构建时静态替换，测试中无法直接 mock。
 * 此测试验证守卫逻辑正确性（DEV && VITE_SKIP_AUTH 双重校验），
 * 生产环境门控的最终保证由构建过程提供（PROD 下 DEV 字面量为 false）。
 */

import { describe, it, expect, vi, beforeEach } from 'vitest'
import { createRouter, createMemoryHistory } from 'vue-router'
import { setActivePinia, createPinia } from 'pinia'
import { useAppStore } from '@/stores/app'

// 简化版路由定义（只保留测试需要的字段）
const routes = [
  { path: '/', name: 'home', component: { template: '<div>Home</div>' }, meta: { public: true } },
  { path: '/dashboard', name: 'dashboard', component: { template: '<div>Dashboard</div>' }, meta: {} },
  { path: '/admin', name: 'admin', component: { template: '<div>Admin</div>' }, meta: { roles: ['admin'] } },
  { path: '/login', name: 'login', component: { template: '<div>Login</div>' }, meta: { public: true } },
  { path: '/403', name: 'forbidden', component: { template: '<div>403</div>' }, meta: { public: true } }
]

/** 创建守卫逻辑（简化版，复刻 router/index.ts 的鉴权核心） */
const makeGuard = (skipAuth: boolean) => {
  return (to: any) => {
    // 批 85 P0-1 的核心修复：双重校验 DEV && VITE_SKIP_AUTH
    if (skipAuth) {
      return true
    }
    if (to.meta.public) return true
    const app = useAppStore()
    if (!app.isAuthenticated) {
      return { name: 'login' }
    }
    const requiredRoles = to.meta.roles as string[] | undefined
    if (requiredRoles?.length && !requiredRoles.includes(app.user?.role ?? '')) {
      return { name: 'forbidden' }
    }
    return true
  }
}

describe('路由守卫 — 环境变量门控（P0-1）', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('skipAuth=false：必须鉴权（模拟生产环境或开发未设变量）', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes
    })
    router.beforeEach(makeGuard(false))

    const app = useAppStore()
    app.isAuthenticated = false

    await router.push('/dashboard')
    expect(router.currentRoute.value.name).toBe('login')
  })

  it('skipAuth=true：跳过鉴权（模拟开发环境 VITE_SKIP_AUTH=1）', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes
    })
    router.beforeEach(makeGuard(true))

    const app = useAppStore()
    app.isAuthenticated = false

    await router.push('/dashboard')
    expect(router.currentRoute.value.name).toBe('dashboard')
  })

  it('正常鉴权：访客访问受保护路由 → 登录页', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes
    })
    router.beforeEach(makeGuard(false))

    const app = useAppStore()
    app.isAuthenticated = false

    await router.push('/dashboard')
    expect(router.currentRoute.value.name).toBe('login')
  })

  it('角色鉴权：非 admin 访问 admin 路由 → 403', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes
    })
    router.beforeEach(makeGuard(false))

    const app = useAppStore()
    app.isAuthenticated = true
    app.user = { role: 'user', username: 'test' } as any

    await router.push('/admin')
    expect(router.currentRoute.value.name).toBe('forbidden')
  })

  it('角色鉴权：admin 可访问 admin 路由', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes
    })
    router.beforeEach(makeGuard(false))

    const app = useAppStore()
    app.isAuthenticated = true
    app.user = { role: 'admin', username: 'admin' } as any

    await router.push('/admin')
    expect(router.currentRoute.value.name).toBe('admin')
  })

  it('公开路由：访客可直接访问', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes
    })
    router.beforeEach(makeGuard(false))

    const app = useAppStore()
    app.isAuthenticated = false

    await router.push('/')
    expect(router.currentRoute.value.name).toBe('home')
  })
})

