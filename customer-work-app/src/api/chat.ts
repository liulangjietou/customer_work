import { request } from '@/api/request'
import type { ChatAttachmentResult } from '@/types/api'

// baseURL 已配置为 /api（见 request.ts + .env.development），此处 url 不再重复 /api 前缀

/**
 * 上传聊天附件并解析为文本（图片走视觉大模型 OCR，pdf/office/html 走 Tika/POI，md/txt/csv/json 直读），
 * 落盘+落库；解析失败时返回体 parseStatus=FAILED、content 为空，由调用方决定是否提示并跳过拼接。
 * channel 固定 user_chat，后端写死，前端无需传。
 */
export function uploadChatAttachment(file: File, sessionId: string): Promise<ChatAttachmentResult> {
  const formData = new FormData()
  formData.append('file', file)
  formData.append('sessionId', sessionId)
  return request<ChatAttachmentResult>({ url: '/customer/attachment', method: 'post', data: formData })
}
