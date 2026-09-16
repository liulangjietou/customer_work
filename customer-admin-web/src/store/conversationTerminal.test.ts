import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { SseHandlers } from '@/utils/sse'
import { SseHttpError } from '@/utils/sseHttpError'
import { getChatSessionMessages } from '@/api/chat'
import { useChatConversationsStore } from './chatConversations'
import { useVibeConversationsStore } from './vibeConversations'

const streams = vi.hoisted(() => ({ chat: vi.fn(), vibe: vi.fn() }))
vi.mock('@/api/chat', () => ({
  streamChat: streams.chat,
  getChatSessionMessages: vi.fn(),
  interruptChat: vi.fn(),
}))
vi.mock('@/api/vibecoding', () => ({
  streamVibeCoding: streams.vibe,
  getSandboxMode: vi.fn().mockResolvedValue({ mode: 'docker' }),
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
afterEach(() => vi.useRealTimers())

for (const mode of ['chat', 'vibe'] as const) {
  describe(`${mode} 的权威终态`, () => {
    it.each(['STOPPED', 'FINAL', 'UNKNOWN'] as const)(
      '重新打开 %s 历史时只依据已记录阶段提供继续入口',
      async (phase) => {
        vi.mocked(getChatSessionMessages).mockResolvedValueOnce([
          {
            id: 'reply-1',
            role: 'assistant',
            text: '已保存的内容',
            attachments: [],
            timestamp: '2026-09-11T10:00:00',
            turnId: 'turn-1',
            phase,
          },
        ])
        const store = mode === 'chat' ? useChatConversationsStore() : useVibeConversationsStore()
        await store.openSession('terminal-agent', 'history-stopped')
        expect(store.activeOf('terminal-agent')?.interrupted).toBe(phase === 'STOPPED')
        expect(store.activeOf('terminal-agent')?.messages[0]).toMatchObject({
          messageId: 'reply-1',
          historySaved: true,
        })
        expect(store.activeOf('terminal-agent')?.messages[0]?.knowledgeSourcesSaved).toBeUndefined()
      },
    )

    function start() {
      let handlers!: SseHandlers
      streams[mode].mockImplementation((_agent, _request, callbacks) => {
        handlers = callbacks
        return () => {}
      })
      const store = mode === 'chat' ? useChatConversationsStore() : useVibeConversationsStore()
      store.ensureAgent('terminal-agent')
      const conversation = store.activeOf('terminal-agent')!
      conversation.input = '请处理这项任务'
      if (mode === 'chat') useChatConversationsStore().send('terminal-agent', (_c, text) => text)
      else useVibeConversationsStore().send('terminal-agent', false, (_c, text) => text)
      handlers.onEvent({ event: 'message', data: '已完成第一步。' })
      return { conversation, message: conversation.messages.at(-1)!, handlers }
    }

    it('只有旧 done 或 EOF 时保持完成状态未知，保留已接收内容与新草稿', () => {
      const { conversation, message, handlers } = start()
      conversation.input = '  下一条草稿\n'
      conversation.interrupting = true
      handlers.onEvent({ event: 'done', data: '[DONE]' })
      handlers.onComplete?.()
      expect(message).toMatchObject({ text: '已完成第一步。', phase: 'UNKNOWN' })
      expect(message.error).toContain('完成状态')
      expect(conversation.streaming).toBe(false)
      expect(conversation.interrupting).toBe(false)
      expect(conversation.interrupted).toBe(false)
      expect(conversation.input).toBe('  下一条草稿\n')
      expect(message.messageId).toBeUndefined()
      expect(message.historySaved).toBeUndefined()
      expect(message.knowledgeSourcesSaved).toBeUndefined()
    })

    it.each([
      { phase: 'FINAL', historySaved: true, knowledgeSourcesSaved: false },
      { phase: 'STOPPED', historySaved: false, knowledgeSourcesSaved: true },
      { phase: 'UNKNOWN', historySaved: true, knowledgeSourcesSaved: null },
    ])('消息与来源留存状态独立于 $phase 传递', (saved) => {
      const { message, handlers } = start()
      handlers.onEvent({
        event: 'terminal',
        data: JSON.stringify({ ...saved, turnId: 'turn-1', messageId: 'reply-1' }),
      })
      handlers.onComplete?.()
      expect(message).toMatchObject({ ...saved, messageId: 'reply-1' })
    })

    it('点击停止后收到正常终态，以后端结果为准，不误显示可继续', () => {
      const { conversation, message, handlers } = start()
      conversation.interrupting = true
      handlers.onEvent({
        event: 'terminal',
        data: JSON.stringify({
          turnId: 'turn-1',
          messageId: 'reply-1',
          phase: 'FINAL',
          finishReason: 'MODEL_STOP',
        }),
      })
      handlers.onComplete?.()
      expect(message).toMatchObject({
        phase: 'FINAL',
        turnId: 'turn-1',
        finishReason: 'MODEL_STOP',
      })
      expect(message.failed).not.toBe(true)
      expect(conversation.interrupted).toBe(false)
      expect(conversation.interrupting).toBe(false)
    })

    it('未在本页面点击停止，仍能依据后端已停止终态提供继续入口', () => {
      const { conversation, message, handlers } = start()
      handlers.onEvent({
        event: 'terminal',
        data: JSON.stringify({ turnId: 'turn-1', phase: 'STOPPED', finishReason: 'INTERRUPTED' }),
      })
      handlers.onComplete?.()
      expect(message.phase).toBe('STOPPED')
      expect(conversation.interrupted).toBe(true)
      expect(conversation.streaming).toBe(false)
    })

    it('非法终态不能把这轮标记成完成', () => {
      const { message, handlers } = start()
      handlers.onEvent({ event: 'terminal', data: '{"phase":"NOT_A_PHASE"}' })
      handlers.onComplete?.()
      expect(message.phase).toBe('UNKNOWN')
      expect(message.error).toContain('完成状态')
    })

    it('终态后的连接错误不推翻已确认结果，也不提前允许下一轮发送', () => {
      const { conversation, message, handlers } = start()
      handlers.onEvent({ event: 'terminal', data: '{"phase":"FINAL","finishReason":"MODEL_STOP"}' })
      expect(conversation.streaming).toBe(true)
      handlers.onError?.(new TypeError('Failed to fetch'))
      expect(message.phase).toBe('FINAL')
      expect(message.error).toBeUndefined()
      expect(conversation.streaming).toBe(false)
    })

    it('业务配额拒绝明确失败，保留后端可理解的错误说明', () => {
      const { message, handlers } = start()
      handlers.onError?.(new SseHttpError('本月额度已用完', 200, SseHttpError.QUOTA_EXCEEDED))
      expect(message).toMatchObject({ phase: 'FAILED', failed: true, error: '本月额度已用完' })
    })

    it('代理服务器 502 未提供业务终态时不能推断任务失败', () => {
      const { message, handlers } = start()
      handlers.onError?.(new SseHttpError('HTTP 502', 502))
      expect(message.phase).toBe('UNKNOWN')
      expect(message.error).toContain('完成状态')
    })
  })
}
