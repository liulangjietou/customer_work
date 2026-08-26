<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { listChatSessions } from '@/api/chat'
import AddToProjectDialog from './AddToProjectDialog.vue'
import type { ChatSessionSummary, LiveSession } from '@/types/api'

/** liveSessions：父面板内存里"活着"的会话（进行中/本次加载过的），默认空数组兼容尚未接入的调用方。 */
const props = withDefaults(
  defineProps<{ agentCode: string; activeSessionId: string; liveSessions?: LiveSession[] }>(),
  { liveSessions: () => [] },
)
const emit = defineEmits<{
  select: [sessionId: string]
  /** 首次加载完成后把排序第一的会话交给父面板决定是否恢复；手动刷新不会重复触发。 */
  initialSession: [sessionId: string]
}>()

/** 每页条数，与后端 ChatController#sessions 的 size 默认值保持一致。 */
const PAGE_SIZE = 20

const sessions = ref<ChatSessionSummary[]>([])
const page = ref(0)
const total = ref(0)
const loading = ref(false) // 首页加载
const loadingMore = ref(false) // 滚动追加下一页

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

onMounted(initialize)

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
      <span>历史对话</span>
      <el-button link type="primary" :loading="loading" @click="refresh">刷新</el-button>
    </div>
    <el-empty v-if="!loading && displaySessions.length === 0" description="暂无历史对话" :image-size="50" />
    <div
      v-else
      class="session-scroll"
      v-infinite-scroll="loadMore"
      :infinite-scroll-disabled="loading || loadingMore || noMore"
      :infinite-scroll-immediate="false"
      :infinite-scroll-distance="10"
    >
      <ul class="session-list">
        <li
          v-for="session in displaySessions"
          :key="session.sessionId"
          class="session-item"
          :class="{ active: session.sessionId === activeSessionId }"
          @click="emit('select', session.sessionId)"
        >
          <div class="session-row">
            <div class="session-main">
              <div class="session-preview">
                <span v-if="session.streaming" class="streaming-dot" />
                {{ session.preview || '（空会话）' }}
              </div>
              <div class="session-meta">
                <el-tag v-if="session.streaming" type="warning" size="small" effect="light" class="streaming-tag">进行中</el-tag>
                <span>{{ session.messageCount }} 条</span>
                <span v-if="session.lastMessageTime">{{ session.lastMessageTime }}</span>
              </div>
            </div>
            <el-dropdown trigger="click" @command="openAddToProject(session.sessionId)" @click.stop>
              <el-icon class="session-more" @click.stop><MoreFilled /></el-icon>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="add-to-project">加入 Project</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </div>
        </li>
      </ul>
      <div v-if="loadingMore" class="load-status">加载中…</div>
      <div v-else-if="noMore && displaySessions.length > 0" class="load-status">没有更多了</div>
    </div>

    <AddToProjectDialog v-model="addToProjectVisible" :agent-code="agentCode" :session-id="addToProjectSessionId" />
  </div>
</template>

<style scoped>
.history-sidebar {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-width: 0;
}

.history-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
  font-weight: 600;
  font-size: 13px;
}

/* 用普通 overflow 容器承接 v-infinite-scroll（指令监听宿主元素自身滚动，el-scrollbar 的内部滚动它监听不到） */
.session-scroll {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  scrollbar-width: thin;
}

.session-scroll::-webkit-scrollbar {
  width: 6px;
}

.session-scroll::-webkit-scrollbar-thumb {
  background: #dcdfe6;
  border-radius: 3px;
}

.session-list {
  list-style: none;
  margin: 0;
  padding: 0;
}

.load-status {
  text-align: center;
  font-size: 12px;
  color: #999;
  padding: 8px 0;
}

.session-item {
  padding: 8px;
  border-radius: 6px;
  cursor: pointer;
  margin-bottom: 4px;
}

.session-item:hover {
  background: #f5f7fa;
}

.session-item.active {
  background: #ecf5ff;
}

.session-row {
  display: flex;
  align-items: flex-start;
  gap: 4px;
}

.session-main {
  flex: 1;
  min-width: 0;
}

.session-more {
  flex-shrink: 0;
  margin-top: 2px;
  padding: 2px;
  border-radius: 4px;
  color: #909399;
}

.session-more:hover {
  background: #e4e7ed;
  color: #409eff;
}

.session-preview {
  font-size: 13px;
  color: #333;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.session-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: #999;
  margin-top: 2px;
}

.streaming-tag {
  margin-right: 2px;
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

@keyframes streaming-blink {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.3; }
}
</style>
