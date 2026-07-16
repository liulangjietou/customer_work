import type { AxiosError } from 'axios'
import { showToast } from 'vant'
import { request } from '@/api/request'
import type { ChatMessage, CreateSessionResponse, ReasonPayload, TicketDetail, TicketPage, TicketStatus } from '@/types/api'

// baseURL 已配置为 /api（见 request.ts + .env.development），此处 url 不再重复 /api 前缀

const HTTP_CONFLICT = 409

export interface CreateSessionResult extends CreateSessionResponse {
  /** true 表示用户已有进行中会话，本次直接复用该会话而非新建（后端 409 语义） */
  conflict: boolean
  status?: TicketStatus
}

interface ConflictSessionBody {
  code?: string
  message?: string
  sessionId?: string
  ticketId?: string
  status?: TicketStatus
}

/**
 * 创建新会话。用户已有进行中会话时后端返回 409（响应体平铺携带现有 sessionId/ticketId/status），
 * 这里在 API 层统一吸收成非异常的 conflict 结果，调用方无需各自 try/catch 判断状态码。
 */
export async function createSession(): Promise<CreateSessionResult> {
  try {
    const result = await request<CreateSessionResponse>({ url: '/customer/user/sessions', method: 'post', silentError: true })
    return { ...result, conflict: false }
  } catch (error) {
    const axiosError = error as AxiosError<ConflictSessionBody>
    const body = axiosError.response?.status === HTTP_CONFLICT ? axiosError.response.data : undefined
    if (!body?.sessionId || !body?.ticketId) {
      throw error
    }
    showToast('当前有进行中的会话')
    return { sessionId: body.sessionId, ticketId: body.ticketId, status: body.status, conflict: true }
  }
}

export interface FetchTicketsParams {
  status?: TicketStatus
  page: number
  size: number
}

export function fetchTickets(params: FetchTicketsParams): Promise<TicketPage> {
  return request({ url: '/customer/user/tickets', method: 'get', params })
}

export function fetchTicketDetail(id: string): Promise<TicketDetail> {
  return request({ url: `/customer/user/tickets/${id}`, method: 'get' })
}

export interface FetchMessagesParams {
  beforeId?: number
  limit?: number
}

export function fetchMessages(sessionId: string, params: FetchMessagesParams = {}): Promise<ChatMessage[]> {
  return request({ url: `/customer/user/sessions/${sessionId}/messages`, method: 'get', params })
}

export function handoffTicket(id: string, reason: string): Promise<void> {
  return request({ url: `/customer/user/tickets/${id}/handoff`, method: 'post', data: { reason } satisfies ReasonPayload })
}

export function confirmTicket(id: string): Promise<void> {
  return request({ url: `/customer/user/tickets/${id}/confirm`, method: 'post' })
}

export function rejectTicket(id: string, reason: string): Promise<void> {
  return request({ url: `/customer/user/tickets/${id}/reject`, method: 'post', data: { reason } satisfies ReasonPayload })
}

/**
 * 重新打开工单。用户已有另一张进行中会话时后端返回 409（响应体平铺携带该会话的 sessionId/ticketId/status，
 * 与 createSession 409 体同构）——silentError 跳过拦截器默认 toast，交调用方在页面层识别状态码后自行提示，
 * 与 createSession 的"就地吸收成 conflict 结果"不同：reopen 失败没有可用于替代导航的新会话，抛出更简洁。
 */
export function reopenTicket(id: string, reason: string): Promise<void> {
  return request({
    url: `/customer/user/tickets/${id}/reopen`,
    method: 'post',
    data: { reason } satisfies ReasonPayload,
    silentError: true,
  })
}

export interface CloseTicketOptions {
  /** 工单仍在排队/处理中时的强制关闭 */
  force?: boolean
  /** 调用方需要自行处理失败提示（如两段式确认强制关闭）时传 true，跳过拦截器默认 toast */
  silentError?: boolean
}

export function closeTicket(id: string, options: CloseTicketOptions = {}): Promise<void> {
  return request({
    url: `/customer/user/tickets/${id}/close`,
    method: 'post',
    data: options.force ? { force: true } : undefined,
    silentError: options.silentError,
  })
}
