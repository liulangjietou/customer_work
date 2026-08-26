<script setup lang="ts">
import { computed, nextTick, onActivated, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { createScrollFollower } from '@/utils/scrollFollower'
import { ATTACHMENT_ACCEPT, useChatAttachments } from '@/composables/useChatAttachments'
import { confirmChatPlan } from '@/api/chat'
import AssistantResponse from '@/components/AssistantResponse.vue'
import ChatHistorySidebar from '@/components/ChatHistorySidebar.vue'
import ExecutionModeSelect from '@/components/ExecutionModeSelect.vue'
import PlanConfirmCard from '@/components/PlanConfirmCard.vue'
import AttachmentPendingList from '@/components/attachment/AttachmentPendingList.vue'
import MessageAttachments from '@/components/attachment/MessageAttachments.vue'
import { useThemeStore } from '@/store/theme'
import {
  useChatConversationsStore,
  type ChatConversation,
} from '@/store/chatConversations'
import type { PlanCard } from '@/utils/planCard'

const props = defineProps<{ agentCode: string; initialSessionId?: string }>()

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

// 附件上传（回形针按钮 + 输入框 ⌘/Ctrl+V 粘贴）统一走组合式函数，与 VibeCodingPanel 共用同一条链路
const { beforeAttachmentUpload, handleAttachmentUpload, handleAttachmentPaste, removeAttachment } =
  useChatAttachments({
    agentCode: () => props.agentCode,
    channel: 'admin_chat',
    getConversation: () => active.value,
  })

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
  // 只发附件不写文字时正文就是附件内容本身
  return text ? `${attachmentText}\n\n${text}` : attachmentText
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

// 跟随到底部：每帧最多滚一次、瞬时定位。流式增量的到达频率远高于平滑滚动动画的时长，
// 逐条调 scrollTo({behavior:'smooth'}) 会不断打断上一次动画，反而跟不上内容（详见 scrollFollower）。
const scrollFollower = createScrollFollower(() => scrollRef.value)

function scrollToBottom() {
  nextTick(() => scrollFollower.follow())
}

/** 只有当前会话最后一条助手消息属于本轮 SSE，避免切历史或旧消息显示成执行中。 */
function isStreamingMessage(index: number): boolean {
  return !!active.value?.streaming && index === active.value.messages.length - 1
}

onBeforeUnmount(() => scrollFollower.cancel())

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

/** 用户对某个计划卡片点批准/拒绝：调后端确认接口，成功后翻成终态。逻辑镜像 VibeCodingPanel 的
 * handlePlanDecision（对话与 VibeCoding 的确认接口路径不同，各自面板调各自的 API）。 */
async function handlePlanDecision(card: PlanCard, approved: boolean) {
  const conv = active.value
  if (!conv || card.status !== 'PENDING' || card.submitting) return
  card.submitting = true
  try {
    await confirmChatPlan(props.agentCode, {
      sessionId: conv.sessionId,
      planId: card.planId,
      approved,
    })
    card.status = approved ? 'APPROVED' : 'REJECTED'
    conv.pendingPlans.delete(card.planId)
  } catch (error) {
    // 失败常见于挂起项已失效（超时/服务重启）：提示并按拒绝态收尾，避免卡片永久停在"等待确认"
    ElMessage.error('计划确认失败：' + (error instanceof Error ? error.message : String(error)))
    card.status = 'TIMEOUT'
    conv.pendingPlans.delete(card.planId)
  } finally {
    card.submitting = false
  }
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
            <AssistantResponse
              v-if="msg.role === 'assistant'"
              :nodes="msg.nodes"
              :text="msg.text"
              :active="isStreamingMessage(index)"
              :failed="msg.failed"
            >
              <!-- Plan Mode 确认卡片（P1-1 HITL）：高风险操作待人工确认，批准/拒绝按钮 + 倒计时 -->
              <PlanConfirmCard
                v-if="msg.plans && msg.plans.length > 0"
                :plans="msg.plans"
                @decision="handlePlanDecision"
              />
            </AssistantResponse>
            <template v-else>{{ msg.text }}</template>
            <!-- 用户消息携带的附件：图片缩略图/文本芯片，历史消息与刚发送的消息共用同一组件 -->
            <MessageAttachments
              v-if="msg.role === 'user' && msg.attachments && msg.attachments.length > 0"
              :agent-code="agentCode"
              :attachments="msg.attachments"
            />
          </div>
        </div>
        <el-empty v-if="(active?.messages.length ?? 0) === 0" description="开始和智能体对话吧" />
      </div>
      <AttachmentPendingList
        v-if="active && active.attachments.length > 0"
        class="attachment-tags"
        :attachments="active.attachments"
        @remove="removeAttachment"
      />
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
        <ExecutionModeSelect
          v-if="active"
          v-model="active.mode"
          :disabled="active.streaming"
        />
        <el-input
          v-if="active"
          v-model="active.input"
          placeholder="输入消息，回车发送；⌘/Ctrl+V 可粘贴截图或文件作为附件"
          :disabled="active.streaming"
          @keyup.enter="send"
          @paste="handleAttachmentPaste"
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
  min-width: 0;
  word-break: break-word;
}

.message-row.user .bubble {
  max-width: 70%;
  padding: 10px 14px;
  background: var(--theme-primary, var(--el-color-primary));
  border-radius: 12px 12px 3px 12px;
  color: #fff;
  white-space: pre-wrap;
}

.message-row.user .bubble:hover {
  background: var(--theme-primary-light, #79bbff);
}

.message-row.assistant .bubble {
  width: 100%;
  max-width: 100%;
  padding: 0;
}

.input-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 12px;
}

.input-bar :deep(.el-input) {
  flex: 1;
  min-width: 180px;
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

@media (max-width: 900px) {
  .chat-panel {
    flex-direction: column;
    height: auto;
    min-height: 60vh;
  }

  .history-column {
    min-height: 180px;
    padding-top: 14px;
    padding-left: 0;
    border-top: 1px solid var(--el-border-color-lighter);
    border-left: 0;
  }

  .message-row.user .bubble {
    max-width: 88%;
  }

  .input-bar {
    flex-wrap: wrap;
  }
}
</style>
