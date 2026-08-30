import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useCrudPage } from './useCrudPage'

const feedback = vi.hoisted(() => ({
  success: vi.fn(),
  confirm: vi.fn(),
}))

vi.mock('element-plus/es', () => ({
  ElMessage: { success: feedback.success },
  ElMessageBox: { confirm: feedback.confirm },
}))

interface Row {
  id: number
  name: string
}

interface Query {
  pageNum: number
  pageSize: number
  keyword: string
}

interface Form {
  name: string
}

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((done, fail) => {
    resolve = done
    reject = fail
  })
  return { promise, resolve, reject }
}

beforeEach(() => {
  feedback.success.mockReset()
  feedback.confirm.mockReset().mockResolvedValue(undefined)
})

describe('useCrudPage', () => {
  it('搜索会回到第一页', async () => {
    const page = vi.fn().mockResolvedValue({ list: [], total: 0 })
    const crud = useCrudPage<Row, Query, Form>({
      page,
      initQuery: () => ({ pageNum: 3, pageSize: 10, keyword: 'agent' }),
      initForm: () => ({ name: '' }),
    })

    crud.handleSearch()
    expect(crud.query.pageNum).toBe(1)
    await vi.waitFor(() => expect(page).toHaveBeenCalledTimes(1))
  })

  it('加载失败保留旧数据并暴露页面级错误，成功重试后清除', async () => {
    const failure = new Error('network unavailable')
    const page = vi.fn()
      .mockResolvedValueOnce({ list: [{ id: 1, name: 'cached' }], total: 1 })
      .mockRejectedValueOnce(failure)
      .mockResolvedValueOnce({ list: [{ id: 2, name: 'fresh' }], total: 1 })
    const crud = useCrudPage<Row, Query, Form>({
      page,
      initQuery: () => ({ pageNum: 1, pageSize: 10, keyword: '' }),
      initForm: () => ({ name: '' }),
    })

    await crud.loadList()
    await crud.loadList()
    expect(crud.list.value).toEqual([{ id: 1, name: 'cached' }])
    expect(crud.loadError.value).toBe(failure)

    await crud.loadList()
    expect(crud.list.value).toEqual([{ id: 2, name: 'fresh' }])
    expect(crud.loadError.value).toBeNull()
  })

  it('快速连续查询时只接收最后一次响应', async () => {
    const slow = deferred<{ list: Row[]; total: number }>()
    const fast = deferred<{ list: Row[]; total: number }>()
    const page = vi.fn()
      .mockImplementationOnce(() => slow.promise)
      .mockImplementationOnce(() => fast.promise)
    const crud = useCrudPage<Row, Query, Form>({
      page,
      initQuery: () => ({ pageNum: 1, pageSize: 10, keyword: '' }),
      initForm: () => ({ name: '' }),
    })

    const first = crud.loadList()
    crud.query.keyword = 'latest'
    const second = crud.loadList()
    fast.resolve({ list: [{ id: 2, name: 'latest' }], total: 1 })
    await second
    expect(crud.list.value).toEqual([{ id: 2, name: 'latest' }])
    expect(crud.loading.value).toBe(false)

    slow.resolve({ list: [{ id: 1, name: 'stale' }], total: 1 })
    await first
    expect(crud.list.value).toEqual([{ id: 2, name: 'latest' }])
  })

  it('旧请求晚失败不会污染最新成功请求的数据和错误状态', async () => {
    const stale = deferred<{ list: Row[]; total: number }>()
    const latest = deferred<{ list: Row[]; total: number }>()
    const page = vi.fn()
      .mockImplementationOnce(() => stale.promise)
      .mockImplementationOnce(() => latest.promise)
    const crud = useCrudPage<Row, Query, Form>({
      page,
      initQuery: () => ({ pageNum: 1, pageSize: 10, keyword: '' }),
      initForm: () => ({ name: '' }),
    })

    const staleLoad = crud.loadList()
    crud.query.keyword = 'latest'
    const latestLoad = crud.loadList()
    latest.resolve({ list: [{ id: 2, name: 'latest' }], total: 1 })
    await latestLoad

    stale.reject(new Error('stale request failed'))
    await staleLoad

    expect(crud.list.value).toEqual([{ id: 2, name: 'latest' }])
    expect(crud.total.value).toBe(1)
    expect(crud.loadError.value).toBeNull()
    expect(crud.loading.value).toBe(false)
  })

  it('保存期间拒绝重复提交并暴露 loading 状态', async () => {
    const createResult = deferred<unknown>()
    const create = vi.fn(() => createResult.promise)
    const page = vi.fn().mockResolvedValue({ list: [], total: 0 })
    const crud = useCrudPage<Row, Query, Form>({
      page,
      create,
      initQuery: () => ({ pageNum: 1, pageSize: 10, keyword: '' }),
      initForm: () => ({ name: '' }),
    })
    crud.openCreate()
    crud.form.name = 'Customer Agent'

    const first = crud.handleSubmit()
    const second = crud.handleSubmit()
    expect(crud.submitting.value).toBe(true)
    expect(create).toHaveBeenCalledTimes(1)

    createResult.resolve(undefined)
    await Promise.all([first, second])
    expect(crud.submitting.value).toBe(false)
    expect(crud.dialogVisible.value).toBe(false)
    expect(page).toHaveBeenCalledTimes(1)
  })

  it('新建失败后恢复提交状态并保留弹窗与表单', async () => {
    const failure = new Error('create failed')
    const create = vi.fn().mockRejectedValue(failure)
    const page = vi.fn().mockResolvedValue({ list: [], total: 0 })
    const crud = useCrudPage<Row, Query, Form>({
      page,
      create,
      initQuery: () => ({ pageNum: 1, pageSize: 10, keyword: '' }),
      initForm: () => ({ name: '' }),
    })
    crud.openCreate()
    crud.form.name = 'Customer Agent'

    await expect(crud.handleSubmit()).rejects.toBe(failure)

    expect(crud.submitting.value).toBe(false)
    expect(crud.dialogVisible.value).toBe(true)
    expect(crud.form.name).toBe('Customer Agent')
    expect(page).not.toHaveBeenCalled()
    expect(feedback.success).not.toHaveBeenCalled()
  })

  it('更新失败后恢复提交状态并保留编辑上下文', async () => {
    const failure = new Error('update failed')
    const update = vi.fn().mockRejectedValue(failure)
    const page = vi.fn().mockResolvedValue({ list: [], total: 0 })
    const crud = useCrudPage<Row, Query, Form>({
      page,
      update,
      initQuery: () => ({ pageNum: 1, pageSize: 10, keyword: '' }),
      initForm: () => ({ name: '' }),
      toForm: (row) => ({ name: row.name }),
    })
    crud.openEdit({ id: 7, name: 'Existing Agent' })
    crud.form.name = 'Edited Agent'

    await expect(crud.handleSubmit()).rejects.toBe(failure)

    expect(update).toHaveBeenCalledWith(7, crud.form)
    expect(crud.submitting.value).toBe(false)
    expect(crud.dialogVisible.value).toBe(true)
    expect(crud.dialogMode.value).toBe('edit')
    expect(crud.editingId.value).toBe(7)
    expect(crud.form.name).toBe('Edited Agent')
    expect(page).not.toHaveBeenCalled()
    expect(feedback.success).not.toHaveBeenCalled()
  })

  it('取消删除后恢复行操作状态且不调用删除接口', async () => {
    const confirmation = deferred<unknown>()
    feedback.confirm.mockReturnValueOnce(confirmation.promise)
    const remove = vi.fn()
    const page = vi.fn().mockResolvedValue({ list: [], total: 0 })
    const crud = useCrudPage<Row, Query, Form>({
      page,
      remove,
      initQuery: () => ({ pageNum: 1, pageSize: 10, keyword: '' }),
      initForm: () => ({ name: '' }),
    })

    const deletion = crud.handleDelete({ id: 5, name: 'Protected Agent' })
    expect(feedback.confirm).toHaveBeenCalledTimes(1)
    expect(feedback.confirm.mock.results[0]?.value).toBe(confirmation.promise)
    confirmation.reject('cancel')
    await expect(deletion).resolves.toBeUndefined()

    expect(crud.deletingId.value).toBeNull()
    expect(remove).not.toHaveBeenCalled()
    expect(page).not.toHaveBeenCalled()
    expect(feedback.success).not.toHaveBeenCalled()
  })

  it('删除接口失败后恢复行操作状态且不刷新列表', async () => {
    const failure = new Error('delete failed')
    const remove = vi.fn().mockRejectedValue(failure)
    const page = vi.fn().mockResolvedValue({ list: [], total: 0 })
    const crud = useCrudPage<Row, Query, Form>({
      page,
      remove,
      initQuery: () => ({ pageNum: 2, pageSize: 10, keyword: '' }),
      initForm: () => ({ name: '' }),
    })

    await expect(crud.handleDelete({ id: 6, name: 'Failing Agent' })).rejects.toBe(failure)

    expect(remove).toHaveBeenCalledTimes(1)
    expect(crud.deletingId.value).toBeNull()
    expect(crud.query.pageNum).toBe(2)
    expect(page).not.toHaveBeenCalled()
    expect(feedback.success).not.toHaveBeenCalled()
  })

  it('删除当前页最后一条后回到合法页码，并拒绝重复删除', async () => {
    const removeResult = deferred<unknown>()
    const remove = vi.fn(() => removeResult.promise)
    const page = vi.fn()
      .mockResolvedValueOnce({ list: [{ id: 9, name: 'last' }], total: 11 })
      .mockResolvedValueOnce({ list: [{ id: 8, name: 'previous' }], total: 10 })
    const crud = useCrudPage<Row, Query, Form>({
      page,
      remove,
      initQuery: () => ({ pageNum: 2, pageSize: 10, keyword: '' }),
      initForm: () => ({ name: '' }),
    })
    await crud.loadList()

    const first = crud.handleDelete(crud.list.value[0])
    const second = crud.handleDelete(crud.list.value[0])
    expect(crud.deletingId.value).toBe(9)
    await vi.waitFor(() => expect(remove).toHaveBeenCalledTimes(1))

    removeResult.resolve(undefined)
    await Promise.all([first, second])
    expect(crud.query.pageNum).toBe(1)
    expect(crud.deletingId.value).toBeNull()
    expect(page).toHaveBeenCalledTimes(2)
  })
})
