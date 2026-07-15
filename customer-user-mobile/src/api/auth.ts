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
