/**
 * 鉴权 API（方向三：Sa-Token）
 *
 * 纯 API 层：登录/取当前用户/登出。token 的存取由 utils/http 的
 * setAuthToken/clearAuthToken 负责，登录态由 stores/app 管理。
 */
import { API_ENDPOINTS } from '../config/api'
import { http, unwrapBiz } from '../utils/http'

/** 登录用户视图（后端 toUserView，绝不含密码） */
export interface AuthUser {
  id: number
  username: string
  displayName: string | null
  role: string
}

export interface LoginResult {
  token: string
  tokenName: string
  user: AuthUser
}

/** 登录：成功返回 token + 用户信息；失败抛 HttpError（bizCode 40100） */
export async function login(username: string, password: string): Promise<LoginResult> {
  const payload = await http.post<unknown>(API_ENDPOINTS.AUTH_LOGIN, { username, password })
  return unwrapBiz<LoginResult>(payload, '登录失败')
}

/** 取当前登录用户（带 token 自动附带）；未登录抛 HttpError */
export async function getMe(): Promise<AuthUser> {
  const payload = await http.get<unknown>(API_ENDPOINTS.AUTH_ME)
  return unwrapBiz<AuthUser>(payload, '获取用户信息失败')
}

/** 登出：Sa-Token 服务端失效 token。失败不抛（登出应尽力而为）。 */
export async function logout(): Promise<void> {
  try {
    await http.post<unknown>(API_ENDPOINTS.AUTH_LOGOUT, {})
  } catch {
    /* 登出失败（如 token 已失效）忽略——前端仍会清本地 token */
  }
}
