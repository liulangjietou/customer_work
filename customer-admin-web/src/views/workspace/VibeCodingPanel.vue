<script setup lang="ts">
import { nextTick, onUnmounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { listVibeCodingArtifacts, streamVibeCoding } from '@/api/vibecoding'
import { getChatSessionMessages } from '@/api/chat'
import MarkdownRenderer from '@/components/MarkdownRenderer.vue'
import ThinkingBlock from '@/components/ThinkingBlock.vue'
import ChatHistorySidebar from '@/components/ChatHistorySidebar.vue'

const props = defineProps<{ agentCode: string }>()

interface ChatMessage {
  role: 'user' | 'assistant'
  text: string
  reasoning: string
}

const sessionId = ref(crypto.randomUUID())
const messages = ref<ChatMessage[]>([])
const input = ref('')
const streaming = ref(false)
const historyLoading = ref(false)
const scrollRef = ref<HTMLElement>()
const artifacts = ref<string[]>([])
const artifactsLoading = ref(false)
const artifactsLoaded = ref(false)
const historySidebar = ref<InstanceType<typeof ChatHistorySidebar>>()
let abortStream: (() => void) | null = null

function newSession() {
  abortStream?.()
  streaming.value = false
  sessionId.value = crypto.randomUUID()
  messages.value = []
  input.value = ''
  artifacts.value = []
  artifactsLoaded.value = false
}

async function openSession(targetSessionId: string) {
  if (streaming.value) {
    return
  }
  abortStream?.()
  historyLoading.value = true
  try {
    const history = await getChatSessionMessages(props.agentCode, targetSessionId)
    sessionId.value = targetSessionId
    messages.value = history.map((msg) => ({ role: msg.role, text: msg.text, reasoning: '' }))
    input.value = ''
    artifacts.value = []
    artifactsLoaded.value = false
    scrollToBottom()
  } catch (error) {
    ElMessage.error('历史会话加载失败：' + (error instanceof Error ? error.message : String(error)))
  } finally {
    historyLoading.value = false
  }
}

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
  messages.value.push({ role: 'user', text, reasoning: '' })
  const assistantMessage: ChatMessage = { role: 'assistant', text: '', reasoning: '' }
  messages.value.push(assistantMessage)
  input.value = ''
  streaming.value = true
  scrollToBottom()

  abortStream = streamVibeCoding(props.agentCode, { sessionId: sessionId.value, message: text }, {
    onEvent: (event) => {
      if (event.event === 'done') {
        streaming.value = false
        return
      }
      if (event.event === 'reasoning') {
        assistantMessage.reasoning += event.data
      } else {
        assistantMessage.text += event.data
      }
      scrollToBottom()
    },
    onError: (error) => {
      streaming.value = false
      ElMessage.error('对话失败：' + (error instanceof Error ? error.message : String(error)))
    },
    onComplete: () => {
      streaming.value = false
      historySidebar.value?.refresh()
    },
  })
}

async function loadArtifacts() {
  artifactsLoading.value = true
  try {
    artifacts.value = await listVibeCodingArtifacts(props.agentCode, sessionId.value)
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
      <div class="panel-header">
        <el-button size="small" @click="newSession">
          <el-icon style="margin-right: 4px"><Plus /></el-icon>
          新建会话
        </el-button>
      </div>
      <div ref="scrollRef" class="messages" v-loading="historyLoading">
        <div v-for="(msg, index) in messages" :key="index" class="message-row" :class="msg.role">
          <div class="bubble">
            <ThinkingBlock
              v-if="msg.role === 'assistant' && msg.reasoning"
              :text="msg.reasoning"
              :active="streaming && index === messages.length - 1 && !msg.text"
            />
            <MarkdownRenderer v-if="msg.role === 'assistant'" :text="msg.text" />
            <template v-else>{{ msg.text }}</template>
            <span v-if="msg.role === 'assistant' && !msg.text && !msg.reasoning && streaming && index === messages.length - 1">生成中…</span>
          </div>
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
    <div class="history-column">
      <ChatHistorySidebar ref="historySidebar" :agent-code="agentCode" :active-session-id="sessionId" @select="openSession" />
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

.panel-header {
  display: flex;
  justify-content: flex-end;
  margin-bottom: 8px;
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
  word-break: break-word;
}

.message-row.user .bubble {
  background: #409eff;
  color: #fff;
  white-space: pre-wrap;
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

.history-column {
  flex: 1;
  border-left: 1px solid #eee;
  padding-left: 16px;
  min-width: 180px;
}
</style>
