import { defineStore } from 'pinia'
import { loadPersisted, savePersisted, type Migrator } from '@/utils/persist'

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

const PROFILE_KEY = 'user-profile'
const SETTINGS_KEY = 'app-settings'
const STORE_VERSION = 1

const PROFILE_MIGRATIONS: Record<number, Migrator> = {}
const SETTINGS_MIGRATIONS: Record<number, Migrator> = {}

// TODO(P2-鉴权): 此处为硬编码假管理员，待真实登录鉴权接入后替换。
// isAuthenticated: true（下方 state）同理——L1 阶段无认证，MVP 演示用默认管理员身份。
// 06-模块核查设计修复方案 P1 已修 AppNavbar.doLogout 调用 signOut()，
// 此处保留硬编码默认值；真实鉴权落地时需：① 改 isAuthenticated 初值为 false；
// ② DEFAULT_USER 改为 guest 占位；③ 由登录回调写入真实 user。
const DEFAULT_USER: CurrentUser = {
  name: '管理员',
  email: 'admin@devops.local',
  avatar: '管',
  role: 'admin',
  title: '高级运维工程师',
  permissions: ['*']
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

const loadProfile = (): CurrentUser => {
  // 检查登出标记：signOut 时写入 app-authenticated=false
  // 若标记存在，不恢复管理员身份
  try {
    if (localStorage.getItem('app-authenticated') === 'false') {
      return {
        name: '访客',
        email: '',
        avatar: '访',
        role: 'guest',
        title: '',
        permissions: []
      }
    }
  } catch {
    // localStorage 不可用，退回默认
  }
  const saved = loadPersisted<Partial<CurrentUser>>(PROFILE_KEY, STORE_VERSION, { migrations: PROFILE_MIGRATIONS })
  return { ...DEFAULT_USER, ...(saved || {}) }
}

const loadSettings = (): AppSettings => {
  const saved = loadPersisted<Partial<AppSettings>>(SETTINGS_KEY, STORE_VERSION, { migrations: SETTINGS_MIGRATIONS })
  return { ...DEFAULT_SETTINGS, ...(saved || {}) }
}

export const useAppStore = defineStore('app', {
  state: () => ({
    loadingCount: 0,
    isAuthenticated: true,
    currentUser: loadProfile(),
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
    },
    signOut() {
      this.isAuthenticated = false
      this.currentUser = {
        name: '访客',
        email: '',
        avatar: '访',
        role: 'guest',
        title: '',
        permissions: []
      }
      // 持久化登出态，避免刷新后 loadProfile 又恢复成管理员
      savePersisted(PROFILE_KEY, this.currentUser, STORE_VERSION)
      // 写入一个 isAuthenticated=false 标记，loadProfile 据此不合并 DEFAULT_USER
      try {
        localStorage.setItem('app-authenticated', 'false')
      } catch {
        // 持久化失败不阻塞登出
      }
    }
  }
})
