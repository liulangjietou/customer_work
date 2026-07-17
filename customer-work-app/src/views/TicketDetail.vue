<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { showToast } from 'vant'
import { closeTicket, confirmTicket, fetchTicketDetail, reopenTicket, rejectTicket } from '@/api/ticket'
import type { Ticket, TicketEvent } from '@/types/api'
import { TICKET_STATUS_TAG_TYPE, TICKET_STATUS_TEXT } from '@/types/api'

const props = defineProps<{ id: string }>()
const router = useRouter()

const ticket = ref<Ticket | null>(null)
const events = ref<TicketEvent[]>([])
const loading = ref(true)

const rejectVisible = ref(false)
const reopenVisible = ref(false)
const reasonInput = ref('')
const acting = ref(false)

const ticketId = computed(() => props.id)

async function loadDetail() {
  loading.value = true
  try {
    const detail = await fetchTicketDetail(ticketId.value)
    ticket.value = detail.ticket
    events.value = detail.events
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
    <van-nav-bar title="工单详情" left-arrow @click-left="router.back()" />
    <div class="content">
      <van-loading v-if="loading" class="loading" vertical>加载中...</van-loading>
      <template v-else-if="ticket">
        <van-cell-group inset title="基本信息">
          <van-cell title="工单编号" :value="`#${ticket.id}`" />
          <van-cell title="标题" :value="ticket.title || '-'" />
          <van-cell title="状态">
            <template #value>
              <van-tag :type="TICKET_STATUS_TAG_TYPE[ticket.status]">{{ TICKET_STATUS_TEXT[ticket.status] }}</van-tag>
            </template>
          </van-cell>
          <van-cell title="分类" :value="ticket.category || '-'" />
          <van-cell title="优先级" :value="ticket.priority || '-'" />
          <van-cell title="处理人" :value="ticket.assignee || '-'" />
          <van-cell v-if="ticket.handoffReason" title="转人工原因" :value="ticket.handoffReason" />
          <van-cell v-if="ticket.resolveNote" title="处理说明" :value="ticket.resolveNote" />
          <van-cell title="重开次数" :value="String(ticket.reopenCount)" />
          <van-cell title="创建时间" :value="formatTime(ticket.createdAtMs)" />
          <van-cell title="更新时间" :value="formatTime(ticket.updatedAtMs)" />
        </van-cell-group>

        <div class="section-title">流转记录</div>
        <van-steps direction="vertical" :active="events.length">
          <van-step v-for="(event, index) in events" :key="index">
            <div class="event-type">{{ event.eventType }}</div>
            <div v-if="event.fromStatus || event.toStatus" class="event-status">
              {{ event.fromStatus ? TICKET_STATUS_TEXT[event.fromStatus] : '—' }}
              →
              {{ event.toStatus ? TICKET_STATUS_TEXT[event.toStatus] : '—' }}
            </div>
            <div v-if="event.note" class="event-note">{{ event.note }}</div>
            <div class="event-time">{{ formatTime(event.createdAtMs) }} · {{ event.actorType }}</div>
          </van-step>
        </van-steps>

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
  flex: 1;
  display: flex;
  flex-direction: column;
  background: #f7f8fa;
}

.content {
  flex: 1;
  overflow-y: auto;
  padding-bottom: 24px;
}

.loading {
  margin-top: 40vh;
}

.section-title {
  margin: 16px 16px 8px;
  font-size: 14px;
  color: #969799;
}

.event-type {
  font-weight: 500;
}

.event-status {
  font-size: 13px;
  color: #646566;
  margin-top: 2px;
}

.event-note {
  font-size: 13px;
  color: #646566;
  margin-top: 2px;
}

.event-time {
  font-size: 12px;
  color: #969799;
  margin-top: 2px;
}

.actions {
  margin: 20px 16px 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.dialog-field {
  padding: 16px;
}
</style>
