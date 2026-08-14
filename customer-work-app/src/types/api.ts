// 与 customer-work-app-server（8080）HTTP 契约对齐的类型定义。
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

/** 已结束状态集合：消息列表"已结束"标记、Chat 页只读态判断共用同一份定义，避免两处各写一套判断散漂移 */
const ENDED_TICKET_STATUSES: ReadonlySet<TicketStatus> = new Set(['CLOSED', 'RESOLVED'])

export function isTicketEnded(status: TicketStatus): boolean {
  return ENDED_TICKET_STATUSES.has(status)
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
  avatarUrl?: string | null
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

/**
 * 订单视图（与 /api/customer/user/orders 契约对齐）。
 * 列表返回除 logisticsTrace 外的字段（列表页 logisticsTrace 为 null）；详情额外含 logisticsTrace。
 */
export interface OrderView {
  orderId: string
  productId: string
  productName: string
  amount: string
  status: string
  receiverAddr: string
  logisticsTrace: string | null
  createdAtMs: number
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

// ------------------------- 消息级反馈（点赞/点踩） -------------------------

export type FeedbackType = 'UP' | 'DOWN'

/** 后端 MessageFeedback record 的平铺 JSON（无 Result 包装） */
export interface MessageFeedback {
  messageId: string
  sessionId: string
  type: FeedbackType
  comment: string | null
  createdAtMs: number
}

/** 附件上传解析结果（与 /api/customer/attachment 契约对齐，裸 JSON，无 Result 包装）；
 * 解析失败时 content 为空、errorMessage 说明原因，由调用方决定是否提示并跳过拼接。 */
export interface ChatAttachmentResult {
  id: string
  fileName: string
  content: string
  parseStatus: 'SUCCESS' | 'FAILED'
  errorMessage: string | null
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
  /** 至少一次投递的稳定去重键；兼容旧服务端时可能缺失 */
  eventId?: number
  eventType: string
  fromStatus: TicketStatus | null
  toStatus: TicketStatus | null
  actorType: string
  /** 系统超时自动关闭等场景下的原因说明（含 idle 字样），用户主动操作触发的事件通常为空 */
  reason?: string | null
  ts: number
}

export interface WsSystemMessage {
  content: string
  /** 所属会话 ID：后端新格式携带，旧格式帧无此字段（兼容） */
  sessionId?: string
  /** 所属工单 ID：后端新格式携带，旧格式帧无此字段（兼容） */
  ticketId?: string
  ts: number
}

export interface WsErrorMessage {
  code: string
  message: string
}
