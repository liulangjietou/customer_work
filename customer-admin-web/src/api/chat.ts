import { download, request as httpRequest, requestBlob } from './request'
import { streamSse, type SseHandlers } from '@/utils/sse'
import type {
  ChatAttachmentResult,
  ChatMessageVO,
  ChatRequest,
  ChatSessionSummary,
  PageResult,
  PlanConfirmRequest,
} from '@/types/api'

export function streamChat(agentCode: string, request: ChatRequest, handlers: SseHandlers) {
  return streamSse(`/workspace/${agentCode}/chat/stream`, request, handlers)
}

/** 历史会话分页（按最后更新时间倒序），供侧边栏滚动加载。默认每页 20 条。 */
export function listChatSessions(agentCode: string, page = 1, size = 20) {
  return httpRequest<PageResult<ChatSessionSummary>>({
    url: `/workspace/${agentCode}/chat/sessions`,
    method: 'get',
    params: { page, size },
  })
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
 * sessionId 可选（会话尚未落库前上传也允许），有则一并传给后端关联到具体会话。
 */
export function parseChatAttachment(agentCode: string, file: File, channel: string, sessionId?: string) {
  const formData = new FormData()
  formData.append('file', file)
  if (sessionId) {
    formData.append('sessionId', sessionId)
  }
  return httpRequest<ChatAttachmentResult>({
    url: `/workspace/${agentCode}/chat/attachment`,
    method: 'post',
    data: formData,
    params: { channel },
  })
}

/** 附件详情（含解析出的文本 content），用于文本类附件（非图片）的预览弹窗。 */
export function getChatAttachmentDetail(agentCode: string, attachmentId: string) {
  return httpRequest<ChatAttachmentResult>({
    url: `/workspace/${agentCode}/chat/attachment/${attachmentId}`,
    method: 'get',
  })
}

/** 附件原文件字节（图片内联预览的 objectURL 来源、以及下载按钮的数据来源）。 */
export function fetchChatAttachmentFile(agentCode: string, attachmentId: string) {
  return requestBlob({
    url: `/workspace/${agentCode}/chat/attachment/${attachmentId}/file`,
    method: 'get',
  })
}

/** 附件下载：blob 请求 + Content-Disposition 文件名，触发浏览器保存（不走 img 内联预览路径）。 */
export function downloadChatAttachment(agentCode: string, attachmentId: string, fallbackFilename: string) {
  return download(
    { url: `/workspace/${agentCode}/chat/attachment/${attachmentId}/file`, method: 'get' },
    fallbackFilename,
  )
}

/** Plan Mode 计划确认/拒绝（P1-1 HITL，对话面板）：镜像 VibeCoding 的 confirmVibeCodingPlan，
 * 路径把 vibecoding 段换成 chat 段。 */
export function confirmChatPlan(agentCode: string, req: PlanConfirmRequest) {
  return httpRequest<void>({
    url: `/workspace/${agentCode}/chat/plan/confirm`,
    method: 'post',
    data: req,
  })
}
