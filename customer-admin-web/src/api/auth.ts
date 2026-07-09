import { request } from './request'
import type { ChangePasswordRequest, LoginRequest, LoginResponse, SsoLoginRequest } from '@/types/api'

export function login(data: LoginRequest) {
  return request<LoginResponse>({ url: '/auth/login', method: 'post', data })
}

/** OA 域账号（LDAP/AD）单点登录。 */
export function ssoLogin(data: SsoLoginRequest) {
  return request<LoginResponse>({ url: '/auth/sso-login', method: 'post', data })
}

export function logout() {
  return request<void>({ url: '/auth/logout', method: 'post' })
}

export function changePassword(data: ChangePasswordRequest) {
  return request<void>({ url: '/auth/change-password', method: 'post', data })
}

/** 当前用户全量权限点（含按钮/接口级），v-permission 指令用。 */
export function fetchMyPermissions() {
  return request<string[]>({ url: '/auth/permissions', method: 'get' })
}
