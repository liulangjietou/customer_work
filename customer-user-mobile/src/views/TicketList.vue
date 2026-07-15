<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { fetchTickets } from '@/api/ticket'
import type { Ticket } from '@/types/api'
import { TICKET_STATUS_TAG_TYPE, TICKET_STATUS_TEXT } from '@/types/api'

const PAGE_SIZE = 10

const router = useRouter()

const tickets = ref<Ticket[]>([])
const page = ref(1)
const loading = ref(false)
const finished = ref(false)
const refreshing = ref(false)

async function loadPage() {
  const result = await fetchTickets({ page: page.value, size: PAGE_SIZE })
  if (page.value === 1) {
    tickets.value = result.items
  } else {
    tickets.value = [...tickets.value, ...result.items]
  }
  finished.value = tickets.value.length >= result.total
  page.value += 1
}

async function onLoad() {
  try {
    await loadPage()
  } finally {
    loading.value = false
  }
}

async function onRefresh() {
  page.value = 1
  finished.value = false
  try {
    await loadPage()
  } finally {
    refreshing.value = false
  }
}

function goDetail(id: string) {
  router.push(`/tickets/${id}`)
}

function formatTime(ms: number) {
  return new Date(ms).toLocaleString('zh-CN', { hour12: false })
}
</script>

<template>
  <div class="ticket-list-page">
    <van-nav-bar title="我的工单" left-arrow @click-left="router.back()" />
    <van-pull-refresh v-model="refreshing" @refresh="onRefresh" class="refresh-area">
      <van-list v-model:loading="loading" :finished="finished" finished-text="没有更多了" @load="onLoad">
        <van-cell-group v-if="tickets.length" inset>
          <van-cell v-for="ticket in tickets" :key="ticket.id" clickable @click="goDetail(ticket.id)">
            <template #title>
              <span class="ticket-title">{{ ticket.title || `工单 #${ticket.id}` }}</span>
            </template>
            <template #label>
              <div class="ticket-meta">{{ formatTime(ticket.createdAtMs) }}</div>
            </template>
            <template #value>
              <van-tag :type="TICKET_STATUS_TAG_TYPE[ticket.status]">{{ TICKET_STATUS_TEXT[ticket.status] }}</van-tag>
            </template>
          </van-cell>
        </van-cell-group>
        <van-empty v-else-if="finished" description="暂无工单" />
      </van-list>
    </van-pull-refresh>
  </div>
</template>

<style scoped>
.ticket-list-page {
  flex: 1;
  display: flex;
  flex-direction: column;
  background: #f7f8fa;
}

.refresh-area {
  flex: 1;
  overflow-y: auto;
  padding-top: 12px;
}

.ticket-title {
  font-weight: 500;
}

.ticket-meta {
  font-size: 12px;
  color: #969799;
  margin-top: 4px;
}
</style>
