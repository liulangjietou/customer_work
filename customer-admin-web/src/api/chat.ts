import { request as httpRequest } from './request'
import { streamSse, type SseHandlers } from '@/utils/sse'
import type { ChatAttachmentResult, ChatMessageVO, ChatRequest, ChatSessionSummary } from '@/types/api'

export function streamChat(agentCode: string, request: ChatRequest, handlers: SseHandlers) {
  return streamSse(`/workspace/${agentCode}/chat/stream`, request, handlers)
}

export function listChatSessions(agentCode: string) {
  return httpRequest<ChatSessionSummary[]>({ url: `/workspace/${agentCode}/chat/sessions`, method: 'get' })
}

export function getChatSessionMessages(agentCode: string, sessionId: string) {
  return httpRequest<ChatMessageVO[]>({ url: `/workspace/${agentCode}/chat/sessions/${sessionId}/messages`, method: 'get' })
}

/** 安全中断该会话正在执行的流式对话（协作式中断，不保证立即生效）。 */
export function interruptChat(agentCode: string, sessionId: string) {
  return httpRequest<boolean>({ url: `/workspace/${agentCode}/chat/sessions/${sessionId}/interrupt`, method: 'post' })
}

/**
 * 上传聊天附件并解析为文本（图片走视觉大模型 OCR，pdf/office/html 走 Tika/POI，md/txt/csv/json 直读），
 * 落盘+落库；解析失败时返回体 parseStatus=FAILED、content 为空，由调用方决定是否提示并跳过拼接。
 * channel 区分调用来源：ChatPanel 传 'admin_chat'、VibeCodingPanel 传 'vibecoding'。
 */
export function parseChatAttachment(agentCode: string, file: File, channel: string) {
  const formData = new FormData()
  formData.append('file', file)
  return httpRequest<ChatAttachmentResult>({
    url: `/workspace/${agentCode}/chat/attachment`,
    method: 'post',
    data: formData,
    params: { channel },
  })
}
