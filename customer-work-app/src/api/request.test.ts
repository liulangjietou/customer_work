// @vitest-environment happy-dom
import { AxiosError, type InternalAxiosRequestConfig } from 'axios'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useAuthStore } from '@/store/auth'
import { fetchCustomerAnswerSourcePreview } from './customerAnswerSources'
import http from './request'

const { replace, showToast } = vi.hoisted(() => ({ replace: vi.fn(), showToast: vi.fn() }))
vi.mock('@/router', () => ({ default: { currentRoute: { value: { name: 'Chat' } }, replace } }))
vi.mock('vant', () => ({ showToast }))

const originalAdapter = http.defaults.adapter

/** 保留真实 Axios 两侧拦截器，仅控制网络响应何时返回。 */
function delayedUnauthorized() {
  let started!: (config: InternalAxiosRequestConfig) => void
  let rejectRequest!: (error: AxiosError) => void
  const sent = new Promise<InternalAxiosRequestConfig>(resolve => { started = resolve })
  http.defaults.adapter = config => new Promise((_resolve, reject) => {
    rejectRequest = reject
    started(config)
  })
  return {
    sent,
    reject(config: InternalAxiosRequestConfig) {
      rejectRequest(new AxiosError('Unauthorized', 'ERR_BAD_REQUEST', config, undefined, {
        status: 401, statusText: 'Unauthorized', data: { message: '登录失效' }, headers: {}, config,
      }))
    },
  }
}

describe('request 登录身份隔离', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })
  afterEach(() => { http.defaults.adapter = originalAdapter })

  it.each(['old-token', null])('已发送凭据 %s 的迟到 401 不清除之后的新登录', async token => {
    const auth = useAuthStore()
    if (token) auth.applyLogin(token, 'U1', '旧登录')
    const network = delayedUnauthorized()
    const response = fetchCustomerAnswerSourcePreview('session-1', 'message-1', 0).catch(error => error)
    const sent = await network.sent
    expect(sent.headers.get('Authorization') ?? null).toBe(token ? `Bearer ${token}` : null)
    auth.applyLogin('new-token', 'U1', '新登录')
    network.reject(sent)
    expect(await response).toBeInstanceOf(AxiosError)
    expect(auth.token).toBe('new-token')
    expect(auth.nickname).toBe('新登录')
    expect(localStorage.getItem('user-token')).toBe('new-token')
    expect(replace).not.toHaveBeenCalled()
    expect(showToast).not.toHaveBeenCalled()
  })

  it('当前登录自身的 401 仍清理状态并要求重新登录', async () => {
    const auth = useAuthStore()
    auth.applyLogin('current-token', 'U1', '当前登录')
    const network = delayedUnauthorized()
    const response = fetchCustomerAnswerSourcePreview('session-1', 'message-1', 0).catch(error => error)
    network.reject(await network.sent)
    expect(await response).toBeInstanceOf(AxiosError)
    expect(auth.token).toBeNull()
    expect(localStorage.getItem('user-token')).toBeNull()
    expect(replace).toHaveBeenCalledExactlyOnceWith({ name: 'Login' })
    expect(showToast).toHaveBeenCalledTimes(1)
  })
})
