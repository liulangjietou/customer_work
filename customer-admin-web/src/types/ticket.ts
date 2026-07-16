// 用户工单模块类型定义，与 admin-server /api/ticket/** 契约一一对应。
// 该模块（工单表 + WS 推送）与 admin-server 并行开发，字段/枚举以协商契约为准，
// 联调阶段如有出入以后端实际返回为准调整。

// ---------- 枚举 ----------
export type TicketStatus =
  | 'AI_SERVING'
  | 'WAITING_AGENT'
  | 'PROCESSING'
  | 'ON_HOLD'
  | 'WAITING_CONFIRM'
  | 'RESOLVED'
  | 'CLOSED'

export type TicketPriority = 'LOW' | 'NORMAL' | 'HIGH' | 'URGENT'

export type TicketCategory = 'CONSULT' | 'ORDER' | 'AFTER_SALE' | 'COMPLAINT' | 'OTHER'

export type TicketSenderType = 'USER' | 'BOT' | 'AGENT' | 'SYSTEM'

export type TicketActorType = 'USER' | 'BOT' | 'AGENT' | 'SYSTEM'

// ---------- 中文标签 + el-tag 颜色映射 ----------
export const STATUS_LABELS: Record<TicketStatus, string> = {
  AI_SERVING: '机器人服务中',
  WAITING_AGENT: '待人工接入',
  PROCESSING: '人工处理中',
  ON_HOLD: '已挂起',
  WAITING_CONFIRM: '待用户确认',
  RESOLVED: '已解决',
  CLOSED: '已关闭',
}

export const STATUS_TAG_TYPE: Record<TicketStatus, '' | 'success' | 'warning' | 'danger' | 'info'> = {
  AI_SERVING: 'info',
  WAITING_AGENT: 'warning',
  PROCESSING: '',
  ON_HOLD: 'warning',
  WAITING_CONFIRM: 'warning',
  RESOLVED: 'success',
  CLOSED: 'info',
}

export const PRIORITY_LABELS: Record<TicketPriority, string> = {
  LOW: '低',
  NORMAL: '普通',
  HIGH: '高',
  URGENT: '紧急',
}

export const PRIORITY_TAG_TYPE: Record<TicketPriority, '' | 'success' | 'warning' | 'danger' | 'info'> = {
  LOW: 'info',
  NORMAL: '',
  HIGH: 'warning',
  URGENT: 'danger',
}

export const CATEGORY_LABELS: Record<TicketCategory, string> = {
  CONSULT: '咨询',
  ORDER: '订单',
  AFTER_SALE: '售后',
  COMPLAINT: '投诉',
  OTHER: '其他',
}

export const SENDER_TYPE_LABELS: Record<TicketSenderType, string> = {
  USER: '用户',
  BOT: '机器人',
  AGENT: '坐席',
  SYSTEM: '系统',
}

// ---------- VO / DTO ----------
export interface TicketVO {
  id: string
  sessionId: string
  userId: string
  title: string
  category: TicketCategory
  priority: TicketPriority
  status: TicketStatus
  assignee: string | null
  handoffReason: string | null
  resolveNote: string | null
  reopenCount: number
  createdAtMs: number
  updatedAtMs: number
  handoffAtMs: number | null
  claimedAtMs: number | null
  resolvedAtMs: number | null
  closedAtMs: number | null
}

export interface TicketEventVO {
  id: number
  ticketId: string
  eventType: string
  fromStatus: TicketStatus | null
  toStatus: TicketStatus | null
  actorType: TicketActorType
  actorId: string | null
  note: string | null
  createdAtMs: number
}

export interface TicketMessageVO {
  id: number
  messageId: string
  sessionId: string
  ticketId: string
  senderType: TicketSenderType
  senderId: string | null
  content: string
  createdAtMs: number
}

export interface TicketDetailVO {
  ticket: TicketVO
  events: TicketEventVO[]
}

export interface TicketPageQuery {
  status?: TicketStatus | ''
  category?: TicketCategory | ''
  priority?: TicketPriority | ''
  assignee?: string
  pageNum?: number
  pageSize?: number
}

export interface TicketPageResult {
  total: number
  items: TicketVO[]
}

export interface TicketReplyRequest {
  content: string
}

export interface TicketHoldRequest {
  reason?: string
}

export interface TicketTransferRequest {
  toAgent?: string
}

export interface TicketResolveRequest {
  note: string
}

export interface TicketCloseRequest {
  reason: string
}

export interface WsCredentialVO {
  token: string
  wsUrl: string
  expiresAtMs: number
  agentId: string
}

// ---------- WebSocket 帧 ----------
export interface WsChatFrameData {
  messageId: string
  ticketId: string
  senderType: TicketSenderType
  senderId: string | null
  content: string
  ts: number
}

export interface WsTicketEventFrameData {
  ticketId: string
  eventType: string
  fromStatus: TicketStatus | null
  toStatus: TicketStatus | null
  actorType: TicketActorType
  ts: number
}

export interface WsTicketNewFrameData {
  ticketId: string
  userId: string
  title: string
  priority: TicketPriority
  category: TicketCategory
  ts: number
}

export interface WsSystemFrameData {
  content: string
  ts: number
}

export interface WsErrorFrameData {
  code: string
  message: string
}
