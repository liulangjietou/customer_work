import type { ChatMessagePhase, ChatMessageVO } from '@/types/api'
import type { MessageAttachmentVM } from '@/utils/attachment'
import type { TraceNode } from '@/utils/traceTimeline'

export interface HistoryMessage {
  role: 'user' | 'assistant'
  text: string
  nodes: TraceNode[]
  turnId?: string | null
  messageId: string
  historySaved: boolean
  phase: ChatMessagePhase
  finishReason?: string | null
  attachments?: MessageAttachmentVM[]
}

/** 只合并同一轮明确标记的过程输出，缺少阶段或轮次的旧消息逐条保留。 */
export function presentChatHistory(history: ChatMessageVO[]): HistoryMessage[] {
  const messages: HistoryMessage[] = []
  for (const message of history) {
    const phase = message.phase ?? (message.role === 'user' ? 'USER_INPUT' : 'UNKNOWN')
    const previous = messages.at(-1)
    const canJoinProcess =
      message.role === 'assistant' &&
      phase !== 'UNKNOWN' &&
      !!message.turnId &&
      previous?.role === 'assistant' &&
      previous.phase === 'PROCESS' &&
      previous.turnId === message.turnId
    const target: HistoryMessage = canJoinProcess
      ? previous
      : {
          role: message.role,
          text: '',
          nodes: [],
          turnId: message.turnId,
          messageId: message.id,
          historySaved: true,
          phase,
        }
    if (!canJoinProcess) messages.push(target)
    target.phase = phase
    target.messageId = message.id
    target.finishReason = message.finishReason
    if (phase === 'PROCESS') {
      target.nodes.push({ kind: 'stage_output', text: message.text })
    } else {
      target.text = message.text
    }
    if (message.attachments.length) target.attachments = message.attachments
  }
  return messages
}
