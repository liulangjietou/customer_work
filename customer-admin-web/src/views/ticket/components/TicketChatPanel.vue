<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import {
  claimTicket,
  closeTicket,
  getTicketDetail,
  getTicketMessages,
  holdTicket,
  replyTicket,
  resolveTicket,
  resumeTicket,
  transferTicket,
  updateTicketCategory,
  updateTicketPriority,
} from '@/api/user-ticket'
import type { WsClient } from '@/utils/ws'
import {
  CATEGORY_LABELS,
  PRIORITY_LABELS,
  SENDER_TYPE_LABELS,
  STATUS_LABELS,
  STATUS_TAG_TYPE,
  type TicketCategory,
  type TicketDetailVO,
  type TicketMessageVO,
  type TicketPriority,
  type WsChatFrameData,
  type WsTicketEventFrameData,
} from '@/types/ticket'

const props = defineProps<{
  visible: boolean
  ticketId: string | null
  ws: WsClient
  /** 当前登录坐席在工单系统里的标识，与后端 assignee 字段比对，判断"是否本人负责"的输入权限。 */
  agentId: string
}>()

const emit = defineEmits<{
  (e: 'update:visible', value: boolean): void
  /** 工单状态被本面板的操作改变后，通知列表页刷新对应行。 */
  (e: 'refresh'): void
}>()

const MESSAGE_PAGE_SIZE = 30

const detailLoading = ref(false)
const detail = ref<TicketDetailVO | null>(null)
const messages = ref<TicketMessageVO[]>([])
const messagesLoading = ref(false)
const hasMoreHistory = ref(true)
const scrollRef = ref<HTMLElement>()
const input = ref('')
const sending = ref(false)

let unsubscribeChat: (() => void) | null = null
let unsubscribeEvent: (() => void) | null = null

const isSelfAssignee = computed(() => !!detail.value && detail.value.ticket.assignee === props.agentId)
const canInput = computed(
  () => !!detail.value && ['PROCESSING', 'ON_HOLD'].includes(detail.value.ticket.status) && isSelfAssignee.value,
)

function closeDrawer() {
  emit('update:visible', false)
}

async function loadDetail() {
  if (!props.ticketId) {
    return
  }
  detailLoading.value = true
  try {
    detail.value = await getTicketDetail(props.ticketId)
  } finally {
    detailLoading.value = false
  }
}

async function loadInitialMessages() {
  if (!props.ticketId) {
    return
  }
  messagesLoading.value = true
  try {
    const list = await getTicketMessages(props.ticketId, undefined, MESSAGE_PAGE_SIZE)
    messages.value = list
    hasMoreHistory.value = list.length >= MESSAGE_PAGE_SIZE
    scrollToBottom()
  } finally {
    messagesLoading.value = false
  }
}

/** 上滑到顶加载更早的消息：取当前最早一条的 id 作为 beforeId，保持滚动位置不跳动。 */
async function loadMoreHistory() {
  if (!props.ticketId || messagesLoading.value || !hasMoreHistory.value || messages.value.length === 0) {
    return
  }
  const el = scrollRef.value
  const prevScrollHeight = el?.scrollHeight ?? 0
  messagesLoading.value = true
  try {
    const beforeId = messages.value[0].id
    const older = await getTicketMessages(props.ticketId, beforeId, MESSAGE_PAGE_SIZE)
    hasMoreHistory.value = older.length >= MESSAGE_PAGE_SIZE
    messages.value = [...older, ...messages.value]
    await nextTick()
    if (el) {
      el.scrollTop = el.scrollHeight - prevScrollHeight
    }
  } finally {
    messagesLoading.value = false
  }
}

function handleScroll() {
  const el = scrollRef.value
  if (el && el.scrollTop < 40) {
    loadMoreHistory()
  }
}

function scrollToBottom() {
  nextTick(() => {
    scrollRef.value?.scrollTo({ top: scrollRef.value.scrollHeight, behavior: 'smooth' })
  })
}

function subscribeWs() {
  unsubscribeChat = props.ws.on('chat', (data) => {
    const frame = data as WsChatFrameData
    if (frame.ticketId !== props.ticketId) {
      return
    }
    messages.value.push({
      id: -frame.ts, // WS 推送没有落库自增 id，用负时间戳占位，避免与历史消息 key 冲突；仅供 v-for key 使用
      messageId: frame.messageId,
      sessionId: detail.value?.ticket.sessionId ?? '',
      ticketId: frame.ticketId,
      senderType: frame.senderType,
      senderId: frame.senderId,
      content: frame.content,
      createdAtMs: frame.ts,
    })
    scrollToBottom()
  })
  unsubscribeEvent = props.ws.on('ticket_event', (data) => {
    const frame = data as WsTicketEventFrameData
    if (frame.ticketId !== props.ticketId || !detail.value) {
      return
    }
    // 状态被其他坐席/系统动作改变时（如超时自动关闭），同步刷新详情兜底，事件时间线一起重拉，
    // 比本地拼接单条事件更可靠（events 列表字段较多，本地拼接容易漏字段）。
    loadDetail()
  })
}

function unsubscribeWs() {
  unsubscribeChat?.()
  unsubscribeEvent?.()
  unsubscribeChat = null
  unsubscribeEvent = null
}

watch(
  () => props.visible,
  (visible) => {
    if (visible && props.ticketId) {
      loadDetail()
      loadInitialMessages()
      subscribeWs()
    } else {
      unsubscribeWs()
    }
  },
)

async function handleSend() {
  const content = input.value.trim()
  if (!content || !props.ticketId || sending.value) {
    return
  }
  sending.value = true
  try {
    if (props.ws.isOpen()) {
      props.ws.send('chat', { ticketId: props.ticketId, content })
    } else {
      // WS 断开时降级走 HTTP 接口，保证坐席回复不因连接抖动丢失；成功后本地立即回显，
      // 不等 WS 恢复后的回声帧（服务端是否会给 HTTP 提交的消息也广播一份 WS 回声未知，
      // 宁可本地先显示，即使真收到回声也只是极小概率的重复气泡，不影响可用性）。
      await replyTicket(props.ticketId, { content })
      messages.value.push({
        id: -Date.now(),
        messageId: `local-${Date.now()}`,
        sessionId: detail.value?.ticket.sessionId ?? '',
        ticketId: props.ticketId,
        senderType: 'AGENT',
        senderId: props.agentId,
        content,
        createdAtMs: Date.now(),
      })
      scrollToBottom()
    }
    input.value = ''
  } catch (error) {
    ElMessage.error('发送失败：' + (error instanceof Error ? error.message : String(error)))
  } finally {
    sending.value = false
  }
}

// ---------- 操作按钮 ----------
const acting = ref(false)

async function runAction(action: () => Promise<void>, successMessage: string) {
  if (!props.ticketId) {
    return
  }
  acting.value = true
  try {
    await action()
    ElMessage.success(successMessage)
    await loadDetail()
    emit('refresh')
  } catch (error) {
    ElMessage.error('操作失败：' + (error instanceof Error ? error.message : String(error)))
  } finally {
    acting.value = false
  }
}

function handleClaim() {
  if (!props.ticketId) return
  runAction(() => claimTicket(props.ticketId!), '已接入')
}

async function handleHold() {
  if (!props.ticketId) return
  const { value } = await ElMessageBox.prompt('挂起原因（可选）', '挂起工单', { inputType: 'textarea', confirmButtonText: '确定', cancelButtonText: '取消' }).catch(() => ({ value: undefined }))
  if (value === undefined) return
  runAction(() => holdTicket(props.ticketId!, { reason: value || undefined }), '已挂起')
}

function handleResume() {
  if (!props.ticketId) return
  runAction(() => resumeTicket(props.ticketId!), '已恢复')
}

async function handleTransferBack() {
  if (!props.ticketId) return
  await ElMessageBox.confirm('确认将该工单转回接单池？转回后需要其他坐席重新接入。', '转回接单池', { type: 'warning' })
  runAction(() => transferTicket(props.ticketId!, {}), '已转回接单池')
}

async function handleTransferTo() {
  if (!props.ticketId) return
  const { value } = await ElMessageBox.prompt('转派给哪位坐席（填坐席标识）', '转派工单', {
    inputPattern: /\S+/,
    inputErrorMessage: '请输入坐席标识',
  }).catch(() => ({ value: undefined }))
  if (!value) return
  runAction(() => transferTicket(props.ticketId!, { toAgent: value }), '已转派')
}

async function handleResolve() {
  if (!props.ticketId) return
  const { value } = await ElMessageBox.prompt('请填写解决结论', '标记解决', {
    inputType: 'textarea',
    inputPattern: /\S+/,
    inputErrorMessage: '请输入解决结论',
  }).catch(() => ({ value: undefined }))
  if (!value) return
  runAction(() => resolveTicket(props.ticketId!, { note: value }), '已标记解决')
}

async function handleClose() {
  if (!props.ticketId) return
  const { value } = await ElMessageBox.prompt('请填写关闭原因', '关闭工单', {
    inputType: 'textarea',
    inputPattern: /\S+/,
    inputErrorMessage: '请输入关闭原因',
  }).catch(() => ({ value: undefined }))
  if (!value) return
  runAction(() => closeTicket(props.ticketId!, { reason: value }), '已关闭')
}

function handlePriorityChange(priority: TicketPriority) {
  if (!props.ticketId) return
  runAction(() => updateTicketPriority(props.ticketId!, priority), '优先级已更新')
}

function handleCategoryChange(category: TicketCategory) {
  if (!props.ticketId) return
  runAction(() => updateTicketCategory(props.ticketId!, category), '分类已更新')
}

const priorityOptions = Object.entries(PRIORITY_LABELS) as [TicketPriority, string][]
const categoryOptions = Object.entries(CATEGORY_LABELS) as [TicketCategory, string][]
</script>

<template>
  <el-drawer :model-value="visible" title="工单详情" size="900px" @update:model-value="(v: boolean) => emit('update:visible', v)" @close="closeDrawer">
    <div v-loading="detailLoading" class="panel">
      <div class="chat-column">
        <div ref="scrollRef" class="messages" @scroll="handleScroll">
          <div v-if="messagesLoading" class="loading-more">加载中…</div>
          <div v-for="msg in messages" :key="msg.id" class="message-row" :class="msg.senderType.toLowerCase()">
            <div v-if="msg.senderType === 'SYSTEM'" class="system-text">{{ msg.content }}</div>
            <div v-else class="bubble">
              <div v-if="msg.senderType === 'BOT'" class="bot-badge">机器人</div>
              <div class="content">{{ msg.content }}</div>
            </div>
          </div>
          <el-empty v-if="!messagesLoading && messages.length === 0" description="暂无消息" />
        </div>
        <div class="input-bar">
          <el-input
            v-model="input"
            type="textarea"
            :rows="2"
            :disabled="!canInput"
            :placeholder="canInput ? '输入回复内容，Enter 发送' : '仅本人负责的处理中/已挂起工单可回复'"
            @keyup.enter.exact.prevent="handleSend"
          />
          <el-button type="primary" :disabled="!canInput" :loading="sending" @click="handleSend">发送</el-button>
        </div>
      </div>

      <div class="info-column">
        <template v-if="detail">
          <el-descriptions :column="1" border size="small">
            <el-descriptions-item label="工单号">{{ detail.ticket.id }}</el-descriptions-item>
            <el-descriptions-item label="标题">{{ detail.ticket.title }}</el-descriptions-item>
            <el-descriptions-item label="用户">{{ detail.ticket.userId }}</el-descriptions-item>
            <el-descriptions-item label="状态">
              <el-tag :type="STATUS_TAG_TYPE[detail.ticket.status]" size="small">{{ STATUS_LABELS[detail.ticket.status] }}</el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="优先级">
              <el-select
                v-permission="'user-ticket:edit'"
                :model-value="detail.ticket.priority"
                size="small"
                :disabled="detail.ticket.status === 'CLOSED'"
                @change="handlePriorityChange"
              >
                <el-option v-for="[value, label] in priorityOptions" :key="value" :value="value" :label="label" />
              </el-select>
            </el-descriptions-item>
            <el-descriptions-item label="分类">
              <el-select
                v-permission="'user-ticket:edit'"
                :model-value="detail.ticket.category"
                size="small"
                :disabled="detail.ticket.status === 'CLOSED'"
                @change="handleCategoryChange"
              >
                <el-option v-for="[value, label] in categoryOptions" :key="value" :value="value" :label="label" />
              </el-select>
            </el-descriptions-item>
            <el-descriptions-item label="当前坐席">{{ detail.ticket.assignee || '-' }}</el-descriptions-item>
            <el-descriptions-item label="转人工原因">{{ detail.ticket.handoffReason || '-' }}</el-descriptions-item>
            <el-descriptions-item v-if="detail.ticket.resolveNote" label="解决结论">{{ detail.ticket.resolveNote }}</el-descriptions-item>
            <el-descriptions-item label="重开次数">{{ detail.ticket.reopenCount }}</el-descriptions-item>
          </el-descriptions>

          <div class="action-group">
            <el-button
              v-if="detail.ticket.status === 'WAITING_AGENT'"
              v-permission="'user-ticket:claim'"
              type="primary"
              size="small"
              :loading="acting"
              @click="handleClaim"
            >
              接入
            </el-button>
            <el-button
              v-if="detail.ticket.status === 'PROCESSING' && isSelfAssignee"
              v-permission="'user-ticket:edit'"
              size="small"
              :loading="acting"
              @click="handleHold"
            >
              挂起
            </el-button>
            <el-button
              v-if="detail.ticket.status === 'ON_HOLD' && isSelfAssignee"
              v-permission="'user-ticket:edit'"
              size="small"
              :loading="acting"
              @click="handleResume"
            >
              恢复
            </el-button>
            <el-button
              v-if="['PROCESSING', 'ON_HOLD'].includes(detail.ticket.status) && isSelfAssignee"
              v-permission="'user-ticket:transfer'"
              size="small"
              :loading="acting"
              @click="handleTransferBack"
            >
              转回接单池
            </el-button>
            <el-button
              v-if="['PROCESSING', 'ON_HOLD'].includes(detail.ticket.status) && isSelfAssignee"
              v-permission="'user-ticket:transfer'"
              size="small"
              :loading="acting"
              @click="handleTransferTo"
            >
              转派
            </el-button>
            <el-button
              v-if="['PROCESSING', 'ON_HOLD', 'WAITING_CONFIRM'].includes(detail.ticket.status) && isSelfAssignee"
              v-permission="'user-ticket:resolve'"
              type="success"
              size="small"
              :loading="acting"
              @click="handleResolve"
            >
              标记解决
            </el-button>
            <el-button
              v-if="detail.ticket.status !== 'CLOSED'"
              v-permission="'user-ticket:close'"
              type="danger"
              size="small"
              :loading="acting"
              @click="handleClose"
            >
              关闭
            </el-button>
          </div>

          <div class="timeline-title">状态时间线</div>
          <el-timeline>
            <el-timeline-item
              v-for="event in detail.events"
              :key="event.id"
              :timestamp="new Date(event.createdAtMs).toLocaleString()"
              size="normal"
            >
              <div>{{ event.eventType }}</div>
              <div v-if="event.fromStatus || event.toStatus" class="event-status">
                {{ event.fromStatus ? STATUS_LABELS[event.fromStatus] : '-' }} → {{ event.toStatus ? STATUS_LABELS[event.toStatus] : '-' }}
              </div>
              <div v-if="event.note" class="event-note">{{ event.note }}</div>
              <div class="event-actor">{{ SENDER_TYPE_LABELS[event.actorType] }}{{ event.actorId ? ` · ${event.actorId}` : '' }}</div>
            </el-timeline-item>
          </el-timeline>
        </template>
      </div>
    </div>
  </el-drawer>
</template>

<style scoped>
.panel {
  display: flex;
  gap: 16px;
  height: calc(100vh - 108px);
}

.chat-column {
  flex: 2;
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.messages {
  flex: 1;
  overflow-y: auto;
  padding: 8px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
}

.loading-more {
  text-align: center;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  padding: 4px;
}

.message-row {
  display: flex;
  margin-bottom: 12px;
}

.message-row.user,
.message-row.bot {
  justify-content: flex-start;
}

.message-row.agent {
  justify-content: flex-end;
}

.message-row.system {
  justify-content: center;
}

.bubble {
  max-width: 70%;
  padding: 8px 12px;
  border-radius: 8px;
  background: var(--el-fill-color-light);
  word-break: break-word;
}

.message-row.agent .bubble {
  background: var(--theme-primary, var(--el-color-primary));
  color: #fff;
}

.bot-badge {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-bottom: 2px;
}

.message-row.agent .bot-badge {
  color: rgba(255, 255, 255, 0.8);
}

.content {
  white-space: pre-wrap;
}

.system-text {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.input-bar {
  display: flex;
  gap: 8px;
  margin-top: 12px;
  align-items: flex-end;
}

.info-column {
  flex: 1;
  min-width: 320px;
  overflow-y: auto;
  padding-left: 4px;
}

.action-group {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin: 16px 0;
}

.timeline-title {
  font-weight: 600;
  margin: 12px 0 8px;
}

.event-status {
  font-size: 13px;
  color: var(--el-text-color-regular);
}

.event-note {
  font-size: 13px;
  color: var(--el-text-color-regular);
}

.event-actor {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
