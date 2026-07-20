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
const emit = defineEmits<{ select: [sessionId: string] }>()

const sessions = ref<ChatSessionSummary[]>([])
const loading = ref(false)

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

async function refresh() {
  loading.value = true
  try {
    sessions.value = await listChatSessions(props.agentCode)
  } catch (error) {
    ElMessage.error('历史会话加载失败：' + (error instanceof Error ? error.message : String(error)))
  } finally {
    loading.value = false
  }
}

onMounted(refresh)

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
    <el-scrollbar v-else height="100%">
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
    </el-scrollbar>

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

.session-list {
  list-style: none;
  margin: 0;
  padding: 0;
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
