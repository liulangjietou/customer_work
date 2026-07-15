// 与 customer-work-app（8080）HTTP 契约对齐的类型定义。
// 注意：8080 无统一 Result 包装，成功直接返回 JSON body，失败用 HTTP 状态码，
// 所以这里的类型都是"裸"的业务数据结构，不包 code/message 外壳。

/** 工单状态枚举（后端字符串常量，前端按字面量收窄） */
export type TicketStatus =
  | 'AI_SERVING'
  | 'WAITING_AGENT'
  | 'PROCESSING'
  | 'ON_HOLD'
  | 'WAITING_CONFIRM'
  | 'RESOLVED'
  | 'CLOSED'

/** 工单状态中文文案映射 */
export const TICKET_STATUS_TEXT: Record<TicketStatus, string> = {
  AI_SERVING: '智能服务中',
  WAITING_AGENT: '等待人工接入',
  PROCESSING: '人工服务中',
  ON_HOLD: '处理挂起',
  WAITING_CONFIRM: '待您确认',
  RESOLVED: '已解决',
  CLOSED: '已关闭',
}

/** 工单状态对应的 van-tag type（用于列表/详情页状态色） */
export const TICKET_STATUS_TAG_TYPE: Record<TicketStatus, 'primary' | 'success' | 'warning' | 'danger' | 'default'> = {
  AI_SERVING: 'primary',
  WAITING_AGENT: 'warning',
  PROCESSING: 'primary',
  ON_HOLD: 'default',
  WAITING_CONFIRM: 'warning',
  RESOLVED: 'success',
  CLOSED: 'default',
}

/** 消息发送者类型 */
export type SenderType = 'USER' | 'BOT' | 'AGENT' | 'SYSTEM'

export interface RegisterRequest {
  username: string
  password: string
  nickname: string
  phone: string
}

export interface RegisterResponse {
  userId: string
}

export interface LoginRequest {
  username: string
  password: string
}

export interface LoginResponse {
  token: string
  userId: string
  nickname: string
  expiresAtMs: number
}

export interface UserInfo {
  userId: string
  username: string
  nickname: string
  phone: string
}

export interface CreateSessionResponse {
  sessionId: string
  ticketId: string
}

export interface Ticket {
  id: string
  sessionId: string
  userId: string
  title: string
  category: string
  priority: string
  status: TicketStatus
  assignee: string | null
  handoffReason: string | null
  resolveNote: string | null
  reopenCount: number
  createdAtMs: number
  updatedAtMs: number
}

export interface TicketPage {
  total: number
  items: Ticket[]
}

export interface TicketEvent {
  eventType: string
  fromStatus: TicketStatus | null
  toStatus: TicketStatus | null
  actorType: string
  note: string | null
  createdAtMs: number
}

export interface TicketDetail {
  ticket: Ticket
  events: TicketEvent[]
}

export interface ChatMessage {
  id: number
  messageId: string
  sessionId: string
  ticketId: string
  senderType: SenderType
  senderId: string | null
  content: string
  createdAtMs: number
}

export interface ReasonPayload {
  reason: string
}

// ------------------------- WebSocket 帧 -------------------------

/** 用户 -> 服务端 */
export interface WsChatFrame {
  type: 'chat'
  data: { sessionId: string; content: string }
}

export interface WsPingFrame {
  type: 'ping'
}

/** 服务端 -> 用户：聊天消息（客服/系统/机器人非流式） */
export interface WsChatMessage {
  messageId: string
  ticketId: string
  senderType: SenderType
  senderId: string | null
  content: string
  ts: number
}

/** 服务端 -> 用户：机器人流式增量 */
export interface WsChatChunk {
  content: string
}

/** 服务端 -> 用户：流式完成，含全文 */
export interface WsChatDone {
  messageId: string
  content: string
  ts: number
}

/** 服务端 -> 用户：工单状态变更事件 */
export interface WsTicketEvent {
  ticketId: string
  eventType: string
  fromStatus: TicketStatus | null
  toStatus: TicketStatus | null
  actorType: string
  ts: number
}

export interface WsSystemMessage {
  content: string
  ts: number
}

export interface WsErrorMessage {
  code: string
  message: string
}
