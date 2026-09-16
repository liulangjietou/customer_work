import { request } from './request'
import type {
  TicketCloseRequest,
  TicketDetailVO,
  TicketHoldRequest,
  TicketMessageVO,
  TicketMessageReceipt,
  TicketPageQuery,
  TicketPageResult,
  TicketPriority,
  TicketCategory,
  TicketReplyRequest,
  TicketResolveRequest,
  TicketTransferRequest,
  WsCredentialVO,
} from '@/types/ticket'

const BASE_URL = '/ticket'

export function pageTickets(query: TicketPageQuery) {
  return request<TicketPageResult>({
    url: `${BASE_URL}/page`,
    method: 'get',
    params: query,
    suppressErrorMessage: true,
  })
}

export function getTicketDetail(id: string) {
  return request<TicketDetailVO>({
    url: `${BASE_URL}/${id}`,
    method: 'get',
    suppressErrorMessage: true,
  })
}

export function getTicketMessages(id: string, beforeId?: number, limit?: number) {
  return request<TicketMessageVO[]>({
    url: `${BASE_URL}/${id}/messages`,
    method: 'get',
    params: { beforeId, limit },
    suppressErrorMessage: true,
  })
}

export function claimTicket(id: string) {
  return request<void>({
    url: `${BASE_URL}/${id}/claim`,
    method: 'post',
    suppressErrorMessage: true,
  })
}

export function replyTicket(id: string, data: TicketReplyRequest) {
  return request<TicketMessageVO>({
    url: `${BASE_URL}/${id}/reply`,
    method: 'post',
    data,
    suppressErrorMessage: true,
  })
}

/** 回执查询失败必须保留未知状态，不得按空记录自动重发。 */
export function getTicketMessageReceipt(id: string, clientMsgId: string) {
  return request<TicketMessageReceipt>({
    url: `${BASE_URL}/${encodeURIComponent(id)}/receipts/${encodeURIComponent(clientMsgId)}`,
    method: 'get',
    suppressErrorMessage: true,
  })
}

export function holdTicket(id: string, data: TicketHoldRequest) {
  return request<void>({
    url: `${BASE_URL}/${id}/hold`,
    method: 'post',
    data,
    suppressErrorMessage: true,
  })
}

export function resumeTicket(id: string) {
  return request<void>({
    url: `${BASE_URL}/${id}/resume`,
    method: 'post',
    suppressErrorMessage: true,
  })
}

export function transferTicket(id: string, data: TicketTransferRequest) {
  return request<void>({
    url: `${BASE_URL}/${id}/transfer`,
    method: 'post',
    data,
    suppressErrorMessage: true,
  })
}

export function resolveTicket(id: string, data: TicketResolveRequest) {
  return request<void>({
    url: `${BASE_URL}/${id}/resolve`,
    method: 'post',
    data,
    suppressErrorMessage: true,
  })
}

export function closeTicket(id: string, data: TicketCloseRequest) {
  return request<void>({
    url: `${BASE_URL}/${id}/close`,
    method: 'post',
    data,
    suppressErrorMessage: true,
  })
}

export function updateTicketPriority(id: string, priority: TicketPriority) {
  return request<void>({
    url: `${BASE_URL}/${id}/priority`,
    method: 'put',
    data: { priority },
    suppressErrorMessage: true,
  })
}

export function updateTicketCategory(id: string, category: TicketCategory) {
  return request<void>({
    url: `${BASE_URL}/${id}/category`,
    method: 'put',
    data: { category },
    suppressErrorMessage: true,
  })
}

export function getTicketWsCredential() {
  return request<WsCredentialVO>({
    url: `${BASE_URL}/ws-credential`,
    method: 'get',
    suppressErrorMessage: true,
  })
}
