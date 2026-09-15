import { createPinia, setActivePinia } from 'pinia'
import { effectScope, ref, type EffectScope } from 'vue'
import type { FormInstance } from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useAuthStore } from '@/store/auth'
import { useCrudPage } from './useCrudPage'

const feedback = vi.hoisted(() => ({ success: vi.fn(), confirm: vi.fn() }))
vi.mock('element-plus/es', () => ({
  ElMessage: { success: feedback.success },
  ElMessageBox: { confirm: feedback.confirm },
}))
vi.mock('@/api/auth', () => ({ fetchMyPermissions: vi.fn(), logout: vi.fn() }))

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((done, fail) => { resolve = done; reject = fail })
  return { promise, resolve, reject }
}
function login(token = 'current-token') {
  useAuthStore().applyLoginResult({ token, nickname: '管理员', forceChangePassword: false,
    approvalStatus: 'APPROVED', approvalRemark: null }, 'admin')
  useAuthStore().permissions = ['role:add', 'role:edit', 'role:delete']
}
const scopes: EffectScope[] = []
function setup(validate?: () => Promise<boolean>) {
  const scope = effectScope()
  scopes.push(scope)
  const page = vi.fn().mockResolvedValue({ list: [], total: 0 })
  const create = vi.fn().mockResolvedValue(undefined)
  const update = vi.fn().mockResolvedValue(undefined)
  const remove = vi.fn().mockResolvedValue(undefined)
  const crud = scope.run(() => useCrudPage<{ id: number; name: string },
    { pageNum: number; pageSize: number }, { name: string }>({
    page, create, update, remove,
    formRef: validate ? ref({ validate } as FormInstance) : undefined,
    initQuery: () => ({ pageNum: 1, pageSize: 10 }),
    initForm: () => ({ name: '' }), toForm: row => ({ name: row.name }),
  }))!
  return { scope, crud, page, create, update, remove }
}
beforeEach(() => {
  const storage = new Map<string, string>()
  vi.stubGlobal('localStorage', { getItem: (key: string) => storage.get(key) ?? null,
    setItem: (key: string, value: string) => storage.set(key, value),
    removeItem: (key: string) => storage.delete(key) })
  setActivePinia(createPinia())
  vi.resetAllMocks()
  feedback.confirm.mockResolvedValue(undefined)
  login()
})
afterEach(() => { scopes.splice(0).forEach(scope => scope.stop()); vi.unstubAllGlobals() })

describe('管理页动作归属', () => {
  it.each(['dispose', 'new-login', 'same-token', 'password-change', 'permissions'] as const)(
    '等待删除确认时 %s 不得向新的页面或身份发送旧删除', async change => {
      const confirmation = deferred<unknown>()
      feedback.confirm.mockReturnValueOnce(confirmation.promise)
      const { scope, crud, remove, page } = setup()
      const pending = crud.handleDelete({ id: 7, name: '原角色' })
      if (change === 'dispose') scope.stop()
      if (change === 'new-login') login('another-token')
      if (change === 'same-token') login()
      if (change === 'password-change') useAuthStore().requirePasswordChange()
      if (change === 'permissions') useAuthStore().permissions = ['role:view']
      confirmation.resolve(undefined)
      await pending
      expect(remove).not.toHaveBeenCalled()
      expect(page).not.toHaveBeenCalled()
      expect(feedback.success).not.toHaveBeenCalled()
    },
  )

  it('旧表单校验迟到不得提交后来打开的另一个角色', async () => {
    const validation = deferred<boolean>()
    const { crud, update } = setup(() => validation.promise)
    crud.openEdit({ id: 1, name: '原角色' })
    const pending = crud.handleSubmit()
    crud.dialogVisible.value = false
    crud.openEdit({ id: 2, name: '新角色' })
    validation.resolve(true)
    await pending
    expect(update).not.toHaveBeenCalled()
    expect(crud.dialogVisible.value).toBe(true)
    expect(crud.editingId.value).toBe(2)
    expect(crud.form.name).toBe('新角色')
  })

  it('旧保存迟到不能关闭新弹窗或释放新提交的 loading', async () => {
    const oldSave = deferred<unknown>()
    const newSave = deferred<unknown>()
    const { crud, update, page } = setup()
    update.mockReturnValueOnce(oldSave.promise).mockReturnValueOnce(newSave.promise)
    crud.openEdit({ id: 1, name: '原角色' })
    const oldPending = crud.handleSubmit()
    crud.dialogVisible.value = false
    crud.openEdit({ id: 2, name: '新角色' })
    const newPending = crud.handleSubmit()
    expect(update).toHaveBeenCalledTimes(2)
    oldSave.resolve(undefined)
    await oldPending
    expect(crud.dialogVisible.value).toBe(true)
    expect(crud.submitting.value).toBe(true)
    expect(feedback.success).not.toHaveBeenCalled()
    expect(page).not.toHaveBeenCalled()
    newSave.resolve(undefined)
    await newPending
    expect(crud.dialogVisible.value).toBe(false)
    expect(crud.submitting.value).toBe(false)
    expect(feedback.success).toHaveBeenCalledTimes(1)
    expect(page).toHaveBeenCalledTimes(1)
  })

  it('同令牌重登后关闭旧编辑内容，旧保存失败不会污染新身份', async () => {
    const save = deferred<unknown>()
    const { crud, update } = setup()
    update.mockReturnValueOnce(save.promise)
    crud.openEdit({ id: 1, name: '旧身份敏感内容' })
    const pending = crud.handleSubmit()
    login()
    expect(crud.dialogVisible.value).toBe(false)
    expect(crud.form.name).toBe('')
    save.reject(new Error('old request failed'))
    await expect(pending).resolves.toBeUndefined()
    expect(feedback.success).not.toHaveBeenCalled()
  })

  it('页面卸载后校验迟到不能创建记录', async () => {
    const validation = deferred<boolean>()
    const { scope, crud, create } = setup(() => validation.promise)
    crud.openCreate()
    const pending = crud.handleSubmit()
    scope.stop()
    validation.resolve(true)
    await pending
    expect(create).not.toHaveBeenCalled()
  })

  it.each([false, true])('同令牌重登清空旧列表并丢弃迟到结果，failed=%s', async failed => {
    const oldQuery = deferred<{ list: Array<{ id: number; name: string }>; total: number }>()
    const { crud, page } = setup()
    page.mockResolvedValueOnce({ list: [{ id: 1, name: '旧身份数据' }], total: 1 })
      .mockReturnValueOnce(oldQuery.promise)
    await crud.loadList()
    const pending = crud.loadList()
    login()
    expect(crud.list.value).toEqual([])
    expect(crud.total.value).toBe(0)
    if (failed) oldQuery.reject(new Error('old query failed'))
    else oldQuery.resolve({ list: [{ id: 2, name: '迟到的旧数据' }], total: 1 })
    await pending
    expect(crud.list.value).toEqual([])
    expect(crud.loadError.value).toBeNull()
    expect(crud.loading.value).toBe(false)
  })

  it.each([false, true])('旧删除完成不清除新身份的行操作状态，failed=%s', async failed => {
    const oldRemove = deferred<unknown>()
    const newRemove = deferred<unknown>()
    const { crud, remove, page } = setup()
    remove.mockReturnValueOnce(oldRemove.promise).mockReturnValueOnce(newRemove.promise)
    const oldPending = crud.handleDelete({ id: 7, name: '旧记录' })
    await vi.waitFor(() => expect(remove).toHaveBeenCalledTimes(1))
    login()
    const newPending = crud.handleDelete({ id: 8, name: '新记录' })
    await vi.waitFor(() => expect(remove).toHaveBeenCalledTimes(2))
    if (failed) oldRemove.reject(new Error('old deletion failed'))
    else oldRemove.resolve(undefined)
    await expect(oldPending).resolves.toBeUndefined()
    expect(crud.deletingId.value).toBe(8)
    expect(feedback.success).not.toHaveBeenCalled()
    expect(page).not.toHaveBeenCalled()
    newRemove.resolve(undefined)
    await newPending
    expect(crud.deletingId.value).toBeNull()
    expect(feedback.success).toHaveBeenCalledTimes(1)
    expect(page).toHaveBeenCalledTimes(1)
  })

  it('当前身份正常确认删除并刷新列表', async () => {
    const { crud, remove, page } = setup()
    await crud.handleDelete({ id: 7, name: '可删除角色' })
    expect(remove).toHaveBeenCalledTimes(1)
    expect(page).toHaveBeenCalledTimes(1)
    expect(feedback.success).toHaveBeenCalledTimes(1)
  })
})
