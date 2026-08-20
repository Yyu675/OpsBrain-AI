import { defineStore } from 'pinia'
import { loadPersisted, savePersisted, type Migrator } from '@/utils/persist'
import { getAuthToken, setAuthToken, clearAuthToken } from '@/utils/http'
import { login as apiLogin, getMe as apiGetMe, logout as apiLogout, type AuthUser } from '@/api/auth'

export type Role = 'admin' | 'operator' | 'viewer' | 'guest'

export interface CurrentUser {
  name: string
  email: string
  avatar: string
  role: Role
  title: string
  permissions: string[]
}

export interface AppSettings {
  notificationsEnabled: boolean
  emailDigest: boolean
  compactTable: boolean
  idleTimeoutMinutes: number
}

const SETTINGS_KEY = 'app-settings'
const PROFILE_KEY = 'user-profile'
const STORE_VERSION = 1

const SETTINGS_MIGRATIONS: Record<number, Migrator> = {}

/** 访客（未登录）身份 */
const GUEST_USER: CurrentUser = {
  name: '访客',
  email: '',
  avatar: '访',
  role: 'guest',
  title: '',
  permissions: []
}

const DEFAULT_SETTINGS: AppSettings = {
  notificationsEnabled: true,
  emailDigest: false,
  compactTable: false,
  idleTimeoutMinutes: 15
}

const roleLabels: Record<Role, string> = {
  admin: '管理员',
  operator: '运维工程师',
  viewer: '只读用户',
  guest: '访客'
}

/** 后端角色（ADMIN/OPS）→ 前端 Role + 权限。ADMIN 全权限，OPS 常规运维权限。 */
const mapBackendRole = (backendRole: string | null | undefined): { role: Role; permissions: string[] } => {
  switch ((backendRole || '').toUpperCase()) {
    case 'ADMIN':
      return { role: 'admin', permissions: ['*'] }
    case 'OPS':
      return { role: 'operator', permissions: [] }
    default:
      return { role: 'viewer', permissions: [] }
  }
}

/** 后端登录用户 → 前端 CurrentUser 视图 */
const toCurrentUser = (u: AuthUser): CurrentUser => {
  const { role, permissions } = mapBackendRole(u.role)
  const name = u.displayName || u.username
  return {
    name,
    email: '',
    avatar: name.charAt(0).toUpperCase(),
    role,
    title: roleLabels[role],
    permissions
  }
}

const loadSettings = (): AppSettings => {
  const saved = loadPersisted<Partial<AppSettings>>(SETTINGS_KEY, STORE_VERSION, { migrations: SETTINGS_MIGRATIONS })
  return { ...DEFAULT_SETTINGS, ...(saved || {}) }
}

export const useAppStore = defineStore('app', {
  state: () => ({
    loadingCount: 0,
    // 真实鉴权（方向三）：初值 false，登录成功或 restoreSession 命中才置 true。
    // 不再硬编码 true——此前 MVP 假管理员已移除。
    isAuthenticated: false,
    currentUser: { ...GUEST_USER } as CurrentUser,
    settings: loadSettings()
  }),
  getters: {
    isLoading(state): boolean {
      return state.loadingCount > 0
    },
    hasAllPermissions(state) {
      return state.currentUser.permissions.includes('*')
    },
    roleLabel(state): string {
      return roleLabels[state.currentUser.role] || state.currentUser.role
    }
  },
  actions: {
    beginLoading() {
      this.loadingCount += 1
    },
    endLoading() {
      if (this.loadingCount > 0) this.loadingCount -= 1
    },
    resetLoading() {
      this.loadingCount = 0
    },
    setLoading(loading: boolean) {
      if (loading) this.beginLoading()
      else this.endLoading()
    },
    hasRole(roles?: Role[]) {
      if (!roles || roles.length === 0) return true
      if (this.hasAllPermissions) return true
      return roles.includes(this.currentUser.role)
    },
    hasPermission(codes?: string[]) {
      if (!codes || codes.length === 0) return true
      if (this.hasAllPermissions) return true
      return codes.every((c) => this.currentUser.permissions.includes(c))
    },

    // ==================== 鉴权（方向三：Sa-Token）====================

    /** 登录：调后端 → 存 token → 写登录态。失败抛出（由登录页 catch 展示） */
    async login(username: string, password: string) {
      const result = await apiLogin(username, password)
      setAuthToken(result.token)
      this.currentUser = toCurrentUser(result.user)
      this.isAuthenticated = true
    },

    /**
     * 恢复会话：应用启动时若本地有 token，调 /auth/me 验证并恢复登录态。
     * token 失效（401）时 http 层已清 token；这里静默失败为未登录。
     */
    async restoreSession(): Promise<boolean> {
      if (!getAuthToken()) {
        this.isAuthenticated = false
        this.currentUser = { ...GUEST_USER }
        return false
      }
      try {
        const user = await apiGetMe()
        this.currentUser = toCurrentUser(user)
        this.isAuthenticated = true
        return true
      } catch {
        clearAuthToken()
        this.isAuthenticated = false
        this.currentUser = { ...GUEST_USER }
        return false
      }
    },

    /** 登出：后端失效 token + 清本地 token + 重置为访客 */
    async signOut() {
      await apiLogout()
      clearAuthToken()
      this.isAuthenticated = false
      this.currentUser = { ...GUEST_USER }
    },

    updateProfile(patch: Partial<Pick<CurrentUser, 'name' | 'email' | 'title'>>) {
      const next: CurrentUser = { ...this.currentUser, ...patch }
      if (patch.name) next.avatar = patch.name.charAt(0).toUpperCase()
      this.currentUser = next
      savePersisted(PROFILE_KEY, next, STORE_VERSION)
    },
    updateSettings(patch: Partial<AppSettings>) {
      this.settings = { ...this.settings, ...patch }
      savePersisted(SETTINGS_KEY, this.settings, STORE_VERSION)
    },
    resetSettings() {
      this.settings = { ...DEFAULT_SETTINGS }
      savePersisted(SETTINGS_KEY, this.settings, STORE_VERSION)
    }
  }
})
