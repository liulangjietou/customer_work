import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useAuthStore } from '@/store/auth'

const api = vi.hoisted(() => ({ permissions: vi.fn(), logout: vi.fn() }))
vi.mock('@/api/auth', () => ({ fetchMyPermissions: api.permissions, logout: api.logout }))

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (error: Error) => void
  const promise = new Promise<T>((done, fail) => { resolve = done; reject = fail })
  return { promise, resolve, reject }
}

function login(token: string, approvalStatus: 'APPROVED' | 'PENDING' = 'APPROVED', forceChangePassword = false) {
  useAuthStore().applyLoginResult({ token, nickname: token, approvalStatus, forceChangePassword, approvalRemark: null }, token)
}

beforeEach(() => {
  const values = new Map<string, string>()
  vi.stubGlobal('localStorage', {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => values.set(key, value),
    removeItem: (key: string) => values.delete(key),
  })
  setActivePinia(createPinia())
  vi.resetAllMocks()
})
afterEach(() => vi.unstubAllGlobals())

describe('登录生命周期的权限与退出隔离', () => {
  it.each(['APPROVED', 'PENDING'] as const)('应用新登录 %s 立即回收上一账号权限', approval => {
    login('old-token')
    useAuthStore().permissions = ['user:edit']
    login('new-token', approval)
    expect(useAuthStore().permissions).toEqual([])
  })

  it('旧权限迟到不能覆盖已经加载的新账号权限', async () => {
    login('old-token')
    const old = deferred<string[]>()
    api.permissions.mockReturnValueOnce(old.promise).mockResolvedValueOnce(['user-order:view'])
    const pending = useAuthStore().loadPermissions()
    login('new-token')
    await useAuthStore().loadPermissions()
    old.resolve(['user:edit'])
    await pending
    expect(useAuthStore().permissions).toEqual(['user-order:view'])
  })

  it.each(['logout', 'same-token', 'force-change', 'server-force-change'] as const)('旧权限在 %s 后不能恢复业务权限', async transition => {
    login('old-token')
    const old = deferred<string[]>()
    api.permissions.mockReturnValueOnce(old.promise)
    const pending = useAuthStore().loadPermissions()
    if (transition === 'server-force-change') useAuthStore().requirePasswordChange()
    else useAuthStore().clear()
    if (transition === 'same-token') login('old-token')
    if (transition === 'force-change') login('new-token', 'APPROVED', true)
    old.resolve(['user:edit'])
    await pending
    expect(useAuthStore().permissions).toEqual([])
  })

  it.each([false, true])('旧退出请求 failed=%s 完成不能清除新登录', async failed => {
    login('old-token')
    const old = deferred<void>()
    api.logout.mockReturnValueOnce(old.promise)
    const pending = useAuthStore().logout().catch(error => error)
    login('new-token')
    useAuthStore().permissions = ['user-order:view']
    if (failed) old.reject(new Error('old logout unavailable'))
    else old.resolve()
    await pending
    expect(useAuthStore().token).toBe('new-token')
    expect(localStorage.getItem('admin-token')).toBe('new-token')
    expect(useAuthStore().permissions).toEqual(['user-order:view'])
  })

  it('当前登录正常加载权限，退出请求失败也清除本地凭据', async () => {
    login('current-token')
    api.permissions.mockResolvedValue(['user-order:view'])
    await useAuthStore().loadPermissions()
    expect(useAuthStore().permissions).toEqual(['user-order:view'])
    api.logout.mockRejectedValue(new Error('logout unavailable'))
    await expect(useAuthStore().logout()).rejects.toThrow('logout unavailable')
    expect(useAuthStore().token).toBeNull()
    expect(useAuthStore().permissions).toEqual([])
    expect(localStorage.getItem('admin-token')).toBeNull()
  })
})
