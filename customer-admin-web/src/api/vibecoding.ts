import { request } from './request'
import { streamSse, type SseHandlers } from '@/utils/sse'
import type { ChatRequest } from '@/types/api'

export function streamVibeCoding(agentCode: string, req: ChatRequest, handlers: SseHandlers) {
  return streamSse(`/workspace/${agentCode}/vibecoding/stream`, req, handlers)
}

export function listVibeCodingArtifacts(agentCode: string, sessionId: string) {
  return request<string[]>({
    url: `/workspace/${agentCode}/vibecoding/artifacts`,
    method: 'get',
    params: { sessionId },
  })
}
