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
