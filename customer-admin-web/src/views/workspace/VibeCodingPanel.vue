<script setup lang="ts">
import { computed, nextTick, onActivated, onBeforeUnmount, onMounted, onUnmounted, ref, useId, watch } from 'vue'
import { createScrollFollower } from '@/utils/scrollFollower'
import { shouldRestoreMostRecentSession } from '@/utils/conversationRestore'
import { ATTACHMENT_ACCEPT, useChatAttachments } from '@/composables/useChatAttachments'
import { useVibeCodingPanelResize } from '@/composables/useVibeCodingPanelResize'
import {
  confirmVibeCodingPlan,
  generateCommitMessage,
  generatePrDescription,
  getGitDiffSummary,
  getReviewTask,
  readWorkspaceFileContent,
  reviewVibeCoding,
  rollbackVibeCoding,
  saveWorkspaceFileContent,
} from '@/api/vibecoding'
import MarkdownRenderer from '@/components/MarkdownRenderer.vue'
import AssistantResponse from '@/components/AssistantResponse.vue'
import ChatHistorySidebar from '@/components/ChatHistorySidebar.vue'
import ReviewReport from '@/components/ReviewReport.vue'
import ExecutionModeSelect from '@/components/ExecutionModeSelect.vue'
import PlanConfirmCard from '@/components/PlanConfirmCard.vue'
import VibeCodingToolsDrawer from '@/components/VibeCodingToolsDrawer.vue'
import AttachmentPendingList from '@/components/attachment/AttachmentPendingList.vue'
import MessageAttachments from '@/components/attachment/MessageAttachments.vue'
import WorkspaceConversationEmptyState from '@/components/workspace/WorkspaceConversationEmptyState.vue'
import { useThemeStore } from '@/store/theme'
import '@/styles/workspace-conversation.css'
import {
  useVibeConversationsStore,
  type PlanCard,
  type VibeConversation,
} from '@/store/vibeConversations'
import type {
  GitDiffSummary,
  ReviewIssue,
  ReviewResult,
  RoleStageEvent,
  TestReport,
  WorkspaceFileContent,
  WorkspaceFileNode,
  RefactorTaskRequest,
} from '@/types/api'
import hljs from 'highlight.js'
import 'highlight.js/styles/github.css'

// AI 代码审查异步化（提交后轮询任务终态）：5 秒一次，最多轮询 40 次（约 200 秒）后停止，
// 提示用户可等站内信通知——避免无限轮询在模型响应异常慢/任务卡住时占着定时器不放。
const REVIEW_POLL_INTERVAL_MS = 5000
const MAX_REVIEW_POLL_COUNT = 40

const props = defineProps<{ agentCode: string; assistantName: string; initialSessionId?: string }>()

/**
 * 会话状态全部在 Pinia store（vibeConversations）里，本组件只是视图：切页面、切智能体、组件销毁
 * 重建都不影响 store 里进行中的会话与 SSE 流。组件保留的只有纯 UI 状态（抽屉、预览、滚动容器）。
 */
const store = useVibeConversationsStore()
store.ensureAgent(props.agentCode)

const active = computed<VibeConversation | undefined>(() => store.activeOf(props.agentCode))
const liveSessions = computed(() => store.liveSessionsOf(props.agentCode))
const activeSessionId = computed(() => store.activeIdOf(props.agentCode))
const anyAttachmentUploading = computed(() => active.value?.attachments.some((a) => a.status === 'uploading') ?? false)

const input = computed({
  get: () => active.value?.input ?? '',
  set: (v) => { if (active.value) active.value.input = v },
})
// 协作模式开关（P3-1）：发送选项，面板级
const collaborationMode = ref(false)
const historyLoading = ref(false)
const scrollRef = ref<HTMLElement>()
const historySidebar = ref<InstanceType<typeof ChatHistorySidebar>>()
const historyCollapsed = ref(false)
const panelRef = ref<HTMLElement>()
const chatColumnId = useId()
const artifactsColumnId = useId()
const {
  ariaValueText: artifactsResizeAriaValueText,
  effectiveWidth: effectiveArtifactsWidth,
  handleKeydown: handleArtifactsResizeKeydown,
  handleLostPointerCapture: handleArtifactsResizeLostPointerCapture,
  handlePointerCancel: handleArtifactsResizePointerCancel,
  handlePointerDown: handleArtifactsResizePointerDown,
  handlePointerMove: handleArtifactsResizePointerMove,
  handlePointerUp: handleArtifactsResizePointerUp,
  isResizing: isArtifactsResizing,
  layout: artifactsResizeLayout,
  panelStyle,
  resetWidth: resetArtifactsWidth,
  resizeEnabled: artifactsResizeEnabled,
} = useVibeCodingPanelResize({ panelRef, historyCollapsed })
const themeStore = useThemeStore()
const toolsDrawer = ref<InstanceType<typeof VibeCodingToolsDrawer>>()

// Git 助手抽屉（面板级，操作对象是当前激活会话）
const gitDrawerVisible = ref(false)
const gitDiffLoading = ref(false)
const gitDiff = ref<GitDiffSummary | null>(null)
const commitStyle = ref<'conventional' | 'simple'>('conventional')
const commitLoading = ref(false)
const commitMessageText = ref('')
const prLoading = ref(false)
const prDescriptionText = ref('')
// AI 代码审查（P0-2，异步化）：reviewLoading 覆盖"提交中 + 轮询中"两个阶段。
// reviewPollTimer/reviewPollSessionId/reviewPollCount 是纯轮询控制状态，不需要响应式（不驱动模板），
// 用普通变量即可；reviewPollSessionId 用来在轮询回调触发时判断会话是否已切换，切换了就丢弃本次结果。
const reviewLoading = ref(false)
const reviewResult = ref<ReviewResult | null>(null)
let reviewPollTimer: ReturnType<typeof setTimeout> | null = null
let reviewPollSessionId: string | null = null
let reviewPollCount = 0

// 文件预览抽屉
const previewVisible = ref(false)
const previewLoading = ref(false)
const previewFile = ref<WorkspaceFileContent | null>(null)
const previewCodeRef = ref<HTMLElement>()
// 编辑模式
const editMode = ref(false)
const editContent = ref('')
const saving = ref(false)

function newSession() {
  store.newSession(props.agentCode)
}

function collapseHistory() {
  historyCollapsed.value = true
}

function restoreHistory() {
  historyCollapsed.value = false
}

// 附件上传（回形针按钮 + 输入框 ⌘/Ctrl+V 粘贴）统一走组合式函数，与 ChatPanel 共用同一条链路
const { beforeAttachmentUpload, handleAttachmentUpload, handleAttachmentPaste, removeAttachment } =
  useChatAttachments({
    agentCode: () => props.agentCode,
    channel: 'vibecoding',
    getConversation: () => active.value,
  })

/** 只拼成功解析的附件，上传中/失败的附件不参与（失败的已经在上传回调里提示过用户）。 */
function buildMessageWithAttachments(conv: VibeConversation, text: string): string {
  const successful = conv.attachments.filter((a) => a.status === 'success')
  if (successful.length === 0) return text
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

/** Chat 面板同语义：只用最新历史替换首次初始化的纯空占位，不覆盖用户当前状态。 */
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

/** 只有当前会话最后一条助手消息属于本轮 SSE；正文开始输出后仍保持执行中，直到流真正完成。 */
function isStreamingMessage(index: number): boolean {
  return !!active.value?.streaming && index === active.value.messages.length - 1
}

onBeforeUnmount(() => scrollFollower.cancel())

function send() {
  store.send(props.agentCode, collaborationMode.value, buildMessageWithAttachments, scrollToBottom)
}

function handleInterrupt() {
  store.interrupt(props.agentCode)
}

function handleDiagnosis(log: string) {
  store.sendDiagnosis(props.agentCode, log, scrollToBottom)
}

function handleRefactor(request: Omit<RefactorTaskRequest, 'sessionId'>) {
  store.sendRefactor(props.agentCode, request, scrollToBottom)
}

/** 点击"继续"：发一句非空续接文案触发框架续跑被打断的挂起工具调用。 */
function resumeInterrupted() {
  const conv = active.value
  if (!conv) return
  conv.interrupted = false
  conv.input = '请继续刚才的任务。'
  send()
}

function refreshFiles() {
  const conv = active.value
  if (!conv) return
  store.loadFiles(props.agentCode, conv.sessionId)
}

/** 阶段状态标签文案。 */
function roleStageStatusText(status: RoleStageEvent['status']): string {
  const map: Record<RoleStageEvent['status'], string> = {
    START: '进行中',
    DONE: '完成',
    FAILED: '失败',
  }
  return map[status]
}

/** 阶段状态标签色（el-tag type）。 */
function roleStageStatusTag(status: RoleStageEvent['status']): 'primary' | 'success' | 'danger' {
  if (status === 'DONE') return 'success'
  if (status === 'FAILED') return 'danger'
  return 'primary'
}

/** 阶段类型徽标文案。 */
function roleStageTypeText(type: RoleStageEvent['type']): string {
  const map: Record<RoleStageEvent['type'], string> = {
    PLAN: '规划',
    CODING: '编码',
    REVIEW: '审查',
  }
  return map[type]
}

/** 用户对某个计划卡片点批准/拒绝：调后端确认接口，成功后翻成终态。卡片属于当前激活会话（视图只渲染 active）。 */
async function handlePlanDecision(card: PlanCard, approved: boolean) {
  const conv = active.value
  if (!conv || card.status !== 'PENDING' || card.submitting) return
  card.submitting = true
  try {
    await confirmVibeCodingPlan(props.agentCode, {
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

/** 测试报告卡片标题：命令 + 轮次 + 通过/失败概览。 */
function testReportTitle(report: TestReport): string {
  const counts = report.command.startsWith('mvn test')
    ? ` · 通过 ${report.passed} / 失败 ${report.failed}${report.skipped ? ` / 跳过 ${report.skipped}` : ''}`
    : ` · 退出码 ${report.exitCode ?? '—'}`
  return `第 ${report.round} 轮 · ${report.command}${counts}`
}

/**
 * 撤销本次会话的全部文件改动（回滚到对话前的 baseline 状态）。
 * 破坏性操作，二次确认后调用；成功后清空变更时间线、刷新文件树与 diff，并在对话流插入系统提示。
 */
async function handleRollback() {
  const conv = active.value
  if (!conv) return
  try {
    await ElMessageBox.confirm(
      '此操作将丢弃本次会话对工作区的全部文件改动：新增文件将被删除，修改/删除的文件将恢复到对话前的状态。操作不可撤销，是否继续？',
      '撤销全部修改',
      { type: 'warning', confirmButtonText: '确认撤销', cancelButtonText: '取消' },
    )
  } catch {
    return // 用户取消
  }
  conv.rollingBack = true
  try {
    const res = await rollbackVibeCoding(props.agentCode, conv.sessionId)
    conv.fileChanges = []
    await store.loadFiles(props.agentCode, conv.sessionId)
    // Git 助手抽屉开着则刷新 diff（回滚后应无变更）
    if (gitDrawerVisible.value) await loadGitDiff()
    // 对话流插入一条系统提示（需求 §4.1.2）
    conv.messages.push({
      role: 'assistant',
      text: `🔄 已撤销本次会话的全部修改（恢复 ${res.restoredFiles.length} 个文件，删除 ${res.deletedFiles.length} 个新增文件）。`,
      nodes: [],
    })
    scrollToBottom()
    ElMessage.success('已撤销本次会话的全部修改')
  } catch (error) {
    ElMessage.error('撤销失败：' + (error instanceof Error ? error.message : String(error)))
  } finally {
    conv.rollingBack = false
  }
}

/** 打开 Git 助手抽屉，并立即加载一次 diff 摘要。 */
async function openGitAssistant() {
  gitDrawerVisible.value = true
  gitDiff.value = null
  commitMessageText.value = ''
  prDescriptionText.value = ''
  reviewResult.value = null
  stopReviewPolling()
  reviewLoading.value = false
  await loadGitDiff()
}

async function loadGitDiff() {
  const conv = active.value
  if (!conv) return
  gitDiffLoading.value = true
  try {
    gitDiff.value = await getGitDiffSummary(props.agentCode, conv.sessionId)
  } catch (error) {
    ElMessage.error('diff 摘要加载失败：' + (error instanceof Error ? error.message : String(error)))
  } finally {
    gitDiffLoading.value = false
  }
}

async function handleGenerateCommitMessage() {
  const conv = active.value
  if (!conv) return
  commitLoading.value = true
  try {
    const res = await generateCommitMessage(props.agentCode, { sessionId: conv.sessionId, style: commitStyle.value })
    commitMessageText.value = res.message
  } catch (error) {
    ElMessage.error('commit message 生成失败：' + (error instanceof Error ? error.message : String(error)))
  } finally {
    commitLoading.value = false
  }
}

async function handleGeneratePrDescription() {
  const conv = active.value
  if (!conv) return
  prLoading.value = true
  try {
    const res = await generatePrDescription(props.agentCode, conv.sessionId)
    prDescriptionText.value = res.description
  } catch (error) {
    ElMessage.error('PR description 生成失败：' + (error instanceof Error ? error.message : String(error)))
  } finally {
    prLoading.value = false
  }
}

/**
 * 触发 AI 代码审查（异步化）：提交后拿到 taskId 立即返回，不再同步等待模型产出结果——
 * 提交成功只代表任务已入队，真正的审查结果通过下方轮询（或稍后到达的站内信）拿到。
 */
async function handleReview() {
  const conv = active.value
  if (!conv) return
  stopReviewPolling()
  reviewResult.value = null
  reviewLoading.value = true
  try {
    const taskId = await reviewVibeCoding(props.agentCode, conv.sessionId)
    ElMessage.success('审查已提交，完成后会通过站内信提醒')
    startReviewPolling(conv.sessionId, taskId)
  } catch (error) {
    reviewLoading.value = false
    ElMessage.error('代码审查提交失败：' + (error instanceof Error ? error.message : String(error)))
  }
}

/** 开始轮询审查任务终态：5 秒一次，最多 40 次，会话切走则丢弃结果并停止。 */
function startReviewPolling(sessionId: string, taskId: number) {
  reviewPollSessionId = sessionId
  reviewPollCount = 0
  const poll = async () => {
    // 轮询期间用户切到了别的会话/新建了会话：本次审查结果不再对应当前视图，结果留给站内信承接
    if (active.value?.sessionId !== reviewPollSessionId) {
      stopReviewPolling()
      return
    }
    reviewPollCount += 1
    try {
      const task = await getReviewTask(props.agentCode, taskId)
      if (task.status === 'SUCCESS') {
        reviewResult.value = task.result
        reviewLoading.value = false
        stopReviewPolling()
        return
      }
      if (task.status === 'FAILED') {
        ElMessage.error(task.errorMsg || '代码审查失败')
        reviewLoading.value = false
        stopReviewPolling()
        return
      }
    } catch (error) {
      // 轮询请求本身失败（网络抖动等）不等于审查失败，按剩余次数继续重试，次数耗尽再提示
      if (reviewPollCount >= MAX_REVIEW_POLL_COUNT) {
        reviewLoading.value = false
        stopReviewPolling()
        ElMessage.error('代码审查结果查询失败：' + (error instanceof Error ? error.message : String(error)))
        return
      }
      reviewPollTimer = setTimeout(poll, REVIEW_POLL_INTERVAL_MS)
      return
    }
    // 仍在 RUNNING：达到最大轮询次数则停止，剩余交给站内信兜底
    if (reviewPollCount >= MAX_REVIEW_POLL_COUNT) {
      reviewLoading.value = false
      stopReviewPolling()
      ElMessage.info('审查仍在进行，可稍后在站内信查看结果')
      return
    }
    reviewPollTimer = setTimeout(poll, REVIEW_POLL_INTERVAL_MS)
  }
  reviewPollTimer = setTimeout(poll, REVIEW_POLL_INTERVAL_MS)
}

function stopReviewPolling() {
  if (reviewPollTimer !== null) {
    clearTimeout(reviewPollTimer)
    reviewPollTimer = null
  }
  reviewPollSessionId = null
}

/** 点击审查意见里的文件，定位到工作区文件查看器（复用现有文件读取/预览抽屉）。 */
async function openIssueFile(issue: ReviewIssue) {
  if (!issue.file) return
  await openFilePreview({ name: issue.file, relativePath: issue.file, directory: false, children: [] })
}

/**
 * 一键生成修复（需求 §4.2.2.4）：把 CRITICAL/WARNING 意见拼成用户消息发回 stream 对话，
 * 由 Agent 走既有 VibeCoding 链路修复（天然带 file_change 与回滚保障）。
 */
function generateFixFromReview() {
  const conv = active.value
  if (!conv) return
  const result = reviewResult.value
  if (!result || result.issues.length === 0) return
  const actionable = result.issues.filter((i) => i.severity === 'CRITICAL' || i.severity === 'WARNING')
  if (actionable.length === 0) {
    ElMessage.info('没有需要修复的 CRITICAL/WARNING 问题')
    return
  }
  const lines = actionable.map(
    (i) => `- [${i.severity}] ${i.file}${i.line ? `:${i.line}` : ''} ${i.message}（建议：${i.suggestion}）`,
  )
  conv.input = `请根据以下代码审查意见修复问题，并在沙箱内重新验证：\n${lines.join('\n')}`
  gitDrawerVisible.value = false
  send()
}

async function copyToClipboard(text: string, label: string) {
  if (!text) return
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success(`${label}已复制`)
  } catch {
    ElMessage.error('复制失败，请手动选择文本复制')
  }
}

/** 点击文件节点，打开预览抽屉并加载内容。 */
async function openFilePreview(node: WorkspaceFileNode) {
  const conv = active.value
  if (!conv || node.directory) return
  previewVisible.value = true
  previewLoading.value = true
  previewFile.value = null
  editMode.value = false
  editContent.value = ''
  try {
    previewFile.value = await readWorkspaceFileContent(props.agentCode, conv.sessionId, node.relativePath)
    // 等 DOM 更新后触发代码高亮
    await nextTick()
    highlightPreview()
  } catch (error) {
    ElMessage.error('文件读取失败：' + (error instanceof Error ? error.message : String(error)))
    previewVisible.value = false
  } finally {
    previewLoading.value = false
  }
}

/** 进入编辑模式。 */
function enterEditMode() {
  if (!previewFile.value || previewFile.value.truncated) return
  editContent.value = previewFile.value.content
  editMode.value = true
}

/** 取消编辑，还原到预览模式。 */
async function cancelEdit() {
  editMode.value = false
  editContent.value = ''
  await nextTick()
  highlightPreview()
}

/** 保存编辑内容到服务端文件。 */
async function saveEdit() {
  const conv = active.value
  if (!conv || !previewFile.value) return
  saving.value = true
  try {
    await saveWorkspaceFileContent(props.agentCode, {
      sessionId: conv.sessionId,
      relativePath: previewFile.value.relativePath,
      content: editContent.value,
    })
    // 更新本地缓存，切回预览模式并重新高亮
    previewFile.value = { ...previewFile.value, content: editContent.value }
    editMode.value = false
    ElMessage.success('保存成功')
    await nextTick()
    highlightPreview()
  } catch (error) {
    ElMessage.error('保存失败：' + (error instanceof Error ? error.message : String(error)))
  } finally {
    saving.value = false
  }
}

function highlightPreview() {
  if (previewCodeRef.value && previewFile.value && !previewFile.value.truncated) {
    previewCodeRef.value.removeAttribute('data-highlighted')
    previewCodeRef.value.className = `language-${previewFile.value.language}`
    hljs.highlightElement(previewCodeRef.value)
  }
}

onMounted(() => {
  themeStore.apply()
})

// 每轮对话流结束（store 里自增版本号）刷新侧边栏的后端历史列表。
watch(
  () => store.historyVersion[props.agentCode],
  () => historySidebar.value?.refresh(),
)

// 从「智能体耗时统计」页「打开会话」跳转过来时带上目标会话 id，直接打开对应历史会话。
// 用 watch 而非 onMounted 一次性读取：本页处于 keep-alive 下，二次带新 sessionId 跳进来不会重新
// mount。immediate 保留首挂载即打开，逻辑镜像 ChatPanel 里同名 watch。
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

// 注意：刻意没有 onUnmounted abort/清 SSE/plan 倒计时定时器——那些都属于全局 store，
// 组件销毁（切智能体/关标签）不应终止后台会话。但 AI 代码审查轮询定时器是本组件的纯 UI 局部状态
// （审查任务本身在后端异步跑，不受前端轮询影响），组件销毁时必须清掉，否则定时器泄漏到已卸载组件。
onUnmounted(() => {
  stopReviewPolling()
})

// newSession 供 WorkspaceView 上提后的工具栏"新建会话"按钮按激活 Tab 分发调用
defineExpose({ newSession })
</script>

<template>
  <div
    ref="panelRef"
    class="vibecoding-panel workspace-conversation"
    :class="{
      'is-history-collapsed': historyCollapsed,
      'is-resizing': isArtifactsResizing,
    }"
    :style="panelStyle"
  >
    <!-- 左列：对话区 -->
    <div :id="chatColumnId" class="chat-column">
      <div
        ref="scrollRef"
        class="messages"
        :class="{ 'is-empty': (active?.messages.length ?? 0) === 0 }"
        v-loading="historyLoading"
      >
        <div v-for="(msg, index) in active?.messages ?? []" :key="index" class="message-row" :class="msg.role">
          <div class="bubble">
            <AssistantResponse
              v-if="msg.role === 'assistant'"
              :nodes="msg.nodes"
              :text="msg.text"
              :active="isStreamingMessage(index)"
              :failed="msg.failed"
            >
              <!-- 沙箱编译/测试报告卡片时间线（P0-3）：每轮验证一张，通过绿/失败红，可展开看失败明细 -->
              <div
                v-if="msg.testReports && msg.testReports.length > 0"
                class="test-reports"
              >
                <el-collapse>
                  <el-collapse-item v-for="(report, ri) in msg.testReports" :key="ri" :name="ri">
                    <template #title>
                      <span class="test-report-title" :class="report.success ? 'is-success' : 'is-failure'">
                        <el-icon v-if="report.success"><SuccessFilled /></el-icon>
                        <el-icon v-else><CircleCloseFilled /></el-icon>
                        <span class="test-report-title-text">{{ testReportTitle(report) }}</span>
                        <el-tag v-if="report.exhausted" type="danger" size="small" effect="dark" class="exhausted-tag">
                          已达最大修复轮次
                        </el-tag>
                      </span>
                    </template>
                    <div v-if="report.durationMs != null" class="test-report-meta">耗时 {{ report.durationMs }} ms</div>
                    <ul v-if="report.failureDetails.length > 0" class="test-report-failures">
                      <li v-for="(d, di) in report.failureDetails" :key="di">{{ d }}</li>
                    </ul>
                    <pre v-if="report.rawOutput" class="test-report-raw">{{ report.rawOutput }}</pre>
                    <div
                      v-if="report.success && report.failureDetails.length === 0 && !report.rawOutput"
                      class="test-report-ok"
                    >
                      验证通过 ✓
                    </div>
                  </el-collapse-item>
                </el-collapse>
              </div>
              <!-- 协作模式多角色阶段进度（P3-1）：每个角色一张卡片，展示状态标签、类型徽标与文本产物 -->
              <div
                v-if="msg.stages && msg.stages.length > 0"
                class="role-stages"
              >
                <div
                  v-for="(stage, si) in msg.stages"
                  :key="si"
                  class="role-stage-card"
                  :class="`role-stage-card--${stage.status.toLowerCase()}`"
                >
                  <div class="role-stage-header">
                    <span class="role-stage-name">{{ stage.role }}</span>
                    <el-tag size="small" class="role-stage-type">{{ roleStageTypeText(stage.type) }}</el-tag>
                    <span class="role-stage-index">{{ stage.index }}/{{ stage.total }}</span>
                    <el-tag
                      :type="roleStageStatusTag(stage.status)"
                      size="small"
                      effect="dark"
                      class="role-stage-status"
                    >
                      {{ roleStageStatusText(stage.status) }}
                    </el-tag>
                  </div>
                  <div v-if="stage.output" class="role-stage-output">
                    <MarkdownRenderer :text="stage.output" />
                  </div>
                </div>
              </div>
              <!-- Plan Mode 确认卡片（P1-1 HITL）：高风险操作待人工确认，批准/拒绝按钮 + 倒计时 -->
              <PlanConfirmCard
                v-if="msg.plans && msg.plans.length > 0"
                :plans="msg.plans"
                @decision="handlePlanDecision"
              />
            </AssistantResponse>
            <template v-if="msg.role === 'user'">{{ msg.text }}</template>
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
              v-model="input"
              placeholder="描述需求，回车发送；⌘/Ctrl+V 可粘贴截图或文件作为附件"
              :disabled="active?.streaming"
              @keyup.enter="send"
              @paste="handleAttachmentPaste"
            />
            <div class="composer-send">
              <el-button v-if="!active?.streaming" type="primary" :disabled="anyAttachmentUploading" @click="send">发送</el-button>
              <el-button v-else type="danger" :loading="active?.interrupting" @click="handleInterrupt">
                {{ active?.interrupting ? '终止中…' : '终止' }}
              </el-button>
              <el-button v-if="active?.interrupted && !active?.streaming" link type="primary" @click="resumeInterrupted">继续</el-button>
            </div>
          </div>
          <div class="composer-toolbar">
            <el-button title="运行命令、诊断日志、自动化重构与沙箱管理" @click="toolsDrawer?.open('run')">
              <el-icon><Tools /></el-icon>
              开发工具
            </el-button>
            <el-upload :show-file-list="false" :http-request="handleAttachmentUpload" :before-upload="beforeAttachmentUpload" :accept="ATTACHMENT_ACCEPT">
              <el-button :disabled="active?.streaming" title="上传附件（文档/表格/图片等），随消息一起发给智能体">
                <el-icon><Paperclip /></el-icon>
              </el-button>
            </el-upload>
            <ExecutionModeSelect
              v-if="active"
              v-model="active.mode"
              :disabled="active.streaming"
            />
            <el-tooltip
              placement="top"
              content="开启后按 需求分析→方案设计→编码实现→自测审查 多角色顺序协作"
            >
              <el-switch
                v-model="collaborationMode"
                :disabled="active?.streaming"
                active-text="协作模式"
                class="collaboration-switch"
              />
            </el-tooltip>
          </div>
        </div>
      </div>
    </div>

    <!-- 中列：产物文件树 -->
    <div :id="artifactsColumnId" class="artifacts-column">
      <button
        v-if="artifactsResizeEnabled"
        type="button"
        class="artifacts-resizer"
        role="separator"
        aria-label="调整对话区与产物文件区宽度"
        aria-orientation="vertical"
        :aria-controls="`${chatColumnId} ${artifactsColumnId}`"
        :aria-valuemin="artifactsResizeLayout.minWidth"
        :aria-valuemax="artifactsResizeLayout.maxWidth"
        :aria-valuenow="effectiveArtifactsWidth"
        :aria-valuetext="artifactsResizeAriaValueText"
        title="拖动调整产物文件区宽度；双击恢复默认宽度"
        @pointerdown="handleArtifactsResizePointerDown"
        @pointermove="handleArtifactsResizePointerMove"
        @pointerup="handleArtifactsResizePointerUp"
        @pointercancel="handleArtifactsResizePointerCancel"
        @lostpointercapture="handleArtifactsResizeLostPointerCapture"
        @keydown="handleArtifactsResizeKeydown"
        @dblclick="resetArtifactsWidth"
      >
        <span class="artifacts-resizer-grip" aria-hidden="true"><i /><i /></span>
        <span class="artifacts-resizer-value" aria-hidden="true">{{ effectiveArtifactsWidth }} px</span>
      </button>
      <div class="artifacts-header">
        <span>
          产物文件
          <el-tag v-if="active?.sandboxMode" size="small" :type="active.sandboxMode === 'docker' ? 'warning' : 'info'" class="sandbox-mode-tag">
            {{ active.sandboxMode === 'docker' ? 'docker' : 'local' }}
          </el-tag>
        </span>
        <div class="artifacts-header-actions">
          <el-button link type="primary" @click="openGitAssistant">Git 助手</el-button>
          <el-button link type="primary" :loading="active?.filesLoading" @click="refreshFiles">刷新</el-button>
        </div>
      </div>

      <!-- 实时文件变更时间线 -->
      <div v-if="active && active.fileChanges.length > 0" class="file-change-timeline">
        <div class="file-change-timeline-header">
          <span class="file-change-timeline-title">本轮变更</span>
          <el-button
            link
            type="danger"
            size="small"
            :loading="active?.rollingBack"
            :disabled="active?.streaming"
            title="撤销本次会话的全部文件改动，恢复到对话前状态"
            @click="handleRollback"
          >
            撤销全部修改
          </el-button>
        </div>
        <el-scrollbar max-height="120px">
          <div v-for="(fc, idx) in active.fileChanges" :key="idx" class="file-change-item">
            <el-icon v-if="fc.operation === 'CREATE'" style="color:#67c23a"><CirclePlus /></el-icon>
            <el-icon v-else-if="fc.operation === 'MODIFY'" style="color:#e6a23c"><EditPen /></el-icon>
            <el-icon v-else style="color:#f56c6c"><Delete /></el-icon>
            <span class="file-change-path" :title="fc.path">{{ fc.path }}</span>
          </div>
        </el-scrollbar>
      </div>

      <!-- 空状态 -->
      <el-empty
        v-if="!active?.filesLoaded"
        description="对话结束后自动刷新"
        :image-size="50"
      />
      <el-empty
        v-else-if="active?.filesLoaded && active.fileNodes.length === 0"
        description="本次会话暂无产出文件"
        :image-size="50"
      />

      <!-- 目录树 -->
      <el-scrollbar v-else height="100%">
        <el-tree
          :data="active?.fileNodes ?? []"
          :props="{ label: 'name', children: 'children', isLeaf: (n: WorkspaceFileNode) => !n.directory }"
          node-key="relativePath"
          default-expand-all
          highlight-current
          @node-click="openFilePreview"
        >
          <template #default="{ node, data }">
            <span class="tree-node">
              <el-icon v-if="data.directory" style="margin-right:4px;color:#e6a23c"><Folder /></el-icon>
              <el-icon v-else style="margin-right:4px;color:var(--theme-primary, #409eff)"><Document /></el-icon>
              <span :title="data.relativePath">{{ node.label }}</span>
            </span>
          </template>
        </el-tree>
      </el-scrollbar>
    </div>

    <!-- 右列：历史会话 -->
    <div class="history-column" :aria-hidden="historyCollapsed">
      <ChatHistorySidebar
        ref="historySidebar"
        :agent-code="agentCode"
        :active-session-id="activeSessionId"
        :live-sessions="liveSessions"
        @select="openSession"
        @initial-session="restoreMostRecentSession"
        @collapse="collapseHistory"
      />
    </div>

    <el-button
      v-if="historyCollapsed"
      class="history-restore"
      circle
      title="展开历史会话"
      aria-label="展开历史会话"
      @click="restoreHistory"
    >
      <el-icon><Expand /></el-icon>
    </el-button>

    <!-- 文件内容预览抽屉 -->
    <el-drawer
      v-model="previewVisible"
      direction="rtl"
      size="55%"
      :destroy-on-close="false"
    >
      <!-- 自定义标题：文件路径 + 右侧预览/编辑按鈕 -->
      <template #header>
        <div class="drawer-header">
          <span class="drawer-title">{{ previewFile?.relativePath ?? '文件预览' }}</span>
          <div class="drawer-actions">
            <template v-if="!editMode">
              <el-button
                size="small"
                :disabled="!previewFile || previewFile.truncated"
                :title="previewFile?.truncated ? '文件过大，不支持编辑' : '编辑文件'"
                @click="enterEditMode"
              >
                <el-icon style="margin-right:4px"><Edit /></el-icon>编辑
              </el-button>
            </template>
            <template v-else>
              <el-button size="small" @click="cancelEdit" :disabled="saving">取消</el-button>
              <el-button size="small" type="primary" :loading="saving" @click="saveEdit">保存</el-button>
            </template>
          </div>
        </div>
      </template>

      <div v-if="previewLoading" class="preview-loading">
        <el-icon class="is-loading" :size="24"><Loading /></el-icon>
        <span>加载中…</span>
      </div>
      <div v-else-if="previewFile">
        <div v-if="previewFile.truncated" class="preview-truncated">
          {{ previewFile.content }}
        </div>
        <!-- 预览模式 -->
        <el-scrollbar v-else-if="!editMode" height="calc(100vh - 120px)">
          <pre class="code-block"><code ref="previewCodeRef" :class="`language-${previewFile.language}`">{{ previewFile.content }}</code></pre>
        </el-scrollbar>
        <!-- 编辑模式 -->
        <el-scrollbar v-else height="calc(100vh - 120px)">
          <textarea
            v-model="editContent"
            class="code-editor"
            spellcheck="false"
            :placeholder="'请输入文件内容…'"
          />
        </el-scrollbar>
      </div>
    </el-drawer>

    <!-- Git 助手抽屉 -->
    <el-drawer v-model="gitDrawerVisible" direction="rtl" size="45%" title="Git 助手">
      <div class="git-assistant">
        <div class="git-section">
          <div class="git-section-header">
            <span>变更摘要</span>
            <el-button link type="primary" :loading="gitDiffLoading" @click="loadGitDiff">刷新</el-button>
          </div>
          <div v-if="gitDiffLoading" class="git-loading">
            <el-icon class="is-loading"><Loading /></el-icon>
            <span>加载中…</span>
          </div>
          <template v-else-if="gitDiff">
            <p class="git-summary-text">{{ gitDiff.summary }}</p>
            <div v-if="gitDiff.changedFiles.length > 0" class="git-changed-files">
              <el-tag v-for="f in gitDiff.changedFiles" :key="f" size="small" class="git-changed-file-tag">{{ f }}</el-tag>
            </div>
          </template>
        </div>

        <el-divider />

        <div class="git-section">
          <div class="git-section-header">
            <span>Commit Message</span>
            <el-radio-group v-model="commitStyle" size="small">
              <el-radio-button value="conventional">Conventional</el-radio-button>
              <el-radio-button value="simple">Simple</el-radio-button>
            </el-radio-group>
          </div>
          <el-button type="primary" size="small" :loading="commitLoading" @click="handleGenerateCommitMessage">生成</el-button>
          <el-input
            v-if="commitMessageText"
            v-model="commitMessageText"
            type="textarea"
            :rows="3"
            readonly
            class="git-result-text"
          />
          <el-button v-if="commitMessageText" link type="primary" @click="copyToClipboard(commitMessageText, 'commit message')">
            复制
          </el-button>
        </div>

        <el-divider />

        <div class="git-section">
          <div class="git-section-header">
            <span>PR Description</span>
          </div>
          <el-button type="primary" size="small" :loading="prLoading" @click="handleGeneratePrDescription">生成</el-button>
          <div v-if="prDescriptionText" class="git-pr-description">
            <MarkdownRenderer :text="prDescriptionText" />
          </div>
          <el-button v-if="prDescriptionText" link type="primary" @click="copyToClipboard(prDescriptionText, 'PR description')">
            复制
          </el-button>
        </div>

        <el-divider />

        <!-- AI 代码审查（P0-2）：对本轮 diff 输出结构化审查意见，按严重级别分组着色 -->
        <div class="git-section">
          <div class="git-section-header">
            <span>Review 本次变更</span>
          </div>
          <el-button type="primary" size="small" :loading="reviewLoading" @click="handleReview">
            {{ reviewLoading ? '审查中…' : '审查' }}
          </el-button>
          <ReviewReport
            v-if="reviewResult"
            :result="reviewResult"
            :fix-disabled="active?.streaming"
            @open-file="openIssueFile"
            @generate-fix="generateFixFromReview"
          />
        </div>
      </div>
    </el-drawer>

    <VibeCodingToolsDrawer
      ref="toolsDrawer"
      :agent-code="agentCode"
      :conversation="active"
      @diagnose="handleDiagnosis"
      @refactor="handleRefactor"
    />
  </div>
</template>

<style scoped>
.vibecoding-panel {
  --conversation-messages-padding: 28px 28px 36px;
  --conversation-composer-padding: 10px 20px 14px;
  --vibe-artifacts-column-width: 260px;
  grid-template-columns: minmax(520px, 1fr) minmax(220px, var(--vibe-artifacts-column-width)) 300px;
}

.vibecoding-panel.is-history-collapsed {
  grid-template-columns: minmax(520px, 1fr) minmax(220px, var(--vibe-artifacts-column-width)) 0;
}

.vibecoding-panel.is-resizing {
  transition: none;
}

/* 产物文件树列 */
.artifacts-column {
  position: relative;
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
  padding: 15px 14px 12px 16px;
  background: color-mix(in srgb, var(--el-fill-color-lighter) 42%, var(--el-bg-color));
  border-left: 1px solid var(--el-border-color-lighter);
}

.artifacts-resizer {
  position: absolute;
  z-index: 4;
  top: 0;
  bottom: 0;
  left: 0;
  width: 12px;
  padding: 0;
  border: 0;
  outline: none;
  background: transparent;
  cursor: col-resize;
  touch-action: none;
}

.artifacts-resizer::before {
  content: '';
  position: absolute;
  top: 0;
  bottom: 0;
  left: 0;
  width: 2px;
  background: transparent;
  transition: background-color 0.12s ease, box-shadow 0.12s ease;
}

.artifacts-resizer-grip {
  position: absolute;
  top: 50%;
  left: 2px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 2px;
  width: 8px;
  height: 42px;
  border: 1px solid color-mix(in srgb, var(--theme-primary, var(--el-color-primary)) 36%, var(--el-border-color));
  border-radius: 999px;
  background: var(--el-bg-color);
  box-shadow: 0 4px 12px color-mix(in srgb, var(--el-text-color-primary) 12%, transparent);
  opacity: 0;
  transform: translateY(-50%) scale(0.9);
  transition: opacity 0.12s ease, transform 0.12s ease;
}

.artifacts-resizer-grip i {
  display: block;
  width: 1px;
  height: 16px;
  background: var(--theme-primary, var(--el-color-primary));
}

.artifacts-resizer-value {
  position: absolute;
  top: 50%;
  left: 16px;
  padding: 5px 8px;
  border-radius: 6px;
  color: #fff;
  background: rgba(24, 31, 42, 0.92);
  box-shadow: 0 4px 12px rgba(24, 31, 42, 0.16);
  font-size: 11px;
  line-height: 1;
  white-space: nowrap;
  opacity: 0;
  pointer-events: none;
  transform: translateY(-50%) translateX(4px);
  transition: opacity 0.12s ease, transform 0.12s ease;
}

.artifacts-resizer:hover::before,
.artifacts-resizer:focus-visible::before,
.vibecoding-panel.is-resizing .artifacts-resizer::before {
  background: var(--theme-primary, var(--el-color-primary));
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--theme-primary, var(--el-color-primary)) 14%, transparent);
}

.artifacts-resizer:hover .artifacts-resizer-grip,
.artifacts-resizer:focus-visible .artifacts-resizer-grip,
.vibecoding-panel.is-resizing .artifacts-resizer-grip {
  opacity: 1;
  transform: translateY(-50%) scale(1);
}

.artifacts-resizer:focus-visible .artifacts-resizer-grip {
  box-shadow:
    0 0 0 3px color-mix(in srgb, var(--theme-primary, var(--el-color-primary)) 18%, transparent),
    0 4px 12px color-mix(in srgb, var(--el-text-color-primary) 12%, transparent);
}

.artifacts-resizer:focus-visible .artifacts-resizer-value,
.vibecoding-panel.is-resizing .artifacts-resizer-value {
  opacity: 1;
  transform: translateY(-50%) translateX(0);
}

:global(body.is-vibecoding-panel-resizing),
:global(body.is-vibecoding-panel-resizing *) {
  cursor: col-resize !important;
  user-select: none !important;
}

.artifacts-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
  font-weight: 600;
  flex-shrink: 0;
}

.artifacts-header-actions {
  display: flex;
  gap: 4px;
}

.sandbox-mode-tag {
  margin-left: 6px;
  font-weight: normal;
  vertical-align: middle;
}

/* 实时文件变更时间线 */
.file-change-timeline {
  flex-shrink: 0;
  margin-bottom: 8px;
  padding: 6px 8px;
  background: var(--el-fill-color-light);
  border-radius: 6px;
}

.file-change-timeline-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 4px;
}

.file-change-timeline-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--el-text-color-secondary);
}

.file-change-item {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  padding: 2px 0;
}

.file-change-path {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* Git 助手抽屉 */
.git-assistant {
  padding: 0 4px;
}

.git-section-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
  font-weight: 600;
}

.git-loading {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.git-summary-text {
  font-size: 13px;
  line-height: 1.6;
  color: var(--el-text-color-primary);
  margin: 0 0 8px;
}

.git-changed-files {
  margin-bottom: 8px;
}

.git-changed-file-tag {
  margin: 2px;
}

.git-result-text {
  margin: 8px 0;
}

.git-pr-description {
  margin: 8px 0;
  padding: 8px 12px;
  background: var(--el-fill-color-light);
  border-radius: 6px;
}

/* 协作模式开关 */
.collaboration-switch {
  flex-shrink: 0;
  margin: 0 4px;
}

/* 协作模式多角色阶段卡片（对话流内，P3-1） */
.role-stages {
  margin-top: 8px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.role-stage-card {
  padding: 8px 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-left: 3px solid var(--el-color-info);
  border-radius: 6px;
  background: var(--el-fill-color-light);
}

.role-stage-card--start {
  border-left-color: var(--el-color-primary);
}

.role-stage-card--done {
  border-left-color: var(--el-color-success);
}

.role-stage-card--failed {
  border-left-color: var(--el-color-danger);
}

.role-stage-header {
  display: flex;
  align-items: center;
  gap: 8px;
}

.role-stage-name {
  font-weight: 600;
  font-size: 13px;
  color: var(--el-text-color-primary);
}

.role-stage-index {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.role-stage-status {
  margin-left: auto;
}

.role-stage-output {
  margin-top: 8px;
  font-size: 13px;
  color: var(--el-text-color-primary);
}

/* 测试报告卡片（对话流内） */
.test-reports {
  margin-top: 8px;
}

.test-report-title {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  font-weight: 600;
}

.test-report-title.is-success {
  color: var(--el-color-success);
}

.test-report-title.is-failure {
  color: var(--el-color-danger);
}

.test-report-title-text {
  white-space: nowrap;
}

.exhausted-tag {
  margin-left: 4px;
}

.test-report-meta {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-bottom: 4px;
}

.test-report-failures {
  margin: 4px 0;
  padding-left: 18px;
  font-size: 12px;
  color: var(--el-color-danger);
}

.test-report-raw {
  margin: 4px 0 0;
  padding: 8px;
  max-height: 200px;
  overflow: auto;
  background: var(--el-fill-color-darker);
  border-radius: 4px;
  font-size: 12px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-word;
}

.test-report-ok {
  font-size: 12px;
  color: var(--el-color-success);
}

/* Plan Mode 确认卡片（P1-1 HITL）样式已随渲染逻辑一并抽到 PlanConfirmCard.vue，此处不再重复定义。 */

/* AI 代码审查区块本身（分组列表等）的样式已随渲染逻辑一并抽到 ReviewReport.vue，此处不再重复定义。 */

.tree-node {
  display: flex;
  flex: 1;
  align-items: center;
  width: 100%;
  min-width: 0;
  font-size: 13px;
  cursor: pointer;
  white-space: nowrap;
  overflow: hidden;
}

.tree-node > span {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
}

.tree-node:hover {
  color: var(--theme-primary, var(--el-color-primary));
}

/* 预览抽屉 */
.preview-loading {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 24px;
  color: var(--el-text-color-secondary);
}

.preview-truncated {
  padding: 16px;
  background: var(--el-fill-color-light);
  border-radius: 4px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.code-block {
  margin: 0;
  border-radius: 6px;
  font-size: 13px;
  line-height: 1.6;
}

.code-block code {
  font-family: 'JetBrains Mono', 'Fira Code', 'Courier New', monospace;
}

/* 抽屉标题行 */
.drawer-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  gap: 12px;
}

.drawer-title {
  flex: 1;
  font-size: 14px;
  font-weight: 600;
  color: var(--el-text-color-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.drawer-actions {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}

/* 编辑器文本域 */
.code-editor {
  width: 100%;
  height: calc(100vh - 130px);
  font-family: 'JetBrains Mono', 'Fira Code', 'Courier New', monospace;
  font-size: 13px;
  line-height: 1.6;
  padding: 12px;
  border: 1px solid var(--el-border-color);
  border-radius: 6px;
  resize: none;
  outline: none;
  background: var(--el-fill-color-lighter);
  color: var(--el-text-color-primary);
  box-sizing: border-box;
}

.code-editor:focus {
  border-color: var(--theme-primary, var(--el-color-primary));
  background: var(--el-bg-color);
}

@container workspace-panel (max-width: 1040px) {
  .vibecoding-panel {
    --conversation-messages-padding: 28px 20px 36px;
    --conversation-composer-padding: 10px 14px 14px;
    grid-template-columns: minmax(480px, 1fr) minmax(220px, var(--vibe-artifacts-column-width));
    grid-template-rows: minmax(480px, 1fr) 210px;
    overflow-y: auto;
  }

  .vibecoding-panel.is-history-collapsed {
    grid-template-columns: minmax(480px, 1fr) minmax(220px, var(--vibe-artifacts-column-width));
    grid-template-rows: minmax(0, 1fr) 0;
  }

  .history-column {
    grid-column: 1 / -1;
  }
}

@container workspace-panel (max-width: 700px) {
  .vibecoding-panel,
  .vibecoding-panel.is-history-collapsed {
    --conversation-user-bubble-max-width: 88%;
    --conversation-toolbar-wrap: wrap;
    grid-template-columns: minmax(0, 1fr);
    grid-template-rows: minmax(480px, 1fr) 260px 210px;
  }

  .artifacts-column {
    padding: 14px 16px;
    border-top: 1px solid var(--el-border-color-lighter);
    border-left: 0;
  }

  .artifacts-resizer {
    display: none;
  }

  .history-column {
    grid-column: 1;
  }

  .vibecoding-panel.is-history-collapsed {
    grid-template-rows: minmax(480px, 1fr) 260px 0;
  }
}
</style>
