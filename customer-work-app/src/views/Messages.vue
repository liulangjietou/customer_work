<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { showToast } from 'vant'
import { createSession, fetchTickets } from '@/api/ticket'
import type { Ticket } from '@/types/api'
import { isTicketEnded } from '@/types/api'
import AppTabbar from '@/components/AppTabbar.vue'

const PAGE_SIZE = 10

const router = useRouter()

const tickets = ref<Ticket[]>([])
const page = ref(1)
const loading = ref(false)
const finished = ref(false)
const refreshing = ref(false)
const creating = ref(false)

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

function goChat(ticket: Ticket) {
  router.push({ path: '/chat', query: { ticketId: ticket.id } })
}

// 发起新会话：用户已有进行中会话时 createSession 内部已吸收 409 并直接返回该会话，这里统一走进聊天页
async function onCreateSession() {
  creating.value = true
  try {
    const result = await createSession()
    if (!result.conflict) {
      showToast('已创建新会话')
    }
    router.push({ path: '/chat', query: { ticketId: result.ticketId } })
  } finally {
    creating.value = false
  }
}

function formatTime(ms: number) {
  return new Date(ms).toLocaleString('zh-CN', { hour12: false })
}
</script>

<template>
  <div class="messages-page">
    <van-nav-bar title="消息" />
    <van-pull-refresh v-model="refreshing" @refresh="onRefresh" class="refresh-area">
      <van-list v-model:loading="loading" :finished="finished" finished-text="没有更多了" @load="onLoad">
        <van-cell-group v-if="tickets.length" inset>
          <van-cell v-for="ticket in tickets" :key="ticket.id" clickable @click="goChat(ticket)">
            <template #title>
              <span class="conversation-title">{{ ticket.title || `会话 #${ticket.id}` }}</span>
            </template>
            <template #label>
              <div class="conversation-meta">{{ formatTime(ticket.updatedAtMs) }}</div>
            </template>
            <template #value>
              <van-tag :type="isTicketEnded(ticket.status) ? 'default' : 'primary'">
                {{ isTicketEnded(ticket.status) ? '已结束' : '进行中' }}
              </van-tag>
            </template>
          </van-cell>
        </van-cell-group>
        <van-empty v-else-if="finished" description="暂无会话" />
      </van-list>
    </van-pull-refresh>

    <button class="fab" :disabled="creating" @click="onCreateSession">
      <van-icon name="plus" size="18" />
      发起新会话
    </button>

    <AppTabbar />
  </div>
</template>

<style scoped>
.messages-page {
  flex: 1;
  display: flex;
  flex-direction: column;
  background: var(--cw-page-bg);
  position: relative;
}

.refresh-area {
  flex: 1;
  overflow-y: auto;
  padding-top: 12px;
  /* 底部预留 tabbar 高度，防止最后一条被固定导航遮挡 */
  padding-bottom: 66px;
}

.conversation-title {
  font-weight: 500;
  color: var(--cw-text-primary);
}

.conversation-meta {
  font-size: 12px;
  color: var(--cw-text-secondary);
  margin-top: 4px;
}

.fab {
  /* absolute 而非 fixed：van-tabbar 用 fixed+width:100% 已相对整个视口铺满（忽略 480px 居中壳层），
     这里用 absolute 相对 .messages-page（shell 内 flex:1 子项，min-height 100vh 保证撑满视口）定位，
     宽屏桌面浏览器验证时按钮才会落在居中的 480px 列内，而不是贴到真实视口右边缘。 */
  position: absolute;
  right: 20px;
  bottom: 78px;
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 10px 18px;
  border: none;
  border-radius: 999px;
  color: #fff;
  font-size: 14px;
  background: var(--cw-gradient-brand);
  box-shadow: 0 4px 14px rgba(25, 137, 250, 0.35);
}

.fab:disabled {
  opacity: 0.6;
}
</style>
