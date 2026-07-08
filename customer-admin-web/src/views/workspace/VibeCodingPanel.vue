<script setup lang="ts">
import { nextTick, onUnmounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { listVibeCodingArtifacts, streamVibeCoding } from '@/api/vibecoding'

const props = defineProps<{ agentCode: string }>()

interface ChatMessage {
  role: 'user' | 'assistant'
  text: string
}

const sessionId = crypto.randomUUID()
const messages = ref<ChatMessage[]>([])
const input = ref('')
const streaming = ref(false)
const scrollRef = ref<HTMLElement>()
const artifacts = ref<string[]>([])
const artifactsLoading = ref(false)
const artifactsLoaded = ref(false)
let abortStream: (() => void) | null = null

function scrollToBottom() {
  nextTick(() => {
    scrollRef.value?.scrollTo({ top: scrollRef.value.scrollHeight, behavior: 'smooth' })
  })
}

function send() {
  const text = input.value.trim()
  if (!text || streaming.value) {
    return
  }
  messages.value.push({ role: 'user', text })
  const assistantMessage: ChatMessage = { role: 'assistant', text: '' }
  messages.value.push(assistantMessage)
  input.value = ''
  streaming.value = true
  scrollToBottom()

  abortStream = streamVibeCoding(props.agentCode, { sessionId, message: text }, {
    onEvent: (event) => {
      if (event.event === 'done') {
        streaming.value = false
        return
      }
      assistantMessage.text += event.data
      scrollToBottom()
    },
    onError: (error) => {
      streaming.value = false
      ElMessage.error('对话失败：' + (error instanceof Error ? error.message : String(error)))
    },
    onComplete: () => {
      streaming.value = false
    },
  })
}

async function loadArtifacts() {
  artifactsLoading.value = true
  try {
    artifacts.value = await listVibeCodingArtifacts(props.agentCode, sessionId)
    artifactsLoaded.value = true
  } finally {
    artifactsLoading.value = false
  }
}

onUnmounted(() => {
  abortStream?.()
})
</script>

<template>
  <div class="vibecoding-panel">
    <div class="chat-column">
      <div ref="scrollRef" class="messages">
        <div v-for="(msg, index) in messages" :key="index" class="message-row" :class="msg.role">
          <div class="bubble">{{ msg.text || (streaming && index === messages.length - 1 ? '生成中…' : '') }}</div>
        </div>
        <el-empty v-if="messages.length === 0" description="描述你想让智能体生成/修改的代码" />
      </div>
      <div class="input-bar">
        <el-input v-model="input" placeholder="描述需求，回车发送" :disabled="streaming" @keyup.enter="send" />
        <el-button type="primary" :loading="streaming" @click="send">发送</el-button>
      </div>
    </div>
    <div class="artifacts-column">
      <div class="artifacts-header">
        <span>产物清单</span>
        <el-button link type="primary" :loading="artifactsLoading" @click="loadArtifacts">刷新</el-button>
      </div>
      <el-empty v-if="artifactsLoaded && artifacts.length === 0" description="本次会话暂无文件变更" :image-size="60" />
      <el-empty v-else-if="!artifactsLoaded" description="对话结束后点击刷新查看变更文件" :image-size="60" />
      <el-scrollbar v-else height="100%">
        <ul class="artifact-list">
          <li v-for="file in artifacts" :key="file">
            <el-icon><Document /></el-icon>
            {{ file }}
          </li>
        </ul>
      </el-scrollbar>
    </div>
  </div>
</template>

<style scoped>
.vibecoding-panel {
  display: flex;
  gap: 16px;
  height: 60vh;
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
}

.message-row {
  display: flex;
  margin-bottom: 12px;
}

.message-row.user {
  justify-content: flex-end;
}

.bubble {
  max-width: 90%;
  padding: 10px 14px;
  border-radius: 8px;
  white-space: pre-wrap;
  word-break: break-word;
}

.message-row.user .bubble {
  background: #409eff;
  color: #fff;
}

.message-row.assistant .bubble {
  background: #f0f2f5;
  color: #333;
}

.input-bar {
  display: flex;
  gap: 8px;
  margin-top: 12px;
}

.artifacts-column {
  flex: 1;
  border-left: 1px solid #eee;
  padding-left: 16px;
  display: flex;
  flex-direction: column;
  min-width: 200px;
}

.artifacts-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
  font-weight: 600;
}

.artifact-list {
  list-style: none;
  margin: 0;
  padding: 0;
}

.artifact-list li {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 0;
  font-size: 13px;
  border-bottom: 1px dashed #eee;
}
</style>
