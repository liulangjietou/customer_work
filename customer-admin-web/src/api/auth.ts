import { request } from './request'
import type {
  CaptchaChallenge,
  ChangePasswordRequest,
  EmailCodeRequest,
  LoginCaptchaChallenge,
  LoginCaptchaProof,
  LoginCaptchaVerifyRequest,
  LoginRequest,
  LoginResponse,
  PasswordResetEmailCodeRequest,
  PasswordResetRequest,
  RegisterOptionsVO,
  RegisterRequest,
  SsoLoginRequest,
} from '@/types/api'

/** 登录页自助注册。新账号默认进入待审核态，不自动登录。 */
export function register(data: RegisterRequest) {
  return request<void>({ url: '/auth/register', method: 'post', data })
}

/** 本实例是否开放注册、发码是否要求图形验证码。登录前匿名可调。 */
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

/**
 * 向账号登记的邮箱发送密码重置验证码，返回有效期（秒）。
 *
 * 无论该用户名与邮箱是否真的对应一个账号，服务端都返回同样的结果——
 * 这是刻意的，差异会让这个匿名接口变成账号与邮箱的关联查询服务。
 */
export function sendPasswordResetEmailCode(data: PasswordResetEmailCodeRequest) {
  return request<number>({ url: '/auth/password-reset/email-code', method: 'post', data })
}

/** 凭邮箱验证码重置登录密码。成功后既有登录态全部失效，需用新密码重新登录。 */
export function resetPassword(data: PasswordResetRequest) {
  return request<void>({ url: '/auth/password-reset', method: 'post', data })
}

/** 签发与当前来源 IP、浏览器绑定的登录拖动挑战。 */
export function fetchLoginCaptchaChallenge() {
  return request<LoginCaptchaChallenge>({
    url: '/auth/login-captcha/challenge',
    method: 'post',
    suppressErrorMessage: true,
  })
}

/** 提交归一化拖动轨迹，换取短期、一次性的登录 proof。 */
export function verifyLoginCaptcha(data: LoginCaptchaVerifyRequest) {
  return request<LoginCaptchaProof>({
    url: '/auth/login-captcha/verify',
    method: 'post',
    data,
    suppressErrorMessage: true,
  })
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
