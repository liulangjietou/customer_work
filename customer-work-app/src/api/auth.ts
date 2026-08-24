import { request } from '@/api/request'
import type {
  LoginRequest,
  LoginResponse,
  RegisterRequest,
  RegisterResponse,
  RevokeSessionsResponse,
  UserInfo,
} from '@/types/api'
import { currentTenantCode } from '@/utils/tenant'

// baseURL 已配置为 /api（见 request.ts + .env.development），此处 url 不再重复 /api 前缀

// 租户编码在这里统一注入，调用方（登录页/注册页）无需感知多租户；
// 单租户部署下 currentTenantCode() 返回 undefined，请求体与改造前完全一致。
export function register(payload: RegisterRequest): Promise<RegisterResponse> {
  return request({
    url: '/customer/auth/register',
    method: 'post',
    data: { ...payload, tenantCode: currentTenantCode() },
  })
}

export function login(payload: LoginRequest): Promise<LoginResponse> {
  return request({
    url: '/customer/auth/login',
    method: 'post',
    data: { ...payload, tenantCode: currentTenantCode() },
  })
}

export function fetchMe(): Promise<UserInfo> {
  return request({ url: '/customer/auth/me', method: 'get' })
}

/** 撤销该用户的全部 JWT；成功响应返回前服务端已经原子推进 sessionEpoch。 */
export function revokeSessions(): Promise<RevokeSessionsResponse> {
  return request({ url: '/customer/auth/revoke-sessions', method: 'post' })
}

/** 头像上传：multipart/form-data，字段名 file。传 FormData 时 axios 会自动带 boundary 设置 Content-Type，无需手动指定。
 * 后端返回 { avatarUrl } 与 /login、/me 风格一致，这里解包成裸 URL 字符串给调用方。 */
export function uploadAvatar(file: File): Promise<string> {
  const formData = new FormData()
  formData.append('file', file)
  return request<{ avatarUrl: string }>({ url: '/customer/auth/avatar', method: 'post', data: formData }).then(
    (r) => r.avatarUrl,
  )
}
