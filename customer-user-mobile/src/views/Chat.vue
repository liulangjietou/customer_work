<script setup lang="ts">
import type { AxiosError } from 'axios'
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showConfirmDialog, showToast } from 'vant'
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
import { useAuthStore } from '@/store/auth'
import { chatSocket } from '@/utils/ws'
import { TICKET_STATUS_TAG_TYPE, TICKET_STATUS_TEXT, isTicketEnded } from '@/types/api'
import type { ChatMessage, Ticket, WsChatChunk, WsChatDone, WsChatMessage, WsErrorMessage, WsSystemMessage, WsTicketEvent } from '@/types/api'

const HTTP_CONFLICT = 409

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

// 机器人流式回复：chat_chunk 增量拼接到这里做打字机效果，chat_done 定稿后清空并落入 messages
const streamingContent = ref('')
const streamingActive = computed(() => streamingContent.value.length > 0)
/**
 * chat_chunk/chat_done 帧本身不携带 sessionId/ticketId（后端 WsFrame.chatChunk/chatDone 只有
 * content/messageId/ts），无法直接按帧内容与当前会话比对。这里记录"本页面最近一次发起流式请求所属的
 * sessionId"作为本地归属标记：切换会话后 sessionId 变化，迟到的旧会话增量会因不匹配被丢弃。
 */
const streamingSessionId = ref<string | null>(null)

const rejectVisible = ref(false)
const rejectReason = ref('')
const acting = ref(false)

const scrollBox = ref<HTMLElement | null>(null)

const sessionStorageKey = computed(() => `chat-session-${auth.userId}`)
const ticketStorageKey = computed(() => `chat-ticket-${auth.userId}`)

const canSend = computed(() => wsConnected.value && !!sessionId.value && inputContent.value.trim().length > 0)
const canHandoff = computed(() => {
  const status = ticket.value?.status
  return status === 'AI_SERVING' || status === 'ON_HOLD'
})

// 会话已结束（CLOSED/RESOLVED）：历史消息只读展示，输入区替换为"重新开始对话"
const ended = computed(() => (ticket.value ? isTicketEnded(ticket.value.status) : false))

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
  const list = await fetchMessages(sessionId.value, { limit: 50 })
  messages.value = list
  await scrollToBottom()
}

async function refreshTicket() {
  if (!ticketId.value) {
    return
  }
  const detail = await fetchTicketDetail(ticketId.value)
  ticket.value = detail.ticket
}

function cacheSession(newSessionId: string, newTicketId: string) {
  localStorage.setItem(sessionStorageKey.value, newSessionId)
  localStorage.setItem(ticketStorageKey.value, newTicketId)
}

/** 切换会话前的公共收尾：清掉上一个会话遗留的流式增量状态，避免串到新会话的消息区里。 */
function resetStreamingState() {
  streamingContent.value = ''
  streamingSessionId.value = null
}

async function openNewSession() {
  const result = await createSession()
  resetStreamingState()
  sessionId.value = result.sessionId
  ticketId.value = result.ticketId
  cacheSession(result.sessionId, result.ticketId)
  messages.value = []
  await refreshTicket()
}

/** 从消息列表点进某条会话：按 ticketId 精确加载，不依赖/不受本地缓存的"当前会话"影响。 */
async function loadTicketFromRoute(id: string) {
  const detail = await fetchTicketDetail(id)
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
  chatSocket.off('chat_chunk', onWsChatChunk)
  chatSocket.off('chat_done', onWsChatDone)
  chatSocket.off('ticket_event', onWsTicketEvent)
  chatSocket.off('system', onWsSystem)
  chatSocket.off('error', onWsError)
  chatSocket.close()
}

function onWsOpen() {
  wsConnected.value = true
  wsReconnecting.value = false
}

function onWsClose() {
  wsConnected.value = false
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
  messages.value.push({
    id: Date.now(),
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

function onWsChatChunk(data: unknown) {
  const payload = data as WsChatChunk
  // chat_chunk 帧不带 sessionId/ticketId，改用本地记录的"发起流式请求时的会话"做归属匹配
  if (streamingSessionId.value !== sessionId.value) {
    return
  }
  streamingContent.value += payload.content
  scrollToBottom()
}

function onWsChatDone(data: unknown) {
  const payload = data as WsChatDone
  // 理由同 onWsChatChunk：chat_done 同样不带会话标识，按本地流式归属标记比对
  if (streamingSessionId.value !== sessionId.value) {
    return
  }
  messages.value.push({
    id: Date.now(),
    messageId: payload.messageId,
    sessionId: sessionId.value,
    ticketId: ticketId.value ?? '',
    senderType: 'BOT',
    senderId: null,
    content: payload.content,
    createdAtMs: payload.ts,
  })
  streamingContent.value = ''
  streamingSessionId.value = null
  scrollToBottom()
}

function onWsTicketEvent(data: unknown) {
  const payload = data as WsTicketEvent
  if (payload.ticketId !== ticketId.value) {
    return
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

// system 帧（转人工/排队等通知）同样不带 sessionId/ticketId（WsFrame.system 只有 content/ts），
// 无法按会话过滤，属后端已知限制；当前仍归入当前查看会话展示，跨会话误标风险留待后端补充标识字段后再收紧。
function onWsSystem(data: unknown) {
  const payload = data as WsSystemMessage
  messages.value.push({
    id: Date.now(),
    messageId: `system-${payload.ts}`,
    sessionId: sessionId.value,
    ticketId: ticketId.value ?? '',
    senderType: 'SYSTEM',
    senderId: null,
    content: payload.content,
    createdAtMs: payload.ts,
  })
  scrollToBottom()
}

function onWsError(data: unknown) {
  const payload = data as WsErrorMessage
  showToast(payload.message || '连接出现异常')
}

function sendMessage() {
  if (!canSend.value) {
    return
  }
  const content = inputContent.value.trim()
  messages.value.push({
    id: Date.now(),
    messageId: `local-${Date.now()}`,
    sessionId: sessionId.value,
    ticketId: ticketId.value ?? '',
    senderType: 'USER',
    senderId: auth.userId,
    content,
    createdAtMs: Date.now(),
  })
  // 标记本次流式回复归属的会话，供 onWsChatChunk/onWsChatDone 比对，见 streamingSessionId 定义处注释
  streamingSessionId.value = sessionId.value
  chatSocket.send({ type: 'chat', data: { sessionId: sessionId.value, content } })
  inputContent.value = ''
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

onMounted(async () => {
  try {
    await initSession()
  } finally {
    initializing.value = false
  }
  // 带 orderId 进入（订单详情跳转）：预填咨询文案，仅预填不自动发送
  const orderId = route.query.orderId
  if (typeof orderId === 'string' && orderId) {
    inputContent.value = `我想咨询订单 ${orderId} 的情况`
  }
  connectWs()
})

onUnmounted(() => {
  disconnectWs()
})
</script>

<template>
  <div class="chat-page">
    <van-nav-bar title="智能客服" left-arrow @click-left="router.back()">
      <template #right>
        <van-icon name="ellipsis" size="20" @click="openSessionMenu" />
      </template>
    </van-nav-bar>

    <div class="status-bar">
      <van-tag v-if="ticket" :type="TICKET_STATUS_TAG_TYPE[ticket.status]" size="medium">
        {{ TICKET_STATUS_TEXT[ticket.status] }}
      </van-tag>
      <van-button size="small" round plain type="primary" :disabled="!canHandoff" :loading="acting" @click="onHandoff">
        转人工
      </van-button>
    </div>

    <div v-if="wsReconnecting" class="reconnect-tip">连接已断开，正在重连...</div>

    <div ref="scrollBox" class="message-area">
      <van-loading v-if="initializing" class="loading" vertical>加载中...</van-loading>
      <template v-else>
        <div v-for="message in messages" :key="message.messageId" class="message-row" :class="`row-${message.senderType}`">
          <div v-if="message.senderType === 'SYSTEM'" class="system-line">{{ message.content }}</div>
          <div v-else class="bubble-wrap">
            <div v-if="message.senderType === 'AGENT'" class="badge">人工客服</div>
            <div v-else-if="message.senderType === 'BOT'" class="badge">智能助手</div>
            <div class="bubble">{{ message.content }}</div>
          </div>
        </div>
        <div v-if="streamingActive" class="message-row row-BOT">
          <div class="bubble-wrap">
            <div class="badge">智能助手</div>
            <div class="bubble">{{ streamingContent }}<span class="cursor">|</span></div>
          </div>
        </div>
      </template>
    </div>

    <div v-if="ticket?.status === 'WAITING_CONFIRM'" class="confirm-card">
      <van-cell-group inset>
        <van-cell title="客服已处理完毕，请确认是否解决">
          <template #value>
            <div class="confirm-actions">
              <van-button size="small" type="primary" :loading="acting" @click="onConfirmResolved">确认解决</van-button>
              <van-button size="small" plain :loading="acting" @click="openReject">仍有问题</van-button>
            </div>
          </template>
        </van-cell>
      </van-cell-group>
    </div>

    <div v-if="ended" class="ended-bar">
      <van-button block round type="primary" :loading="acting" @click="onReopen">重新开始对话</van-button>
    </div>
    <div v-else class="input-bar">
      <van-field
        v-model="inputContent"
        placeholder="请输入消息"
        :disabled="!wsConnected"
        @keyup.enter="sendMessage"
      >
        <template #button>
          <van-button size="small" type="primary" :disabled="!canSend" @click="sendMessage">
            {{ wsConnected ? '发送' : '重连中' }}
          </van-button>
        </template>
      </van-field>
    </div>

    <van-dialog v-model:show="rejectVisible" title="仍有问题" show-cancel-button @confirm="submitReject">
      <van-field v-model="rejectReason" type="textarea" rows="3" placeholder="请描述遗留问题" class="dialog-field" />
    </van-dialog>

    <van-action-sheet
      v-model:show="sessionMenuVisible"
      :actions="sessionActions"
      cancel-text="取消"
      close-on-click-action
      @select="onSelectSessionAction"
    />
  </div>
</template>

<style scoped>
.chat-page {
  display: flex;
  flex-direction: column;
  /* 锁定视口高度并禁止自身溢出：顶部导航/状态条与底部输入栏固定，仅消息区内部滚动。
     不能加 flex:1——其 flex-basis:0 会让父级 .mobile-shell 按消息内容 max-content 撑高，
     滚动落到 body 上导致顶栏被顶走。
     不再是 tabbar 根页面（改由消息列表点入），无需再为固定 tabbar 预留 padding-bottom。 */
  height: 100vh;
  max-height: 100vh;
  overflow: hidden;
  box-sizing: border-box;
  background: var(--cw-page-bg);
}

.status-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 12px;
  background: var(--cw-card-bg);
  border-bottom: 1px solid var(--cw-border-color);
}

.reconnect-tip {
  text-align: center;
  font-size: 12px;
  color: var(--cw-danger);
  background: #fff4f4;
  padding: 4px 0;
}

.message-area {
  flex: 1;
  /* flex 子项默认 min-height:auto 不会收缩到内容以下，必须显式归零，滚动才发生在本区域内 */
  min-height: 0;
  overflow-y: auto;
  padding: 12px;
}

.loading {
  margin-top: 30vh;
}

.message-row {
  margin-bottom: 12px;
  display: flex;
}

.row-USER {
  justify-content: flex-end;
}

.row-BOT,
.row-AGENT {
  justify-content: flex-start;
}

.row-SYSTEM {
  justify-content: center;
}

.system-line {
  font-size: 12px;
  color: var(--cw-text-secondary);
  background: rgba(0, 0, 0, 0.04);
  padding: 4px 10px;
  border-radius: 10px;
}

.bubble-wrap {
  max-width: 78%;
}

.badge {
  font-size: 11px;
  color: var(--cw-text-secondary);
  margin-bottom: 2px;
}

.bubble {
  padding: 8px 12px;
  border-radius: 10px;
  background: var(--cw-card-bg);
  box-shadow: var(--cw-card-shadow);
  word-break: break-word;
  white-space: pre-wrap;
  line-height: 1.5;
}

.row-USER .bubble {
  background: var(--cw-bubble-user-bg);
}

.cursor {
  animation: blink 1s step-start infinite;
}

@keyframes blink {
  50% {
    opacity: 0;
  }
}

.confirm-card {
  padding: 8px 0;
  background: var(--cw-card-bg);
}

.confirm-actions {
  display: flex;
  gap: 8px;
}

.input-bar {
  border-top: 1px solid var(--cw-border-color);
  background: var(--cw-card-bg);
}

.ended-bar {
  padding: 10px 16px;
  border-top: 1px solid var(--cw-border-color);
  background: var(--cw-card-bg);
}

.dialog-field {
  padding: 16px;
}
</style>
