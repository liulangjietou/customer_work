<script setup lang="ts">
import { computed, nextTick, onActivated, onMounted, ref, watch } from 'vue'
import type { UploadRequestOptions } from 'element-plus'
import {
  confirmVibeCodingPlan,
  generateCommitMessage,
  generatePrDescription,
  getGitDiffSummary,
  readWorkspaceFileContent,
  reviewVibeCoding,
  rollbackVibeCoding,
  saveWorkspaceFileContent,
} from '@/api/vibecoding'
import { parseChatAttachment } from '@/api/chat'
import MarkdownRenderer from '@/components/MarkdownRenderer.vue'
import TraceTimeline from '@/components/TraceTimeline.vue'
import ChatHistorySidebar from '@/components/ChatHistorySidebar.vue'
import { useThemeStore } from '@/store/theme'
import {
  useVibeConversationsStore,
  type PlanCard,
  type VibeAttachmentItem,
  type VibeConversation,
} from '@/store/vibeConversations'
import { generateUuid } from '@/utils/uuid'
import type {
  GitDiffSummary,
  ReviewIssue,
  ReviewResult,
  RoleStageEvent,
  TestReport,
  WorkspaceFileContent,
  WorkspaceFileNode,
} from '@/types/api'
import hljs from 'highlight.js'
import 'highlight.js/styles/github.css'

const props = defineProps<{ agentCode: string }>()

// 与 starter AttachmentParseService 的白名单/大小限制保持一致（后端 customer-work.attachment.max-file-size-mb=10）
const ATTACHMENT_ACCEPT = '.md,.txt,.csv,.tsv,.json,.xml,.yaml,.yml,.toml,.proto,.properties,.ini,.conf,.cfg,.log,.env,.sql,.sh,.bash,.zsh,.bat,.ps1,.java,.kt,.kts,.groovy,.gradle,.scala,.py,.js,.ts,.jsx,.tsx,.vue,.css,.scss,.less,.c,.h,.cpp,.hpp,.cs,.go,.rs,.rb,.php,.swift,.lua,.r,.dart,.html,.pdf,.doc,.docx,.xls,.xlsx,.ppt,.pptx,.png,.jpg,.jpeg,.bmp,.webp'
const MAX_ATTACHMENT_SIZE_BYTES = 10 * 1024 * 1024

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
const themeStore = useThemeStore()

// Git 助手抽屉（面板级，操作对象是当前激活会话）
const gitDrawerVisible = ref(false)
const gitDiffLoading = ref(false)
const gitDiff = ref<GitDiffSummary | null>(null)
const commitStyle = ref<'conventional' | 'simple'>('conventional')
const commitLoading = ref(false)
const commitMessageText = ref('')
const prLoading = ref(false)
const prDescriptionText = ref('')
// AI 代码审查（P0-2）
const reviewLoading = ref(false)
const reviewResult = ref<ReviewResult | null>(null)

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
  const attachment: VibeAttachmentItem = { localId: generateUuid(), name: file.name, content: '', status: 'uploading' }
  conv.attachments.push(attachment)
  try {
    const result = await parseChatAttachment(props.agentCode, file, 'vibecoding')
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

/** 只拼成功解析的附件，上传中/失败的附件不参与（失败的已经在上传回调里提示过用户）。 */
function buildMessageWithAttachments(conv: VibeConversation, text: string): string {
  const successful = conv.attachments.filter((a) => a.status === 'success')
  if (successful.length === 0) return text
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
  store.send(props.agentCode, collaborationMode.value, buildMessageWithAttachments, scrollToBottom)
}

function handleInterrupt() {
  store.interrupt(props.agentCode)
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

/** 计划卡片操作类型 → 中文标签。 */
function planActionLabel(type: string): string {
  switch (type) {
    case 'DELETE': return '删除文件'
    case 'RUN_COMMAND': return '执行命令'
    case 'MODIFY_DEPENDENCY': return '修改依赖'
    case 'BATCH_MODIFY': return '批量修改'
    default: return type
  }
}

/** 计划卡片终态 → 中文文案。 */
function planStatusText(status: PlanCard['status']): string {
  switch (status) {
    case 'APPROVED': return '已批准'
    case 'REJECTED': return '已拒绝'
    case 'TIMEOUT': return '已超时（自动拒绝）'
    default: return '等待确认'
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

/** 触发 AI 代码审查：对本轮 diff 输出结构化审查意见。 */
async function handleReview() {
  const conv = active.value
  if (!conv) return
  reviewLoading.value = true
  try {
    reviewResult.value = await reviewVibeCoding(props.agentCode, conv.sessionId)
  } catch (error) {
    ElMessage.error('代码审查失败：' + (error instanceof Error ? error.message : String(error)))
  } finally {
    reviewLoading.value = false
  }
}

/** 严重级别 → Element Plus tag 类型（着色）。 */
function severityTagType(severity: ReviewIssue['severity']): 'danger' | 'warning' | 'info' {
  if (severity === 'CRITICAL') return 'danger'
  if (severity === 'WARNING') return 'warning'
  return 'info'
}

/** 按严重级别分组审查意见（CRITICAL → WARNING → SUGGESTION 顺序）。 */
function groupedIssues(issues: ReviewIssue[]): Array<{ severity: ReviewIssue['severity']; items: ReviewIssue[] }> {
  const order: ReviewIssue['severity'][] = ['CRITICAL', 'WARNING', 'SUGGESTION']
  return order
    .map((severity) => ({ severity, items: issues.filter((i) => i.severity === severity) }))
    .filter((g) => g.items.length > 0)
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

// 从 keep-alive 缓存里重新激活时滚到最新内容——离开期间进行中的会话仍在后台追加增量。
onActivated(() => {
  scrollToBottom()
})

// 注意：刻意没有 onUnmounted abort/清定时器——会话、SSE 流、plan 倒计时都属于全局 store，
// 组件销毁（切智能体/关标签）不应终止后台会话。

// newSession 供 WorkspaceView 上提后的工具栏"新建会话"按钮按激活 Tab 分发调用
defineExpose({ newSession })
</script>

<template>
  <div class="vibecoding-panel">
    <!-- 左列：对话区 -->
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
            <!-- 沙箱编译/测试报告卡片时间线（P0-3）：每轮验证一张，通过绿/失败红，可展开看失败明细 -->
            <div
              v-if="msg.role === 'assistant' && msg.testReports && msg.testReports.length > 0"
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
              v-if="msg.role === 'assistant' && msg.stages && msg.stages.length > 0"
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
            <div
              v-if="msg.role === 'assistant' && msg.plans && msg.plans.length > 0"
              class="plan-cards"
            >
              <div
                v-for="(plan, pi) in msg.plans"
                :key="pi"
                class="plan-card"
                :class="`plan-card--${plan.status.toLowerCase()}`"
              >
                <div class="plan-card-header">
                  <el-icon class="plan-card-icon"><Warning /></el-icon>
                  <span class="plan-card-title">高风险操作待确认</span>
                  <el-tag
                    v-if="plan.status === 'PENDING'"
                    type="warning"
                    size="small"
                    effect="dark"
                    class="plan-card-countdown"
                  >
                    {{ plan.remainingSeconds }}s
                  </el-tag>
                  <el-tag
                    v-else
                    :type="plan.status === 'APPROVED' ? 'success' : 'info'"
                    size="small"
                    effect="dark"
                  >
                    {{ planStatusText(plan.status) }}
                  </el-tag>
                </div>
                <p v-if="plan.reason" class="plan-card-reason">{{ plan.reason }}</p>
                <ul class="plan-card-actions">
                  <li v-for="(action, ai) in plan.actions" :key="ai" class="plan-card-action">
                    <el-tag size="small" class="plan-action-type">{{ planActionLabel(action.type) }}</el-tag>
                    <code class="plan-action-target" :title="action.target">{{ action.target }}</code>
                  </li>
                </ul>
                <div v-if="plan.status === 'PENDING'" class="plan-card-buttons">
                  <el-button
                    type="primary"
                    size="small"
                    :loading="plan.submitting"
                    @click="handlePlanDecision(plan, true)"
                  >
                    批准执行
                  </el-button>
                  <el-button
                    type="danger"
                    size="small"
                    plain
                    :disabled="plan.submitting"
                    @click="handlePlanDecision(plan, false)"
                  >
                    拒绝
                  </el-button>
                </div>
              </div>
            </div>
            <template v-if="msg.role === 'user'">{{ msg.text }}</template>
            <span v-if="msg.role === 'assistant' && !msg.text && msg.nodes.length === 0 && (active?.streaming ?? false) && index === (active?.messages.length ?? 0) - 1">生成中…</span>
          </div>
        </div>
        <el-empty v-if="(active?.messages.length ?? 0) === 0" description="描述你想让智能体生成/修改的代码" />
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
        <el-upload :show-file-list="false" :http-request="handleAttachmentUpload" :before-upload="beforeAttachmentUpload" :accept="ATTACHMENT_ACCEPT">
          <el-button :disabled="active?.streaming" title="上传附件（文档/表格/图片等），随消息一起发给智能体">
            <el-icon><Paperclip /></el-icon>
          </el-button>
        </el-upload>
        <el-input v-model="input" placeholder="描述需求，回车发送" :disabled="active?.streaming" @keyup.enter="send" />
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
        <el-button v-if="!active?.streaming" type="primary" :disabled="anyAttachmentUploading" @click="send">发送</el-button>
        <el-button v-else type="danger" :loading="active?.interrupting" @click="handleInterrupt">
          {{ active?.interrupting ? '终止中…' : '终止' }}
        </el-button>
        <el-button v-if="active?.interrupted && !active?.streaming" link type="primary" @click="resumeInterrupted">继续</el-button>
      </div>
    </div>

    <!-- 中列：产物文件树 -->
    <div class="artifacts-column">
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
    <div class="history-column">
      <ChatHistorySidebar
        ref="historySidebar"
        :agent-code="agentCode"
        :active-session-id="activeSessionId"
        :live-sessions="liveSessions"
        @select="openSession"
      />
    </div>

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
          <el-button type="primary" size="small" :loading="reviewLoading" @click="handleReview">审查</el-button>
          <template v-if="reviewResult">
            <p v-if="reviewResult.summary" class="git-summary-text review-summary">{{ reviewResult.summary }}</p>
            <el-empty
              v-if="reviewResult.issues.length === 0"
              description="未发现结构化问题"
              :image-size="40"
            />
            <div v-else class="review-issues">
              <div v-for="group in groupedIssues(reviewResult.issues)" :key="group.severity" class="review-group">
                <div class="review-group-header">
                  <el-tag :type="severityTagType(group.severity)" size="small" effect="dark">
                    {{ group.severity }}
                  </el-tag>
                  <span class="review-group-count">{{ group.items.length }} 项</span>
                </div>
                <div
                  v-for="(issue, ii) in group.items"
                  :key="ii"
                  class="review-issue"
                  :class="`review-issue--${group.severity.toLowerCase()}`"
                >
                  <div class="review-issue-loc">
                    <el-tag size="small" class="review-issue-category">{{ issue.category }}</el-tag>
                    <el-link type="primary" :underline="false" @click="openIssueFile(issue)">
                      {{ issue.file }}<template v-if="issue.line">:{{ issue.line }}</template>
                    </el-link>
                  </div>
                  <div class="review-issue-message">{{ issue.message }}</div>
                  <div v-if="issue.suggestion" class="review-issue-suggestion">建议：{{ issue.suggestion }}</div>
                </div>
              </div>
            </div>
            <el-button
              v-if="reviewResult.issues.some((i) => i.severity === 'CRITICAL' || i.severity === 'WARNING')"
              type="warning"
              size="small"
              class="review-fix-btn"
              :disabled="active?.streaming"
              @click="generateFixFromReview"
            >
              一键生成修复
            </el-button>
          </template>
        </div>
      </div>
    </el-drawer>
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
  max-width: 90%;
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

/* 产物文件树列 */
.artifacts-column {
  flex: 1;
  border-left: 1px solid var(--el-border-color-lighter);
  padding-left: 16px;
  display: flex;
  flex-direction: column;
  min-width: 200px;
  overflow: hidden;
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

/* Plan Mode 确认卡片（P1-1 HITL） */
.plan-cards {
  margin-top: 8px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.plan-card {
  padding: 10px 12px;
  border-radius: 6px;
  border-left: 3px solid var(--el-color-warning);
  background: var(--el-color-warning-light-9, var(--el-fill-color-light));
}

.plan-card--approved {
  border-left-color: var(--el-color-success);
  background: var(--el-color-success-light-9, var(--el-fill-color-light));
}

.plan-card--rejected,
.plan-card--timeout {
  border-left-color: var(--el-color-info);
  background: var(--el-fill-color-light);
  opacity: 0.85;
}

.plan-card-header {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 6px;
}

.plan-card-icon {
  color: var(--el-color-warning);
}

.plan-card-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.plan-card-countdown {
  margin-left: auto;
}

.plan-card-header .el-tag:not(.plan-card-countdown) {
  margin-left: auto;
}

.plan-card-reason {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin: 0 0 6px;
}

.plan-card-actions {
  list-style: none;
  margin: 0 0 8px;
  padding: 0;
}

.plan-card-action {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 2px 0;
}

.plan-action-type {
  flex-shrink: 0;
}

.plan-action-target {
  font-size: 12px;
  font-family: 'JetBrains Mono', 'Fira Code', 'Courier New', monospace;
  color: var(--el-text-color-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.plan-card-buttons {
  display: flex;
  gap: 8px;
}

/* AI 代码审查 */
.review-summary {
  margin-top: 8px;
}

.review-group {
  margin-bottom: 10px;
}

.review-group-header {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 4px;
}

.review-group-count {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.review-issue {
  margin-bottom: 6px;
  padding: 6px 8px;
  border-left: 3px solid var(--el-border-color);
  background: var(--el-fill-color-light);
  border-radius: 4px;
}

.review-issue--critical {
  border-left-color: var(--el-color-danger);
}

.review-issue--warning {
  border-left-color: var(--el-color-warning);
}

.review-issue--suggestion {
  border-left-color: var(--el-color-info);
}

.review-issue-loc {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 2px;
}

.review-issue-category {
  flex-shrink: 0;
}

.review-issue-message {
  font-size: 13px;
  color: var(--el-text-color-primary);
  line-height: 1.5;
}

.review-issue-suggestion {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 2px;
}

.review-fix-btn {
  margin-top: 8px;
}

.tree-node {
  display: flex;
  align-items: center;
  font-size: 13px;
  cursor: pointer;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 160px;
}

.tree-node:hover {
  color: var(--theme-primary, var(--el-color-primary));
}

/* 历史会话列 */
.history-column {
  flex: 1;
  border-left: 1px solid var(--el-border-color-lighter);
  padding-left: 16px;
  min-width: 180px;
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
</style>
