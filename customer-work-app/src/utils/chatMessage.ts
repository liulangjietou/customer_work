import type { ChatMessage } from '@/types/api'

/** HTTP 与 WS 合并同一消息：兼容旧响应未提供的字段，明确的空集合仍可清空旧值。 */
export function mergeChatMessage(
  previous: ChatMessage | undefined,
  incoming: ChatMessage,
): ChatMessage {
  return {
    ...previous,
    ...incoming,
    id: incoming.id || previous?.id || 0,
    clientMsgId: incoming.clientMsgId ?? previous?.clientMsgId,
    deliveryStatus: incoming.deliveryStatus ?? previous?.deliveryStatus,
    finishReason: incoming.finishReason ?? previous?.finishReason,
    citations: incoming.citations ?? previous?.citations,
    taskPlan: incoming.taskPlan ?? previous?.taskPlan,
  }
}
