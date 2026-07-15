<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { showConfirmDialog, showToast } from 'vant'
import { closeTicket, createSession, fetchMessages, fetchTicketDetail, confirmTicket, handoffTicket, rejectTicket } from '@/api/ticket'
import { useAuthStore } from '@/store/auth'
import { chatSocket } from '@/utils/ws'
import { TICKET_STATUS_TAG_TYPE, TICKET_STATUS_TEXT } from '@/types/api'
import type { ChatMessage, Ticket, WsChatChunk, WsChatDone, WsChatMessage, WsErrorMessage, WsSystemMessage, WsTicketEvent } from '@/types/api'
import AppTabbar from '@/components/AppTabbar.vue'

const route = useRoute()
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

// 顶栏会话管理 action-sheet
const sessionMenuVisible = ref(false)
// 有活跃工单才允许关闭当前会话（否则动作置灰）
const canCloseSession = computed(() => !!ticketId.value)
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

async function openNewSession() {
  const result = await createSession()
  sessionId.value = result.sessionId
  ticketId.value = result.ticketId
  localStorage.setItem(sessionStorageKey.value, result.sessionId)
  localStorage.setItem(ticketStorageKey.value, String(result.ticketId))
  messages.value = []
  await refreshTicket()
}

async function initSession() {
  const cachedSessionId = localStorage.getItem(sessionStorageKey.value)
  const cachedTicketId = localStorage.getItem(ticketStorageKey.value)
  if (cachedSessionId && cachedTicketId) {
    try {
      const detail = await fetchTicketDetail(cachedTicketId)
      if (detail.ticket.status !== 'CLOSED') {
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

function onWsChat(data: unknown) {
  const payload = data as WsChatMessage
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
  streamingContent.value += payload.content
  scrollToBottom()
}

function onWsChatDone(data: unknown) {
  const payload = data as WsChatDone
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
  scrollToBottom()
}

function onWsTicketEvent(data: unknown) {
  const payload = data as WsTicketEvent
  if (ticket.value && payload.toStatus) {
    ticket.value = { ...ticket.value, status: payload.toStatus }
  } else {
    refreshTicket()
  }
}

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

async function onCloseSession() {
  if (!ticketId.value) {
    return
  }
  try {
    await showConfirmDialog({ title: '关闭当前会话', message: '关闭后将自动开启一个新会话，确认关闭？' })
  } catch {
    return // 用户取消
  }
  await closeTicket(ticketId.value)
  // 清空本地会话缓存后自动新建会话，衔接体验
  localStorage.removeItem(sessionStorageKey.value)
  localStorage.removeItem(ticketStorageKey.value)
  showToast('会话已关闭')
  await openNewSession()
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
    <van-nav-bar title="智能客服">
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

    <div class="input-bar">
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

    <AppTabbar />
  </div>
</template>

<style scoped>
.chat-page {
  display: flex;
  flex-direction: column;
  /* 锁定视口高度并禁止自身溢出：顶部导航/状态条与底部输入栏固定，仅消息区内部滚动。
     不能加 flex:1——其 flex-basis:0 会让父级 .mobile-shell 按消息内容 max-content 撑高，
     滚动落到 body 上导致顶栏被顶走。 */
  height: 100vh;
  max-height: 100vh;
  overflow: hidden;
  /* 底部固定 tabbar（50px）留白：输入栏紧贴 tabbar 上方，内容不被遮挡 */
  padding-bottom: 50px;
  box-sizing: border-box;
  background: #ececec;
}

.status-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 12px;
  background: #fff;
  border-bottom: 1px solid #ebedf0;
}

.reconnect-tip {
  text-align: center;
  font-size: 12px;
  color: #ee0a24;
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
  color: #969799;
  background: rgba(0, 0, 0, 0.04);
  padding: 4px 10px;
  border-radius: 10px;
}

.bubble-wrap {
  max-width: 78%;
}

.badge {
  font-size: 11px;
  color: #969799;
  margin-bottom: 2px;
}

.bubble {
  padding: 8px 12px;
  border-radius: 8px;
  background: #fff;
  word-break: break-word;
  white-space: pre-wrap;
  line-height: 1.5;
}

.row-USER .bubble {
  background: #a2e08e;
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
  background: #fff;
}

.confirm-actions {
  display: flex;
  gap: 8px;
}

.input-bar {
  border-top: 1px solid #ebedf0;
  background: #fff;
}

.dialog-field {
  padding: 16px;
}
</style>
