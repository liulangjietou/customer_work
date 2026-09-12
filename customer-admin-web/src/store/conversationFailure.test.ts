import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { SseHandlers } from '@/utils/sse'
import { useChatConversationsStore } from './chatConversations'
import { useVibeConversationsStore } from './vibeConversations'

const streams = vi.hoisted(() => ({ chat: vi.fn(), vibe: vi.fn(), history: vi.fn() }))
vi.mock('@/api/chat', () => ({
  streamChat: streams.chat,
  getChatSessionMessages: streams.history,
  interruptChat: vi.fn(),
}))
vi.mock('@/api/vibecoding', () => ({
  streamVibeCoding: streams.vibe,
  getSandboxMode: vi.fn().mockResolvedValue('SHARED'),
  listWorkspaceFiles: vi.fn().mockResolvedValue([]),
  interruptVibeCoding: vi.fn(),
  streamDiagnosis: vi.fn(),
  streamRefactor: vi.fn(),
  streamSandboxCommand: vi.fn(),
}))

beforeEach(() => {
  setActivePinia(createPinia())
  vi.useFakeTimers()
  vi.clearAllMocks()
})
afterEach(() => {
  vi.useRealTimers()
})

for (const mode of ['chat', 'vibe'] as const) {
  describe(`${mode} 流式失败`, () => {
    it('发送时独立携带原始输入，附件与运行材料不进入历史气泡', () => {
      streams[mode].mockReturnValue(() => {})
      const store = mode === 'chat' ? useChatConversationsStore() : useVibeConversationsStore()
      store.ensureAgent('test-agent')
      store.activeOf('test-agent')!.input = '请总结附件'
      const build = () => '【附件：报告】\n完整材料\n\n请总结附件'
      if (mode === 'chat') useChatConversationsStore().send('test-agent', build)
      else useVibeConversationsStore().send('test-agent', false, build)
      expect(streams[mode].mock.calls[0][1]).toMatchObject({
        message: '【附件：报告】\n完整材料\n\n请总结附件',
        rawInput: '请总结附件',
      })
    })

    it('历史按已知轮次收纳过程回复，旧记录保持未知阶段', async () => {
      const common = { timestamp: '2026-09-11T09:00:00', attachments: [], turnId: 'input-1' }
      streams.history.mockResolvedValue([
        { ...common, id: 'input-1', role: 'user', text: '查订单', phase: 'USER_INPUT' },
        { ...common, id: 'process-1', role: 'assistant', text: '先查询订单记录', phase: 'PROCESS' },
        { ...common, id: 'final-1', role: 'assistant', text: '订单已发货', phase: 'FINAL' },
        { ...common, id: 'legacy-1', role: 'assistant', text: '旧回复' },
      ])
      const store = mode === 'chat' ? useChatConversationsStore() : useVibeConversationsStore()
      await store.openSession('test-agent', 'history-session')
      const messages = store.activeOf('test-agent')!.messages
      expect(messages).toHaveLength(3)
      expect(messages[1]).toMatchObject({
        text: '订单已发货',
        messageId: 'final-1',
        historySaved: true,
        phase: 'FINAL',
        nodes: [{ kind: 'stage_output', text: '先查询订单记录' }],
      })
      expect(messages[2]).toMatchObject({ text: '旧回复', phase: 'UNKNOWN', messageId: 'legacy-1' })
      expect(messages[1]?.knowledgeSourcesSaved).toBeUndefined()
    })

    it('已停止历史合并保留最后一条消息标识，不把过程记录当成来源所属轮次', async () => {
      const common = { timestamp: '2026-09-11T09:00:00', attachments: [], turnId: 'input-1' }
      streams.history.mockResolvedValue([
        { ...common, id: 'process-1', role: 'assistant', text: '正在查询', phase: 'PROCESS' },
        { ...common, id: 'stopped-1', role: 'assistant', text: '已查询到一部分', phase: 'STOPPED' },
      ])
      const store = mode === 'chat' ? useChatConversationsStore() : useVibeConversationsStore()
      await store.openSession('test-agent', 'history-session')
      expect(store.activeOf('test-agent')!.messages).toMatchObject([
        { messageId: 'stopped-1', phase: 'STOPPED', text: '已查询到一部分', historySaved: true },
      ])
    })

    it('保留已展示和帧内待渲染的回答，错误单独展示且不覆盖下一条草稿', () => {
      let handlers!: SseHandlers
      streams[mode].mockImplementation((_agent, _request, callbacks) => {
        handlers = callbacks
        return () => {}
      })
      const store = mode === 'chat' ? useChatConversationsStore() : useVibeConversationsStore()
      store.ensureAgent('test-agent')
      const conversation = store.activeOf('test-agent')!
      conversation.input = '执行任务'
      if (mode === 'chat')
        useChatConversationsStore().send('test-agent', (_conversation, text) => text)
      else useVibeConversationsStore().send('test-agent', false, (_conversation, text) => text)
      handlers.onEvent({ event: 'message', data: '第一段结果。' })
      vi.advanceTimersByTime(20)
      handlers.onEvent({ event: 'message', data: '第二段结果。' })
      conversation.input = '  下一条草稿\n保持格式  '
      handlers.onError?.(new Error('服务暂时不可用'))
      expect(conversation.messages.at(-1)?.text).toBe('第一段结果。第二段结果。')
      expect(conversation.messages.at(-1)).toMatchObject({ phase: 'UNKNOWN', failed: false })
      expect(conversation.messages.at(-1)?.error).toContain('完成状态尚未确认')
      expect(conversation.input).toBe('  下一条草稿\n保持格式  ')
      expect(conversation.streaming).toBe(false)
    })
  })
}
