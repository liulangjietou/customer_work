<script setup lang="ts">
import { computed, onActivated, onBeforeUnmount, onMounted, ref } from 'vue'
import { listChatSessions } from '@/api/chat'
import {
  filterLoadedChatSessions,
  formatChatHistoryTime,
  groupChatHistorySessions,
} from '@/utils/chatHistoryPresentation'
import AddToProjectDialog from './AddToProjectDialog.vue'
import type { ChatSessionSummary, LiveSession } from '@/types/api'

/** liveSessions：父面板内存里"活着"的会话（进行中/本次加载过的），默认空数组兼容尚未接入的调用方。 */
const props = withDefaults(
  defineProps<{
    agentCode: string
    activeSessionId: string
    liveSessions?: LiveSession[]
    collapsible?: boolean
  }>(),
  { liveSessions: () => [], collapsible: true },
)
const emit = defineEmits<{
  select: [sessionId: string]
  /** 首次加载完成后把排序第一的会话交给父面板决定是否恢复；手动刷新不会重复触发。 */
  initialSession: [sessionId: string]
  /** 只通知父面板视觉收起；组件自身保持挂载，避免丢失列表与分页状态。 */
  collapse: []
}>()

/** 每页条数，与后端 ChatController#sessions 的 size 默认值保持一致。 */
const PAGE_SIZE = 20

const sessions = ref<ChatSessionSummary[]>([])
const page = ref(0)
const total = ref(0)
const loading = ref(false) // 首页加载
const loadingMore = ref(false) // 滚动追加下一页
const searchQuery = ref('')
const currentDate = ref(new Date())
let midnightRefreshTimer: ReturnType<typeof setTimeout> | null = null

// 以"已消费页数"判断到底，而非累积条数——空上下文会话在后端被跳过，累积条数可能永远够不到 total。
const noMore = computed(() => page.value > 0 && page.value * PAGE_SIZE >= total.value)

/** 侧边栏展示项：在后端已落库列表基础上叠加内存 streaming 标记。 */
interface DisplaySession {
  sessionId: string
  preview: string
  messageCount: number
  lastMessageTime: string | null
  streaming: boolean
}

/**
 * 合并后端已落库列表与内存活跃会话：
 * - 进行中的会话（含第一轮还没落库的新会话）后端可能拉不到，用 liveSessions 补进来，保证「找得到」；
 * - 两边都有的同一 sessionId 以后端摘要为准（内容更完整），只把内存的 streaming 标记叠加上去；
 * - 进行中的会话统一置顶，其余保持后端返回的时间倒序。
 */
const displaySessions = computed<DisplaySession[]>(() => {
  const liveById = new Map(props.liveSessions.map((s) => [s.sessionId, s]))
  const merged = new Map<string, DisplaySession>()
  for (const s of sessions.value) {
    const live = liveById.get(s.sessionId)
    merged.set(s.sessionId, {
      sessionId: s.sessionId,
      preview: s.preview,
      messageCount: s.messageCount,
      lastMessageTime: s.lastMessageTime,
      streaming: live?.streaming ?? false,
    })
  }
  // 后端还没有的内存会话（进行中的新会话）补进列表
  for (const live of props.liveSessions) {
    if (!merged.has(live.sessionId)) {
      merged.set(live.sessionId, {
        sessionId: live.sessionId,
        preview: live.preview,
        messageCount: live.messageCount,
        lastMessageTime: null,
        streaming: live.streaming,
      })
    }
  }
  const list = Array.from(merged.values())
  // 进行中置顶；同为进行中/同为非进行中时维持既有顺序（后端已按时间倒序，内存补充项排其后）
  return list.sort((a, b) => Number(b.streaming) - Number(a.streaming))
})

/** 搜索严格限定在已加载的合并列表，不触发请求、不改变分页游标。 */
const hasSearchQuery = computed(() => searchQuery.value.trim().length > 0)
const filteredSessions = computed(() =>
  filterLoadedChatSessions(displaySessions.value, searchQuery.value),
)
const groupedSessions = computed(() =>
  groupChatHistorySessions(filteredSessions.value, currentDate.value),
)

/** keep-alive 页面跨午夜后重新计算“今天”分组；定时器按下一个本地午夜重排，兼容夏令时。 */
function scheduleMidnightRefresh() {
  if (midnightRefreshTimer) {
    clearTimeout(midnightRefreshTimer)
  }
  const now = new Date()
  currentDate.value = now
  const nextMidnight = new Date(now)
  nextMidnight.setHours(24, 0, 0, 0)
  midnightRefreshTimer = setTimeout(
    scheduleMidnightRefresh,
    Math.max(1000, nextMidnight.getTime() - now.getTime()),
  )
}

/** 拉取指定页并去重合并到累积列表（同 sessionId 以已有为准，防加载间隙新会话导致的页间重复）。 */
async function loadPage(target: number) {
  const result = await listChatSessions(props.agentCode, target, PAGE_SIZE)
  total.value = result.total
  page.value = target
  const seen = new Set(sessions.value.map((s) => s.sessionId))
  for (const s of result.list) {
    if (!seen.has(s.sessionId)) {
      sessions.value.push(s)
      seen.add(s.sessionId)
    }
  }
}

/** 刷新：重置分页状态后重新拉第一页（「刷新」按钮与 defineExpose 的 refresh 同一语义）。 */
async function refresh() {
  loading.value = true
  try {
    sessions.value = []
    page.value = 0
    total.value = 0
    await loadPage(1)
  } catch (error) {
    ElMessage.error('历史会话加载失败：' + (error instanceof Error ? error.message : String(error)))
  } finally {
    loading.value = false
  }
}

/** 滚动到底触发：追加下一页（首页加载中 / 追加中 / 已到底时由 infinite-scroll-disabled 拦截，这里再兜一层）。 */
async function loadMore() {
  if (loading.value || loadingMore.value || noMore.value) {
    return
  }
  loadingMore.value = true
  try {
    await loadPage(page.value + 1)
  } catch (error) {
    ElMessage.error('历史会话加载失败：' + (error instanceof Error ? error.message : String(error)))
  } finally {
    loadingMore.value = false
  }
}

/**
 * 初次挂载与用户手动刷新语义不同：只有初次挂载需要尝试恢复最近会话，后续刷新只更新列表，
 * 避免用户已点“新建会话”时被后台历史刷新意外切走。
 */
async function initialize() {
  await refresh()
  const first = displaySessions.value[0]
  if (first) {
    emit('initialSession', first.sessionId)
  }
}

onMounted(() => {
  scheduleMidnightRefresh()
  initialize()
})
onActivated(scheduleMidnightRefresh)
onBeforeUnmount(() => {
  if (midnightRefreshTimer) {
    clearTimeout(midnightRefreshTimer)
    midnightRefreshTimer = null
  }
})

defineExpose({ refresh })

const addToProjectVisible = ref(false)
const addToProjectSessionId = ref('')

function openAddToProject(sessionId: string) {
  addToProjectSessionId.value = sessionId
  addToProjectVisible.value = true
}
</script>

<template>
  <div class="history-sidebar">
    <div class="history-header">
      <div class="history-title">
        <h2>历史会话</h2>
        <span :title="`已加载 ${displaySessions.length} 条会话`">{{ displaySessions.length }}</span>
      </div>
      <div class="history-actions">
        <button
          type="button"
          class="mini-action"
          :disabled="loading"
          title="刷新历史会话"
          aria-label="刷新历史会话"
          @click="refresh"
        >
          <el-icon :class="{ 'is-loading': loading }"><RefreshRight /></el-icon>
        </button>
        <button
          type="button"
          class="mini-action"
          v-if="collapsible"
          title="收起历史会话"
          aria-label="收起历史会话"
          @click="emit('collapse')"
        >
          <el-icon><ArrowLeft /></el-icon>
        </button>
      </div>
    </div>

    <label class="history-search">
      <el-icon aria-hidden="true"><Search /></el-icon>
      <input
        v-model="searchQuery"
        type="search"
        placeholder="搜索已加载会话"
        aria-label="搜索已加载会话"
        autocomplete="off"
      />
    </label>

    <el-empty
      v-if="!loading && displaySessions.length === 0"
      description="暂无历史会话"
      :image-size="48"
    />
    <div
      v-else
      class="session-scroll"
      v-infinite-scroll="loadMore"
      :infinite-scroll-disabled="loading || loadingMore || noMore || hasSearchQuery"
      :infinite-scroll-immediate="false"
      :infinite-scroll-distance="10"
    >
      <div v-if="loading && displaySessions.length === 0" class="load-status">加载中…</div>
      <div v-else-if="groupedSessions.length === 0" class="search-empty">
        <span>当前已加载的会话中无匹配结果</span>
        <button type="button" @click="searchQuery = ''">清空搜索</button>
      </div>
      <template v-else>
        <section v-for="group in groupedSessions" :key="group.key" class="session-group">
          <h3 class="date-label">{{ group.label }}</h3>
          <ul class="session-list">
            <li
              v-for="session in group.sessions"
              :key="session.sessionId"
              class="session-item"
              :class="{ active: session.sessionId === activeSessionId }"
            >
              <button
                type="button"
                class="session-select"
                :aria-current="session.sessionId === activeSessionId ? 'true' : undefined"
                @click="emit('select', session.sessionId)"
              >
                <span class="session-main">
                  <span class="session-preview">
                    <span v-if="session.streaming" class="streaming-dot" />
                    {{ session.preview || '（空会话）' }}
                  </span>
                  <span class="session-meta">
                    <el-tag
                      v-if="session.streaming"
                      type="warning"
                      size="small"
                      effect="light"
                      class="streaming-tag"
                      >进行中</el-tag
                    >
                    <span>{{ session.messageCount }} 条</span>
                    <span v-if="session.lastMessageTime">{{
                      formatChatHistoryTime(session.lastMessageTime)
                    }}</span>
                  </span>
                </span>
              </button>
              <el-dropdown
                trigger="click"
                @command="openAddToProject(session.sessionId)"
                @click.stop
              >
                <button
                  type="button"
                  class="session-more"
                  :aria-label="`打开“${session.preview || '空会话'}”的更多操作`"
                  title="更多操作"
                  @click.stop
                >
                  <el-icon><MoreFilled /></el-icon>
                </button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item command="add-to-project">加入 Project</el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </li>
          </ul>
        </section>
      </template>
      <div v-if="hasSearchQuery" class="search-scope">
        仅搜索已加载的 {{ displaySessions.length }} 条会话
      </div>
      <template v-else>
        <div v-if="loadingMore" class="load-status">加载中…</div>
        <div v-else-if="noMore && displaySessions.length > 0" class="load-status">没有更多了</div>
      </template>
    </div>

    <AddToProjectDialog
      v-model="addToProjectVisible"
      :agent-code="agentCode"
      :session-id="addToProjectSessionId"
    />
  </div>
</template>

<style scoped>
.history-sidebar {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-width: 0;
  overflow: hidden;
  color: var(--el-text-color-primary);
  background: var(--el-bg-color);
}

.history-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 14px 12px 8px 16px;
}

.history-title {
  display: flex;
  min-width: 0;
  align-items: baseline;
  gap: 7px;
}

.history-title h2 {
  margin: 0;
  overflow: hidden;
  font-size: 13px;
  font-weight: 650;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.history-title span {
  color: var(--el-text-color-placeholder);
  font-size: 10px;
}

.history-actions {
  display: flex;
  flex: none;
  gap: 2px;
}

.mini-action {
  display: grid;
  width: 28px;
  height: 28px;
  padding: 0;
  place-items: center;
  color: var(--el-text-color-secondary);
  background: transparent;
  border: 0;
  border-radius: 7px;
  cursor: pointer;
}

.mini-action:hover:not(:disabled) {
  color: var(--el-text-color-primary);
  background: var(--el-fill-color-light);
}

.mini-action:disabled {
  cursor: wait;
  opacity: 0.65;
}

.mini-action:focus-visible,
.session-select:focus-visible,
.session-more:focus-visible,
.search-empty button:focus-visible {
  outline: 0;
  box-shadow:
    0 0 0 2px var(--el-bg-color),
    0 0 0 4px var(--el-text-color-primary);
}

.mini-action .el-icon {
  font-size: 15px;
}

.mini-action .is-loading {
  animation: history-rotate 1s linear infinite;
}

.history-search {
  position: relative;
  display: block;
  flex: none;
  margin: 0 14px 8px;
}

.history-search .el-icon {
  position: absolute;
  top: 50%;
  left: 10px;
  z-index: 1;
  color: var(--el-text-color-placeholder);
  font-size: 14px;
  pointer-events: none;
  transform: translateY(-50%);
}

.history-search input {
  box-sizing: border-box;
  width: 100%;
  height: 32px;
  padding: 0 10px 0 31px;
  color: var(--el-text-color-primary);
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color);
  border-radius: 9px;
  outline: 0;
  font: inherit;
  font-size: 11px;
}

.history-search input::placeholder {
  color: var(--el-text-color-placeholder);
}

.history-search input:focus {
  border-color: var(--el-text-color-primary);
  box-shadow: 0 0 0 1px var(--el-text-color-primary);
}

/* 用普通 overflow 容器承接 v-infinite-scroll（指令监听宿主元素自身滚动，el-scrollbar 的内部滚动它监听不到） */
.session-scroll {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 2px 10px 14px;
  scrollbar-width: thin;
  scrollbar-color: var(--el-border-color-darker) transparent;
}

.session-scroll::-webkit-scrollbar {
  width: 6px;
}

.session-scroll::-webkit-scrollbar-thumb {
  background: var(--el-border-color-darker);
  border-radius: 3px;
}

.session-group {
  margin: 0;
}

.date-label {
  margin: 0;
  padding: 10px 6px 5px;
  color: var(--el-text-color-placeholder);
  font-size: 10px;
  font-weight: 600;
  letter-spacing: 0.06em;
}

.session-list {
  list-style: none;
  margin: 0;
  padding: 0;
}

.load-status {
  text-align: center;
  font-size: 12px;
  color: var(--el-text-color-placeholder);
  padding: 12px 0;
}

.session-item {
  position: relative;
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 6px;
  margin: 2px 0;
  padding: 9px 7px 8px 10px;
  background: transparent;
  border: 1px solid transparent;
  border-radius: 10px;
  transition:
    background-color 140ms ease,
    border-color 140ms ease;
}

.session-item:hover {
  background: var(--el-fill-color-light);
}

.session-item.active {
  background: var(--el-color-primary-light-9);
  border-color: var(--el-color-primary-light-7);
}

.session-item.active::before {
  position: absolute;
  top: 8px;
  bottom: 8px;
  left: -1px;
  width: 3px;
  background: var(--el-color-primary);
  border-radius: 0 3px 3px 0;
  content: '';
}

.session-select {
  display: flex;
  min-width: 0;
  padding: 0;
  color: inherit;
  text-align: left;
  background: transparent;
  border: 0;
  cursor: pointer;
}

.session-main {
  display: block;
  min-width: 0;
}

.session-more {
  display: grid;
  width: 24px;
  height: 24px;
  margin: -3px -2px 0 0;
  padding: 0;
  place-items: center;
  color: var(--el-text-color-placeholder);
  background: transparent;
  border: 0;
  border-radius: 6px;
  cursor: pointer;
}

.session-more:hover {
  color: var(--el-text-color-primary);
  background: var(--el-fill-color);
}

.session-preview {
  display: block;
  min-width: 0;
  overflow: hidden;
  color: var(--el-text-color-regular);
  font-size: 13px;
  font-weight: 550;
  line-height: 1.35;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.session-item.active .session-preview {
  color: var(--el-text-color-primary);
  font-weight: 650;
}

.session-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 4px;
  overflow: hidden;
  color: var(--el-text-color-placeholder);
  font-size: 11px;
  white-space: nowrap;
}

.streaming-tag {
  flex: none;
  height: 18px;
  margin-right: 1px;
  padding: 0 5px;
  font-size: 10px;
}

/* 进行中会话标题前的闪烁小圆点，配合「进行中」tag 强化辨识 */
.streaming-dot {
  display: inline-block;
  width: 6px;
  height: 6px;
  margin-right: 4px;
  border-radius: 50%;
  background: var(--el-color-warning);
  animation: streaming-blink 1s ease-in-out infinite;
}

.search-empty {
  display: grid;
  min-height: 120px;
  padding: 24px 10px;
  place-content: center;
  gap: 10px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  text-align: center;
}

.search-empty button {
  justify-self: center;
  padding: 2px 4px;
  color: var(--el-color-primary);
  background: transparent;
  border: 0;
  border-radius: 4px;
  cursor: pointer;
  font: inherit;
}

.search-scope {
  padding: 10px 6px 2px;
  color: var(--el-text-color-placeholder);
  font-size: 10px;
  text-align: center;
}

@keyframes history-rotate {
  to {
    transform: rotate(360deg);
  }
}

@keyframes streaming-blink {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.3;
  }
}

@media (prefers-reduced-motion: reduce) {
  .session-item {
    transition: none;
  }

  .mini-action .is-loading,
  .streaming-dot {
    animation: none;
  }
}
</style>
