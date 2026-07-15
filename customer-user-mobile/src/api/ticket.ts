import { request } from '@/api/request'
import type { ChatMessage, CreateSessionResponse, ReasonPayload, TicketDetail, TicketPage, TicketStatus } from '@/types/api'

// baseURL 已配置为 /api（见 request.ts + .env.development），此处 url 不再重复 /api 前缀
export function createSession(): Promise<CreateSessionResponse> {
  return request({ url: '/customer/user/sessions', method: 'post' })
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

export function reopenTicket(id: string, reason: string): Promise<void> {
  return request({ url: `/customer/user/tickets/${id}/reopen`, method: 'post', data: { reason } satisfies ReasonPayload })
}

export function closeTicket(id: string): Promise<void> {
  return request({ url: `/customer/user/tickets/${id}/close`, method: 'post' })
}
