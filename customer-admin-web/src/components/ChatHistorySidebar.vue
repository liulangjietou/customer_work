<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { listChatSessions } from '@/api/chat'
import AddToProjectDialog from './AddToProjectDialog.vue'
import type { ChatSessionSummary } from '@/types/api'

const props = defineProps<{ agentCode: string; activeSessionId: string }>()
const emit = defineEmits<{ select: [sessionId: string] }>()

const sessions = ref<ChatSessionSummary[]>([])
const loading = ref(false)

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
    <el-empty v-if="!loading && sessions.length === 0" description="暂无历史对话" :image-size="50" />
    <el-scrollbar v-else height="100%">
      <ul class="session-list">
        <li
          v-for="session in sessions"
          :key="session.sessionId"
          class="session-item"
          :class="{ active: session.sessionId === activeSessionId }"
          @click="emit('select', session.sessionId)"
        >
          <div class="session-row">
            <div class="session-main">
              <div class="session-preview">{{ session.preview || '（空会话）' }}</div>
              <div class="session-meta">
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
  justify-content: space-between;
  font-size: 12px;
  color: #999;
  margin-top: 2px;
}
</style>
