<script setup lang="ts">
import { nextTick, onUnmounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getChatSessionMessages, streamChat } from '@/api/chat'
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
const historySidebar = ref<InstanceType<typeof ChatHistorySidebar>>()
let abortStream: (() => void) | null = null

function newSession() {
  abortStream?.()
  streaming.value = false
  sessionId.value = crypto.randomUUID()
  messages.value = []
  input.value = ''
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

  abortStream = streamChat(props.agentCode, { sessionId: sessionId.value, message: text }, {
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

onUnmounted(() => {
  abortStream?.()
})

defineExpose({ sessionId })
</script>

<template>
  <div class="chat-panel">
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
            <span v-if="msg.role === 'assistant' && !msg.text && !msg.reasoning && streaming && index === messages.length - 1">思考中…</span>
          </div>
        </div>
        <el-empty v-if="messages.length === 0" description="开始和智能体对话吧" />
      </div>
      <div class="input-bar">
        <el-input
          v-model="input"
          placeholder="输入消息，回车发送"
          :disabled="streaming"
          @keyup.enter="send"
        />
        <el-button type="primary" :loading="streaming" @click="send">发送</el-button>
      </div>
    </div>
    <div class="history-column">
      <ChatHistorySidebar ref="historySidebar" :agent-code="agentCode" :active-session-id="sessionId" @select="openSession" />
    </div>
  </div>
</template>

<style scoped>
.chat-panel {
  display: flex;
  gap: 16px;
  height: 60vh;
}

.chat-column {
  flex: 3;
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
  max-width: 70%;
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

.history-column {
  flex: 1;
  min-width: 200px;
  border-left: 1px solid #eee;
  padding-left: 16px;
}
</style>
