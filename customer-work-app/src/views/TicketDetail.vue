<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { showToast } from 'vant'
import { closeTicket, confirmTicket, fetchTicketDetail, reopenTicket, rejectTicket } from '@/api/ticket'
import type { Ticket, TicketEvent } from '@/types/api'
import {
  ticketActorTypeText,
  ticketCategoryText,
  ticketEventTypeText,
  ticketPriorityText,
  TICKET_STATUS_TAG_TYPE,
  TICKET_STATUS_TEXT,
} from '@/types/api'

const props = defineProps<{ id: string }>()
const router = useRouter()

const ticket = ref<Ticket | null>(null)
const events = ref<TicketEvent[]>([])
const loading = ref(true)
const loadError = ref('')

const rejectVisible = ref(false)
const reopenVisible = ref(false)
const reasonInput = ref('')
const acting = ref(false)

const ticketId = computed(() => props.id)

async function loadDetail() {
  loading.value = true
  loadError.value = ''
  try {
    const detail = await fetchTicketDetail(ticketId.value)
    ticket.value = detail.ticket
    events.value = detail.events
  } catch {
    ticket.value = null
    events.value = []
    loadError.value = '工单详情暂时无法加载，请稍后重试'
  } finally {
    loading.value = false
  }
}

onMounted(loadDetail)

function formatTime(ms: number) {
  return new Date(ms).toLocaleString('zh-CN', { hour12: false })
}

async function onConfirm() {
  acting.value = true
  try {
    await confirmTicket(ticketId.value)
    showToast('已确认解决')
    await loadDetail()
  } finally {
    acting.value = false
  }
}

function openReject() {
  reasonInput.value = ''
  rejectVisible.value = true
}

async function submitReject() {
  acting.value = true
  try {
    await rejectTicket(ticketId.value, reasonInput.value)
    showToast('已反馈仍有问题')
    rejectVisible.value = false
    await loadDetail()
  } finally {
    acting.value = false
  }
}

function openReopen() {
  reasonInput.value = ''
  reopenVisible.value = true
}

async function submitReopen() {
  acting.value = true
  try {
    await reopenTicket(ticketId.value, reasonInput.value)
    showToast('已重新打开')
    reopenVisible.value = false
    await loadDetail()
  } finally {
    acting.value = false
  }
}

async function onClose() {
  acting.value = true
  try {
    await closeTicket(ticketId.value)
    showToast('工单已关闭')
    await loadDetail()
  } finally {
    acting.value = false
  }
}

function goChat() {
  router.push('/chat')
}
</script>

<template>
  <div class="ticket-detail-page">
    <van-nav-bar title="工单详情" left-arrow safe-area-inset-top @click-left="router.back()" />
    <div class="content">
      <div v-if="loading" class="state-panel" role="status">
        <van-loading color="var(--cw-primary, #1677ff)" vertical>正在加载工单…</van-loading>
      </div>
      <div v-else-if="loadError" class="state-panel state-error">
        <div class="state-symbol" aria-hidden="true">!</div>
        <strong>工单没有加载成功</strong>
        <p>{{ loadError }}</p>
        <van-button round type="primary" size="small" @click="loadDetail">重新加载</van-button>
      </div>
      <template v-else-if="ticket">
        <section class="ticket-hero">
          <div class="ticket-hero-topline">
            <span>工单 #{{ ticket.id }}</span>
            <van-tag :type="TICKET_STATUS_TAG_TYPE[ticket.status]" plain size="medium">
              {{ TICKET_STATUS_TEXT[ticket.status] }}
            </van-tag>
          </div>
          <h1>{{ ticket.title || '服务工单' }}</h1>
          <p>最近更新于 {{ formatTime(ticket.updatedAtMs) }}</p>
          <div class="hero-meta">
            <span><small>分类</small>{{ ticket.category ? ticketCategoryText(ticket.category) : '未分类' }}</span>
            <span><small>优先级</small>{{ ticket.priority ? ticketPriorityText(ticket.priority) : '未设置' }}</span>
            <span><small>处理人</small>{{ ticket.assignee || '待分配' }}</span>
          </div>
        </section>

        <section class="detail-section">
          <div class="section-heading">
            <span class="section-kicker">SERVICE INFO</span>
            <h2>服务信息</h2>
          </div>
          <van-cell-group class="info-card" inset>
            <van-cell v-if="ticket.handoffReason" title="转人工原因" :value="ticket.handoffReason" />
            <van-cell v-if="ticket.resolveNote" title="处理说明" :value="ticket.resolveNote" />
            <van-cell title="重开次数" :value="`${ticket.reopenCount} 次`" />
            <van-cell title="创建时间" :value="formatTime(ticket.createdAtMs)" />
            <van-cell title="更新时间" :value="formatTime(ticket.updatedAtMs)" />
          </van-cell-group>
        </section>

        <section class="detail-section">
          <div class="section-heading">
            <span class="section-kicker">PROGRESS</span>
            <h2>流转记录</h2>
          </div>
          <div class="timeline-card">
            <van-steps v-if="events.length > 0" direction="vertical" :active="events.length" active-color="#1677ff">
              <van-step v-for="(event, index) in events" :key="index">
                <div class="event-type">{{ ticketEventTypeText(event.eventType) }}</div>
                <div v-if="event.fromStatus || event.toStatus" class="event-status">
                  {{ event.fromStatus ? TICKET_STATUS_TEXT[event.fromStatus] : '—' }}
                  <span aria-hidden="true">→</span>
                  {{ event.toStatus ? TICKET_STATUS_TEXT[event.toStatus] : '—' }}
                </div>
                <div v-if="event.note" class="event-note">{{ event.note }}</div>
                <div class="event-time">{{ formatTime(event.createdAtMs) }} · {{ ticketActorTypeText(event.actorType) }}</div>
              </van-step>
            </van-steps>
            <div v-else class="timeline-empty">暂无流转记录</div>
          </div>
        </section>

        <div class="actions">
          <template v-if="ticket.status === 'WAITING_CONFIRM'">
            <van-button type="primary" block round :loading="acting" @click="onConfirm">确认解决</van-button>
            <van-button type="default" block round :loading="acting" @click="openReject">仍有问题</van-button>
          </template>
          <template v-else-if="ticket.status === 'RESOLVED'">
            <van-button type="warning" block round :loading="acting" @click="openReopen">重新打开</van-button>
            <van-button type="default" block round :loading="acting" @click="onClose">关闭工单</van-button>
          </template>
          <template v-else-if="ticket.status === 'CLOSED'">
            <van-button type="warning" block round :loading="acting" @click="openReopen">重新打开</van-button>
          </template>
          <van-button type="primary" plain block round @click="goChat">继续对话</van-button>
        </div>
      </template>
    </div>

    <van-dialog v-model:show="rejectVisible" title="仍有问题" show-cancel-button @confirm="submitReject">
      <van-field v-model="reasonInput" type="textarea" rows="3" placeholder="请描述遗留问题" class="dialog-field" />
    </van-dialog>

    <van-dialog v-model:show="reopenVisible" title="重新打开工单" show-cancel-button @confirm="submitReopen">
      <van-field v-model="reasonInput" type="textarea" rows="3" placeholder="请描述重开原因" class="dialog-field" />
    </van-dialog>
  </div>
</template>

<style scoped>
.ticket-detail-page {
  display: flex;
  min-height: 100vh;
  min-height: 100dvh;
  flex-direction: column;
  background:
    radial-gradient(circle at 92% 3%, rgba(24, 119, 242, 0.1), transparent 25%),
    var(--cw-page-bg, #f4f7fb);
}

.content {
  flex: 1;
  overflow-y: auto;
  padding: 12px 12px calc(28px + env(safe-area-inset-bottom));
}

.state-panel {
  display: flex;
  min-height: 68vh;
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

.state-panel p {
  margin: 5px 0 16px;
  color: var(--cw-text-secondary, #718096);
  font-size: 13px;
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

.ticket-hero {
  position: relative;
  overflow: hidden;
  padding: 18px;
  border-radius: 22px;
  color: #fff;
  background:
    radial-gradient(circle at 85% 8%, rgba(91, 231, 192, 0.28), transparent 34%),
    linear-gradient(145deg, #14375f, #0b2543);
  box-shadow: 0 18px 38px rgba(12, 38, 70, 0.18);
}

.ticket-hero::after {
  position: absolute;
  right: -36px;
  bottom: -54px;
  width: 150px;
  height: 150px;
  border: 1px solid rgba(255, 255, 255, 0.12);
  border-radius: 50%;
  content: '';
}

.ticket-hero-topline {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  color: rgba(255, 255, 255, 0.72);
  font-size: 12px;
  letter-spacing: 0.4px;
}

.ticket-hero h1 {
  position: relative;
  z-index: 1;
  margin: 19px 0 5px;
  font-size: 22px;
  line-height: 1.35;
}

.ticket-hero > p {
  position: relative;
  z-index: 1;
  margin: 0;
  color: rgba(255, 255, 255, 0.62);
  font-size: 12px;
}

.hero-meta {
  position: relative;
  z-index: 1;
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
  margin-top: 20px;
}

.hero-meta span {
  min-width: 0;
  overflow: hidden;
  padding: 10px;
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: 13px;
  background: rgba(255, 255, 255, 0.07);
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.hero-meta small {
  display: block;
  margin-bottom: 4px;
  color: rgba(255, 255, 255, 0.55);
  font-size: 10px;
}

.detail-section {
  margin-top: 23px;
}

.section-heading {
  margin: 0 4px 10px;
}

.section-heading h2 {
  margin: 1px 0 0;
  color: var(--cw-text-primary, #13233a);
  font-size: 17px;
}

.section-kicker {
  color: var(--cw-primary, #1677ff);
  font-size: 9px;
  font-weight: 800;
  letter-spacing: 1.6px;
}

.info-card,
.timeline-card {
  overflow: hidden;
  border: 1px solid rgba(19, 35, 58, 0.06);
  border-radius: 18px;
  background: var(--cw-card-bg, #fff);
  box-shadow: 0 10px 28px rgba(21, 52, 92, 0.07);
}

.info-card {
  margin: 0;
}

.timeline-card {
  padding: 4px 10px;
}

.timeline-empty {
  padding: 32px 16px;
  color: var(--cw-text-secondary, #718096);
  font-size: 13px;
  text-align: center;
}

.event-type {
  color: var(--cw-text-primary, #13233a);
  font-size: 14px;
  font-weight: 700;
}

.event-status {
  font-size: 13px;
  color: var(--cw-primary, #1677ff);
  margin-top: 4px;
}

.event-note {
  font-size: 13px;
  color: var(--cw-text-secondary, #718096);
  margin-top: 5px;
  line-height: 1.5;
}

.event-time {
  font-size: 12px;
  color: #9aa6b6;
  margin-top: 6px;
}

.actions {
  margin-top: 22px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.actions .van-button {
  min-height: 46px;
}

.dialog-field {
  padding: 16px;
}

@media (max-width: 340px) {
  .hero-meta {
    grid-template-columns: 1fr 1fr;
  }

  .hero-meta span:last-child {
    grid-column: 1 / -1;
  }
}
</style>
