<script setup lang="ts">
import { nextTick, onMounted, onUnmounted, ref } from 'vue'
import type { UploadRequestOptions } from 'element-plus'
import { getChatSessionMessages, interruptChat, parseChatAttachment, streamChat } from '@/api/chat'
import MarkdownRenderer from '@/components/MarkdownRenderer.vue'
import TraceTimeline, { type TraceNode } from '@/components/TraceTimeline.vue'
import ChatHistorySidebar from '@/components/ChatHistorySidebar.vue'
import { useThemeStore } from '@/store/theme'
import { generateUuid } from '@/utils/uuid'
import { ANSWER_KIND, appendChatStreamNode, parseChatStreamPayload } from '@/utils/traceTimeline'

const props = defineProps<{ agentCode: string; initialSessionId?: string }>()

interface ChatMessage {
  role: 'user' | 'assistant'
  text: string
  nodes: TraceNode[]
}

interface Attachment {
  name: string
  content: string
}

const sessionId = ref(generateUuid())
const messages = ref<ChatMessage[]>([])
const input = ref('')
const streaming = ref(false)
const interrupting = ref(false) // 已点终止，等后端真正停下来（协作式中断，不保证立即生效）
const interrupted = ref(false) // 上一轮是被终止结束的，可以点"继续"续跑挂起的工具调用
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
  interrupting.value = false
  interrupted.value = false
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
  interrupting.value = false
  interrupted.value = false
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
  interrupted.value = false
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
        const kind = event.event.slice('node:'.length)
        const payload = parseChatStreamPayload(event.data)
        appendChatStreamNode(assistantMessage.nodes, kind, payload.text, payload.source, payload.subagentName)
      } else if (event.event === 'message') {
        const payload = parseChatStreamPayload(event.data)
        if (payload.source) {
          // 带 source 的正文增量是子Agent 内部产出，复用 ANSWER kind 挂进对应嵌套面板，
          // 不算进主回答正文（主回答正文只承载主 Agent 自己的 message 事件，source 缺省）
          appendChatStreamNode(assistantMessage.nodes, ANSWER_KIND, payload.text, payload.source, payload.subagentName)
        } else {
          assistantMessage.text += payload.text
        }
      }
      // 其余未知事件静默忽略：后端新增 SSE 事件类型时旧前端不受影响（需求 §5.5 向后兼容）
      scrollToBottom()
    },
    onError: (error) => {
      streaming.value = false
      interrupting.value = false
      ElMessage.error('对话失败：' + (error instanceof Error ? error.message : String(error)))
    },
    onComplete: () => {
      streaming.value = false
      // 若这轮是用户主动点了"终止"后自然结束的，翻转成"可继续"状态，冒出继续按钮
      if (interrupting.value) {
        interrupting.value = false
        interrupted.value = true
      }
      historySidebar.value?.refresh()
    },
  })
}

/** 点击"终止"：只通知后端安全中断（协作式，不保证立即生效），不调 abortStream() 断开前端连接——
 * 让现有的 onComplete/onError 在后端真正停止、SSE 自然结束时收尾，避免界面显示"已停止"但后端其实
 * 还在跑的假象。 */
async function handleInterrupt() {
  interrupting.value = true
  try {
    await interruptChat(props.agentCode, sessionId.value)
  } catch (error) {
    interrupting.value = false
    ElMessage.error('终止失败：' + (error instanceof Error ? error.message : String(error)))
  }
}

/** 点击"继续"：发一句非空续接文案触发框架续跑被打断的挂起工具调用（后端 ChatRequest.message 要求非空，
 * 且续跑逻辑本就挂在正常的 chatStream 调用里，无需专门的续跑接口）。 */
function resumeInterrupted() {
  interrupted.value = false
  input.value = '请继续刚才的任务。'
  send()
}

onMounted(() => {
  themeStore.apply()
  // 从 Project 详情页跳转过来时带上目标会话 id，直接打开对应历史会话，不用用户再手动点一遍。
  if (props.initialSessionId) {
    openSession(props.initialSessionId)
  }
})

onUnmounted(() => {
  abortStream?.()
})

// newSession 供 WorkspaceView 上提后的工具栏"新建会话"按钮按激活 Tab 分发调用
defineExpose({ sessionId, newSession })
</script>

<template>
  <div class="chat-panel">
    <div class="chat-column">
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
        <el-button v-if="!streaming" type="primary" @click="send">发送</el-button>
        <el-button v-else type="danger" :loading="interrupting" @click="handleInterrupt">
          {{ interrupting ? '终止中…' : '终止' }}
        </el-button>
        <el-button v-if="interrupted && !streaming" link type="primary" @click="resumeInterrupted">继续</el-button>
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
  background: var(--theme-primary, var(--el-color-primary));
  color: #fff;
  white-space: pre-wrap;
}

.message-row.user .bubble:hover {
  background: var(--theme-primary-light, #79bbff);
}

.message-row.assistant .bubble {
  background: var(--el-fill-color-light);
  color: var(--el-text-color-primary);
}

.input-bar {
  display: flex;
  gap: 8px;
  margin-top: 12px;
}

/* :not(.is-link) 排除"继续"链接按钮——link 按钮的文字色本就用的是同一个主题蓝（靠透明背景显色），
   这条规则如果连它一起覆盖成纯色背景，文字会跟背景同色而"隐形"。 */
.input-bar :deep(.el-button--primary:not(.is-link)) {
  background-color: var(--theme-primary, var(--el-color-primary));
  border-color: var(--theme-primary, var(--el-color-primary));
}

.input-bar :deep(.el-button--primary:not(.is-link):hover) {
  background-color: var(--theme-primary-light, #79bbff);
  border-color: var(--theme-primary-light, #79bbff);
}

.history-column {
  flex: 1;
  min-width: 200px;
  border-left: 1px solid var(--el-border-color-lighter);
  padding-left: 16px;
}
</style>
