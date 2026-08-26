/**
 * 面板首次打开时允许被历史会话替换的最小会话结构。
 * Chat 与 VibeCoding 的会话模型都满足该契约，避免两块面板各自维护一套恢复判断。
 */
export interface RestorableConversation {
  sessionId: string
  messages: readonly unknown[]
  input: string
  attachments: readonly unknown[]
  streaming: boolean
}

/**
 * 仅当当前会话是前端刚初始化的纯空占位时，才自动恢复最新历史会话。
 *
 * 显式 sessionId 优先；已有消息、流式任务、未发送输入或附件都属于用户状态，不能被历史数据覆盖。
 */
export function shouldRestoreMostRecentSession(
  conversation: RestorableConversation | undefined,
  candidateSessionId: string | undefined,
  explicitSessionId: string | undefined,
): candidateSessionId is string {
  if (!conversation || !candidateSessionId || explicitSessionId) {
    return false
  }
  return conversation.sessionId !== candidateSessionId
    && conversation.messages.length === 0
    && conversation.input.length === 0
    && conversation.attachments.length === 0
    && !conversation.streaming
}
