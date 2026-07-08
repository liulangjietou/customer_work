import { streamSse, type SseHandlers } from '@/utils/sse'
import type { ChatRequest } from '@/types/api'

export function streamChat(agentCode: string, request: ChatRequest, handlers: SseHandlers) {
  return streamSse(`/workspace/${agentCode}/chat/stream`, request, handlers)
}
