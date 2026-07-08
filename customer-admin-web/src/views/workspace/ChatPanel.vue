<script setup lang="ts">
import { nextTick, onUnmounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { streamChat } from '@/api/chat'

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

  abortStream = streamChat(props.agentCode, { sessionId, message: text }, {
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

onUnmounted(() => {
  abortStream?.()
})

defineExpose({ sessionId })
</script>

<template>
  <div class="chat-panel">
    <div ref="scrollRef" class="messages">
      <div v-for="(msg, index) in messages" :key="index" class="message-row" :class="msg.role">
        <div class="bubble">{{ msg.text || (streaming && index === messages.length - 1 ? '思考中…' : '') }}</div>
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
</template>

<style scoped>
.chat-panel {
  display: flex;
  flex-direction: column;
  height: 60vh;
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
</style>
