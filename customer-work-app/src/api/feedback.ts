import { request } from '@/api/request'
import type { FeedbackType, MessageFeedback } from '@/types/api'

// baseURL 已配置为 /api（见 request.ts + .env.development），此处 url 不再重复 /api 前缀

export interface SubmitFeedbackPayload {
  sessionId: string
  messageId: string
  type: FeedbackType
  comment?: string
}

/** 提交消息级点赞/点踩；同一 messageId 重复提交按最新一次覆盖（后端 upsert 语义，允许改主意） */
export function submitFeedback(payload: SubmitFeedbackPayload): Promise<MessageFeedback> {
  return request({ url: '/customer/feedback', method: 'post', data: payload })
}

/** 拉取某会话的全部反馈，用于切换会话/加载历史后回显已点状态；辅助信息，失败由调用方静默兜底（跳过拦截器默认 toast） */
export function fetchSessionFeedback(sessionId: string): Promise<MessageFeedback[]> {
  return request({ url: '/customer/feedback', method: 'get', params: { sessionId }, silentError: true })
}
