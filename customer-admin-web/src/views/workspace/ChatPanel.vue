<script setup lang="ts">
import { computed, nextTick, onActivated, onMounted, ref, watch } from 'vue'
import type { UploadRequestOptions } from 'element-plus'
import { parseChatAttachment } from '@/api/chat'
import MarkdownRenderer from '@/components/MarkdownRenderer.vue'
import TraceTimeline from '@/components/TraceTimeline.vue'
import ChatHistorySidebar from '@/components/ChatHistorySidebar.vue'
import { useThemeStore } from '@/store/theme'
import {
  useChatConversationsStore,
  type ChatAttachmentItem,
  type ChatConversation,
} from '@/store/chatConversations'
import { generateUuid } from '@/utils/uuid'

const props = defineProps<{ agentCode: string; initialSessionId?: string }>()

// 与 starter AttachmentParseService 的白名单/大小限制保持一致（后端 customer-work.attachment.max-file-size-mb=10）
const ATTACHMENT_ACCEPT = '.md,.txt,.csv,.tsv,.json,.xml,.yaml,.yml,.toml,.proto,.properties,.ini,.conf,.cfg,.log,.env,.sql,.sh,.bash,.zsh,.bat,.ps1,.java,.kt,.kts,.groovy,.gradle,.scala,.py,.js,.ts,.jsx,.tsx,.vue,.css,.scss,.less,.c,.h,.cpp,.hpp,.cs,.go,.rs,.rb,.php,.swift,.lua,.r,.dart,.html,.pdf,.doc,.docx,.xls,.xlsx,.ppt,.pptx,.png,.jpg,.jpeg,.bmp,.webp'
const MAX_ATTACHMENT_SIZE_BYTES = 10 * 1024 * 1024

/**
 * 会话状态全部在 Pinia store（chatConversations）里，本组件只是"当前正在看哪个会话"的视图：
 * 切页面、切智能体、组件销毁重建都不影响 store 里进行中的会话与 SSE 流——这正是"进行中的会话
 * 换个地方回来还能找到、还在继续跑"的根本保障。组件自己只保留纯视图状态（滚动容器、loading）。
 */
const store = useChatConversationsStore()
store.ensureAgent(props.agentCode)

const active = computed<ChatConversation | undefined>(() => store.activeOf(props.agentCode))
const liveSessions = computed(() => store.liveSessionsOf(props.agentCode))
const activeSessionId = computed(() => store.activeIdOf(props.agentCode))
const anyAttachmentUploading = computed(() => active.value?.attachments.some((a) => a.status === 'uploading') ?? false)

const historyLoading = ref(false)
const scrollRef = ref<HTMLElement>()
const historySidebar = ref<InstanceType<typeof ChatHistorySidebar>>()
const themeStore = useThemeStore()

function newSession() {
  store.newSession(props.agentCode)
}

/** 前端先拦超限文件，与后端 max-file-size-mb 对齐，减少无谓上传请求。 */
function beforeAttachmentUpload(file: File) {
  if (file.size > MAX_ATTACHMENT_SIZE_BYTES) {
    ElMessage.error(`附件 ${file.name} 超过 10MB，已跳过上传`)
    return false
  }
  return true
}

async function handleAttachmentUpload(options: UploadRequestOptions) {
  // 绑定发起上传时所在的会话：上传是异步的，期间用户可能切走，结果要回填到原会话而不是当前激活会话。
  const conv = active.value
  if (!conv) return
  const file = options.file as File
  const attachment: ChatAttachmentItem = { localId: generateUuid(), name: file.name, content: '', status: 'uploading' }
  conv.attachments.push(attachment)
  try {
    const result = await parseChatAttachment(props.agentCode, file, 'admin_chat')
    const target = conv.attachments.find((a) => a.localId === attachment.localId)
    if (!target) {
      return // 结果返回前用户已手动移除该附件，迟到的结果直接丢弃
    }
    if (result.parseStatus === 'FAILED') {
      target.status = 'failed'
      target.errorMessage = result.errorMessage || '解析失败'
      ElMessage.error(`附件解析失败：${file.name}${result.errorMessage ? '，' + result.errorMessage : ''}`)
    } else {
      target.id = result.id
      target.content = result.content
      target.status = 'success'
    }
  } catch (error) {
    const target = conv.attachments.find((a) => a.localId === attachment.localId)
    const message = error instanceof Error ? error.message : String(error)
    if (target) {
      target.status = 'failed'
      target.errorMessage = message
    }
    ElMessage.error('附件解析失败：' + message)
  }
}

function removeAttachment(localId: string) {
  const conv = active.value
  if (!conv) return
  conv.attachments = conv.attachments.filter((a) => a.localId !== localId)
}

/** 把附件内容拼进消息正文——用清晰的分隔符包起来，让模型分得清"附件材料"和"用户实际问题"。
 * 只拼成功解析的附件，上传中/失败的附件不参与（失败的已经在上传回调里提示过用户）。 */
function buildMessageWithAttachments(conv: ChatConversation, text: string): string {
  const successful = conv.attachments.filter((a) => a.status === 'success')
  if (successful.length === 0) {
    return text
  }
  const attachmentText = successful
    .map((a) => `【附件：${a.name}】\n---\n${a.content}\n---`)
    .join('\n\n')
  return `${attachmentText}\n\n${text}`
}

async function openSession(targetSessionId: string) {
  historyLoading.value = true
  try {
    await store.openSession(props.agentCode, targetSessionId)
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
  store.send(props.agentCode, buildMessageWithAttachments, scrollToBottom)
}

function handleInterrupt() {
  store.interrupt(props.agentCode)
}

/** 点击"继续"：发一句非空续接文案触发框架续跑被打断的挂起工具调用（后端 ChatRequest.message 要求非空，
 * 且续跑逻辑本就挂在正常的 chatStream 调用里，无需专门的续跑接口）。 */
function resumeInterrupted() {
  const conv = active.value
  if (!conv) return
  conv.interrupted = false
  conv.input = '请继续刚才的任务。'
  send()
}

onMounted(() => {
  themeStore.apply()
})

// 每轮对话流结束（store 里自增版本号）刷新侧边栏的后端历史列表——流的 onComplete 在 store 里执行，
// 不再直接持有组件 ref，组件通过 watch 版本号补上这层联动。
watch(
  () => store.historyVersion[props.agentCode],
  () => historySidebar.value?.refresh(),
)

// 从 Project 详情页跳转过来时带上目标会话 id，直接打开对应历史会话。用 watch 而非 onMounted 一次性
// 读取：本页处于 keep-alive 下，二次带新 sessionId 跳进来不会重新 mount。immediate 保留首挂载即打开。
watch(
  () => props.initialSessionId,
  (id) => {
    if (id) {
      openSession(id)
    }
  },
  { immediate: true },
)

// 从 keep-alive 缓存里重新激活时滚到最新内容——离开期间进行中的会话仍在后台追加增量。
onActivated(() => {
  scrollToBottom()
})

// 注意：这里刻意没有 onUnmounted abort——会话与 SSE 流属于全局 store，组件销毁（切智能体/关标签）
// 不应终止后台会话；流的生命周期终点是自然完成、用户点"终止"或整页刷新。

// newSession 供 WorkspaceView 上提后的工具栏"新建会话"按钮按激活 Tab 分发调用
defineExpose({ newSession })
</script>

<template>
  <div class="chat-panel">
    <div class="chat-column">
      <div ref="scrollRef" class="messages" v-loading="historyLoading">
        <div v-for="(msg, index) in active?.messages ?? []" :key="index" class="message-row" :class="msg.role">
          <div class="bubble">
            <TraceTimeline
              v-if="msg.role === 'assistant' && msg.nodes.length > 0"
              :nodes="msg.nodes"
              :active="(active?.streaming ?? false) && index === (active?.messages.length ?? 0) - 1 && !msg.text"
            />
            <MarkdownRenderer v-if="msg.role === 'assistant'" :text="msg.text" />
            <template v-else>{{ msg.text }}</template>
            <span v-if="msg.role === 'assistant' && !msg.text && msg.nodes.length === 0 && (active?.streaming ?? false) && index === (active?.messages.length ?? 0) - 1">思考中…</span>
          </div>
        </div>
        <el-empty v-if="(active?.messages.length ?? 0) === 0" description="开始和智能体对话吧" />
      </div>
      <div v-if="active && active.attachments.length > 0" class="attachment-tags">
        <el-tag
          v-for="a in active.attachments"
          :key="a.localId"
          :closable="a.status !== 'uploading'"
          :type="a.status === 'failed' ? 'danger' : undefined"
          size="small"
          @close="removeAttachment(a.localId)"
        >
          <el-icon v-if="a.status === 'uploading'" class="is-loading"><Loading /></el-icon>
          📎 {{ a.name }}
        </el-tag>
      </div>
      <div class="input-bar">
        <el-upload
          :show-file-list="false"
          :http-request="handleAttachmentUpload"
          :before-upload="beforeAttachmentUpload"
          :accept="ATTACHMENT_ACCEPT"
        >
          <el-button :disabled="active?.streaming" title="上传附件（文档/表格/图片等），随消息一起发给智能体">
            <el-icon><Paperclip /></el-icon>
          </el-button>
        </el-upload>
        <el-input
          v-if="active"
          v-model="active.input"
          placeholder="输入消息，回车发送"
          :disabled="active.streaming"
          @keyup.enter="send"
        />
        <el-button v-if="!active?.streaming" type="primary" :disabled="anyAttachmentUploading" @click="send">发送</el-button>
        <el-button v-else type="danger" :loading="active?.interrupting" @click="handleInterrupt">
          {{ active?.interrupting ? '终止中…' : '终止' }}
        </el-button>
        <el-button v-if="active?.interrupted && !active?.streaming" link type="primary" @click="resumeInterrupted">继续</el-button>
      </div>
    </div>
    <div class="history-column">
      <ChatHistorySidebar
        ref="historySidebar"
        :agent-code="agentCode"
        :active-session-id="activeSessionId"
        :live-sessions="liveSessions"
        @select="openSession"
      />
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
