import { request as httpRequest } from './request'
import { streamSse, type SseHandlers } from '@/utils/sse'
import type { ChatMessageVO, ChatRequest, ChatSessionSummary } from '@/types/api'

export function streamChat(agentCode: string, request: ChatRequest, handlers: SseHandlers) {
  return streamSse(`/workspace/${agentCode}/chat/stream`, request, handlers)
}

export function listChatSessions(agentCode: string) {
  return httpRequest<ChatSessionSummary[]>({ url: `/workspace/${agentCode}/chat/sessions`, method: 'get' })
}

export function getChatSessionMessages(agentCode: string, sessionId: string) {
  return httpRequest<ChatMessageVO[]>({ url: `/workspace/${agentCode}/chat/sessions/${sessionId}/messages`, method: 'get' })
}

/** 解析上传的 .md/.txt 附件为纯文本；不落库，由调用方插进输入框随消息一起发送。 */
export function parseChatAttachment(agentCode: string, file: File) {
  const formData = new FormData()
  formData.append('file', file)
  return httpRequest<string>({ url: `/workspace/${agentCode}/chat/attachment`, method: 'post', data: formData })
}
