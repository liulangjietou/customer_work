import { createPinia, setActivePinia } from 'pinia'
import { effectScope, type EffectScope } from 'vue'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { useAuthStore } from '@/store/auth'
import { useRowMutation } from './useRowMutation'

vi.mock('@/api/auth', () => ({ fetchMyPermissions: vi.fn(), logout: vi.fn() }))

function deferred() {
  let resolve!: () => void
  let reject!: (error: Error) => void
  const promise = new Promise<void>((done, fail) => { resolve = done; reject = fail })
  return { promise, resolve, reject }
}

let scope: EffectScope
beforeEach(() => {
  const storage = new Map<string, string>()
  vi.stubGlobal('localStorage', { getItem: (key: string) => storage.get(key) ?? null,
    setItem: (key: string, value: string) => storage.set(key, value),
    removeItem: (key: string) => storage.delete(key) })
  setActivePinia(createPinia())
  scope = effectScope()
  useAuthStore().token = 'same-token'
  useAuthStore().permissions = ['tool:edit']
})
afterEach(() => { scope.stop(); vi.unstubAllGlobals() })

it('同一行不能重复写，不阻塞另一行；失败回滚一次后可重试成功', async () => {
  const mutation = scope.run(() => useRowMutation('tool:edit'))!
  const pending = deferred()
  const write = vi.fn(() => pending.promise)
  const success = vi.fn()
  const rollback = vi.fn()
  const first = mutation.run(7, write, success, rollback)
  await mutation.run(7, write, success, rollback)
  const otherSuccess = vi.fn()
  await mutation.run(8, async () => {}, otherSuccess)
  expect(write).toHaveBeenCalledTimes(1)
  expect(otherSuccess).toHaveBeenCalledTimes(1)
  pending.reject(new Error('save failed'))
  await first
  expect(success).not.toHaveBeenCalled()
  expect(rollback).toHaveBeenCalledTimes(1)
  await mutation.run(7, async () => {}, success, rollback)
  expect(success).toHaveBeenCalledTimes(1)
  expect(rollback).toHaveBeenCalledTimes(1)
})

for (const transition of ['relogin', 'permission', 'dispose'] as const) {
  it(`${transition} 后旧成功与失败不能刷新或回滚当前页面，也不能释放新写操作的锁`, async () => {
    const auth = useAuthStore()
    const mutation = scope.run(() => useRowMutation('tool:edit'))!
    const oldSuccess = deferred()
    const oldFailure = deferred()
    const success = vi.fn()
    const rollback = vi.fn()
    const first = mutation.run(7, () => oldSuccess.promise, success, rollback)
    const second = mutation.run(8, () => oldFailure.promise, success, rollback)
    if (transition === 'dispose') scope.stop()
    else if (transition === 'permission') auth.permissions = []
    else auth.applyLoginResult({ token: auth.token!, nickname: '新登录', forceChangePassword: false,
      approvalStatus: 'APPROVED', approvalRemark: null }, 'admin')

    const current = deferred()
    const currentWrite = vi.fn(() => current.promise)
    if (transition !== 'dispose') auth.permissions = ['tool:edit']
    const next = mutation.run(7, currentWrite, success, rollback)
    oldSuccess.resolve()
    oldFailure.reject(new Error('old save failed'))
    await Promise.all([first, second])
    expect(success).not.toHaveBeenCalled()
    expect(rollback).not.toHaveBeenCalled()
    await mutation.run(7, currentWrite, success, rollback)
    expect(currentWrite).toHaveBeenCalledTimes(transition === 'dispose' ? 0 : 1)
    current.resolve()
    await next
    expect(success).toHaveBeenCalledTimes(transition === 'dispose' ? 0 : 1)
  })
}

it('没有写权限时不请求也不触发成功回调', async () => {
  useAuthStore().permissions = ['tool:view']
  const mutation = scope.run(() => useRowMutation('tool:edit'))!
  const write = vi.fn()
  const success = vi.fn()
  await mutation.run(7, write, success)
  expect(write).not.toHaveBeenCalled()
  expect(success).not.toHaveBeenCalled()
})
