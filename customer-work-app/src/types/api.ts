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

const TICKET_CATEGORY_TEXT: Readonly<Record<string, string>> = {
  CONSULT: '咨询',
  ORDER: '订单',
  AFTER_SALE: '售后',
  COMPLAINT: '投诉',
  OTHER: '其他',
}

const TICKET_PRIORITY_TEXT: Readonly<Record<string, string>> = {
  LOW: '低',
  NORMAL: '普通',
  HIGH: '高',
  URGENT: '紧急',
}

const TICKET_EVENT_TYPE_TEXT: Readonly<Record<string, string>> = {
  CREATE: '创建工单',
  REQUEST_HANDOFF: '申请转人工',
  CANCEL_HANDOFF: '撤销转人工',
  CLAIM: '客服接单',
  HOLD: '挂起处理',
  RESUME: '恢复处理',
  TRANSFER: '转派工单',
  MARK_RESOLVED: '处理完成',
  CONFIRM: '确认解决',
  REJECT: '反馈未解决',
  CLOSE: '关闭工单',
  FORCE_CLOSE: '结束工单',
  REOPEN: '重新打开',
  PRIORITY_CHANGE: '调整优先级',
  CATEGORY_CHANGE: '调整分类',
  HANDOFF_RESOLVE: '人工服务完成',
  ROUTING_SUGGESTION: '更新路由建议',
  HANDOFF_MIGRATED: '迁移历史记录',
}

const TICKET_ACTOR_TYPE_TEXT: Readonly<Record<string, string>> = {
  USER: '用户',
  AGENT: '客服',
  BOT: '智能助手',
  SYSTEM: '系统',
}

/** 显示层使用中文语义；遇到服务端未来新增值时保留原值，避免把真实数据误写成“未知”。 */
export function ticketCategoryText(category: string): string {
  return TICKET_CATEGORY_TEXT[category] || category
}

export function ticketPriorityText(priority: string): string {
  return TICKET_PRIORITY_TEXT[priority] || priority
}

export function ticketEventTypeText(eventType: string): string {
  return TICKET_EVENT_TYPE_TEXT[eventType] || eventType
}

export function ticketActorTypeText(actorType: string): string {
  return TICKET_ACTOR_TYPE_TEXT[actorType] || actorType
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

export interface RevokeSessionsResponse {
  revoked: true
  sessionEpoch: number
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

/** 四种对话协议共用的终止元数据。 */
export interface ChatTerminalEnvelope {
  messageId: string
  finishReason: string
  usage: {
    inputTokens: number
    outputTokens: number
    cachedTokens: number
    totalTokens: number
    timeSeconds: number
  }
  traceId: string
}

/** 服务端 -> 用户：流式完成，含已落库全文与会话归属。 */
export interface WsChatDone extends ChatTerminalEnvelope {
  sessionId: string
  ticketId: string | null
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

/**
 * 我的额度（主体级速率配额）。
 *
 * 窗口是滚动的（最近 windowSeconds 秒），不在整点归零；额度连续释放，
 * 因此服务端刻意不返回"何时恢复"——任何一个恢复时刻都只对某一笔用量成立。
 */
export interface UserQuota {
  /** 生效等级；未受限时为 null */
  levelCode: string | null
  /** 滚动窗口长度（秒），1800 = 30 分钟 */
  windowSeconds: number
  tokenUsed: number
  /** 0 = 不限 */
  tokenLimit: number
  /** -1 = 不限 */
  tokenRemaining: number
  requestUsed: number
  /** 0 = 不限 */
  requestLimit: number
  /** -1 = 不限 */
  requestRemaining: number
  /** 是否真的受限（levelCode 为空时为 false） */
  limited: boolean
}

/**
 * 长期记忆的同意状态。
 *
 * 生产强制 `customer-work.memory.consent-required=true`，服务端在查不到同意记录时 fail-closed。
 * 也就是说：没有这份授权，L2/L3 长期记忆整条链路都是空转的——表照建、清理任务照跑、日志一行不报错。
 * 此前后端接口齐备而 H5 没有任何调用方，正是这个原因。
 */
export interface MemoryConsent {
  granted: boolean
  consentVersion: string | null
  grantedAtMs: number | null
  withdrawnAtMs: number | null
  updatedAtMs: number
}

/** 本人可见的长期记忆内容。memories 为可召回记忆，facts 为只追加的事实审计。 */
export interface MemoryList {
  memories: string[]
  facts: string[]
  count: number
}
