<script setup lang="ts">
import { computed, nextTick, onMounted, onScopeDispose, ref, toRef, watch } from 'vue'
import { useAuthStore } from '@/store/auth'
import { useTicketReplies } from '@/store/ticketReplies'
import type { WsClient } from '@/utils/ws'
import { SENDER_TYPE_LABELS, STATUS_LABELS, STATUS_TAG_TYPE } from '@/types/ticket'
import type { TicketAssistAdoption } from '@/types/ticket'
import { useTicketConversation } from '../useTicketConversation'
import TicketContextPanel from './TicketContextPanel.vue'
import TicketAssistPanel from './TicketAssistPanel.vue'

const MAX_REPLY_CONTENT_BYTES = 65_535
const props = defineProps<{
  ticketId: string | null
  ws: WsClient
  agentId: string
  ready: boolean
  connected: boolean
}>()
const emit = defineEmits<{ back: []; refresh: [] }>()
const auth = useAuthStore()
const replies = useTicketReplies()
const {
  detail,
  messages,
  loading,
  historyLoading,
  detailError,
  historyError,
  hasMore,
  loadHistory,
  synchronize,
} = useTicketConversation(toRef(props, 'ticketId'), toRef(props, 'ready'), props.ws)
const panelRef = ref<HTMLElement>()
const scrollRef = ref<HTMLElement>()
const contextVisible = ref(false)
const wideContext = ref(false)
const nearBottom = ref(true)
const unseen = ref(0)
const inputError = ref('')
const inputRef = ref<{ focus: () => void }>()
const adopting = ref(false)
const readingPositions = new Map<string, { top: number; nearBottom: boolean }>()
let observer: ResizeObserver | null = null
let disposed = false
let restored = false
let adoptionGeneration = 0

const draft = computed(() => (props.ticketId ? replies.state(props.ticketId) : null))
const input = computed({
  get: () => draft.value?.content ?? '',
  set: (value) => {
    if (props.ticketId) replies.setDraft(props.ticketId, value)
    inputError.value = ''
  },
})
const canInput = computed(
  () =>
    props.ready &&
    !loading.value &&
    !detailError.value &&
    !!detail.value &&
    detail.value.ticket.assignee === props.agentId &&
    ['PROCESSING', 'ON_HOLD'].includes(detail.value.ticket.status) &&
    auth.hasPermission('user-ticket:reply'),
)
const samePending = computed(() =>
  draft.value?.pending.find((pending) => pending.content === input.value),
)
const sending = computed(() => samePending.value?.status === 'SENDING' && samePending.value.busy)
const messageRevision = computed(() =>
  JSON.stringify([
    props.ticketId,
    detail.value?.ticket.updatedAtMs,
    messages.value
      .slice(-30)
      .map((message) => [
        message.id,
        message.messageId,
        message.content,
        message.senderType,
        message.createdAtMs,
      ]),
  ]),
)
const assistDisabled = computed(
  () =>
    !props.ready ||
    loading.value ||
    !!detailError.value ||
    historyLoading.value ||
    !!historyError.value,
)
const canAdopt = computed(() => canInput.value && !samePending.value && !adopting.value)
const readonlyReason = computed(() => {
  if (!props.ready) return '正在确认坐席身份，草稿将在确认后显示。'
  if (detailError.value || loading.value) return '工单状态尚未确认，刷新后再回复。'
  if (!auth.hasPermission('user-ticket:reply')) return '当前账号可查看此工单，没有回复权限。'
  if (detail.value?.ticket.status === 'CLOSED' || detail.value?.ticket.status === 'RESOLVED')
    return '工单已结束，当前会话为只读。'
  if (detail.value?.ticket.assignee !== props.agentId) return '接入本人负责的工单后即可回复。'
  return '当前正在等待客户确认，暂时不能继续回复。'
})
const stage = computed(() => {
  const status = detail.value?.ticket.status
  if (status === 'CLOSED' || status === 'RESOLVED') return 3
  if (status === 'WAITING_CONFIRM') return 2
  if (status === 'PROCESSING' || status === 'ON_HOLD') return 1
  return 0
})

function formatTime(ms: number) {
  return new Date(ms).toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  })
}
function readPosition() {
  const element = scrollRef.value
  if (!element || !props.ticketId) return
  nearBottom.value = element.scrollHeight - element.clientHeight - element.scrollTop < 64
  if (nearBottom.value) unseen.value = 0
  readingPositions.set(props.ticketId, { top: element.scrollTop, nearBottom: nearBottom.value })
}
function latest() {
  const element = scrollRef.value
  if (element) element.scrollTop = element.scrollHeight
  nearBottom.value = true
  unseen.value = 0
  readPosition()
}
async function older() {
  const element = scrollRef.value
  const id = props.ticketId
  const previousHeight = element?.scrollHeight ?? 0
  const previousTop = element?.scrollTop ?? 0
  await loadHistory(true)
  await nextTick()
  if (id === props.ticketId && element && !disposed) {
    element.scrollTop = previousTop + element.scrollHeight - previousHeight
    readPosition()
  }
}
async function send() {
  if (!props.ticketId || !canInput.value || !input.value.trim() || samePending.value) return
  if (new TextEncoder().encode(input.value).length > MAX_REPLY_CONTENT_BYTES) {
    inputError.value = '回复内容过长，请缩短后再发送。'
    return
  }
  latest()
  await replies.send(props.ticketId)
}
function onEnter(event: KeyboardEvent) {
  if (
    event.key !== 'Enter' ||
    event.shiftKey ||
    event.ctrlKey ||
    event.metaKey ||
    event.altKey ||
    event.isComposing ||
    event.keyCode === 229
  )
    return
  event.preventDefault()
  void send()
}
function refresh() {
  void synchronize()
  emit('refresh')
}
/** 采用只更新本工单草稿；对话、身份或草稿在确认期间变化时，原文保持不动。 */
async function adoptSuggestion(value: TicketAssistAdoption) {
  if (
    !canAdopt.value ||
    assistDisabled.value ||
    props.ticketId !== value.ticketId ||
    messageRevision.value !== value.messageRevision ||
    !value.reply.trim()
  )
    return
  const original = draft.value!
  const originalVersion = original.version
  const originalContent = original.content
  const generation = adoptionGeneration
  const reply = value.reply.trim()
  if (originalContent.trim() === reply || originalContent.trimEnd().endsWith(`\n\n${reply}`)) {
    ElMessage.info('这条建议已在当前草稿中。')
    return
  }
  const combined = originalContent.trim() ? `${originalContent}\n\n${reply}` : reply
  if (new TextEncoder().encode(combined).length > MAX_REPLY_CONTENT_BYTES) {
    inputError.value = '采用后内容过长，请先缩短草稿。现有内容已保留。'
    return
  }
  adopting.value = true
  try {
    if (originalContent.trim()) {
      await ElMessageBox.confirm('已有草稿将完整保留，建议回复会追加到末尾。', '追加建议到草稿', {
        confirmButtonText: '保留并追加',
        cancelButtonText: '取消',
      })
    }
    if (
      disposed ||
      generation !== adoptionGeneration ||
      !canInput.value ||
      assistDisabled.value ||
      samePending.value ||
      props.ticketId !== value.ticketId ||
      messageRevision.value !== value.messageRevision ||
      draft.value !== original ||
      original.version !== originalVersion ||
      original.content !== originalContent
    ) {
      ElMessage.info('工单、会话或草稿已有变化，请重新核对建议后采用。')
      return
    }
    replies.setDraft(value.ticketId, combined)
    inputError.value = ''
    contextVisible.value = false
    await nextTick()
    if (!disposed && generation === adoptionGeneration) inputRef.value?.focus()
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') {
      ElMessage.error('暂时无法采用建议，原草稿已保留。')
    }
  } finally {
    adopting.value = false
  }
}
watch(
  () => [props.ticketId, props.agentId, auth.token, canInput.value],
  () => {
    adoptionGeneration += 1
  },
  { flush: 'sync' },
)
watch(
  () => props.ticketId,
  () => {
    contextVisible.value = false
    inputError.value = ''
    unseen.value = 0
    restored = false
    nearBottom.value = props.ticketId
      ? (readingPositions.get(props.ticketId)?.nearBottom ?? true)
      : true
  },
)
watch(
  [
    messages,
    historyLoading,
    () => draft.value?.pending.map((pending) => [pending.status, pending.error]),
  ],
  async ([rows], [previousRows]) => {
    const id = props.ticketId
    // 消息可能先于本帧 scroll 事件到达；更新 DOM 前读取实际位置，不能沿用过期的底部标记。
    if (restored) readPosition()
    const followLatest = nearBottom.value
    await nextTick()
    if (disposed || id !== props.ticketId) return
    if (!restored && !historyLoading.value) {
      const position = id ? readingPositions.get(id) : undefined
      if (position && !position.nearBottom && scrollRef.value)
        scrollRef.value.scrollTop = position.top
      else latest()
      restored = true
    } else if (restored) {
      if (followLatest) latest()
      else unseen.value += Math.max(0, rows.length - (previousRows?.length ?? 0))
    }
  },
  { flush: 'pre' },
)
onMounted(() => {
  observer = new ResizeObserver(([entry]) => {
    wideContext.value = (entry?.contentRect.width ?? 0) >= 730
    if (wideContext.value) contextVisible.value = false
  })
  if (panelRef.value) observer.observe(panelRef.value)
})
onScopeDispose(() => {
  disposed = true
  observer?.disconnect()
})
</script>

<template>
  <section
    ref="panelRef"
    class="ticket-workspace"
    :class="{ 'with-context': wideContext && ticketId }"
    aria-label="当前服务会话"
  >
    <div v-if="!ticketId" class="desk-welcome">
      <div class="welcome-symbol">
        <el-icon><ChatDotRound /></el-icon>
      </div>
      <h2>从一张工单开始</h2>
      <p>在左侧选择会话，查看问题和处理记录。</p>
      <span>切换工单时，回复草稿会保留。</span>
    </div>
    <template v-else>
      <section class="conversation-column">
        <header class="conversation-header">
          <button class="back-to-queue" aria-label="返回工单队列" @click="emit('back')">
            <el-icon><ArrowLeft /></el-icon>
          </button>
          <div class="customer-avatar">{{ detail?.ticket.userId.slice(-2) || '客' }}</div>
          <div class="conversation-heading">
            <h2>{{ detail?.ticket.title || '加载工单…' }}</h2>
            <p>
              {{ detail?.ticket.userId || ticketId
              }}<span v-if="detail"> · {{ detail.ticket.id }}</span>
            </p>
          </div>
          <el-button
            v-if="!wideContext"
            class="context-button"
            :disabled="!detail"
            @click="contextVisible = true"
            >工单信息</el-button
          >
        </header>
        <div class="service-progress" aria-label="服务处理进度">
          <span
            v-for="(label, index) in ['接收问题', '人工跟进', '确认解决', '结束服务']"
            :key="label"
            :class="{ current: !!detail && index === stage }"
            :aria-current="detail && index === stage ? 'step' : undefined"
            ><i>{{ index + 1 }}</i
            >{{ label }}</span
          >
        </div>
        <div class="conversation-status">
          <el-tag v-if="detail" :type="STATUS_TAG_TYPE[detail.ticket.status]" size="small">{{
            STATUS_LABELS[detail.ticket.status]
          }}</el-tag
          ><span>{{ connected ? '实时连接已建立' : '实时连接暂未建立，可刷新记录核对' }}</span
          ><el-button text size="small" :loading="loading || historyLoading" @click="synchronize"
            >同步记录</el-button
          >
        </div>
        <div v-if="detailError || historyError" class="conversation-error" role="alert">
          <span>{{ detailError || historyError }} 现有内容已保留。</span
          ><el-button size="small" @click="synchronize">重新加载记录</el-button>
        </div>
        <div ref="scrollRef" class="messages" @scroll="readPosition" :aria-busy="historyLoading">
          <div v-if="hasMore" class="history-action">
            <el-button text :loading="historyLoading" @click="older">加载更早消息</el-button>
          </div>
          <div v-if="historyLoading && !messages.length" class="history-placeholder">
            正在同步会话记录…
          </div>
          <div
            v-for="message in messages"
            :key="message.messageId"
            class="message-row"
            :class="{
              self: message.senderType === 'AGENT' && message.senderId === agentId,
              system: message.senderType === 'SYSTEM',
            }"
          >
            <template v-if="message.senderType === 'SYSTEM'"
              ><span>{{ message.content }}</span></template
            >
            <template v-else
              ><div class="message-meta">
                {{
                  message.senderId === agentId && message.senderType === 'AGENT'
                    ? '你'
                    : SENDER_TYPE_LABELS[message.senderType]
                }}
                · {{ formatTime(message.createdAtMs)
                }}<span v-if="message.senderType === 'AGENT' && message.senderId === agentId">
                  · 已保存</span
                >
              </div>
              <div class="bubble" :class="{ bot: message.senderType === 'BOT' }">
                {{ message.content }}
              </div></template
            >
          </div>
          <div
            v-for="pending in draft?.pending"
            :key="pending.clientMsgId"
            class="message-row self pending-message"
          >
            <div class="message-meta">你 · {{ formatTime(pending.createdAtMs) }}</div>
            <div class="bubble">{{ pending.content }}</div>
            <div class="receipt-status" :class="pending.status.toLowerCase()" role="status">
              <span>{{
                pending.status === 'SENDING'
                  ? '等待保存确认'
                  : pending.status === 'REJECTED'
                    ? '未受理'
                    : '保存结果待确认'
              }}</span
              ><span v-if="pending.error">{{ pending.error }}</span>
            </div>
            <div v-if="pending.status !== 'SENDING'" class="receipt-actions">
              <el-button
                size="small"
                :loading="pending.busy"
                @click="replies.reconcile(ticketId, pending)"
                >查询结果</el-button
              >
              <el-button
                v-if="canInput"
                size="small"
                :disabled="pending.busy"
                @click="replies.reconcile(ticketId, pending, true)"
                >核对后重试</el-button
              >
              <el-button
                v-if="pending.status === 'REJECTED'"
                text
                size="small"
                @click="replies.dismissRejected(ticketId, pending.clientMsgId)"
                >移除未受理记录</el-button
              >
            </div>
          </div>
          <div
            v-if="!historyLoading && !messages.length && !draft?.pending.length && !historyError"
            class="empty-conversation"
          >
            <el-icon><ChatLineRound /></el-icon>
            <p>暂无会话消息</p>
            <span>客户的新消息会显示在这里</span>
          </div>
        </div>
        <button v-if="!nearBottom" class="latest-message" @click="latest">
          回到最新<span v-if="unseen"> · {{ unseen }} 条新消息</span
          ><el-icon><ArrowDown /></el-icon>
        </button>
        <footer class="reply-composer">
          <div class="composer-title">
            <strong>回复客户</strong
            ><span>{{ canInput ? '按 Enter 发送，Shift + Enter 换行' : '当前会话只读' }}</span>
          </div>
          <p v-if="!canInput" class="readonly-reason">{{ readonlyReason }}</p>
          <el-input
            ref="inputRef"
            v-model="input"
            type="textarea"
            :rows="3"
            :readonly="!canInput"
            :disabled="!ready"
            aria-label="回复客户"
            :placeholder="canInput ? '输入回复内容…' : '此处保留你的草稿，可选中复制'"
            @keydown="onEnter"
          />
          <div class="composer-bottom">
            <span v-if="inputError" role="alert" class="input-error">{{ inputError }}</span
            ><span v-else-if="samePending">此内容正在核对，原文已保留</span
            ><span v-else>{{ input ? '草稿仅保留在当前登录会话' : '发送后以保存回执确认' }}</span
            ><el-button
              type="primary"
              :disabled="!canInput || !input.trim() || !!samePending"
              :loading="sending"
              @click="send"
              >发送</el-button
            >
          </div>
        </footer>
      </section>
      <div v-if="wideContext" class="context-column">
        <TicketAssistPanel
          :ticket-id="ticketId"
          :message-revision="messageRevision"
          :disabled="assistDisabled"
          :can-adopt="canAdopt"
          :adopt-reason="
            samePending ? '当前草稿正在核对保存结果，请完成核对后再采用。' : readonlyReason
          "
          @adopt="adoptSuggestion"
        />
        <TicketContextPanel
          v-if="detail"
          :detail="detail"
          :agent-id="agentId"
          :disabled="!ready || loading || !!detailError"
          @refresh="refresh"
        />
        <div v-else class="history-placeholder">正在加载工单信息…</div>
      </div>
      <el-drawer
        v-if="!wideContext"
        v-model="contextVisible"
        title="工单信息"
        size="min(360px, 100vw)"
        append-to-body
        class="ticket-context-drawer"
        ><TicketAssistPanel
          v-if="contextVisible"
          :ticket-id="ticketId"
          :message-revision="messageRevision"
          :disabled="assistDisabled"
          :can-adopt="canAdopt"
          :adopt-reason="
            samePending ? '当前草稿正在核对保存结果，请完成核对后再采用。' : readonlyReason
          "
          @adopt="adoptSuggestion" /><TicketContextPanel
          v-if="detail"
          :detail="detail"
          :agent-id="agentId"
          :show-heading="false"
          :disabled="!ready || loading || !!detailError"
          @refresh="refresh"
      /></el-drawer>
    </template>
  </section>
</template>

<style scoped>
.ticket-workspace {
  min-width: 0;
  min-height: 0;
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  overflow: hidden;
  background: var(--cw-paper);
  border-radius: 12px;
  border: 1px solid var(--cw-line);
}
.ticket-workspace.with-context {
  grid-template-columns: minmax(0, 1fr) 296px;
}
.conversation-column {
  min-width: 0;
  min-height: 0;
  position: relative;
  display: flex;
  flex-direction: column;
}
.conversation-header {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 20px 22px 16px;
}
.customer-avatar {
  width: 40px;
  height: 40px;
  flex: none;
  display: grid;
  place-items: center;
  border-radius: 12px;
  background: color-mix(in srgb, var(--cw-cobalt) 9%, var(--cw-paper));
  color: var(--cw-cobalt);
  font-weight: 650;
}
.conversation-heading {
  min-width: 0;
  flex: 1;
}
.conversation-heading h2 {
  font-size: 17px;
  line-height: 1.4;
  margin: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.conversation-heading p {
  font-size: 12px;
  color: var(--cw-text-muted);
  margin: 5px 0 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.service-progress {
  display: flex;
  justify-content: space-between;
  gap: 6px;
  padding: 0 22px 14px;
  border-bottom: 1px solid var(--cw-line);
  font-size: 12px;
  color: var(--cw-text-muted);
}
.service-progress span {
  display: flex;
  align-items: center;
  gap: 6px;
}
.service-progress i {
  width: 19px;
  height: 19px;
  display: grid;
  place-items: center;
  border-radius: 50%;
  background: var(--cw-canvas);
  font-style: normal;
  font-size: 11px;
}
.service-progress .current {
  color: var(--cw-cobalt);
  font-weight: 650;
}
.service-progress .current i {
  background: var(--cw-cobalt);
  color: white;
}
.conversation-status {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  padding: 9px 18px;
  font-size: 12px;
  color: var(--cw-text-muted);
  background: color-mix(in srgb, var(--cw-canvas) 70%, var(--cw-paper));
}
.conversation-status .el-button {
  margin-left: auto;
}
.messages {
  flex: 1;
  min-height: 140px;
  overflow-y: auto;
  overscroll-behavior: contain;
  padding: 20px 24px;
  background: var(--cw-paper);
}
.message-row {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  margin-bottom: 22px;
}
.message-row.self {
  align-items: flex-end;
}
.message-meta {
  color: var(--cw-text-muted);
  font-size: 11px;
  line-height: 1.5;
  margin: 0 2px 6px;
}
.bubble {
  max-width: 88%;
  padding: 12px 15px;
  background: var(--cw-canvas);
  color: var(--cw-text);
  border: 1px solid var(--cw-line);
  border-radius: 3px 12px 12px 12px;
  font-size: 14px;
  line-height: 1.85;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.self .bubble {
  background: color-mix(in srgb, var(--cw-cobalt) 8%, var(--cw-paper));
  border-color: color-mix(in srgb, var(--cw-cobalt) 16%, var(--cw-line));
  border-radius: 12px 3px 12px 12px;
}
.system {
  display: block;
  text-align: center;
  color: var(--cw-text-muted);
  font-size: 12px;
}
.history-action {
  text-align: center;
  margin: -12px 0 16px;
}
.history-placeholder {
  padding: 26px;
  color: var(--cw-text-muted);
  font-size: 13px;
  text-align: center;
}
.receipt-status {
  max-width: 92%;
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  font-size: 12px;
  color: var(--cw-text-muted);
  margin-top: 7px;
  gap: 4px;
  line-height: 1.6;
  text-align: right;
  overflow-wrap: anywhere;
}
.receipt-status.rejected {
  color: var(--el-color-danger);
}
.receipt-status.unknown {
  color: var(--el-color-warning-dark-2);
}
.receipt-actions {
  display: flex;
  justify-content: flex-end;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 8px;
}
.receipt-actions .el-button {
  margin: 0;
}
.reply-composer {
  border-top: 1px solid var(--cw-line);
  padding: 14px 20px 16px;
  background: var(--cw-paper);
}
.composer-title,
.composer-bottom {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  align-items: center;
}
.composer-title {
  margin-bottom: 9px;
  font-size: 13px;
}
.composer-title span,
.composer-bottom > span {
  color: var(--cw-text-muted);
  font-size: 11px;
  line-height: 1.5;
}
.reply-composer :deep(.el-textarea__inner) {
  resize: none;
  min-height: 80px !important;
  line-height: 1.7;
  padding: 10px 12px;
  box-shadow: 0 0 0 1px var(--cw-line) inset;
  border-radius: 8px;
  background: var(--cw-canvas);
}
.reply-composer :deep(.el-textarea__inner:focus) {
  box-shadow: 0 0 0 1px var(--cw-cobalt) inset;
}
.composer-bottom {
  margin-top: 10px;
}
.composer-bottom .el-button {
  min-width: 88px;
  min-height: 36px;
}
.readonly-reason {
  font-size: 12px;
  color: var(--cw-text-muted);
  margin: 0 0 8px;
  line-height: 1.6;
}
.conversation-error {
  padding: 10px 18px;
  display: flex;
  align-items: center;
  gap: 10px;
  color: var(--el-color-danger);
  background: var(--el-color-danger-light-9);
  font-size: 12px;
  line-height: 1.6;
}
.conversation-error span {
  flex: 1;
  min-width: 0;
}
.context-column {
  border-left: 1px solid var(--cw-line);
  overflow-y: auto;
  min-width: 0;
  background: color-mix(in srgb, var(--cw-canvas) 28%, var(--cw-paper));
}
.desk-welcome {
  display: flex;
  justify-content: center;
  align-items: center;
  flex-direction: column;
  padding: 30px;
  text-align: center;
  color: var(--cw-text-muted);
}
.welcome-symbol {
  width: 66px;
  height: 66px;
  border-radius: 20px;
  background: color-mix(in srgb, var(--cw-cobalt) 8%, var(--cw-paper));
  color: var(--cw-cobalt);
  font-size: 30px;
}
.desk-welcome h2 {
  font-size: 20px;
  color: var(--cw-text);
  margin-top: 22px;
}
.desk-welcome p {
  margin: 0 0 10px;
  font-size: 14px;
}
.desk-welcome span {
  font-size: 12px;
}
.empty-conversation {
  text-align: center;
  padding: 45px 20px;
  color: var(--cw-text-muted);
  font-size: 13px;
}
.empty-conversation .el-icon {
  font-size: 25px;
}
.empty-conversation span {
  font-size: 12px;
}
.back-to-queue {
  display: none;
  border: 0;
  background: transparent;
  color: var(--cw-text);
}
.latest-message {
  position: absolute;
  bottom: 210px;
  align-self: center;
  display: flex;
  align-items: center;
  gap: 5px;
  padding: 8px 14px;
  background: var(--cw-paper);
  color: var(--cw-cobalt);
  box-shadow: 0 2px 10px #172d4820;
  border: 1px solid var(--cw-line);
  border-radius: 20px;
  cursor: pointer;
  font-size: 12px;
}
@media (max-width: 700px) {
  .conversation-header {
    padding: 12px;
    gap: 8px;
  }
  .conversation-heading h2 {
    font-size: 15px;
  }
  .customer-avatar {
    display: none;
  }
  .back-to-queue {
    display: grid;
    place-items: center;
    min-width: 36px;
    min-height: 44px;
  }
  .context-button {
    min-height: 44px;
  }
  .service-progress {
    padding: 0 12px 12px;
    font-size: 11px;
  }
  .service-progress span {
    gap: 4px;
  }
  .service-progress i {
    width: 17px;
    height: 17px;
  }
  .conversation-status {
    padding: 6px 12px;
    gap: 6px;
  }
  .conversation-status .el-button {
    min-height: 36px;
  }
  .messages {
    padding: 18px 12px;
  }
  .bubble {
    max-width: 94%;
    padding: 10px 12px;
  }
  .message-meta {
    font-size: 11px;
  }
  .reply-composer {
    padding: 12px;
  }
  .composer-title span {
    display: none;
  }
  .composer-bottom .el-button,
  .receipt-actions .el-button,
  .history-action .el-button {
    min-height: 44px;
  }
  .conversation-error {
    padding: 10px 12px;
    align-items: flex-start;
    flex-direction: column;
  }
  .latest-message {
    bottom: 216px;
    min-height: 40px;
  }
}
</style>
