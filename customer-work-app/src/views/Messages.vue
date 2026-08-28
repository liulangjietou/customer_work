<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { showToast } from 'vant'
import { createSession, fetchTickets } from '@/api/ticket'
import type { Ticket, TicketStatus } from '@/types/api'
import { isTicketEnded, ticketCategoryText, TICKET_STATUS_TEXT } from '@/types/api'
import AppTabbar from '@/components/AppTabbar.vue'

const PAGE_SIZE = 10

const router = useRouter()

const tickets = ref<Ticket[]>([])
const total = ref(0)
const page = ref(1)
const initialLoading = ref(true)
const loading = ref(false)
const finished = ref(false)
const refreshing = ref(false)
const loadError = ref(false)
const loadMoreError = ref(false)
const creating = ref(false)
let requestGeneration = 0

const listSummary = computed(() => {
  if (initialLoading.value) {
    return '同步中'
  }
  if (loadError.value) {
    return '获取失败'
  }
  return `${total.value} 条`
})

async function loadPage(currentPage: number, generation: number) {
  const result = await fetchTickets({ page: currentPage, size: PAGE_SIZE })
  // 下拉刷新会推进 generation，使刷新前尚未返回的分页请求失效，避免旧页污染新列表。
  if (generation !== requestGeneration) {
    return
  }
  tickets.value = currentPage === 1 ? result.items : [...tickets.value, ...result.items]
  total.value = result.total
  finished.value = tickets.value.length >= result.total
  page.value = currentPage + 1
}

async function loadInitial() {
  const generation = ++requestGeneration
  initialLoading.value = true
  loadError.value = false
  loadMoreError.value = false
  page.value = 1
  finished.value = false
  try {
    await loadPage(1, generation)
  } catch {
    if (generation === requestGeneration) {
      loadError.value = true
      finished.value = true
    }
  } finally {
    if (generation === requestGeneration) {
      initialLoading.value = false
    }
  }
}

async function onLoad() {
  if (initialLoading.value || refreshing.value || loadError.value || finished.value) {
    loading.value = false
    return
  }

  loadMoreError.value = false
  const generation = requestGeneration
  try {
    await loadPage(page.value, generation)
  } catch {
    // 停止 Vant 的触底自动重试，交给用户显式重试，避免弱网下连续请求。
    if (generation === requestGeneration) {
      loadMoreError.value = true
      finished.value = true
    }
  } finally {
    loading.value = false
  }
}

async function retryLoadMore() {
  loadMoreError.value = false
  finished.value = false
  loading.value = true
  await onLoad()
}

async function onRefresh() {
  const generation = ++requestGeneration
  const previousPage = page.value
  const previousFinished = finished.value
  const previousTotal = total.value
  const previousLoadMoreError = loadMoreError.value

  page.value = 1
  finished.value = false
  loadError.value = false
  loadMoreError.value = false
  // 中止 Vant List 的加载态；其迟到响应会被 generation 丢弃。
  loading.value = false
  try {
    await loadPage(1, generation)
  } catch {
    // 刷新失败时保留屏幕上已有的会话，避免一次网络抖动把可用内容清空。
    if (generation === requestGeneration) {
      page.value = previousPage
      finished.value = previousFinished
      total.value = previousTotal
      loadMoreError.value = previousLoadMoreError
      if (tickets.value.length === 0) {
        loadError.value = true
        finished.value = true
      }
    }
  } finally {
    if (generation === requestGeneration) {
      refreshing.value = false
      initialLoading.value = false
    }
  }
}

onMounted(loadInitial)

function goChat(ticket: Ticket) {
  router.push({ path: '/chat', query: { ticketId: ticket.id } })
}

// 发起新会话：用户已有进行中会话时 createSession 内部已吸收 409 并直接返回该会话，这里统一走进聊天页。
async function onCreateSession() {
  if (creating.value) {
    return
  }

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

function ticketStatusClass(status: TicketStatus) {
  if (status === 'PROCESSING') {
    return 'status-pill--success'
  }
  if (status === 'WAITING_AGENT' || status === 'WAITING_CONFIRM') {
    return 'status-pill--warning'
  }
  if (status === 'ON_HOLD' || status === 'RESOLVED' || status === 'CLOSED') {
    return 'status-pill--muted'
  }
  return 'status-pill--primary'
}

function formatTime(ms: number) {
  const date = new Date(ms)
  if (Number.isNaN(date.getTime())) {
    return '—'
  }

  const now = new Date()
  const startOfToday = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime()
  const startOfDate = new Date(date.getFullYear(), date.getMonth(), date.getDate()).getTime()
  const time = date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', hour12: false })

  if (startOfDate === startOfToday) {
    return `今天 ${time}`
  }
  if (startOfDate === startOfToday - 24 * 60 * 60 * 1000) {
    return `昨天 ${time}`
  }
  if (date.getFullYear() === now.getFullYear()) {
    const month = String(date.getMonth() + 1).padStart(2, '0')
    const day = String(date.getDate()).padStart(2, '0')
    return `${month}-${day} ${time}`
  }
  return date.toLocaleDateString('zh-CN')
}
</script>

<template>
  <div class="messages-page">
    <header class="root-header">
      <div>
        <span class="header-eyebrow">客服中心</span>
        <h1>消息</h1>
      </div>
      <span class="service-presence"><i aria-hidden="true"></i>智能服务</span>
    </header>

    <van-pull-refresh
      v-model="refreshing"
      class="refresh-area"
      :disabled="initialLoading || loading"
      @refresh="onRefresh"
    >
      <main class="page-content">
        <section class="service-hero" aria-labelledby="service-hero-title">
          <span class="assistant-mark" aria-hidden="true">
            <span class="assistant-glyph"><i></i></span>
          </span>
          <div class="service-copy">
            <small>智能客服</small>
            <h2 id="service-hero-title">需要帮助？我们现在就开始。</h2>
            <p>新会话会自动延续您的服务记录。</p>
          </div>
          <button
            class="hero-action"
            type="button"
            :disabled="creating"
            :aria-label="creating ? '正在发起新会话' : '发起新会话'"
            @click="onCreateSession"
          >
            <van-loading v-if="creating" color="#fff" size="18" />
            <van-icon v-else name="plus" size="21" />
          </button>
        </section>

        <section class="conversation-section" aria-labelledby="conversation-heading">
          <div class="section-heading">
            <h2 id="conversation-heading">最近会话</h2>
            <span>{{ listSummary }}</span>
          </div>

          <div v-if="initialLoading" class="skeleton-list" aria-label="会话加载中" aria-busy="true">
            <div v-for="index in 3" :key="index" class="skeleton-card">
              <span class="skeleton-avatar"></span>
              <span class="skeleton-lines"><i></i><i></i></span>
              <span class="skeleton-aside"></span>
            </div>
          </div>

          <div v-else-if="loadError && tickets.length === 0" class="state-panel" role="alert">
            <span class="state-visual"><van-icon name="chat-o" size="34" /></span>
            <h3>会话加载失败</h3>
            <p>网络似乎不太稳定，请重新加载。</p>
            <button class="primary-button" type="button" @click="loadInitial">重新加载</button>
          </div>

          <div v-else-if="tickets.length === 0" class="state-panel">
            <span class="state-visual"><van-icon name="chat-o" size="34" /></span>
            <h3>还没有会话</h3>
            <p>发起一次新会话，服务记录会保存在这里。</p>
            <button class="primary-button" type="button" :disabled="creating" @click="onCreateSession">
              <van-loading v-if="creating" color="#fff" size="16" />
              <van-icon v-else name="plus" />
              <span>{{ creating ? '正在发起' : '发起新会话' }}</span>
            </button>
          </div>

          <van-list
            v-else
            v-model:loading="loading"
            :finished="finished"
            finished-text="没有更多会话了"
            @load="onLoad"
          >
            <div class="conversation-list">
              <button
                v-for="ticket in tickets"
                :key="ticket.id"
                class="conversation-card"
                :class="{ 'conversation-card--ended': isTicketEnded(ticket.status) }"
                type="button"
                @click="goChat(ticket)"
              >
                <span class="conversation-symbol" aria-hidden="true"><van-icon name="chat-o" size="21" /></span>
                <span class="card-main">
                  <strong class="card-title">{{ ticket.title || `会话 #${ticket.id}` }}</strong>
                  <span class="card-meta">
                    <template v-if="ticket.category">
                      <span>{{ ticketCategoryText(ticket.category) }}</span>
                      <i></i>
                    </template>
                    <span>会话 #{{ ticket.id }}</span>
                  </span>
                </span>
                <span class="card-aside">
                  <span class="time-text">{{ formatTime(ticket.updatedAtMs) }}</span>
                  <span class="status-pill" :class="ticketStatusClass(ticket.status)">
                    {{ TICKET_STATUS_TEXT[ticket.status] }}
                  </span>
                </span>
              </button>
            </div>

            <template #finished>
              <p v-if="loadMoreError" class="load-more-error">
                更多会话加载失败
                <button type="button" @click="retryLoadMore">重试</button>
              </p>
              <p v-else-if="tickets.length > PAGE_SIZE" class="list-finished">没有更多会话了</p>
            </template>
          </van-list>
        </section>
      </main>
    </van-pull-refresh>

    <AppTabbar />
  </div>
</template>

<style scoped>
.messages-page {
  --page-ink: var(--cw-ink, #142033);
  --page-ink-soft: var(--cw-ink-soft, #526078);
  --page-primary: var(--cw-primary, #316cff);
  --page-primary-dark: var(--cw-primary-dark, #1748bf);
  --page-primary-soft: var(--cw-primary-soft, #eaf1ff);
  --page-success: var(--cw-success, #19a995);
  --page-success-soft: var(--cw-success-soft, #e5f8f3);
  --page-line: var(--cw-line, #e6ebf2);
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  background: var(--cw-page-bg, #f4f7fb);
  color: var(--page-ink);
}

button {
  font: inherit;
}

button:focus-visible {
  outline: 3px solid rgba(49, 108, 255, 0.26);
  outline-offset: 2px;
}

.root-header {
  min-height: calc(77px + env(safe-area-inset-top));
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: calc(14px + env(safe-area-inset-top)) 17px 10px;
  background: rgba(255, 255, 255, 0.96);
  border-bottom: 1px solid rgba(230, 235, 242, 0.76);
}

.header-eyebrow {
  display: block;
  margin-bottom: 2px;
  color: #8290a5;
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.12em;
}

.root-header h1 {
  margin: 0;
  font-size: 23px;
  line-height: 1.2;
  letter-spacing: -0.025em;
}

.service-presence {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  min-height: 30px;
  padding: 6px 10px;
  border-radius: 999px;
  background: var(--page-success-soft);
  color: #117d6f;
  font-size: 11px;
  font-weight: 700;
}

.service-presence i {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--page-success);
  box-shadow: 0 0 0 4px rgba(25, 169, 149, 0.12);
}

.refresh-area {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
}

.page-content {
  min-height: 100%;
  padding: 13px 14px var(--cw-tabbar-space, 100px);
}

.service-hero {
  position: relative;
  overflow: hidden;
  min-height: 139px;
  display: flex;
  align-items: center;
  gap: 15px;
  padding: 20px;
  border-radius: 24px;
  background: var(--page-ink);
  color: #fff;
  box-shadow: 0 16px 34px rgba(20, 32, 51, 0.16);
}

.service-hero::after {
  content: '';
  position: absolute;
  width: 152px;
  height: 152px;
  right: -67px;
  top: -77px;
  border: 1px solid rgba(255, 255, 255, 0.16);
  border-radius: 50%;
  box-shadow: 0 0 0 25px rgba(255, 255, 255, 0.035);
}

.assistant-mark {
  position: relative;
  z-index: 1;
  width: 54px;
  height: 54px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 auto;
  border-radius: 18px;
  background: var(--page-primary);
  color: #fff;
  box-shadow: 0 10px 23px rgba(49, 108, 255, 0.33);
}

.assistant-glyph {
  position: relative;
  width: 27px;
  height: 21px;
  border: 2px solid currentColor;
  border-radius: 11px 11px 9px 9px;
}

.assistant-glyph::before,
.assistant-glyph::after {
  content: '';
  position: absolute;
  top: 7px;
  width: 4px;
  height: 4px;
  border-radius: 50%;
  background: currentColor;
}

.assistant-glyph::before {
  left: 5px;
}

.assistant-glyph::after {
  right: 5px;
}

.assistant-glyph i {
  position: absolute;
  left: 50%;
  bottom: -7px;
  width: 8px;
  height: 8px;
  border-left: 2px solid currentColor;
  transform: skew(-24deg) translateX(-50%);
}

.service-copy {
  position: relative;
  z-index: 1;
  min-width: 0;
  flex: 1;
}

.service-copy small {
  display: block;
  margin-bottom: 5px;
  color: rgba(255, 255, 255, 0.68);
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.08em;
}

.service-copy h2 {
  margin: 0;
  font-size: 19px;
  line-height: 1.28;
  letter-spacing: -0.025em;
}

.service-copy p {
  margin: 6px 0 0;
  color: rgba(255, 255, 255, 0.73);
  font-size: 11px;
  line-height: 1.5;
}

.hero-action {
  position: relative;
  z-index: 2;
  width: 45px;
  height: 45px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 auto;
  border: 1px solid rgba(255, 255, 255, 0.34);
  border-radius: 15px;
  background: rgba(255, 255, 255, 0.13);
  color: #fff;
  backdrop-filter: blur(8px);
}

.hero-action:disabled,
.primary-button:disabled {
  cursor: not-allowed;
  opacity: 0.64;
}

.conversation-section {
  margin-top: 23px;
}

.section-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 0 3px 11px;
}

.section-heading h2 {
  margin: 0;
  font-size: 16px;
  letter-spacing: -0.02em;
}

.section-heading > span {
  color: #7d899c;
  font-family: 'DIN Alternate', 'SF Pro Display', 'PingFang SC', sans-serif;
  font-size: 11px;
}

.conversation-list,
.skeleton-list {
  display: grid;
  gap: 10px;
}

.conversation-card,
.skeleton-card {
  width: 100%;
  min-height: 84px;
  display: grid;
  grid-template-columns: 42px minmax(0, 1fr) auto;
  align-items: center;
  gap: 12px;
  padding: 13px 14px;
  border: 1px solid rgba(230, 235, 242, 0.9);
  border-radius: 18px;
  background: #fff;
  box-shadow: 0 7px 22px rgba(37, 55, 85, 0.055);
}

.conversation-card {
  color: inherit;
  text-align: left;
  cursor: pointer;
  transition: transform 150ms ease, box-shadow 150ms ease;
}

.conversation-card:active {
  transform: scale(0.988);
  box-shadow: 0 3px 12px rgba(37, 55, 85, 0.06);
}

.conversation-symbol {
  position: relative;
  width: 42px;
  height: 42px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 15px;
  background: var(--page-primary-soft);
  color: var(--page-primary);
}

.conversation-symbol::after {
  content: '';
  position: absolute;
  right: 1px;
  bottom: 1px;
  width: 9px;
  height: 9px;
  border: 2px solid #fff;
  border-radius: 50%;
  background: var(--page-success);
}

.conversation-card--ended .conversation-symbol {
  background: #f0f2f5;
  color: #7d899c;
}

.conversation-card--ended .conversation-symbol::after {
  background: #a7b0bf;
}

.card-main {
  min-width: 0;
}

.card-title {
  display: block;
  overflow: hidden;
  color: var(--page-ink);
  font-size: 14px;
  font-weight: 700;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.card-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  margin-top: 7px;
  overflow: hidden;
  color: #59687c;
  font-size: 12px;
  white-space: nowrap;
}

.card-meta span {
  overflow: hidden;
  text-overflow: ellipsis;
}

.card-meta i {
  width: 3px;
  height: 3px;
  flex: 0 0 auto;
  border-radius: 50%;
  background: #b8c0cd;
}

.card-aside {
  display: grid;
  justify-items: end;
  gap: 8px;
}

.time-text {
  color: #59687c;
  font-family: 'DIN Alternate', 'SF Pro Display', 'PingFang SC', sans-serif;
  font-size: 12px;
  white-space: nowrap;
}

.status-pill {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  min-height: 24px;
  padding: 4px 8px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 700;
  white-space: nowrap;
}

.status-pill::before {
  content: '';
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: currentColor;
}

.status-pill--primary {
  background: var(--page-primary-soft);
  color: var(--page-primary-dark);
}

.status-pill--success {
  background: var(--page-success-soft);
  color: #117d6f;
}

.status-pill--warning {
  background: #fff4df;
  color: #a86300;
}

.status-pill--muted {
  background: #f0f2f5;
  color: #59687c;
}

.state-panel {
  min-height: 282px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 28px 22px;
  text-align: center;
}

.state-visual {
  width: 82px;
  height: 82px;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 18px;
  border-radius: 28px;
  background: var(--page-primary-soft);
  color: var(--page-primary);
  transform: rotate(-4deg);
}

.state-panel h3 {
  margin: 0;
  font-size: 17px;
}

.state-panel p {
  max-width: 250px;
  margin: 8px 0 18px;
  color: #7d899c;
  font-size: 12px;
  line-height: 1.65;
}

.primary-button {
  min-height: 42px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  padding: 10px 18px;
  border: 0;
  border-radius: 14px;
  background: var(--page-primary);
  color: #fff;
  font-size: 13px;
  font-weight: 700;
  box-shadow: 0 9px 20px rgba(49, 108, 255, 0.2);
}

.skeleton-card {
  grid-template-columns: 42px minmax(0, 1fr) 64px;
}

.skeleton-avatar,
.skeleton-lines i,
.skeleton-aside {
  display: block;
  border-radius: 999px;
  background: linear-gradient(90deg, #eef1f5 25%, #f7f8fa 50%, #eef1f5 75%);
  background-size: 200% 100%;
  animation: skeleton 1.35s ease-in-out infinite;
}

.skeleton-avatar {
  width: 42px;
  height: 42px;
  border-radius: 15px;
}

.skeleton-lines {
  display: grid;
  gap: 10px;
}

.skeleton-lines i:first-child {
  width: 74%;
  height: 12px;
}

.skeleton-lines i:last-child {
  width: 48%;
  height: 9px;
}

.skeleton-aside {
  width: 64px;
  height: 23px;
}

.load-more-error,
.list-finished {
  margin: 15px 0 2px;
  color: #657388;
  font-size: 12px;
  text-align: center;
}

.load-more-error button {
  min-width: 44px;
  min-height: 44px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  margin-left: 5px;
  padding: 0 8px;
  border: 0;
  background: transparent;
  color: var(--page-primary);
  font-weight: 700;
}

@keyframes skeleton {
  to {
    background-position: -200% 0;
  }
}

@media (max-width: 350px) {
  .service-hero {
    gap: 11px;
    padding: 17px;
  }

  .assistant-mark {
    width: 48px;
    height: 48px;
  }

  .service-copy h2 {
    font-size: 17px;
  }

  .service-copy p {
    display: none;
  }

  .conversation-card {
    grid-template-columns: 38px minmax(0, 1fr);
  }

  .conversation-symbol {
    width: 38px;
    height: 38px;
  }

  .card-aside {
    grid-column: 2;
    grid-row: 2;
    display: flex;
    align-items: center;
    justify-content: space-between;
    width: 100%;
    margin-top: -5px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .conversation-card,
  .skeleton-avatar,
  .skeleton-lines i,
  .skeleton-aside {
    animation: none;
    transition: none;
  }
}
</style>
