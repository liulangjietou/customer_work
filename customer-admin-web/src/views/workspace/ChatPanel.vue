<script setup lang="ts">
import { nextTick, onMounted, onUnmounted, ref } from 'vue'
import { ElMessage, type UploadRequestOptions } from 'element-plus'
import { getChatSessionMessages, parseChatAttachment, streamChat } from '@/api/chat'
import MarkdownRenderer from '@/components/MarkdownRenderer.vue'
import TraceTimeline, { type TraceNode } from '@/components/TraceTimeline.vue'
import ChatHistorySidebar from '@/components/ChatHistorySidebar.vue'
import ThemeToolbar from '@/components/ThemeToolbar.vue'
import { useThemeStore } from '@/store/theme'
import { generateUuid } from '@/utils/uuid'

const props = defineProps<{ agentCode: string }>()

interface ChatMessage {
  role: 'user' | 'assistant'
  text: string
  nodes: TraceNode[]
}

/** 追加一个执行轨迹节点：连续的 thinking 是同一段思考内容的增量分片，合并进上一个节点而不是各占一条；
 * 其余节点类型（开始思考/调用大模型/调用 Skill 等）后端已经做过去重，各自独立成一条时间线项。 */
function appendNode(msg: ChatMessage, kind: string, text: string) {
  const last = msg.nodes[msg.nodes.length - 1]
  if (last && last.kind === 'thinking' && kind === 'thinking') {
    last.text += text
  } else {
    msg.nodes.push({ kind, text })
  }
}

interface Attachment {
  name: string
  content: string
}

const sessionId = ref(generateUuid())
const messages = ref<ChatMessage[]>([])
const input = ref('')
const streaming = ref(false)
const historyLoading = ref(false)
const uploading = ref(false)
const attachments = ref<Attachment[]>([])
const scrollRef = ref<HTMLElement>()
const historySidebar = ref<InstanceType<typeof ChatHistorySidebar>>()
let abortStream: (() => void) | null = null
const themeStore = useThemeStore()

function newSession() {
  abortStream?.()
  streaming.value = false
  sessionId.value = generateUuid()
  messages.value = []
  input.value = ''
  attachments.value = []
}

async function handleAttachmentUpload(options: UploadRequestOptions) {
  uploading.value = true
  try {
    const file = options.file as File
    const content = await parseChatAttachment(props.agentCode, file)
    attachments.value.push({ name: file.name, content })
  } catch (error) {
    ElMessage.error('附件解析失败：' + (error instanceof Error ? error.message : String(error)))
  } finally {
    uploading.value = false
  }
}

function removeAttachment(index: number) {
  attachments.value.splice(index, 1)
}

/** 把附件内容拼进消息正文——用清晰的分隔符包起来，让模型分得清"附件材料"和"用户实际问题"。 */
function buildMessageWithAttachments(text: string): string {
  if (attachments.value.length === 0) {
    return text
  }
  const attachmentText = attachments.value
    .map((a) => `【附件：${a.name}】\n---\n${a.content}\n---`)
    .join('\n\n')
  return `${attachmentText}\n\n${text}`
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
    messages.value = history.map((msg) => ({ role: msg.role, text: msg.text, nodes: [] }))
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
  const messageToSend = buildMessageWithAttachments(text)
  const attachedNames = attachments.value.map((a) => a.name)
  // 用户气泡只展示原始输入 + 附件文件名提示，不把拼进正文的附件全文也显示出来（那部分只是发给模型看的）。
  messages.value.push({
    role: 'user',
    text: attachedNames.length > 0 ? `${text}\n📎 ${attachedNames.join('、')}` : text,
    nodes: [],
  })
  messages.value.push({ role: 'assistant', text: '', nodes: [] })
  // 坑：不能拿 push 前创建的原始对象引用去改——Vue 的响应式数组对存进去的对象是"读取时才转成响应式
  // 代理"，闭包里这个原始对象跟模板 v-for 里读到的 msg 不是同一个代理，直接改原始对象的属性绕过了
  // 代理的 setter，Vue 感知不到，界面不会增量刷新（只有等 streaming 这类真正的响应式变量变化触发整体
  // 重新渲染时才会一次性显示全部内容）。push 完再从数组里取出来，这时候拿到的才是响应式代理本身。
  const assistantMessage = messages.value[messages.value.length - 1]
  input.value = ''
  attachments.value = []
  streaming.value = true
  scrollToBottom()

  abortStream = streamChat(props.agentCode, { sessionId: sessionId.value, message: messageToSend }, {
    onEvent: (event) => {
      if (event.event === 'done') {
        streaming.value = false
        return
      }
      if (event.event.startsWith('node:')) {
        appendNode(assistantMessage, event.event.slice('node:'.length), event.data)
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

onMounted(() => {
  themeStore.apply()
})

onUnmounted(() => {
  abortStream?.()
})

defineExpose({ sessionId })
</script>

<template>
  <div class="chat-panel">
    <div class="chat-column">
      <div class="panel-header">
        <ThemeToolbar :on-new-session="newSession" />
      </div>
      <div ref="scrollRef" class="messages" v-loading="historyLoading">
        <div v-for="(msg, index) in messages" :key="index" class="message-row" :class="msg.role">
          <div class="bubble">
            <TraceTimeline
              v-if="msg.role === 'assistant' && msg.nodes.length > 0"
              :nodes="msg.nodes"
              :active="streaming && index === messages.length - 1 && !msg.text"
            />
            <MarkdownRenderer v-if="msg.role === 'assistant'" :text="msg.text" />
            <template v-else>{{ msg.text }}</template>
            <span v-if="msg.role === 'assistant' && !msg.text && msg.nodes.length === 0 && streaming && index === messages.length - 1">思考中…</span>
          </div>
        </div>
        <el-empty v-if="messages.length === 0" description="开始和智能体对话吧" />
      </div>
      <div v-if="attachments.length > 0" class="attachment-tags">
        <el-tag v-for="(a, idx) in attachments" :key="idx" closable size="small" @close="removeAttachment(idx)">
          📎 {{ a.name }}
        </el-tag>
      </div>
      <div class="input-bar">
        <el-upload
          :show-file-list="false"
          :http-request="handleAttachmentUpload"
          accept=".md,.txt"
        >
          <el-button :loading="uploading" :disabled="streaming" title="上传 .md/.txt 附件，随消息一起发给智能体">
            <el-icon><Paperclip /></el-icon>
          </el-button>
        </el-upload>
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
  background-color: var(--theme-page-bg, #fff);
  border-radius: 8px;
  padding: 12px;
  transition: background-color 0.3s ease;
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
  border-radius: 6px;
}

.attachment-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 8px;
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
  background: var(--theme-primary, #409eff);
  color: #fff;
  white-space: pre-wrap;
}

.message-row.user .bubble:hover {
  background: var(--theme-primary-light, #79bbff);
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

.input-bar :deep(.el-button--primary) {
  background-color: var(--theme-primary, #409eff);
  border-color: var(--theme-primary, #409eff);
}

.input-bar :deep(.el-button--primary:hover) {
  background-color: var(--theme-primary-light, #79bbff);
  border-color: var(--theme-primary-light, #79bbff);
}

.history-column {
  flex: 1;
  min-width: 200px;
  border-left: 1px solid #eee;
  padding-left: 16px;
}
</style>
