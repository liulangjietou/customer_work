import { AxiosError, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useAuthStore } from '@/store/auth'
import http, { download, request } from './request'

const feedback = vi.hoisted(() => ({ error: vi.fn(), replace: vi.fn(), push: vi.fn() }))
vi.mock('element-plus/es', () => ({ ElMessage: { error: feedback.error } }))
vi.mock('@/router', () => ({ default: {
  currentRoute: { value: { name: 'Orders', fullPath: '/ticket/user-order' } },
  replace: feedback.replace, push: feedback.push,
} }))

const originalAdapter = http.defaults.adapter

/** 保留真实 Axios 请求/响应拦截器，仅控制网络何时完成以及实际发出的凭据。 */
function delayedNetwork() {
  let started!: (config: InternalAxiosRequestConfig) => void
  let resolve!: (response: AxiosResponse) => void
  let reject!: (error: AxiosError) => void
  const sent = new Promise<InternalAxiosRequestConfig>(done => { started = done })
  http.defaults.adapter = config => new Promise((done, fail) => {
    resolve = done
    reject = fail
    started(config)
  })
  return {
    sent,
    result(config: InternalAxiosRequestConfig, code: number, message = '请求作用域错误') {
      resolve({ config, status: 200, statusText: 'OK', headers: {}, data: { code, message, data: null } })
    },
    httpError(config: InternalAxiosRequestConfig) {
      reject(new AxiosError('Request failed', 'ERR_BAD_RESPONSE', config, undefined, {
        config, status: 503, statusText: 'Unavailable', headers: {}, data: { code: 50000, message: '后端暂不可用' },
      }))
    },
    blobError(config: InternalAxiosRequestConfig) {
      resolve({ config, status: 200, statusText: 'OK', headers: { 'content-type': 'application/json' },
        data: new Blob([JSON.stringify({ code: 50000, message: '导出暂不可用' })], { type: 'application/json' }),
      })
    },
  }
}

function applyLogin(token: string) {
  useAuthStore().applyLoginResult({
    token, nickname: token, forceChangePassword: false, approvalStatus: 'APPROVED', approvalRemark: null,
  }, token)
}

beforeEach(() => {
  const storage = new Map<string, string>()
  vi.stubGlobal('localStorage', {
    getItem: (key: string) => storage.get(key) ?? null,
    setItem: (key: string, value: string) => storage.set(key, value),
    removeItem: (key: string) => storage.delete(key),
  })
  setActivePinia(createPinia())
  vi.clearAllMocks()
})

afterEach(() => {
  http.defaults.adapter = originalAdapter
  vi.unstubAllGlobals()
})

describe('Admin 请求凭据与全局副作用隔离', () => {
  for (const token of ['old-token', null]) {
    it.each([10001, 20002, 50000])(`请求凭据 ${token} 的迟到 code=%s 不干扰新登录`, async code => {
      if (token) applyLogin(token)
      const network = delayedNetwork()
      const pending = request({ url: '/ticket/orders/old-order' }).catch(error => error)
      const sent = await network.sent
      expect(sent.headers.get('Authorization') ?? null).toBe(token)
      applyLogin('new-token')

      network.result(sent, code)

      expect(await pending).toMatchObject({ code })
      expect(useAuthStore().token).toBe('new-token')
      expect(localStorage.getItem('admin-token')).toBe('new-token')
      expect(feedback.replace).not.toHaveBeenCalled()
      expect(feedback.push).not.toHaveBeenCalled()
      expect(feedback.error).not.toHaveBeenCalled()
    })
  }

  it('当前登录的 10001 仍清凭据、跳登录并提示，不受局部提示配置影响', async () => {
    applyLogin('current-token')
    const network = delayedNetwork()
    const pending = request({ url: '/ticket/orders/current-order', suppressErrorMessage: true }).catch(error => error)
    network.result(await network.sent, 10001, '当前登录已失效')

    expect(await pending).toMatchObject({ code: 10001 })
    expect(useAuthStore().token).toBeNull()
    expect(localStorage.getItem('admin-token')).toBeNull()
    expect(feedback.replace).toHaveBeenCalledExactlyOnceWith({
      name: 'Login', query: { redirect: '/ticket/user-order' },
    })
    expect(feedback.error).toHaveBeenCalledExactlyOnceWith('当前登录已失效')
  })

  it('当前登录的 20002 仍进入改密流程', async () => {
    applyLogin('current-token')
    const network = delayedNetwork()
    const pending = request({ url: '/ticket/orders/current-order' }).catch(error => error)
    network.result(await network.sent, 20002)

    expect(await pending).toMatchObject({ code: 20002 })
    expect(useAuthStore().token).toBe('current-token')
    expect(feedback.push).toHaveBeenCalledExactlyOnceWith('/change-password')
    expect(feedback.replace).not.toHaveBeenCalled()
  })

  it('当前作用域的业务失败仍向调用方拒绝并显示原因', async () => {
    applyLogin('current-token')
    const network = delayedNetwork()
    const pending = request({ url: '/ticket/orders/current-order' }).catch(error => error)
    network.result(await network.sent, 50000, '当前订单查询失败')

    expect(await pending).toMatchObject({ code: 50000 })
    expect(feedback.error).toHaveBeenCalledExactlyOnceWith('当前订单查询失败')
    expect(useAuthStore().token).toBe('current-token')
  })

  it.each([false, true])('HTTP 失败 changedIdentity=%s 只在原身份仍有效时提示', async changedIdentity => {
    applyLogin('current-token')
    const network = delayedNetwork()
    const pending = request({ url: '/ticket/orders/current-order' }).catch(error => error)
    const sent = await network.sent
    if (changedIdentity) applyLogin('new-token')
    network.httpError(sent)

    expect(await pending).toBeInstanceOf(AxiosError)
    expect(feedback.error).toHaveBeenCalledTimes(changedIdentity ? 0 : 1)
    if (!changedIdentity) expect(feedback.error).toHaveBeenCalledWith('后端暂不可用')
  })

  it.each([false, true])('导出返回 JSON 错误 changedIdentity=%s 只在原身份仍有效时提示', async changedIdentity => {
    applyLogin('current-token')
    const network = delayedNetwork()
    const pending = download({ url: '/ticket/orders/export' }, 'orders.xlsx').catch(error => error)
    const sent = await network.sent
    if (changedIdentity) applyLogin('new-token')
    network.blobError(sent)

    expect(await pending).toMatchObject({ code: 50000 })
    expect(feedback.error).toHaveBeenCalledTimes(changedIdentity ? 0 : 1)
    if (!changedIdentity) expect(feedback.error).toHaveBeenCalledWith('导出暂不可用')
  })

  it('当前请求主动关闭全局错误提示时保留既有语义', async () => {
    applyLogin('current-token')
    const network = delayedNetwork()
    const pending = request({ url: '/ticket/orders/current-order', suppressErrorMessage: true }).catch(error => error)
    network.result(await network.sent, 50000)

    expect(await pending).toMatchObject({ code: 50000 })
    expect(feedback.error).not.toHaveBeenCalled()
  })
})
