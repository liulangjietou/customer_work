import type { ChatMessagePhase } from '@/types/api'
import { SseHttpError } from '@/utils/sseHttpError'

const UNKNOWN_COMPLETION = '连接已结束，完成状态尚未确认。请先查看会话历史，再决定是否重新发送。'
const TERMINAL_PHASES: ChatMessagePhase[] = ['FINAL', 'STOPPED', 'WAITING', 'FAILED', 'UNKNOWN']

interface CompletionMessage {
  phase?: ChatMessagePhase
  turnId?: string | null
  finishReason?: string | null
  failed?: boolean
  error?: string
}

interface CompletionConversation {
  streaming: boolean
  interrupting: boolean
  interrupted: boolean
}

/** 两种工作区共用收尾规则；请求停止和网络关闭均不能证明任务已经停止或完成。 */
export function createChatCompletion(message: CompletionMessage, conversation: CompletionConversation) {
  let received = false
  let finished = false

  function unknown() {
    message.phase = 'UNKNOWN'
    message.failed = false
    message.error = UNKNOWN_COMPLETION
    conversation.interrupted = false
  }

  function close() {
    conversation.streaming = false
    conversation.interrupting = false
    finished = true
  }

  return {
    /** 只接受后端独立终态事件；旧 done 仅代表传输收尾。 */
    terminal(raw: string) {
      if (finished || received) return
      try {
        const value: unknown = JSON.parse(raw)
        if (!value || typeof value !== 'object') return
        const payload = value as Record<string, unknown>
        if (!TERMINAL_PHASES.includes(payload.phase as ChatMessagePhase)) return
        received = true
        message.phase = payload.phase as ChatMessagePhase
        message.turnId = typeof payload.turnId === 'string' ? payload.turnId : null
        message.finishReason = typeof payload.finishReason === 'string' ? payload.finishReason : null
        message.failed = message.phase === 'FAILED'
        message.error = typeof payload.error === 'string' ? payload.error : undefined
        if (message.phase === 'UNKNOWN') message.error ??= UNKNOWN_COMPLETION
        if (message.phase === 'FAILED') message.error ??= '本轮执行未完成，请查看执行记录。'
        conversation.interrupted = message.phase === 'STOPPED'
        conversation.interrupting = false
      } catch {
        // 非法事件保持未确认，等连接收尾时统一展示未知状态。
      }
    },
    complete() {
      if (finished) return
      if (!received) unknown()
      close()
    },
    fail(error: unknown) {
      if (finished) return
      // 已收到权威终态后的连接错误不推翻后端结果。
      if (!received) {
        if (error instanceof SseHttpError && (error.code !== undefined || error.status < 500)) {
          message.phase = 'FAILED'
          message.failed = true
          message.error = error.message
          conversation.interrupted = false
        } else {
          unknown()
        }
      }
      close()
    },
  }
}
