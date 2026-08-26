import { describe, expect, it } from 'vitest'
import { shouldRestoreMostRecentSession, type RestorableConversation } from './conversationRestore'

function pristineConversation(): RestorableConversation {
  return {
    sessionId: 'temporary-session',
    messages: [],
    input: '',
    attachments: [],
    streaming: false,
  }
}

describe('shouldRestoreMostRecentSession', () => {
  it('首次进入只有临时空会话时恢复最新历史', () => {
    expect(shouldRestoreMostRecentSession(pristineConversation(), 'history-session', undefined)).toBe(true)
  })

  it('显式目标会话或当前已经是候选会话时不重复恢复', () => {
    expect(shouldRestoreMostRecentSession(pristineConversation(), 'history-session', 'project-session')).toBe(false)

    const current = pristineConversation()
    current.sessionId = 'history-session'
    expect(shouldRestoreMostRecentSession(current, 'history-session', undefined)).toBe(false)
  })

  it.each([
    ['已有消息', { messages: [{}] }],
    ['正在流式执行', { streaming: true }],
    ['存在未发送输入', { input: '尚未发送' }],
    ['存在待发送附件', { attachments: [{}] }],
  ])('%s时保留当前会话', (_label, patch) => {
    expect(shouldRestoreMostRecentSession(
      { ...pristineConversation(), ...patch },
      'history-session',
      undefined,
    )).toBe(false)
  })
})
