<script setup lang="ts">
import type { AxiosError } from 'axios'
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showConfirmDialog, showToast } from 'vant'
import type { UploaderAfterRead, UploaderBeforeRead, UploaderFileListItem } from 'vant'
import CsatSurveyCard from '@/components/CsatSurveyCard.vue'
import { useMessageDelivery } from '@/composables/useMessageDelivery'
import {
  closeTicket,
  createSession,
  fetchMessages,
  fetchTicketDetail,
  confirmTicket,
  handoffTicket,
  rejectTicket,
  reopenTicket,
} from '@/api/ticket'
import { fetchSessionFeedback, submitFeedback } from '@/api/feedback'
import { uploadChatAttachment } from '@/api/chat'
import { fetchMyQuota } from '@/api/quota'
import { useAuthStore } from '@/store/auth'
import { chatSocket } from '@/utils/ws'
import { mergeChatMessage } from '@/utils/chatMessage'
import { TICKET_STATUS_TAG_TYPE, TICKET_STATUS_TEXT, isTicketEnded } from '@/types/api'
import type {
  ChatMessage,
  FeedbackType,
  Ticket,
  UserQuota,
  WsChatChunk,
  WsChatDone,
  WsChatMessage,
  WsErrorMessage,
  WsSystemMessage,
  WsTicketEvent,
} from '@/types/api'

const HTTP_CONFLICT = 409
/** Outbox 是至少一次投递；按工单事件主键去重，避免重试帧重复弹提示。 */
const processedTicketEvents = new Set<string>()

// 附件：与后端 starter AttachmentParseService 白名单/大小限制保持一致（customer-work.attachment.max-file-size-mb=10）
const ATTACHMENT_ACCEPT =
  '.md,.txt,.csv,.tsv,.json,.xml,.yaml,.yml,.toml,.proto,.properties,.ini,.conf,.cfg,.log,.env,.sql,.sh,.bash,.zsh,.bat,.ps1,.java,.kt,.kts,.groovy,.gradle,.scala,.py,.js,.ts,.jsx,.tsx,.vue,.css,.scss,.less,.c,.h,.cpp,.hpp,.cs,.go,.rs,.rb,.php,.swift,.lua,.r,.dart,.html,.pdf,.doc,.docx,.xls,.xlsx,.ppt,.pptx,.png,.jpg,.jpeg,.bmp,.webp'
const MAX_ATTACHMENT_BYTES = 10 * 1024 * 1024

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const sessionId = ref('')
const ticketId = ref<string | null>(null)
const ticket = ref<Ticket | null>(null)
const messages = ref<ChatMessage[]>([])
const inputContent = ref('')
const wsConnected = ref(false)
const wsReconnecting = ref(false)
const initializing = ref(true)
const initializationError = ref('')
let disposed = false
const synchronizationError = ref('')
const delivery = useMessageDelivery({ sessionId, ticketId, messages,
  userId: () => auth.userId, identity: () => auth.token ?? '',
})
const DELIVERY_LABELS = { SENDING: '发送中', ACCEPTED: '已受理', REJECTED: '未受理', UNKNOWN: '受理状态未知' }

/** 仅说明助手答复的生成情况，业务办理状态由工单与订单各自确认。 */
function answerStatus(reason: ChatMessage['finishReason']) {
  switch (reason) {
    case 'MODEL_STOP':
    case 'CACHE_HIT':
    case 'STRUCTURED_OUTPUT':
      return { label: '答复已生成', tone: 'neutral' }
    case 'INTERRUPTED':
    case 'REASONING_STOP_REQUESTED':
    case 'ACTING_STOP_REQUESTED':
    case 'MIDDLEWARE_STOP_REQUESTED':
      return { label: '答复已中断', tone: 'warning' }
    case 'ERROR':
      return { label: '答复生成失败', tone: 'error' }
    case 'QUOTA_EXCEEDED':
      return { label: '本轮额度不足', tone: 'warning' }
    case 'MAX_ITERATIONS':
    case 'TOOL_CALLS':
      return { label: '答复尚未完成', tone: 'warning' }
    case 'TOOL_SUSPENDED':
    case 'PERMISSION_ASKING':
      return { label: '等待后续处理', tone: 'warning' }
    case 'ALL_TOOLS_DENIED':
      return { label: '本轮操作未获授权', tone: 'warning' }
    case null:
    case undefined:
    case '':
      return { label: '未记录结束状态', tone: 'neutral' }
    default:
      return { label: '结束状态待核对', tone: 'warning' }
  }
}

function taskStatusLabel(status: string) {
  switch (status) {
    case 'completed': return '助手标记完成'
    case 'in_progress': return '助手标记进行中'
    case 'pending': return '助手标记待处理'
    default: return '助手状态待核对'
  }
}

// 机器人流式回复：chat_chunk 增量拼接到这里做打字机效果，chat_done 定稿后清空并落入 messages
const streamingContent = ref('')
const streamingActive = computed(() => streamingContent.value.length > 0)
const interruptedReplies = ref<Array<{ id: number; label: string; content: string; clientMsgId: string | null }>>([])
const streamingClientMessageId = ref<string | null>(null)
let interruptedReplySequence = 0
const HISTORY_PAGE_SIZE = 50
const hasOlderMessages = ref(false)
const historyLoading = ref(false)
const historyError = ref('')
let historyRequestVersion = 0
/**
 * 优先按服务端会话和请求归属校验，旧服务端增量则仅能沿用当前发起会话过滤。
 */
const streamingSessionId = ref<string | null>(null)

/** localId 是前端本地生成的临时 key，用于 chips 移除定位（上传过程中后端 id 还不存在）；
 * status 驱动 chip 的 loading/失败态展示，失败/上传中的附件不参与 buildMessageWithAttachments 拼接。 */
interface ChatAttachmentItem {
  localId: string
  id?: string
  name: string
  content: string
  status: 'uploading' | 'success' | 'failed'
  errorMessage?: string
}

const attachments = ref<ChatAttachmentItem[]>([])
const attachmentBlocked = computed(() => attachments.value.some((a) => a.status !== 'success'))

const rejectVisible = ref(false)
const rejectReason = ref('')
const acting = ref(false)

// 消息级反馈（点赞/点踩）：messageId -> 已点类型，切会话时按 sessionId 重拉回显
const feedbackByMessage = ref<Record<string, FeedbackType>>({})
const feedbackSubmitting = ref(false)

/**
 * 我的额度：只在快用完时才提示。
 *
 * 常驻显示一个剩余次数对绝大多数正常用户是纯噪音，还会让人盯着数字聊天；
 * 而完全不显示的话，用户只能在被拦的那一刻才知道有额度这回事——那是最糟的知情方式。
 */
const quota = ref<UserQuota | null>(null)

/** 触发提示的剩余比例：低于两成才出现。 */
const QUOTA_WARN_RATIO = 0.2

const quotaHint = computed(() => {
  const q = quota.value
  if (!q || !q.limited) {
    return ''
  }
  const minutes = Math.max(1, Math.round(q.windowSeconds / 60))
  // 两个维度各算剩余比例，谁更紧张报谁——token 对用户是不可理解的概念，故换算成百分比说
  const requestRatio = q.requestLimit > 0 ? q.requestRemaining / q.requestLimit : 1
  const tokenRatio = q.tokenLimit > 0 ? q.tokenRemaining / q.tokenLimit : 1
  if (requestRatio > QUOTA_WARN_RATIO && tokenRatio > QUOTA_WARN_RATIO) {
    return ''
  }
  if (requestRatio <= tokenRatio) {
    return q.requestRemaining <= 0
      ? `${minutes} 分钟内的提问次数已用完，稍等片刻即可继续`
      : `${minutes} 分钟内还可提问 ${q.requestRemaining} 次`
  }
  return q.tokenRemaining <= 0
    ? `${minutes} 分钟内的用量额度已用完，稍等片刻即可继续`
    : `${minutes} 分钟内的用量额度剩余 ${Math.round(tokenRatio * 100)}%`
})

/** 拉额度。失败静默：额度是锦上添花的信息，查不到就不显示。 */
async function refreshQuota() {
  try {
    quota.value = await fetchMyQuota()
  } catch {
    quota.value = null
  }
}

const scrollBox = ref<HTMLElement | null>(null)

const sessionStorageKey = computed(() => `chat-session-${auth.userId}`)
const ticketStorageKey = computed(() => `chat-ticket-${auth.userId}`)

const canSend = computed(
  () =>
    wsConnected.value &&
    !!sessionId.value &&
    (inputContent.value.trim().length > 0 || attachments.value.some(item => item.status === 'success')) &&
    !attachmentBlocked.value &&
    !ended.value &&
    !delivery.isPending(buildMessageWithAttachments(inputContent.value)),
)
const canHandoff = computed(() => {
  const status = ticket.value?.status
  return status === 'AI_SERVING' || status === 'ON_HOLD'
})

// 会话已结束（CLOSED/RESOLVED）：历史消息只读展示，输入区替换为"重新开始对话"
const ended = computed(() => (ticket.value ? isTicketEnded(ticket.value.status) : false))

const statusHint = computed(() => {
  switch (ticket.value?.status) {
    case 'AI_SERVING':
      return '智能助手正在为您服务'
    case 'WAITING_AGENT':
      return '已进入人工队列，请稍候'
    case 'PROCESSING':
      return '人工客服已接入当前会话'
    case 'ON_HOLD':
      return '问题处理中，消息会继续保留'
    case 'WAITING_CONFIRM':
      return '处理结果等待您的确认'
    case 'RESOLVED':
      return '本次服务已解决'
    case 'CLOSED':
      return '本次会话已关闭'
    default:
      return '正在准备服务会话'
  }
})

// 顶栏会话管理 action-sheet
const sessionMenuVisible = ref(false)
// 有活跃工单且未结束才允许关闭当前会话（否则动作置灰）
const canCloseSession = computed(() => !!ticketId.value && !ended.value)
const sessionActions = computed(() => [
  { name: '新建会话' },
  { name: '关闭当前会话', disabled: !canCloseSession.value, color: '#ee0a24' },
])

async function scrollToBottom() {
  await nextTick()
  if (scrollBox.value) {
    scrollBox.value.scrollTop = scrollBox.value.scrollHeight
  }
}

async function loadHistory() {
  if (disposed) return
  const targetSession = sessionId.value
  const identity = auth.token
  const requestVersion = ++historyRequestVersion
  const latestKnownId = messages.value.reduce((latest, message) => Math.max(latest, message.id), 0)
  let page = await fetchMessages(targetSession, { limit: HISTORY_PAGE_SIZE })
  const collected = [...page]
  let oldestId = Math.min(...page.map(message => message.id))
  // 重连先补到已知消息的边界，再一次性合并；中途失败保留当前已展示的记录。
  while (latestKnownId > 0 && page.length === HISTORY_PAGE_SIZE && oldestId > latestKnownId) {
    if (disposed || targetSession !== sessionId.value || identity !== auth.token || requestVersion !== historyRequestVersion) return
    page = await fetchMessages(targetSession, { beforeId: oldestId, limit: HISTORY_PAGE_SIZE })
    const nextOldest = Math.min(...page.map(message => message.id))
    if (page.length > 0 && (nextOldest <= 0 || nextOldest >= oldestId)) throw new Error('History cursor did not advance')
    collected.push(...page)
    oldestId = nextOldest
  }
  if (disposed || targetSession !== sessionId.value || identity !== auth.token || requestVersion !== historyRequestVersion) return
  if (latestKnownId === 0 || page.length < HISTORY_PAGE_SIZE) hasOlderMessages.value = page.length === HISTORY_PAGE_SIZE
  messages.value = delivery.mergeHistory(collected)
  await loadFeedback()
  await scrollToBottom()
}

/** 主动查看更早记录；保留滚动锚点，失败可原地重试。 */
async function loadOlderMessages() {
  if (historyLoading.value) return
  const ids = messages.value.map(message => message.id).filter(id => id > 0)
  if (ids.length === 0) return
  const targetSession = sessionId.value
  const identity = auth.token
  const beforeId = Math.min(...ids)
  historyLoading.value = true
  historyError.value = ''
  const oldHeight = scrollBox.value?.scrollHeight ?? 0
  const oldTop = scrollBox.value?.scrollTop ?? 0
  try {
    const page = await fetchMessages(targetSession, { beforeId, limit: HISTORY_PAGE_SIZE })
    if (targetSession !== sessionId.value || identity !== auth.token) return
    if (page.some(message => message.id <= 0 || message.id >= beforeId)) throw new Error('Invalid older history cursor')
    messages.value = delivery.mergeHistory(page)
    hasOlderMessages.value = page.length === HISTORY_PAGE_SIZE
    await nextTick()
    if (scrollBox.value) scrollBox.value.scrollTop = oldTop + scrollBox.value.scrollHeight - oldHeight
  } catch {
    if (targetSession === sessionId.value && identity === auth.token) historyError.value = '更早的记录未加载成功，请重试。'
  } finally {
    historyLoading.value = false
  }
}

/** 按当前会话拉取已有反馈做回显；反馈是辅助信息，拉取失败不打断历史加载、不弹错误提示。 */
async function loadFeedback() {
  feedbackByMessage.value = {}
  try {
    const list = await fetchSessionFeedback(sessionId.value)
    const map: Record<string, FeedbackType> = {}
    for (const item of list) {
      map[item.messageId] = item.type
    }
    feedbackByMessage.value = map
  } catch {
    // 静默失败：回显缺失只影响图标高亮，不影响聊天主流程
  }
}

/** 点赞/点踩：重复点击同一类型不重复提交；点另一类型走后端 upsert 覆盖（允许改主意）。 */
async function onFeedback(message: ChatMessage, type: FeedbackType) {
  if (feedbackSubmitting.value || feedbackByMessage.value[message.messageId] === type) {
    return
  }
  feedbackSubmitting.value = true
  try {
    const saved = await submitFeedback({
      sessionId: message.sessionId || sessionId.value,
      messageId: message.messageId,
      type,
    })
    feedbackByMessage.value[saved.messageId] = saved.type
    showToast(type === 'UP' ? '感谢您的认可' : '感谢反馈，我们会持续改进')
  } finally {
    feedbackSubmitting.value = false
  }
}

async function refreshTicket() {
  if (!ticketId.value) {
    return
  }
  const targetTicket = ticketId.value
  const detail = await fetchTicketDetail(targetTicket)
  if (targetTicket !== ticketId.value) return
  ticket.value = detail.ticket
}

function cacheSession(newSessionId: string, newTicketId: string) {
  localStorage.setItem(sessionStorageKey.value, newSessionId)
  localStorage.setItem(ticketStorageKey.value, newTicketId)
}

/** 切换会话前的公共收尾：清掉上一个会话遗留的流式增量状态与未发送的附件 chips，避免串到新会话里。 */
function resetStreamingState() {
  streamingContent.value = ''
  streamingSessionId.value = null
  interruptedReplies.value = []
  streamingClientMessageId.value = null
  hasOlderMessages.value = false
  historyError.value = ''
  historyRequestVersion++
  attachments.value = []
}

async function openNewSession() {
  const identity = auth.token
  const result = await createSession()
  if (disposed || identity !== auth.token) return
  resetStreamingState()
  sessionId.value = result.sessionId
  ticketId.value = result.ticketId
  cacheSession(result.sessionId, result.ticketId)
  messages.value = []
  feedbackByMessage.value = {}
  await refreshTicket()
}

/** 从消息列表点进某条会话：按 ticketId 精确加载，不依赖/不受本地缓存的"当前会话"影响。 */
async function loadTicketFromRoute(id: string) {
  const identity = auth.token
  const detail = await fetchTicketDetail(id)
  if (disposed || identity !== auth.token) return
  resetStreamingState()
  ticket.value = detail.ticket
  ticketId.value = detail.ticket.id
  sessionId.value = detail.ticket.sessionId
  // 已结束的会话只是被查看，不应顶替本地缓存的"当前活跃会话"
  if (!isTicketEnded(detail.ticket.status)) {
    cacheSession(detail.ticket.sessionId, detail.ticket.id)
  }
  await loadHistory()
}

async function initSession() {
  const queryTicketId = route.query.ticketId
  if (typeof queryTicketId === 'string' && queryTicketId) {
    await loadTicketFromRoute(queryTicketId)
    return
  }

  const cachedSessionId = localStorage.getItem(sessionStorageKey.value)
  const cachedTicketId = localStorage.getItem(ticketStorageKey.value)
  if (cachedSessionId && cachedTicketId) {
    try {
      const detail = await fetchTicketDetail(cachedTicketId)
      if (!isTicketEnded(detail.ticket.status)) {
        sessionId.value = cachedSessionId
        ticketId.value = cachedTicketId
        ticket.value = detail.ticket
        await loadHistory()
        return
      }
    } catch {
      // 缓存的工单查询失败（如已被清理），走下面新建会话兜底
    }
  }
  await openNewSession()
}

function connectWs() {
  if (!auth.token) {
    return
  }
  chatSocket.on('open', onWsOpen)
  chatSocket.on('close', onWsClose)
  chatSocket.on('reconnecting', onWsReconnecting)
  chatSocket.on('chat', onWsChat)
  chatSocket.on('chat_accepted', delivery.accept)
  chatSocket.on('chat_chunk', onWsChatChunk)
  chatSocket.on('chat_done', onWsChatDone)
  chatSocket.on('ticket_event', onWsTicketEvent)
  chatSocket.on('system', onWsSystem)
  chatSocket.on('error', onWsError)
  chatSocket.connect(auth.token)
}

function disconnectWs() {
  chatSocket.off('open', onWsOpen)
  chatSocket.off('close', onWsClose)
  chatSocket.off('reconnecting', onWsReconnecting)
  chatSocket.off('chat', onWsChat)
  chatSocket.off('chat_accepted', delivery.accept)
  chatSocket.off('chat_chunk', onWsChatChunk)
  chatSocket.off('chat_done', onWsChatDone)
  chatSocket.off('ticket_event', onWsTicketEvent)
  chatSocket.off('system', onWsSystem)
  chatSocket.off('error', onWsError)
  chatSocket.close()
}

/**
 * 连接建立。
 *
 * <p>断线期间服务端推给本用户的帧（坐席回复、工单状态变更、系统提示）<b>不会补发</b>——
 * 它们在服务端只留了一行日志。数据本身都已落库，所以重连后重新拉一次历史就能补齐；
 * 不拉的话用户会一直以为坐席没回，而实际上回复早就在库里了，刷新页面就能看到。</p>
 *
 * <p>首次订阅也需补拉，覆盖首次 HTTP 快照与 WS 连接建立之间的空隙。</p>
 */
async function onWsOpen() {
  wsConnected.value = true
  wsReconnecting.value = false
  if (!sessionId.value || initializationError.value) return
  await synchronizeConversation()
}

async function synchronizeConversation() {
  const targetSession = sessionId.value
  synchronizationError.value = ''
  await delivery.reconcile()
  const results = await Promise.allSettled([loadHistory(), refreshTicket()])
  if (targetSession === sessionId.value && results.some(result => result.status === 'rejected')) {
    synchronizationError.value = '部分会话记录或工单状态尚未同步，请重试核对。'
  }
}

function onWsClose() {
  wsConnected.value = false
  delivery.disconnected()
  archiveInterruptedReply('断线前收到的回复片段')
}

function archiveInterruptedReply(label: string) {
  if (streamingContent.value) {
    interruptedReplies.value.push({ id: ++interruptedReplySequence, label, content: streamingContent.value,
      clientMsgId: streamingClientMessageId.value })
    streamingContent.value = ''
  }
  streamingSessionId.value = null
  streamingClientMessageId.value = null
}

function onWsReconnecting() {
  wsReconnecting.value = true
}

/**
 * WS 按用户维度广播：一个用户所有工单/会话的事件都走同一条连接，而 Chat 页可通过
 * `/chat?ticketId=X` 查看任意历史会话，因此每个业务帧落地前必须先校验"是否属于当前正在查看的会话"，
 * 不匹配的帧直接忽略——否则历史会话的消息/状态会污染当前页面（如已结束会话被误置为进行中）。
 */
function onWsChat(data: unknown) {
  const payload = data as WsChatMessage
  if (payload.ticketId !== ticketId.value) {
    return
  }
  upsertIncomingMessage({
    id: payload.id ?? 0,
    messageId: payload.messageId,
    sessionId: sessionId.value,
    ticketId: payload.ticketId,
    senderType: payload.senderType,
    senderId: payload.senderId,
    content: payload.content,
    createdAtMs: payload.ts,
  })
  scrollToBottom()
}

/** HTTP 快照和 WS 可能覆盖同一消息；只能按持久化消息号合并，时间戳不能充当数据库游标。 */
function upsertIncomingMessage(message: ChatMessage) {
  const existing = messages.value.find(item => item.messageId === message.messageId)
  if (existing) Object.assign(existing, mergeChatMessage(existing, message))
  else messages.value.push(message)
}

function onWsChatChunk(data: unknown) {
  const payload = data as WsChatChunk
  if (streamingSessionId.value !== sessionId.value
    || (payload.sessionId && payload.sessionId !== sessionId.value)
    || (payload.ticketId && payload.ticketId !== ticketId.value)
    || (payload.clientMsgId && payload.clientMsgId !== streamingClientMessageId.value)) {
    return
  }
  streamingContent.value += payload.content
  scrollToBottom()
}

function onWsChatDone(data: unknown) {
  const payload = data as WsChatDone
  const frameSessionId = payload.sessionId || streamingSessionId.value
  if (
    frameSessionId !== sessionId.value ||
    (payload.ticketId && payload.ticketId !== ticketId.value)
  ) {
    return
  }
  upsertIncomingMessage({
    id: payload.id ?? 0,
    messageId: payload.messageId,
    sessionId: sessionId.value,
    ticketId: ticketId.value ?? '',
    senderType: 'BOT',
    senderId: null,
    content: payload.content,
    createdAtMs: payload.ts,
    finishReason: payload.finishReason,
    citations: payload.citations,
    taskPlan: payload.taskPlan,
  })
  if (!payload.clientMsgId || payload.clientMsgId === streamingClientMessageId.value) {
    streamingContent.value = ''
    streamingSessionId.value = null
    streamingClientMessageId.value = null
  }
  if (payload.clientMsgId) interruptedReplies.value = interruptedReplies.value.filter(reply => reply.clientMsgId !== payload.clientMsgId)
  scrollToBottom()
  // 回复定稿后才刷额度：token 记在模型调用之后，回复过程中查到的还是上一轮的数
  refreshQuota()
}

function onWsTicketEvent(data: unknown) {
  const payload = data as WsTicketEvent
  if (payload.ticketId !== ticketId.value) {
    return
  }
  const eventKey = payload.eventId == null ? null : `${payload.ticketId}:${payload.eventId}`
  if (eventKey && processedTicketEvents.has(eventKey)) {
    return
  }
  if (eventKey) {
    processedTicketEvents.add(eventKey)
  }
  const previousStatus = ticket.value?.status
  if (ticket.value && payload.toStatus) {
    ticket.value = { ...ticket.value, status: payload.toStatus }
  } else {
    refreshTicket()
  }
  // 系统超时等原因自动关闭当前工单：输入区已随 ended 计算属性联动锁定，这里只需要提示用户
  const justClosed = payload.toStatus === 'CLOSED' && previousStatus !== 'CLOSED'
  if (justClosed) {
    showToast('会话已结束')
  }
}

// system 帧（转人工/排队等通知）：后端新格式已携带 sessionId/ticketId，与当前查看会话不匹配的
// 通知直接丢弃，不再跨会话误标；不带标识的旧格式帧维持原行为归入当前会话展示（兼容旧服务端）。
function onWsSystem(data: unknown) {
  const payload = data as WsSystemMessage
  if (payload.sessionId && payload.sessionId !== sessionId.value) {
    return
  }
  if (payload.ticketId && ticketId.value && payload.ticketId !== ticketId.value) {
    return
  }
  messages.value.push({
    id: Date.now(),
    messageId: `system-${payload.ts}`,
    sessionId: payload.sessionId ?? sessionId.value,
    ticketId: payload.ticketId ?? ticketId.value ?? '',
    senderType: 'SYSTEM',
    senderId: null,
    content: payload.content,
    createdAtMs: payload.ts,
  })
  scrollToBottom()
}

function onWsError(data: unknown) {
  const payload = data as WsErrorMessage
  if (payload.sessionId && payload.sessionId !== sessionId.value) return
  const replyInterrupted = payload.acceptance === 'ACCEPTED' && payload.clientMsgId
    && payload.clientMsgId === streamingClientMessageId.value
  if (replyInterrupted) {
    archiveInterruptedReply('出错前收到的回复片段')
    synchronizationError.value = '回复尚未完成，请同步核对已保存的会话记录。'
  }
  if (delivery.handleError(payload)) return
  if (!replyInterrupted) showToast(payload.message || '连接出现异常')
}

/** 前端先拦超限文件，与后端 max-file-size-mb 对齐，减少无谓上传请求；van-uploader 超限时不会触发 afterRead。 */
const beforeReadAttachment: UploaderBeforeRead = (file) => {
  const target = Array.isArray(file) ? file[0] : file
  if (target.size > MAX_ATTACHMENT_BYTES) {
    showToast('附件大小不能超过 10MB')
    return false
  }
  return true
}

/**
 * 未绑定 v-model/file-list 给 van-uploader，其自身不渲染预览格（只有自定义触发按钮可见）——
 * 附件 chips 由本组件自行维护并展示在输入栏上方，与后台 ChatPanel 同款交互。
 */
const afterReadAttachment: UploaderAfterRead = async (file) => {
  const item = Array.isArray(file) ? file[0] : (file as UploaderFileListItem)
  const rawFile = item.file
  if (!rawFile) {
    return
  }
  const attachment: ChatAttachmentItem = {
    localId: `attachment-${Date.now()}-${Math.random().toString(36).slice(2)}`,
    name: rawFile.name,
    content: '',
    status: 'uploading',
  }
  attachments.value.push(attachment)
  try {
    const result = await uploadChatAttachment(rawFile, sessionId.value)
    const target = attachments.value.find((a) => a.localId === attachment.localId)
    if (!target) {
      return // 结果返回前用户已手动移除该附件，或已切会话清空，迟到的结果直接丢弃
    }
    if (result.parseStatus === 'FAILED') {
      target.status = 'failed'
      target.errorMessage = result.errorMessage || '解析失败'
      showToast(`附件解析失败：${rawFile.name}`)
    } else {
      target.id = result.id
      target.content = result.content
      target.status = 'success'
    }
  } catch (error) {
    // 网络异常等已由 request.ts 拦截器统一 toast，这里只落失败态，不重复提示
    const target = attachments.value.find((a) => a.localId === attachment.localId)
    if (target) {
      target.status = 'failed'
      target.errorMessage = error instanceof Error ? error.message : String(error)
    }
  }
}

function removeAttachment(localId: string) {
  attachments.value = attachments.value.filter((a) => a.localId !== localId)
}

/** 把附件内容拼进消息正文，格式与后台 ChatPanel 的 buildMessageWithAttachments 保持一致，方便模型统一识别。
 * 只拼成功解析的附件，上传中/失败的附件不参与（失败的已经在上传回调里提示过用户）。 */
function buildMessageWithAttachments(text: string): string {
  const successful = attachments.value.filter((a) => a.status === 'success')
  if (successful.length === 0) {
    return text
  }
  const attachmentText = successful
    .map((a) => `【附件：${a.name}】\n---\n${a.content}\n---`)
    .join('\n\n')
  return `${attachmentText}\n\n${text}`
}

/** 输入法确认与 Shift+Enter 仅编辑草稿，普通回车沿用发送门禁。 */
function handleInputKeydown(event: KeyboardEvent) {
  if (
    event.key !== 'Enter' ||
    event.isComposing ||
    event.keyCode === 229 ||
    event.shiftKey ||
    event.ctrlKey ||
    event.metaKey ||
    event.altKey ||
    event.repeat
  )
    return
  event.preventDefault()
  sendMessage()
}

function sendMessage() {
  if (!canSend.value) {
    return
  }
  const draft = inputContent.value
  const text = draft
  const messageToSend = buildMessageWithAttachments(text)
  const sentAttachments = [...attachments.value]
  const attachedNames = sentAttachments.filter((a) => a.status === 'success').map((a) => a.name)
  const displayContent = attachedNames.length > 0 ? `${draft}\n📎 ${attachedNames.join('、')}` : draft
  const sent = delivery.submit(messageToSend, displayContent, () => {
    if (inputContent.value === draft) inputContent.value = ''
    attachments.value = attachments.value.filter(item => !sentAttachments.includes(item))
  })
  if (!sent) return
  // 标记本次流式回复归属的会话，供 onWsChatChunk/onWsChatDone 比对，见 streamingSessionId 定义处注释
  streamingSessionId.value = sessionId.value
  streamingClientMessageId.value = sent
  scrollToBottom()
}

async function onHandoff() {
  if (!ticketId.value) {
    return
  }
  acting.value = true
  try {
    await handoffTicket(ticketId.value, '用户主动转人工')
    showToast('已申请转人工')
  } finally {
    acting.value = false
  }
}

async function onConfirmResolved() {
  if (!ticketId.value) {
    return
  }
  acting.value = true
  try {
    await confirmTicket(ticketId.value)
    showToast('已确认解决')
    await refreshTicket()
  } finally {
    acting.value = false
  }
}

function openReject() {
  rejectReason.value = ''
  rejectVisible.value = true
}

async function submitReject() {
  if (!ticketId.value) {
    return
  }
  acting.value = true
  try {
    await rejectTicket(ticketId.value, rejectReason.value)
    showToast('已反馈仍有问题')
    rejectVisible.value = false
    await refreshTicket()
  } finally {
    acting.value = false
  }
}

/**
 * 已结束会话的"重新开始对话"：延续原工单上下文，恢复可聊状态。
 * 用户已有另一张进行中会话时后端返回 409（reopenTicket 已设 silentError，跳过拦截器默认 toast），
 * 这里识别状态码后用后端 message 提示，不做状态变更——避免绕过"用户级唯一活跃会话"不变式。
 */
async function onReopen() {
  if (!ticketId.value) {
    return
  }
  acting.value = true
  try {
    await reopenTicket(ticketId.value, '用户重新开始对话')
    showToast('已重新开始对话')
    cacheSession(sessionId.value, ticketId.value)
    await refreshTicket()
    await loadHistory()
  } catch (error) {
    // reopenTicket 已设 silentError，此处兜底所有失败分支的提示，409 用专属文案，其余沿用与拦截器一致的兜底话术
    const axiosError = error as AxiosError<{ message?: string }>
    if (axiosError.response?.status === HTTP_CONFLICT) {
      showToast(axiosError.response.data?.message || '当前有进行中的会话，请先结束后再重开')
    } else {
      showToast(axiosError.response?.data?.message || axiosError.message || '重新开始对话失败')
    }
  } finally {
    acting.value = false
  }
}

function openSessionMenu() {
  sessionMenuVisible.value = true
}

async function onSelectSessionAction(action: { name: string }) {
  sessionMenuVisible.value = false
  if (action.name === '新建会话') {
    await onNewSession()
  } else if (action.name === '关闭当前会话') {
    await onCloseSession()
  }
}

async function onNewSession() {
  // WS 连接按用户维度建立，切换会话无需重连
  await openNewSession()
  showToast('已新建会话')
}

async function afterSessionClosed() {
  showToast('会话已关闭')
  // 会话关闭后 ended 会变 true，输入区随之隐藏；顺手清掉未发出的附件 chips，避免重开同一会话时"复活"
  attachments.value = []
  await refreshTicket()
}

/**
 * 两段式关闭：先不带 force 调用，工单仍在排队/处理中会失败（409/500），
 * 失败后弹二次确认，用户确认则带 force:true 强制关闭；取消则保留会话不变。
 */
async function onCloseSession() {
  if (!ticketId.value) {
    return
  }
  try {
    await showConfirmDialog({ title: '关闭当前会话', message: '确认关闭当前会话？' })
  } catch {
    return // 用户取消
  }
  try {
    await closeTicket(ticketId.value, { silentError: true })
    await afterSessionClosed()
  } catch {
    try {
      await showConfirmDialog({ title: '强制结束会话', message: '会话仍在处理中，是否强制结束？' })
    } catch {
      return // 用户取消强制关闭，会话保持不变
    }
    await closeTicket(ticketId.value, { force: true })
    await afterSessionClosed()
  }
}

async function initializeChat() {
  initializing.value = true
  initializationError.value = ''
  try {
    await initSession()
  } catch {
    initializationError.value = '会话暂时加载失败，请稍后重试'
  } finally {
    initializing.value = false
  }
}

onMounted(async () => {
  const identity = auth.token
  await initializeChat()
  if (disposed || identity !== auth.token) return
  // 带 orderId 进入（订单详情跳转）：预填咨询文案，仅预填不自动发送
  const orderId = route.query.orderId
  if (typeof orderId === 'string' && orderId) {
    inputContent.value = `我想咨询订单 ${orderId} 的情况`
  }
  connectWs()
  // 进页面先看一眼：已经快用完的话，第一条消息发出去之前就该知道
  refreshQuota()
})

onUnmounted(() => {
  disposed = true
  historyRequestVersion++
  disconnectWs()
})
</script>

<template>
  <div class="chat-page">
    <van-nav-bar title="智能客服" left-arrow safe-area-inset-top @click-left="router.back()">
      <template #right>
        <button class="nav-action" type="button" aria-label="会话管理" @click="openSessionMenu">
          <van-icon name="ellipsis" size="22" />
        </button>
      </template>
    </van-nav-bar>

    <div class="status-card">
      <div class="status-copy">
        <span class="status-pulse" :class="{ offline: !wsConnected }" aria-hidden="true"></span>
        <div>
          <div class="status-title">
            {{ ticket ? TICKET_STATUS_TEXT[ticket.status] : '正在连接' }}
            <van-tag
              v-if="ticket"
              :type="TICKET_STATUS_TAG_TYPE[ticket.status]"
              plain
              size="medium"
            >
              {{ wsConnected ? '在线' : '连接中' }}
            </van-tag>
          </div>
          <p>{{ statusHint }}</p>
        </div>
      </div>
      <van-button
        class="handoff-button"
        size="small"
        round
        plain
        type="primary"
        :disabled="!canHandoff"
        :loading="acting"
        @click="onHandoff"
      >
        转人工
      </van-button>
    </div>

    <div v-if="wsReconnecting" class="reconnect-tip" role="status">
      <van-loading size="13" />
      连接已断开，正在重连…
    </div>
    <div v-if="synchronizationError" class="synchronization-error" role="alert">
      <span>{{ synchronizationError }}</span>
      <button type="button" @click="synchronizeConversation">重新同步</button>
    </div>

    <div ref="scrollBox" class="message-area">
      <div v-if="initializing" class="state-panel" role="status">
        <van-loading color="var(--cw-primary, #1677ff)" vertical>正在加载会话…</van-loading>
      </div>
      <div v-else-if="initializationError" class="state-panel state-error">
        <div class="state-symbol" aria-hidden="true">!</div>
        <strong>会话没有加载成功</strong>
        <p>{{ initializationError }}</p>
        <van-button round type="primary" size="small" @click="initializeChat">重新加载</van-button>
      </div>
      <template v-else>
        <div v-if="hasOlderMessages || historyError" class="history-control">
          <button type="button" :disabled="historyLoading" @click="loadOlderMessages">
            {{ historyLoading ? '正在加载…' : '加载更早消息' }}
          </button>
          <span v-if="historyError" role="alert">{{ historyError }}</span>
        </div>
        <div v-if="messages.length === 0" class="welcome-card">
          <div class="welcome-mark">AI</div>
          <div>
            <strong>您好，我是智能客服</strong>
            <p>请描述您遇到的问题，我会结合服务记录为您提供帮助。</p>
          </div>
        </div>
        <div
          v-for="message in messages"
          :key="message.messageId"
          class="message-row"
          :class="`row-${message.senderType}`"
        >
          <div v-if="message.senderType === 'SYSTEM'" class="system-line">
            {{ message.content }}
          </div>
          <template v-else>
            <div class="sender-avatar" aria-hidden="true">
              {{
                message.senderType === 'BOT' ? 'AI' : message.senderType === 'AGENT' ? '客' : '我'
              }}
            </div>
            <div class="bubble-wrap">
              <div class="badge">
                {{
                  message.senderType === 'AGENT'
                    ? '人工客服'
                    : message.senderType === 'BOT'
                      ? '智能助手'
                      : '我'
                }}
              </div>
              <div class="bubble">{{ message.content }}</div>
              <div
                v-if="message.senderType === 'BOT'"
                class="answer-status"
                :class="`answer-status-${answerStatus(message.finishReason).tone}`"
                role="status"
              >
                {{ answerStatus(message.finishReason).label }}
              </div>
              <div v-if="message.deliveryStatus" class="delivery-state" :class="`delivery-${message.deliveryStatus}`" role="status">
                <span>{{ DELIVERY_LABELS[message.deliveryStatus] }}</span>
                <span v-if="message.deliveryError" class="delivery-error">{{ message.deliveryError }}</span>
                <button v-if="message.deliveryStatus === 'UNKNOWN' || message.deliveryStatus === 'REJECTED'"
                  type="button" :disabled="!wsConnected || ended" @click="delivery.retry(message)">
                  {{ message.deliveryStatus === 'UNKNOWN' ? '核对并重试' : '重试发送' }}
                </button>
              </div>
              <div
                v-if="message.senderType === 'BOT' && message.taskPlan?.length"
                class="task-plan"
              >
                <div class="task-plan-title">助手计划</div>
                <p class="task-plan-note">实际办理结果以订单或工单为准</p>
                <div
                  v-for="(task, index) in message.taskPlan"
                  :key="`${message.messageId}-task-${index}`"
                  class="task-item"
                >
                  <span class="task-text">{{ task.content }}</span>
                  <span class="task-state">{{ taskStatusLabel(task.status) }}</span>
                </div>
              </div>
              <details
                v-if="message.senderType === 'BOT' && message.citations?.length"
                class="citations"
              >
                <summary>参考线索 · {{ message.citations.length }} 条</summary>
                <p class="citations-note">答复附带的线索，供核对相关资料。</p>
                <dl
                  v-for="(citation, index) in message.citations"
                  :key="`${message.messageId}-citation-${index}`"
                  class="citation-detail"
                >
                  <dt>知识库</dt>
                  <dd>{{ citation.knowledgeBase }}</dd>
                  <dt>文档标识</dt>
                  <dd>{{ citation.documentId }}</dd>
                  <dt>片段标识</dt>
                  <dd>{{ citation.chunkId }}</dd>
                </dl>
              </details>
              <div
                v-if="message.senderType === 'BOT'"
                class="feedback-actions"
                aria-label="评价这条回复"
              >
                <button
                  type="button"
                  aria-label="回复有帮助"
                  :aria-pressed="feedbackByMessage[message.messageId] === 'UP'"
                  :class="{ active: feedbackByMessage[message.messageId] === 'UP' }"
                  @click="onFeedback(message, 'UP')"
                >
                  <van-icon
                    :name="
                      feedbackByMessage[message.messageId] === 'UP' ? 'good-job' : 'good-job-o'
                    "
                  />
                </button>
                <button
                  type="button"
                  aria-label="回复没有帮助"
                  :aria-pressed="feedbackByMessage[message.messageId] === 'DOWN'"
                  class="thumb-down"
                  :class="{ active: feedbackByMessage[message.messageId] === 'DOWN' }"
                  @click="onFeedback(message, 'DOWN')"
                >
                  <van-icon
                    :name="
                      feedbackByMessage[message.messageId] === 'DOWN' ? 'good-job' : 'good-job-o'
                    "
                  />
                </button>
              </div>
            </div>
          </template>
        </div>
        <details v-for="reply in interruptedReplies" :key="reply.id" class="interrupted-reply">
          <summary>{{ reply.label }}</summary>
          <p>内容可能不完整，完整记录以同步后的消息为准。</p>
          <div class="interrupted-content">{{ reply.content }}</div>
        </details>
        <div v-if="streamingActive" class="message-row row-BOT">
          <div class="sender-avatar" aria-hidden="true">AI</div>
          <div class="bubble-wrap">
            <div class="badge">智能助手</div>
            <div class="bubble">
              {{ streamingContent }}<span class="cursor" aria-hidden="true">|</span>
            </div>
          </div>
        </div>
      </template>
    </div>

    <div v-if="ticket?.status === 'WAITING_CONFIRM'" class="confirm-card">
      <div class="confirm-copy">
        <span class="confirm-icon" aria-hidden="true">✓</span>
        <div>
          <strong>问题已处理完成</strong>
          <p>请确认本次服务是否解决了您的问题</p>
        </div>
      </div>
      <div class="confirm-actions">
        <van-button size="small" type="primary" round :loading="acting" @click="onConfirmResolved"
          >确认解决</van-button
        >
        <van-button size="small" plain round :loading="acting" @click="openReject"
          >仍有问题</van-button
        >
      </div>
    </div>

    <div v-if="ended" class="ended-bar">
      <div>
        <strong>本次会话已结束</strong>
        <p>历史消息会一直保留，您也可以继续咨询。</p>
      </div>
      <van-button block round type="primary" :loading="acting" @click="onReopen"
        >重新开始对话</van-button
      >
    </div>
    <template v-else>
      <div class="composer-shell">
        <div v-if="attachments.length > 0" class="attachment-chips">
          <van-tag
            v-for="a in attachments"
            :key="a.localId"
            :closeable="a.status !== 'uploading'"
            :type="a.status === 'failed' ? 'danger' : 'primary'"
            plain
            size="medium"
            @close="removeAttachment(a.localId)"
          >
            <van-loading v-if="a.status === 'uploading'" size="12" class="chip-loading" />
            📎 {{ a.name }}
          </van-tag>
        </div>
        <p v-if="attachmentBlocked" class="attachment-warning" role="status">
          请等待附件上传完成，或移除失败附件
        </p>
        <div v-if="quotaHint" class="quota-hint">
          <van-icon name="info-o" class="quota-hint-icon" />
          {{ quotaHint }}
        </div>
        <div class="input-bar">
          <van-uploader
            :accept="ATTACHMENT_ACCEPT"
            :before-read="beforeReadAttachment"
            :after-read="afterReadAttachment"
          >
            <button class="attach-button" type="button" aria-label="添加附件">
              <van-icon name="link-o" size="22" />
            </button>
          </van-uploader>
          <label for="chat-message-input" class="composer-label">消息内容</label>
          <van-field
            v-model="inputContent"
            class="message-input"
            placeholder="输入消息…"
            type="textarea"
            :autosize="{ minHeight: 28, maxHeight: 120 }"
            id="chat-message-input"
            @keydown="handleInputKeydown"
          />
          <van-button
            class="send-button"
            round
            type="primary"
            :disabled="!canSend"
            @click="sendMessage"
          >
            {{ wsConnected ? '发送' : '重连中' }}
          </van-button>
        </div>
      </div>
    </template>

    <van-dialog
      v-model:show="rejectVisible"
      title="仍有问题"
      show-cancel-button
      @confirm="submitReject"
    >
      <van-field
        v-model="rejectReason"
        type="textarea"
        rows="3"
        placeholder="请描述遗留问题"
        class="dialog-field"
      />
    </van-dialog>

    <van-action-sheet
      v-model:show="sessionMenuVisible"
      :actions="sessionActions"
      cancel-text="取消"
      close-on-click-action
      @select="onSelectSessionAction"
    />

    <!-- 会话结束后弹满意度评分：组件内部会先查"有没有待评价的邀请"，
         没被邀请或已评过都不会弹，不会重复打扰 -->
    <CsatSurveyCard :session-id="sessionId" :session-ended="ended" />
  </div>
</template>

<style scoped>
.history-control { display: grid; justify-items: center; gap: 6px; margin-bottom: 16px; color: #925e13; font-size: 13px; }
.history-control button { min-height: 44px; padding: 0 16px; border: 0; border-radius: 12px; background: #edf3ff; color: #235fc7; cursor: pointer; }
.interrupted-reply { margin: 12px 0; padding: 0 14px 10px; border: 1px solid #e4d4aa; border-radius: 12px; background: #fffcf4; font-size: 13px; color: #70551e; }
.interrupted-reply summary { min-height: 44px; align-content: center; cursor: pointer; }
.interrupted-reply p { margin: 0 0 10px; line-height: 1.6; }
.interrupted-content { white-space: pre-wrap; overflow-wrap: anywhere; color: #374151; }
.history-control button:focus-visible, .interrupted-reply summary:focus-visible { outline: 2px solid #235fc7; outline-offset: 3px; }
.delivery-state {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: flex-end;
  gap: 4px 10px;
  margin-top: 6px;
  color: var(--cw-text-secondary, #5d6d83);
  font-size: 12px;
  line-height: 1.6;
}
.delivery-ACCEPTED { color: #267765; }
.delivery-UNKNOWN,
.delivery-REJECTED { color: #925e13; }
.delivery-error { flex-basis: 100%; overflow-wrap: anywhere; }
.delivery-state button,
.synchronization-error button {
  min-height: 44px;
  padding: 8px 12px;
  border: 1px solid var(--cw-line);
  border-radius: 8px;
  background: var(--cw-card-bg, #fff);
  color: var(--cw-primary, #3658cb);
  font: inherit;
  cursor: pointer;
}
.delivery-state button:disabled { opacity: .5; cursor: default; }
.delivery-state button:focus-visible,
.synchronization-error button:focus-visible { outline: 2px solid var(--cw-primary, #3658cb); outline-offset: 2px; }
.synchronization-error {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 16px;
  color: #925e13;
  background: #fff8e9;
  font-size: 13px;
}
.synchronization-error span { flex: 1; }
.synchronization-error button { flex-shrink: 0; }

.composer-label {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  overflow: hidden;
  clip-path: inset(50%);
  white-space: nowrap;
}
.attachment-warning {
  margin: 0 0 6px;
  color: var(--cw-danger);
  font-size: 12px;
}

.chat-page {
  display: flex;
  flex-direction: column;
  height: 100vh;
  height: 100dvh;
  max-height: 100vh;
  max-height: 100dvh;
  overflow: hidden;
  box-sizing: border-box;
  background: var(--cw-page-bg);
}

.nav-action,
.feedback-actions button,
.attach-button {
  display: inline-grid;
  place-items: center;
  min-width: 44px;
  min-height: 44px;
  padding: 0;
  border: 0;
  color: inherit;
  background: transparent;
  cursor: pointer;
}

.status-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin: 0;
  padding: 12px 16px;
  border-bottom: 1px solid var(--cw-line);
  background: var(--cw-card-bg);
}

.status-copy {
  display: flex;
  align-items: center;
  min-width: 0;
  gap: 10px;
}

.status-pulse {
  position: relative;
  flex: 0 0 10px;
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: var(--cw-success, #25c48a);
  box-shadow: 0 0 0 5px rgba(37, 196, 138, 0.13);
}

.status-pulse::after {
  position: absolute;
  inset: -5px;
  border: 1px solid rgba(37, 196, 138, 0.45);
  border-radius: inherit;
  content: '';
  animation: status-pulse 1.8s ease-out infinite;
}

.status-pulse.offline {
  background: #aeb7c4;
  box-shadow: 0 0 0 5px rgba(174, 183, 196, 0.13);
}

.status-pulse.offline::after {
  display: none;
}

.status-title {
  display: flex;
  align-items: center;
  gap: 7px;
  color: var(--cw-text-primary, #13233a);
  font-size: 14px;
  font-weight: 700;
}

.status-copy p,
.confirm-copy p,
.ended-bar p,
.welcome-card p,
.state-panel p {
  margin: 3px 0 0;
  color: var(--cw-text-secondary, #718096);
  font-size: 12px;
  line-height: 1.45;
}

.handoff-button {
  flex: 0 0 auto;
  min-height: 44px;
}

.reconnect-tip {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  margin: 5px 12px 0;
  border-radius: 10px;
  text-align: center;
  font-size: 12px;
  color: #a46100;
  background: #fff5df;
  padding: 7px 10px;
}

.message-area {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  overscroll-behavior: contain;
  padding: 14px 12px 20px;
  scrollbar-width: none;
}

.message-area::-webkit-scrollbar {
  display: none;
}

.state-panel {
  display: flex;
  min-height: 48vh;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 24px;
  text-align: center;
}

.state-panel strong {
  margin-top: 12px;
  color: var(--cw-text-primary, #13233a);
  font-size: 16px;
}

.state-error .van-button {
  margin-top: 16px;
}

.state-symbol {
  display: grid;
  width: 50px;
  height: 50px;
  place-items: center;
  border-radius: 16px;
  color: #fff;
  background: #ef6d6d;
  font-size: 24px;
  font-weight: 800;
  box-shadow: 0 10px 22px rgba(239, 109, 109, 0.25);
}

.welcome-card {
  display: flex;
  gap: 12px;
  margin: 6px 0 18px;
  padding: 16px;
  border: 1px solid rgba(24, 119, 242, 0.1);
  border-radius: 18px;
  background: linear-gradient(135deg, #fff 20%, #f1f7ff 100%);
  box-shadow: 0 10px 30px rgba(21, 52, 92, 0.06);
}

.welcome-card strong {
  color: var(--cw-text-primary, #13233a);
  font-size: 15px;
}

.welcome-mark,
.sender-avatar {
  display: grid;
  flex: 0 0 auto;
  place-items: center;
  color: #fff;
  background: linear-gradient(145deg, #258cff, #0b61da);
  font-size: 11px;
  font-weight: 800;
  letter-spacing: -0.3px;
  box-shadow: 0 7px 16px rgba(24, 119, 242, 0.22);
}

.welcome-mark {
  width: 42px;
  height: 42px;
  border-radius: 14px;
}

.message-row {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin-bottom: 16px;
}

.row-USER {
  flex-direction: row-reverse;
  justify-content: flex-start;
}

.row-BOT,
.row-AGENT {
  justify-content: flex-start;
}

.row-SYSTEM {
  justify-content: center;
}

.system-line {
  max-width: 88%;
  font-size: 12px;
  color: var(--cw-text-secondary, #718096);
  background: rgba(19, 35, 58, 0.06);
  padding: 6px 12px;
  border-radius: 999px;
  text-align: center;
}

.sender-avatar {
  width: 34px;
  height: 34px;
  margin-top: 18px;
  border-radius: 11px;
}

.row-USER .sender-avatar {
  background: linear-gradient(145deg, #193b66, #102642);
  box-shadow: 0 7px 16px rgba(16, 38, 66, 0.2);
}

.row-AGENT .sender-avatar {
  color: #0d6e54;
  background: #dff8ee;
  box-shadow: 0 7px 16px rgba(37, 196, 138, 0.14);
}

.bubble-wrap {
  min-width: 0;
  max-width: calc(82% - 42px);
}

.badge {
  font-size: 11px;
  color: var(--cw-text-secondary, #718096);
  margin: 0 2px 4px;
}

.row-USER .badge {
  text-align: right;
}

.bubble {
  padding: 10px 13px;
  border: 1px solid rgba(19, 35, 58, 0.05);
  border-radius: 6px 17px 17px;
  color: var(--cw-text-primary, #13233a);
  background: var(--cw-card-bg, #fff);
  box-shadow: 0 7px 20px rgba(21, 52, 92, 0.07);
  word-break: break-word;
  white-space: pre-wrap;
  line-height: 1.55;
}

.row-USER .bubble {
  border-color: rgba(24, 119, 242, 0.15);
  border-radius: 17px 6px 17px 17px;
  color: #fff;
  background: linear-gradient(135deg, #268cff, #1268df);
  box-shadow: 0 9px 22px rgba(24, 119, 242, 0.2);
}

.answer-status {
  margin: 6px 2px 0;
  color: var(--cw-text-secondary, #718096);
  font-size: 12px;
  line-height: 1.6;
}

.answer-status-warning {
  color: #946200;
}

.answer-status-error {
  color: #b53638;
}

.task-plan {
  margin-top: 8px;
  padding: 8px 10px;
  border-radius: 8px;
  background: rgba(100, 110, 130, 0.06);
  font-size: 12px;
  line-height: 1.8;
}

.task-plan-title {
  font-weight: 600;
  color: var(--cw-text-primary, #13233a);
}

.task-plan-note,
.citations-note {
  margin: 2px 0 0;
  color: var(--cw-text-secondary, #718096);
}

.task-item {
  display: grid;
  gap: 2px;
  margin-top: 8px;
  padding-top: 8px;
  border-top: 1px solid rgba(19, 35, 58, 0.08);
}

.task-text {
  min-width: 0;
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}

.task-state {
  color: var(--cw-text-secondary, #718096);
}

.citations {
  margin-top: 6px;
  border: 1px solid rgba(24, 119, 242, 0.14);
  border-radius: 10px;
  padding: 0 10px;
  font-size: 12px;
  line-height: 1.6;
  background: rgba(24, 119, 242, 0.03);
}

.citations summary {
  min-height: 44px;
  padding: 12px 0;
  color: var(--van-primary-color, #1677ff);
  cursor: pointer;
}

.citations summary:focus-visible {
  outline: 2px solid var(--van-primary-color, #1677ff);
  outline-offset: 3px;
  border-radius: 4px;
}

.citation-detail {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  gap: 6px 8px;
  margin: 10px 0;
  padding-top: 10px;
  border-top: 1px solid rgba(24, 119, 242, 0.12);
}

.citation-detail dt {
  color: var(--cw-text-secondary, #718096);
}

.citation-detail dd {
  margin: 0;
  overflow-wrap: anywhere;
}

.feedback-actions {
  display: flex;
  gap: 2px;
  margin-top: 2px;
  color: var(--cw-text-secondary, #718096);
}

.feedback-actions button {
  min-width: 44px;
  min-height: 44px;
  border-radius: 12px;
  font-size: 16px;
}

.feedback-actions button:active {
  background: rgba(24, 119, 242, 0.08);
}

/* Vant 无独立点踩图标，复用 good-job 旋转 180 度表达"踩" */
.feedback-actions .thumb-down {
  transform: rotate(180deg);
}

.feedback-actions .active {
  color: var(--van-primary-color, #1677ff);
  background: rgba(24, 119, 242, 0.08);
}

.cursor {
  animation: blink 1s step-start infinite;
}

@keyframes blink {
  50% {
    opacity: 0;
  }
}

@keyframes status-pulse {
  from {
    transform: scale(0.75);
    opacity: 1;
  }
  to {
    transform: scale(1.55);
    opacity: 0;
  }
}

.confirm-card {
  display: grid;
  gap: 12px;
  margin: 0 12px 10px;
  padding: 14px;
  border: 1px solid rgba(37, 196, 138, 0.2);
  border-radius: 18px;
  background: #f4fcf9;
  box-shadow: 0 9px 24px rgba(20, 91, 71, 0.08);
}

.confirm-copy {
  display: flex;
  align-items: center;
  gap: 10px;
  color: var(--cw-text-primary, #13233a);
}

.confirm-icon {
  display: grid;
  flex: 0 0 36px;
  height: 36px;
  place-items: center;
  border-radius: 12px;
  color: #fff;
  background: var(--cw-success, #25c48a);
  font-weight: 800;
}

.confirm-actions {
  display: flex;
  gap: 8px;
  padding-left: 46px;
}

.confirm-actions .van-button {
  min-height: 36px;
}

.composer-shell {
  z-index: 2;
  padding: 7px 10px calc(8px + env(safe-area-inset-bottom));
  border-top: 1px solid rgba(19, 35, 58, 0.06);
  background: var(--cw-card-bg);
}

.attachment-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  padding: 0 4px 7px;
}

.chip-loading {
  margin-right: 2px;
  vertical-align: middle;
}

.quota-hint {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-bottom: 6px;
  padding: 7px 10px;
  border-radius: 10px;
  font-size: 12px;
  color: #9b5e00;
  background: #fff4dc;
}

.quota-hint-icon {
  font-size: 13px;
}

.input-bar {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 5px;
  border: 1px solid rgba(19, 35, 58, 0.08);
  border-radius: 12px;
  background: var(--cw-card-bg, #fff);
  box-shadow: var(--cw-card-shadow-soft);
}

.attach-button {
  color: var(--cw-text-secondary, #718096);
  border-radius: 14px;
}

.message-input :deep(textarea) {
  font-size: 16px;
  line-height: 1.6;
  padding: 5px 0;
}

.message-input {
  min-width: 0;
  padding: 0;
  background: transparent;
}

.send-button {
  flex: 0 0 auto;
  min-width: 66px;
  min-height: 44px;
  padding: 0 16px;
}

.ended-bar {
  display: grid;
  grid-template-columns: 1fr auto;
  align-items: center;
  gap: 12px;
  padding: 12px 14px calc(12px + env(safe-area-inset-bottom));
  border-top: 1px solid rgba(19, 35, 58, 0.07);
  color: var(--cw-text-primary, #13233a);
  background: rgba(255, 255, 255, 0.96);
}

.ended-bar .van-button {
  width: auto;
  min-height: 42px;
  padding: 0 18px;
}

.dialog-field {
  padding: 16px;
}

@media (max-width: 340px) {
  .status-card {
    align-items: flex-start;
  }

  .status-copy p {
    display: none;
  }

  .bubble-wrap {
    max-width: calc(86% - 42px);
  }

  .send-button {
    min-width: 58px;
    padding: 0 12px;
  }

  .confirm-actions {
    padding-left: 0;
  }
}

@media (prefers-reduced-motion: reduce) {
  .status-pulse::after,
  .cursor {
    animation: none;
  }
}
</style>
