import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useChatConversationsStore } from './chatConversations'
import { useVibeConversationsStore } from './vibeConversations'

const streams = vi.hoisted(() => ({ chat: vi.fn(), vibe: vi.fn(), receipt: vi.fn(), history: vi.fn() }))
vi.mock('@/api/chat', () => ({ streamChat: streams.chat, getChatSessionMessages: streams.history, getChatReceipt: streams.receipt, interruptChat: vi.fn() }))
vi.mock('@/api/vibecoding', () => ({
  getVibeReceipt: streams.receipt, streamVibeCoding: streams.vibe, getSandboxMode: vi.fn().mockResolvedValue('SHARED'),
  listWorkspaceFiles: vi.fn().mockResolvedValue([]), interruptVibeCoding: vi.fn(),
  streamDiagnosis: vi.fn(), streamRefactor: vi.fn(), streamSandboxCommand: vi.fn(),
}))

beforeEach(() => {
  setActivePinia(createPinia())
  vi.clearAllMocks()
  streams.receipt.mockReset()
  streams.history.mockReset()
  streams.chat.mockReturnValue(() => {})
  streams.vibe.mockReturnValue(() => {})
})

for (const mode of ['chat', 'vibe'] as const) {
  describe(`${mode} 受理前保护`, () => {
    function send() {
      const store = mode === 'chat' ? useChatConversationsStore() : useVibeConversationsStore()
      store.ensureAgent('acceptance-agent')
      const conv = store.activeOf('acceptance-agent')!
      conv.input = '请处理附件'
      conv.attachments = [{ localId: 'local-file', id: 'stored-file', name: '报告.txt', content: '材料', status: 'success' }]
      if (mode === 'chat') useChatConversationsStore().send('acceptance-agent', (_conv, text) => text)
      else useVibeConversationsStore().send('acceptance-agent', false, (_conv, text) => text)
      return conv
    }

    it('没有持久受理回执时保留原文和已上传附件', () => {
      const conv = send()
      expect(conv.input).toBe('请处理附件')
      expect(conv.attachments.map(file => file.id)).toEqual(['stored-file'])
    })

    it('合法受理只清除本次原文和附件，不清除后来编辑的下一条草稿', () => {
      const conv = send()
      const request = streams[mode].mock.calls[0][1]
      const handlers = streams[mode].mock.calls[0][2]
      conv.input = '下一条原文'
      conv.attachments.push({ localId: 'next-file', id: 'next-stored', name: '下一份.txt', content: '', status: 'success' })
      handlers.onEvent({ event: 'accepted', data: JSON.stringify({ clientMessageId: request.clientMessageId, acceptedAtMs: 1 }) })
      expect(conv.input).toBe('下一条原文')
      expect(conv.attachments.map(file => file.id)).toEqual(['next-stored'])
      expect(conv.pendingMessage).toBeNull()
    })

    it('错误消息标识和缺受理时间都不能清空草稿', () => {
      const conv = send()
      const request = streams[mode].mock.calls[0][1]
      const handlers = streams[mode].mock.calls[0][2]
      for (const receipt of [{ clientMessageId: 'wrong', acceptedAtMs: 1 }, { clientMessageId: request.clientMessageId }]) {
        handlers.onEvent({ event: 'accepted', data: JSON.stringify(receipt) })
      }
      expect(conv.input).toBe('请处理附件')
      expect(conv.pendingMessage).not.toBeNull()
    })

    it('回执丢失后查到受理和已保存答复，不再次执行原消息', async () => {
      const conv = send()
      const request = streams[mode].mock.calls[0][1]
      streams[mode].mock.calls[0][2].onError(new Error('connection lost'))
      streams.receipt.mockResolvedValue({ clientMessageId: request.clientMessageId, acceptedAtMs: 1,
        terminal: { phase: 'FINAL', turnId: 'turn', messageId: 'reply', historySaved: true } })
      streams.history.mockResolvedValue([{ id: 'reply', role: 'assistant', text: '已保存答复' }])
      const store = mode === 'chat' ? useChatConversationsStore() : useVibeConversationsStore()
      await store.reconcileMessage('acceptance-agent')
      expect(streams[mode]).toHaveBeenCalledTimes(1)
      expect(conv.messages.at(-1)).toMatchObject({ text: '已保存答复', phase: 'FINAL', messageId: 'reply' })
      expect(conv.pendingMessage).toBeNull()
      expect(conv.input).toBe('')
    })

    it('明确未受理时重试同一请求，后来编辑的草稿不进入原请求', async () => {
      const conv = send()
      const request = JSON.parse(JSON.stringify(streams[mode].mock.calls[0][1]))
      streams[mode].mock.calls[0][2].onError(new Error('not delivered'))
      conv.input = '另一个任务'
      streams.receipt.mockRejectedValue({ code: 30003 })
      const store = mode === 'chat' ? useChatConversationsStore() : useVibeConversationsStore()
      await store.reconcileMessage('acceptance-agent')
      expect(streams[mode]).toHaveBeenCalledTimes(2)
      expect(streams[mode].mock.calls[1][1]).toEqual(request)
      expect(conv.messages).toHaveLength(2)
      expect(conv.input).toBe('另一个任务')
    })

    it('核对失败或已有执行片段但缺记录时不会重复调用模型', async () => {
      const conv = send()
      const handlers = streams[mode].mock.calls[0][2]
      handlers.onEvent({ event: 'node:thinking_start', data: '开始' })
      handlers.onError(new Error('connection lost'))
      const store = mode === 'chat' ? useChatConversationsStore() : useVibeConversationsStore()
      streams.receipt.mockRejectedValue({ code: 50000 })
      await store.reconcileMessage('acceptance-agent')
      expect(conv.pendingMessage?.error).toContain('暂时无法核对')
      streams.receipt.mockRejectedValue({ code: 30003 })
      await store.reconcileMessage('acceptance-agent')
      expect(conv.pendingMessage?.error).toContain('不会自动重试')
      expect(streams[mode]).toHaveBeenCalledTimes(1)
    })

    it('旧连接迟到成功或失败不能修改新登录的会话', () => {
      send()
      const handlers = streams[mode].mock.calls[0][2]
      const request = streams[mode].mock.calls[0][1]
      const store = mode === 'chat' ? useChatConversationsStore() : useVibeConversationsStore()
      store.resetForLogin()
      store.ensureAgent('acceptance-agent')
      const current = store.activeOf('acceptance-agent')!
      current.input = '新账号草稿'
      handlers.onEvent({ event: 'accepted', data: JSON.stringify({ clientMessageId: request.clientMessageId, acceptedAtMs: 1 }) })
      handlers.onEvent({ event: 'message', data: '旧正文' })
      handlers.onError(new Error('旧故障'))
      handlers.onComplete()
      expect(current.input).toBe('新账号草稿')
      expect(current.messages).toEqual([])
      expect(store.historyVersion).toEqual({})
    })

    it('每次发送包含用于丢失回执核对的稳定消息标识', () => {
      send()
      expect(streams[mode].mock.calls[0][1].clientMessageId).toMatch(/^[0-9a-f-]{36}$/i)
    })
  })
}
