import { request } from './request'
import type {
  CaptchaChallenge,
  ChangePasswordRequest,
  EmailCodeRequest,
  LoginRequest,
  LoginResponse,
  RegisterOptionsVO,
  RegisterRequest,
  SsoLoginRequest,
} from '@/types/api'

/** 登录页自助注册。新账号默认进入待审核态，不自动登录。 */
export function register(data: RegisterRequest) {
  return request<void>({ url: '/auth/register', method: 'post', data })
}

/** 本实例是否开放注册、是否要求验证码与邮箱。登录前匿名可调。 */
export function fetchRegisterOptions() {
  return request<RegisterOptionsVO>({ url: '/auth/register-options', method: 'get' })
}

/** 取一张新的图形验证码。每次调用都是新的一张。 */
export function fetchCaptcha() {
  return request<CaptchaChallenge>({ url: '/auth/captcha', method: 'get' })
}

/** 向注册邮箱发送验证码，返回有效期（秒）。 */
export function sendRegisterEmailCode(data: EmailCodeRequest) {
  return request<number>({ url: '/auth/email-code', method: 'post', data })
}

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
