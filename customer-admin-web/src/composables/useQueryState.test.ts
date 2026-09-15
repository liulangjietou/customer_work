import { createPinia, setActivePinia } from 'pinia'
import { effectScope, type EffectScope } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useAuthStore } from '@/store/auth'
import { useQueryState } from './useQueryState'

vi.mock('@/api/auth', () => ({ fetchMyPermissions: vi.fn(), logout: vi.fn() }))
function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((done, fail) => { resolve = done; reject = fail })
  return { promise, resolve, reject }
}
function login(token = 'test-token') {
  useAuthStore().applyLoginResult({ token, nickname: '验收账号', forceChangePassword: false,
    approvalStatus: 'APPROVED', approvalRemark: null }, 'operator')
  useAuthStore().permissions = ['csat:view']
}
const scopes: EffectScope[] = []
function setup() {
  const scope = effectScope()
  scopes.push(scope)
  const query = vi.fn<() => Promise<{ count: number; rows: string[] }>>()
  const state = scope.run(() => useQueryState(query, () => ({ count: 0, rows: [] as string[] })))!
  return { scope, query, state }
}
beforeEach(() => {
  const storage = new Map<string, string>()
  vi.stubGlobal('localStorage', { getItem: (key: string) => storage.get(key) ?? null,
    setItem: (key: string, value: string) => storage.set(key, value), removeItem: (key: string) => storage.delete(key) })
  setActivePinia(createPinia())
  login()
})
afterEach(() => { scopes.splice(0).forEach(scope => scope.stop()); vi.unstubAllGlobals() })

describe('看板查询快照', () => {
  it('汇总和明细部分失败时保留完整旧快照，重试成功才一起替换', async () => {
    const { query, state } = setup()
    query.mockResolvedValueOnce({ count: 1, rows: ['原结果'] })
    await state.load()
    const failure = new Error('summary unavailable')
    query.mockImplementationOnce(async () => {
      const [count, rows] = await Promise.all([Promise.reject(failure), Promise.resolve(['新结果'])])
      return { count, rows }
    })
    await state.load()
    expect(state.error.value).toBe(failure)
    expect(state.data.value).toEqual({ count: 1, rows: ['原结果'] })
    expect(state.loaded.value).toBe(true)
    query.mockResolvedValueOnce({ count: 0, rows: [] })
    await state.load()
    expect(state.error.value).toBeNull()
    expect(state.data.value).toEqual({ count: 0, rows: [] })
    expect(state.loaded.value).toBe(true)
  })

  it.each(['success', 'failure'] as const)('旧查询迟到 %s 不覆盖新查询，也不能提前解除等待状态', async outcome => {
    const { query, state } = setup()
    const old = deferred<{ count: number; rows: string[] }>()
    const latest = deferred<{ count: number; rows: string[] }>()
    query.mockReturnValueOnce(old.promise).mockReturnValueOnce(latest.promise)
    const oldLoad = state.load()
    const currentLoad = state.load()
    if (outcome === 'success') old.resolve({ count: 1, rows: ['旧数据'] })
    else old.reject(new Error('old failure'))
    await oldLoad
    expect(state.data.value.rows).toEqual([])
    expect(state.error.value).toBeNull()
    expect(state.loading.value).toBe(true)
    latest.resolve({ count: 1, rows: ['当前数据'] })
    await currentLoad
    expect(state.data.value.rows).toEqual(['当前数据'])
    expect(state.loading.value).toBe(false)
  })

  it.each(['same-token', 'new-token', 'permission', 'dispose'] as const)(
    '%s 后不接受迟到结果，身份变化立即清除已显示的记录', async change => {
      const { scope, query, state } = setup()
      query.mockResolvedValueOnce({ count: 1, rows: ['原身份数据'] })
      await state.load()
      const held = deferred<{ count: number; rows: string[] }>()
      query.mockReturnValueOnce(held.promise)
      const pending = state.load()
      if (change === 'dispose') scope.stop()
      else if (change === 'permission') useAuthStore().permissions = []
      else login(change === 'same-token' ? 'test-token' : 'new-token')
      if (change !== 'dispose') {
        expect(state.data.value.rows).toEqual([])
        expect(state.loaded.value).toBe(false)
        expect(state.loading.value).toBe(false)
      }
      held.resolve({ count: 1, rows: ['迟到数据'] })
      await pending
      expect(state.data.value.rows).not.toContain('迟到数据')
    },
  )
})
