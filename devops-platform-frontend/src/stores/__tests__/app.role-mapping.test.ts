/**
 * app store 角色映射测试（R0-1 RBAC 门控的码表源头）。
 *
 * 保护的真实行为：登录后 store 里的权限码集合决定 v-permission 的放行面。
 * 历史上 OPS 映射为空集合，导致「运维看得到按钮却被禁」或「前端裸放行吃 403」，
 * 本测试钉死三个角色各自的权限面，改码表必须过这里。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'

vi.mock('@/api/auth', () => ({
  login: vi.fn(),
  getMe: vi.fn(),
  logout: vi.fn()
}))

import { useAppStore } from '../app'
import { login as apiLogin } from '@/api/auth'
import { PERMISSIONS } from '@/constants/permissions'
import { setAuthToken } from '@/utils/http'

const loginAs = async (role: string) => {
  vi.mocked(apiLogin).mockResolvedValue({
    token: 'tok-' + role,
    tokenName: 'satoken',
    user: { id: 1, username: 'u', displayName: 'U', role }
  })
  const app = useAppStore()
  await app.login('u', 'p')
  return app
}

describe('mapBackendRole → 权限码集合', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
    vi.clearAllMocks()
  })

  it('ADMIN：全权限通配符，任意权限码都放行', async () => {
    const app = await loginAs('ADMIN')
    expect(app.currentUser.role).toBe('admin')
    expect(app.hasAllPermissions).toBe(true)
    expect(app.hasPermission([PERMISSIONS.alertWrite])).toBe(true)
    expect(app.hasPermission(['anything:unmapped'])).toBe(true)
  })

  it('OPS：按码表发码——三类写操作放行，未映射的治理/审批类不放行', async () => {
    const app = await loginAs('OPS')
    expect(app.currentUser.role).toBe('operator')
    expect(app.hasPermission([PERMISSIONS.alertWrite])).toBe(true)
    expect(app.hasPermission([PERMISSIONS.ticketWrite])).toBe(true)
    expect(app.hasPermission([PERMISSIONS.knowledgeEdit])).toBe(true)
    // 后端实证仅 ADMIN 的面，operator 前端必须没有码
    expect(app.hasPermission(['approvals:view'])).toBe(false)
    expect(app.hasPermission(['ticket:delete'])).toBe(false)
  })

  it('未知角色（含 VIEWER）：折成 viewer 空权限，任何权限码都拒绝（fail-closed）', async () => {
    const app = await loginAs('VIEWER')
    expect(app.currentUser.role).toBe('viewer')
    expect(app.hasPermission([PERMISSIONS.alertWrite])).toBe(false)
    expect(app.hasPermission([PERMISSIONS.ticketWrite])).toBe(false)
    expect(app.hasPermission([])).toBe(true) // 空码 = 无要求，放行
  })

  it('角色大小写不敏感：后端返回小写 ops 仍映射为 operator 并带上码表', async () => {
    const app = await loginAs('ops')
    expect(app.currentUser.role).toBe('operator')
    expect(app.hasPermission([PERMISSIONS.ticketWrite])).toBe(true)
  })

  it('restoreSession 恢复的登录态同样携带码表（刷新后门控不丢）', async () => {
    setAuthToken('tok-x')
    const { getMe } = await import('@/api/auth')
    vi.mocked(getMe).mockResolvedValue({ id: 2, username: 'v', displayName: 'V', role: 'OPS' })
    const app = useAppStore()
    const ok = await app.restoreSession()
    expect(ok).toBe(true)
    expect(app.hasPermission([PERMISSIONS.alertWrite])).toBe(true)
  })
})
