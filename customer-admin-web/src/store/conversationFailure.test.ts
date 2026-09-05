import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { SseHandlers } from '@/utils/sse'
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
      expect(conversation.messages.at(-1)).toMatchObject({ failed: true, error: '服务暂时不可用' })
      expect(conversation.input).toBe('  下一条草稿\n保持格式  ')
      expect(conversation.streaming).toBe(false)
    })
  })
}
