import { defineStore } from 'pinia'
import { loadPersisted, savePersisted, type Migrator } from '@/utils/persist'
import { getAuthToken, setAuthToken, clearAuthToken, HttpError } from '@/utils/http'
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
/**
 * 登录身份缓存键。
 *
 * 必须与 PROFILE_KEY 分开——后者存的是 `CurrentUser`（前端视图，含 avatar/title/permissions），
 * 本键存的是 `AuthUser`（后端原始字段）。共用一个键会互相覆盖成错误结构，
 * 读出来的对象缺字段却不报错，故障表现为身份信息莫名残缺。
 */
const AUTH_USER_CACHE_KEY = 'auth-user-cache'
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

/**
 * 判定异常是否为「服务端明确拒绝凭证」。
 *
 * 只有 HTTP 401 与后端未登录业务码 40101 算凭证失效；
 * 网络故障 / 超时 / 5xx 都不算——把它们当失效会因一次抖动就登出已登录用户。
 * 40101 需单列：`AuthController.me()` 未登录时返回 **HTTP 200 + bizCode 40101**，
 * 不是 401，仅判 status 会漏掉这条真实的失效路径。
 */
const isCredentialRejected = (e: unknown): boolean => {
  if (!(e instanceof HttpError)) return false
  return e.status === 401 || e.bizCode === 40101
}

/**
 * 缓存登录用户信息，供 /auth/me 暂时不可达时沿用身份。
 *
 * 只缓存后端 toUserView 的字段（id/username/displayName/role，绝不含密码）。
 * 缓存仅用于「已持有有效 token 但服务暂时问不到」的降级展示，
 * 不作为鉴权依据——鉴权始终由服务端对 token 的判定决定。
 */
const saveCachedUser = (user: AuthUser): void => {
  savePersisted(AUTH_USER_CACHE_KEY, user, STORE_VERSION)
}

const loadCachedUser = (): AuthUser | null =>
  loadPersisted<AuthUser>(AUTH_USER_CACHE_KEY, STORE_VERSION) ?? null

const clearCachedUser = (): void => {
  try {
    localStorage.removeItem(AUTH_USER_CACHE_KEY)
  } catch {
    /* 隐私模式：忽略 */
  }
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
      // 缓存身份供服务暂时不可达时沿用；换账号时覆盖，不会串用上一个人的信息
      saveCachedUser(result.user)
    },

    /**
     * 恢复会话：应用启动时若本地有 token，调 /auth/me 验证并恢复登录态。
     *
     * **必须区分「凭证失效」与「服务暂时不可达」**——此前一律 catch 后 clearAuthToken，
     * 导致后端未启动 / 重启中 / 网络抖动 / 超时都会把已登录用户登出，
     * 用户下一次点导航就收到「请先登录」弹窗，而 token 其实还有效（sa-token timeout 24h）。
     *
     * - 401 或 bizCode 40101 → 服务端明确说未登录，清 token 并降级为访客
     * - 其他错误（网络 / 超时 / 5xx）→ **保留 token**，用缓存的用户信息乐观维持登录态；
     *   若 token 真已失效，后续任一业务请求的 401 会走 http 层的正规登出流程
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
        saveCachedUser(user)
        return true
      } catch (e) {
        if (isCredentialRejected(e)) {
          // 服务端明确拒绝：凭证确实失效
          clearAuthToken()
          clearCachedUser()
          this.isAuthenticated = false
          this.currentUser = { ...GUEST_USER }
          return false
        }

        // 服务暂时不可达：保留 token，维持登录态，避免因故障把用户登出
        const cached = loadCachedUser()
        console.warn('[auth] /auth/me 暂时不可达，保留本地 token 并沿用缓存身份:', e)
        this.currentUser = cached ? toCurrentUser(cached) : { ...GUEST_USER }
        this.isAuthenticated = true
        return true
      }
    },

    /**
     * 本地重置为访客态（不调后端）。
     *
     * 用于 http 层 401 后的状态收敛：token 已失效，再调 /auth/logout 必然也是 401，
     * 故只清本地状态。与 signOut 的区别是它不做服务端登出。
     */
    resetToGuest() {
      clearAuthToken()
      clearCachedUser()
      this.isAuthenticated = false
      this.currentUser = { ...GUEST_USER }
    },

    /** 登出：后端失效 token + 清本地 token + 重置为访客 */
    async signOut() {
      await apiLogout()
      clearAuthToken()
      clearCachedUser()
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
