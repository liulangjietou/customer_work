import { request } from '@/api/request'
import type { LoginRequest, LoginResponse, RegisterRequest, RegisterResponse, UserInfo } from '@/types/api'

// baseURL 已配置为 /api（见 request.ts + .env.development），此处 url 不再重复 /api 前缀
export function register(payload: RegisterRequest): Promise<RegisterResponse> {
  return request({ url: '/customer/auth/register', method: 'post', data: payload })
}

export function login(payload: LoginRequest): Promise<LoginResponse> {
  return request({ url: '/customer/auth/login', method: 'post', data: payload })
}

export function fetchMe(): Promise<UserInfo> {
  return request({ url: '/customer/auth/me', method: 'get' })
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
