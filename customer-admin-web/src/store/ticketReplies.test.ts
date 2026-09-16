import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useAuthStore } from './auth'
import { useTicketReplies } from './ticketReplies'
import type { TicketMessageVO } from '@/types/ticket'

const api = vi.hoisted(() => ({ reply: vi.fn(), receipt: vi.fn() }))
vi.mock('@/api/user-ticket', () => ({
  replyTicket: api.reply,
  getTicketMessageReceipt: api.receipt,
}))
vi.mock('@/api/auth', () => ({ fetchMyPermissions: vi.fn(), logout: vi.fn() }))
vi.mock('@/api/request', () => ({
  getRequestErrorMessage: (_error: unknown, fallback: string) => fallback,
}))

function saved(ticketId = 'TK-1', content = '  核对进度\n'): TicketMessageVO {
  return {
    id: 31,
    messageId: 'saved-31',
    ticketId,
    sessionId: `session-${ticketId}`,
    senderType: 'AGENT',
    senderId: 'agent-1',
    content,
    createdAtMs: 100,
  }
}
function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (error: unknown) => void
  const promise = new Promise<T>((yes, no) => {
    resolve = yes
    reject = no
  })
  return { promise, resolve, reject }
}
function setup() {
  useAuthStore().token = 'token-a'
  const store = useTicketReplies()
  store.bindIdentity('tenant-a', 'agent-1')
  store.setDraft('TK-1', '  核对进度\n')
  return store
}
beforeEach(() => {
  vi.stubGlobal('localStorage', { getItem: () => null, setItem: vi.fn(), removeItem: vi.fn() })
  setActivePinia(createPinia())
  vi.resetAllMocks()
})
afterEach(() => {
  vi.unstubAllGlobals()
})

describe('坐席工单草稿与保存回执', () => {
  it('等待保存时保留原文，确认后也不清空新编辑的草稿或另一张工单', async () => {
    const store = setup()
    const first = deferred<TicketMessageVO>()
    api.reply.mockReturnValue(first.promise)
    const sending = store.send('TK-1')
    expect(store.state('TK-1').content).toBe('  核对进度\n')
    expect(store.state('TK-1').pending[0]?.status).toBe('SENDING')
    store.setDraft('TK-1', '补充说明')
    store.setDraft('TK-2', '另一位客户')
    first.resolve(saved())
    await sending
    expect(store.state('TK-1').content).toBe('补充说明')
    expect(store.state('TK-2').content).toBe('另一位客户')
    expect(store.state('TK-1').pending).toHaveLength(0)
    expect(store.state('TK-1').saved[0]?.id).toBe(31)
  })

  it('回执丢失后查到已保存记录，不再提交回复', async () => {
    const store = setup()
    api.reply.mockRejectedValue(new Error('connection lost'))
    await store.send('TK-1')
    const pending = store.state('TK-1').pending[0]!
    expect(pending.status).toBe('UNKNOWN')
    api.receipt.mockResolvedValue({ clientMsgId: pending.clientMsgId, message: saved() })
    await store.reconcile('TK-1', pending, true)
    expect(api.reply).toHaveBeenCalledTimes(1)
    expect(store.state('TK-1').content).toBe('')
    expect(store.state('TK-1').saved).toHaveLength(1)
  })

  it('确认缺失后重试，沿用同一个标识和未经裁剪的内容', async () => {
    const store = setup()
    api.reply.mockRejectedValueOnce(new Error('timeout')).mockResolvedValueOnce(saved())
    await store.send('TK-1')
    const pending = store.state('TK-1').pending[0]!
    api.receipt.mockResolvedValue({ clientMsgId: pending.clientMsgId, message: null })
    await store.reconcile('TK-1', pending, true)
    expect(api.reply.mock.calls[1]).toEqual(api.reply.mock.calls[0])
    expect(store.state('TK-1').saved).toHaveLength(1)
    expect(store.state('TK-1').content).toBe('')
  })

  it('回执查询失败时保持未知，禁止重发', async () => {
    const store = setup()
    api.reply.mockRejectedValue(new Error('timeout'))
    await store.send('TK-1')
    api.receipt.mockRejectedValue(new Error('database unavailable'))
    const pending = store.state('TK-1').pending[0]!
    await store.reconcile('TK-1', pending, true)
    expect(pending.status).toBe('UNKNOWN')
    expect(api.reply).toHaveBeenCalledTimes(1)
    store.dismissRejected('TK-1', pending.clientMsgId)
    expect(store.state('TK-1').pending).toHaveLength(1)
  })

  it('租户切换后旧响应不会把消息和草稿带进新租户', async () => {
    const store = setup()
    const first = deferred<TicketMessageVO>()
    api.reply.mockReturnValue(first.promise)
    const sending = store.send('TK-1')
    store.bindIdentity('tenant-b', 'agent-1')
    store.setDraft('TK-1', '新租户内容')
    first.resolve(saved())
    await sending
    expect(store.state('TK-1').saved).toHaveLength(0)
    expect(store.state('TK-1').content).toBe('新租户内容')
  })

  it('退出登录立刻移除本地草稿，旧请求结束也不能恢复', async () => {
    const store = setup()
    const first = deferred<TicketMessageVO>()
    api.reply.mockReturnValue(first.promise)
    const sending = store.send('TK-1')
    useAuthStore().token = null
    first.resolve(saved())
    await sending
    expect(store.state('TK-1')).toMatchObject({ content: '', pending: [], saved: [] })
  })

  it('记录归属或内容不匹配时不能确认保存', async () => {
    const store = setup()
    api.reply.mockResolvedValue(saved('TK-foreign'))
    await store.send('TK-1')
    expect(store.state('TK-1').pending[0]?.status).toBe('UNKNOWN')
    expect(store.state('TK-1').content).toBe('  核对进度\n')
    expect(store.state('TK-1').saved).toHaveLength(0)
  })

  it('明确拒绝与未知分开，同内容重复点击不会创建新发送', async () => {
    const store = setup()
    api.reply.mockRejectedValue({ code: 40010 })
    await store.send('TK-1')
    await store.send('TK-1')
    expect(store.state('TK-1').pending[0]?.status).toBe('REJECTED')
    expect(api.reply).toHaveBeenCalledTimes(1)
  })
})
