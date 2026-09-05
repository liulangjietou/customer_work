<script setup lang="ts">
import {
  computed,
  nextTick,
  onActivated,
  onDeactivated,
  onBeforeUnmount,
  onMounted,
  ref,
  watch,
} from 'vue'
import { handleComposerKeydown } from '@/utils/composerKeyboard'
import { createScrollFollower } from '@/utils/scrollFollower'
import { shouldRestoreMostRecentSession } from '@/utils/conversationRestore'
import { ATTACHMENT_ACCEPT, useChatAttachments } from '@/composables/useChatAttachments'
import { confirmChatPlan } from '@/api/chat'
import ExecutionRecord from '@/components/workspace/ExecutionRecord.vue'
import AssistantResponse from '@/components/AssistantResponse.vue'
import ChatHistorySidebar from '@/components/ChatHistorySidebar.vue'
import ExecutionModeSelect from '@/components/ExecutionModeSelect.vue'
import PlanConfirmCard from '@/components/PlanConfirmCard.vue'
import AttachmentPendingList from '@/components/attachment/AttachmentPendingList.vue'
import MessageAttachments from '@/components/attachment/MessageAttachments.vue'
import WorkspaceConversationEmptyState from '@/components/workspace/WorkspaceConversationEmptyState.vue'
import { useThemeStore } from '@/store/theme'
import '@/styles/workspace-conversation.css'
import { useChatConversationsStore, type ChatConversation } from '@/store/chatConversations'
import type { PlanCard } from '@/utils/planCard'

const props = defineProps<{
  agentCode: string
  assistantName: string
  initialSessionId?: string
  historyActive?: boolean
}>()

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
const attachmentBlocked = computed(
  () => active.value?.attachments.some((a) => a.status !== 'success') ?? false,
)
const canSend = computed(
  () =>
    !attachmentBlocked.value &&
    !!active.value &&
    !active.value.streaming &&
    (!!active.value.input.trim() ||
      active.value.attachments.some((a) => a.status === 'success' && a.id)),
)

const historyLoading = ref(false)
const scrollRef = ref<HTMLElement>()
const historySidebar = ref<InstanceType<typeof ChatHistorySidebar>>()
const viewActive = ref(true)
onActivated(() => {
  viewActive.value = true
})
onDeactivated(() => {
  viewActive.value = false
})
const themeStore = useThemeStore()
const detailsOpen = ref(false)
const detailsMobile = ref(window.matchMedia('(max-width: 1100px)').matches)
const selectedMessageIndex = ref(-1)
const detailsTrigger = ref<{ $el: HTMLButtonElement }>()
const selectedIndex = computed(() => {
  if (selectedMessageIndex.value >= 0) return selectedMessageIndex.value
  const messages = active.value?.messages ?? []
  for (let index = messages.length - 1; index >= 0; index -= 1) {
    if (messages[index].role === 'assistant') return index
  }
  return -1
})
const selectedMessage = computed(() => active.value?.messages[selectedIndex.value])
const selectedRequest = computed(() => active.value?.messages[selectedIndex.value - 1])
const detailsQuery = window.matchMedia('(max-width: 1100px)')
function updateDetailsViewport(event: MediaQueryListEvent) {
  detailsMobile.value = event.matches
}
onMounted(() => detailsQuery.addEventListener('change', updateDetailsViewport))
onBeforeUnmount(() => detailsQuery.removeEventListener('change', updateDetailsViewport))
watch(activeSessionId, () => {
  selectedMessageIndex.value = -1
})
function inspectMessage(index = -1) {
  selectedMessageIndex.value = index
  detailsOpen.value = true
}
function closeDetails() {
  detailsOpen.value = false
  nextTick(() => detailsTrigger.value?.$el.focus())
}

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

/**
 * 页面刷新/组件重建后 store 只含临时空会话时，恢复后端按更新时间倒序返回的第一条历史。
 * 判断集中在共享工具中，确保 Chat/VibeCoding 都不会覆盖显式跳转、草稿或进行中的会话。
 */
function restoreMostRecentSession(targetSessionId: string) {
  if (shouldRestoreMostRecentSession(active.value, targetSessionId, props.initialSessionId)) {
    openSession(targetSessionId)
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
  if (!canSend.value) return
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
  if (conv.streaming) return
  const draft = conv.input
  const attachments = conv.attachments
  conv.input = '请继续刚才的任务。'
  conv.attachments = []
  send()
  conv.input = draft
  conv.attachments = attachments
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
  <div
    class="chat-panel workspace-conversation"
    :class="{ 'has-details': detailsOpen && !detailsMobile }"
  >
    <div class="chat-column">
      <div
        ref="scrollRef"
        class="messages"
        :class="{ 'is-empty': (active?.messages.length ?? 0) === 0 }"
        v-loading="historyLoading"
      >
        <div
          v-for="(msg, index) in active?.messages ?? []"
          :key="index"
          class="message-row"
          :class="msg.role"
        >
          <div class="bubble">
            <AssistantResponse
              v-if="msg.role === 'assistant'"
              :nodes="msg.nodes"
              :text="msg.text"
              :active="isStreamingMessage(index)"
              :failed="msg.failed"
              :error="msg.error"
              :show-trace="false"
              @inspect="inspectMessage(index)"
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
        <WorkspaceConversationEmptyState
          v-if="(active?.messages.length ?? 0) === 0"
          :assistant-name="assistantName"
          @prompt="
            (prompt: string) => {
              if (active) active.input = prompt
            }
          "
        />
      </div>
      <div class="composer-wrap">
        <div class="composer-shell">
          <AttachmentPendingList
            v-if="active && active.attachments.length > 0"
            class="attachment-tags"
            :attachments="active.attachments"
            @remove="removeAttachment"
          />
          <div class="input-bar">
            <el-input
              v-if="active"
              v-model="active.input"
              placeholder="输入消息，回车发送；⌘/Ctrl+V 可粘贴截图或文件作为附件"
              type="textarea"
              :autosize="{ minRows: 2, maxRows: 6 }"
              aria-label="消息内容"
              @keydown="handleComposerKeydown($event, send, !!active?.streaming)"
              @paste="handleAttachmentPaste"
            />
            <div class="composer-send">
              <el-button v-if="!active?.streaming" type="primary" :disabled="!canSend" @click="send"
                >发送</el-button
              >
              <el-button
                v-else
                type="danger"
                :loading="active?.interrupting"
                @click="handleInterrupt"
              >
                {{ active?.interrupting ? '终止中…' : '终止' }}
              </el-button>
              <el-button
                v-if="active?.interrupted && !active?.streaming"
                link
                type="primary"
                @click="resumeInterrupted"
                >继续</el-button
              >
            </div>
          </div>
          <div class="composer-toolbar">
            <span v-if="attachmentBlocked" class="composer-warning" role="status"
              >请等待附件上传完成，或移除失败附件</span
            >
            <el-upload
              :show-file-list="false"
              :http-request="handleAttachmentUpload"
              :before-upload="beforeAttachmentUpload"
              :accept="ATTACHMENT_ACCEPT"
            >
              <el-button
                :disabled="active?.streaming"
                aria-label="上传附件"
                title="上传附件（文档/表格/图片等），随消息一起发给智能体"
              >
                <el-icon><Paperclip /></el-icon>
              </el-button>
            </el-upload>
            <el-button
              ref="detailsTrigger"
              :aria-expanded="detailsOpen"
              aria-controls="chat-execution-details"
              @click="inspectMessage()"
              ><el-icon><List /></el-icon>执行详情</el-button
            >
            <ExecutionModeSelect v-if="active" v-model="active.mode" :disabled="active.streaming" />
          </div>
        </div>
        <p class="composer-hint">
          {{
            active?.streaming
              ? '任务进行中，可继续编写下一条草稿'
              : 'Enter 发送 · Shift + Enter 换行'
          }}
        </p>
      </div>
    </div>
    <aside
      v-if="detailsOpen && !detailsMobile"
      id="chat-execution-details"
      class="execution-details"
      aria-label="执行详情"
      @keydown.esc.stop="closeDetails"
    >
      <header>
        <h2>执行详情</h2>
        <el-button text aria-label="关闭执行详情" @click="closeDetails"
          ><el-icon><Close /></el-icon
        ></el-button>
      </header>
      <ExecutionRecord
        :agent-code="agentCode"
        :assistant-name="assistantName"
        :message="selectedMessage"
        :request="selectedRequest"
        :active="isStreamingMessage(selectedIndex)"
      />
    </aside>
    <el-drawer
      v-if="detailsMobile"
      v-model="detailsOpen"
      title="执行详情"
      size="min(390px, 100vw)"
      append-to-body
      :show-close="false"
      @closed="closeDetails"
    >
      <template #header="{ titleId, titleClass }"
        ><h2 :id="titleId" :class="titleClass">执行详情</h2>
        <el-button text aria-label="关闭执行详情" @click="closeDetails"
          ><el-icon><Close /></el-icon></el-button
      ></template>
      <ExecutionRecord
        :agent-code="agentCode"
        :assistant-name="assistantName"
        :message="selectedMessage"
        :request="selectedRequest"
        :active="isStreamingMessage(selectedIndex)"
      />
    </el-drawer>
    <Teleport defer to="#workspace-history-slot" :disabled="!historyActive || !viewActive">
      <div v-show="historyActive && viewActive" class="workspace-history-content">
        <ChatHistorySidebar
          ref="historySidebar"
          :agent-code="agentCode"
          :active-session-id="activeSessionId"
          :live-sessions="liveSessions"
          :collapsible="false"
          @select="openSession"
          @initial-session="restoreMostRecentSession"
        />
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.chat-panel {
  --conversation-messages-padding: 28px 32px 36px;
  --conversation-composer-padding: 10px 28px 16px;
  grid-template-columns: minmax(0, 1fr);
}
.chat-panel.has-details {
  grid-template-columns: minmax(0, 1fr) 320px;
}
.execution-details {
  min-height: 0;
  overflow: auto;
  border-left: 1px solid var(--cw-line);
  background: var(--cw-paper);
}
.execution-details > header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  position: sticky;
  top: 0;
  z-index: 2;
  background: var(--cw-paper);
  padding: 8px 12px 8px 18px;
  border-bottom: 1px solid var(--cw-line);
}
.execution-details h2 {
  font-size: 13px;
  margin: 0;
  font-weight: 600;
}
@container workspace-panel (max-width: 700px) {
  .chat-panel {
    --conversation-messages-padding: 20px 16px;
    --conversation-composer-padding: 8px 12px 12px;
    --conversation-user-bubble-max-width: 90%;
  }
}
</style>
