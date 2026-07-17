<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import type { UploadRequestOptions } from 'element-plus'
import {
  confirmVibeCodingPlan,
  generateCommitMessage,
  generatePrDescription,
  getGitDiffSummary,
  getSandboxMode,
  interruptVibeCoding,
  listWorkspaceFiles,
  readWorkspaceFileContent,
  reviewVibeCoding,
  rollbackVibeCoding,
  saveWorkspaceFileContent,
  streamVibeCoding,
} from '@/api/vibecoding'
import { getChatSessionMessages, parseChatAttachment } from '@/api/chat'
import MarkdownRenderer from '@/components/MarkdownRenderer.vue'
import TraceTimeline, { type TraceNode } from '@/components/TraceTimeline.vue'
import ChatHistorySidebar from '@/components/ChatHistorySidebar.vue'
import { useThemeStore } from '@/store/theme'
import { generateUuid } from '@/utils/uuid'
import type {
  FileChangeEvent,
  GitDiffSummary,
  PlanEvent,
  PlanResultEvent,
  ReviewIssue,
  ReviewResult,
  RoleStageEvent,
  TestReport,
  WorkspaceFileContent,
  WorkspaceFileNode,
} from '@/types/api'
import { ANSWER_KIND, appendChatStreamNode, parseChatStreamPayload } from '@/utils/traceTimeline'
import hljs from 'highlight.js'
import 'highlight.js/styles/github.css'

const props = defineProps<{ agentCode: string }>()

/** Plan Mode 确认卡片（P1-1 HITL）：一条 plan 事件对应一张卡片，用户批准/拒绝或超时后翻成终态。 */
interface PlanCard {
  planId: string
  actions: PlanEvent['actions']
  reason: string
  status: 'PENDING' | 'APPROVED' | 'REJECTED' | 'TIMEOUT'
  remainingSeconds: number
  submitting: boolean
}

interface ChatMessage {
  role: 'user' | 'assistant'
  text: string
  nodes: TraceNode[]
  // 本条助手消息内累积的沙箱编译/测试报告（P0-3），按到达顺序渲染成测试报告卡片时间线
  testReports?: TestReport[]
  // 本条助手消息内的 Plan Mode 确认卡片（P1-1），高风险操作待人工确认
  plans?: PlanCard[]
  // 协作模式多角色阶段进度（P3-1），按 role_stage 事件到达顺序累积
  stages?: RoleStageEvent[]
}

/** localId 是前端本地生成的临时 key，用于 v-for/移除定位（上传过程中后端 id 还不存在）；
 * status 驱动 tag 的 loading/失败态展示，失败附件不参与 buildMessageWithAttachments 拼接。 */
interface Attachment {
  localId: string
  id?: string
  name: string
  content: string
  status: 'uploading' | 'success' | 'failed'
  errorMessage?: string
}

// 与 starter AttachmentParseService 的白名单/大小限制保持一致（后端 customer-work.attachment.max-file-size-mb=10）
const ATTACHMENT_ACCEPT = '.md,.txt,.csv,.tsv,.json,.xml,.yaml,.yml,.toml,.proto,.properties,.ini,.conf,.cfg,.log,.env,.sql,.sh,.bash,.zsh,.bat,.ps1,.java,.kt,.kts,.groovy,.gradle,.scala,.py,.js,.ts,.jsx,.tsx,.vue,.css,.scss,.less,.c,.h,.cpp,.hpp,.cs,.go,.rs,.rb,.php,.swift,.lua,.r,.dart,.html,.pdf,.doc,.docx,.xls,.xlsx,.ppt,.pptx,.png,.jpg,.jpeg,.bmp,.webp'
const MAX_ATTACHMENT_SIZE_BYTES = 10 * 1024 * 1024

const sessionId = ref(generateUuid())
const messages = ref<ChatMessage[]>([])
const input = ref('')
// 协作模式开关（P3-1）：开启后按 需求分析→方案设计→编码实现→自测审查 多角色顺序协作
const collaborationMode = ref(false)
const streaming = ref(false)
const interrupting = ref(false) // 已点终止，等后端真正停下来（协作式中断，不保证立即生效）
const interrupted = ref(false) // 上一轮是被终止结束的，可以点"继续"续跑挂起的工具调用
const historyLoading = ref(false)
const attachments = ref<Attachment[]>([])
const anyAttachmentUploading = computed(() => attachments.value.some((a) => a.status === 'uploading'))
const scrollRef = ref<HTMLElement>()
const historySidebar = ref<InstanceType<typeof ChatHistorySidebar>>()
let abortStream: (() => void) | null = null
const themeStore = useThemeStore()

// 目录树相关
const fileNodes = ref<WorkspaceFileNode[]>([])
const filesLoading = ref(false)
const filesLoaded = ref(false)

// 沙箱模式（local/docker，全局配置，进面板时查一次即可，不随会话变化）
const sandboxMode = ref<'local' | 'docker' | null>(null)

// 实时文件变更时间线（本轮对话内累积，切会话/新建会话时清空）
const fileChanges = ref<Array<FileChangeEvent & { time: number }>>([])
// 会话一键回滚进行中
const rollingBack = ref(false)

// Plan Mode（P1-1 HITL）：待确认计划的 planId -> 卡片，供 plan_result 快速定位；倒计时统一由一个定时器驱动
const pendingPlans = new Map<string, PlanCard>()
let planCountdownTimer: ReturnType<typeof setInterval> | null = null

// Git 助手抽屉
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
  abortStream?.()
  streaming.value = false
  interrupting.value = false
  interrupted.value = false
  sessionId.value = generateUuid()
  messages.value = []
  input.value = ''
  attachments.value = []
  fileNodes.value = []
  filesLoaded.value = false
  fileChanges.value = []
  clearPendingPlans()
  // 新会话会用当前全局配置，不是上一个（可能是历史会话解析出的）沙箱模式
  loadCurrentSandboxMode()
}

/** 清空待确认计划与倒计时（切会话/新建会话/卸载时）。 */
function clearPendingPlans() {
  pendingPlans.clear()
  if (planCountdownTimer) {
    clearInterval(planCountdownTimer)
    planCountdownTimer = null
  }
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
  const file = options.file as File
  const attachment: Attachment = { localId: generateUuid(), name: file.name, content: '', status: 'uploading' }
  attachments.value.push(attachment)
  try {
    const result = await parseChatAttachment(props.agentCode, file, 'vibecoding')
    const target = attachments.value.find((a) => a.localId === attachment.localId)
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
    const target = attachments.value.find((a) => a.localId === attachment.localId)
    const message = error instanceof Error ? error.message : String(error)
    if (target) {
      target.status = 'failed'
      target.errorMessage = message
    }
    ElMessage.error('附件解析失败：' + message)
  }
}

function removeAttachment(localId: string) {
  attachments.value = attachments.value.filter((a) => a.localId !== localId)
}

/** 只拼成功解析的附件，上传中/失败的附件不参与（失败的已经在上传回调里提示过用户）。 */
function buildMessageWithAttachments(text: string): string {
  const successful = attachments.value.filter((a) => a.status === 'success')
  if (successful.length === 0) return text
  const attachmentText = successful
    .map((a) => `【附件：${a.name}】\n---\n${a.content}\n---`)
    .join('\n\n')
  return `${attachmentText}\n\n${text}`
}

async function openSession(targetSessionId: string) {
  if (streaming.value) return
  abortStream?.()
  interrupting.value = false
  interrupted.value = false
  historyLoading.value = true
  try {
    const history = await getChatSessionMessages(props.agentCode, targetSessionId)
    sessionId.value = targetSessionId
    messages.value = history.map((msg) => ({ role: msg.role, text: msg.text, nodes: [] }))
    input.value = ''
    fileNodes.value = []
    filesLoaded.value = false
    fileChanges.value = []
    clearPendingPlans()
    // 标签要反映"这条会话当时真正用的模式"，从首条用户消息里解析；更早期没有该前缀的历史记录
    // 解析不出来，此时不展示误导性的标签（不回退成当前全局配置，两者含义不同不能互相替代）
    const firstUserMessage = history.find((msg) => msg.role === 'user')
    sandboxMode.value = firstUserMessage ? parseSandboxModeFromMessage(firstUserMessage.text) : null
    scrollToBottom()
    // 切到历史会话时该会话可能已有产物文件，无需等用户手动点“刷新”
    loadFiles()
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
  if (!text || streaming.value || anyAttachmentUploading.value) return
  interrupted.value = false
  const messageToSend = buildMessageWithAttachments(text)
  const attachedNames = attachments.value.filter((a) => a.status === 'success').map((a) => a.name)
  messages.value.push({
    role: 'user',
    text: attachedNames.length > 0 ? `${text}\n📎 ${attachedNames.join('、')}` : text,
    nodes: [],
  })
  messages.value.push({ role: 'assistant', text: '', nodes: [], testReports: [] })
  const assistantMessage = messages.value[messages.value.length - 1]
  input.value = ''
  attachments.value = []
  streaming.value = true
  scrollToBottom()

  abortStream = streamVibeCoding(props.agentCode, { sessionId: sessionId.value, message: messageToSend, collaboration: collaborationMode.value }, {
    onEvent: (event) => {
      if (event.event === 'done') {
        streaming.value = false
        // 对话结束后自动刷新文件目录树
        loadFiles()
        return
      }
      if (event.event === 'file_change') {
        handleFileChange(event.data)
        return
      }
      if (event.event === 'test_report') {
        handleTestReport(assistantMessage, event.data)
        scrollToBottom()
        return
      }
      if (event.event === 'role_stage') {
        handleRoleStage(assistantMessage, event.data)
        scrollToBottom()
        return
      }
      if (event.event === 'plan') {
        handlePlanEvent(assistantMessage, event.data)
        scrollToBottom()
        return
      }
      if (event.event === 'plan_result') {
        handlePlanResult(event.data)
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
      // 其余未知事件静默忽略：后端新增 SSE 事件类型（如 test_report/plan）时旧前端不受影响，
      // 避免把结构化 JSON 拼进对话正文（需求 §5.5 向后兼容）
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
    await interruptVibeCoding(props.agentCode, sessionId.value)
  } catch (error) {
    interrupting.value = false
    ElMessage.error('终止失败：' + (error instanceof Error ? error.message : String(error)))
  }
}

/** 点击"继续"：发一句非空续接文案触发框架续跑被打断的挂起工具调用（后端 ChatRequest.message 要求非空，
 * 且续跑逻辑本就挂在正常的 stream 调用里，无需专门的续跑接口）。 */
function resumeInterrupted() {
  interrupted.value = false
  input.value = '请继续刚才的任务。'
  send()
}

/** 解析 file_change SSE 事件，追加到变更时间线（不按路径去重，同一文件多次改动各自成一条，还原真实操作顺序）。 */
function handleFileChange(raw: string) {
  try {
    const parsed = JSON.parse(raw) as FileChangeEvent
    fileChanges.value.push({ ...parsed, time: Date.now() })
  } catch {
    // 解析失败不影响主对话流程，静默丢弃
  }
}

/** 解析 test_report SSE 事件，追加到该助手消息的测试报告时间线（每轮验证一张卡片）。 */
function handleTestReport(assistantMessage: ChatMessage, raw: string) {
  try {
    const parsed = JSON.parse(raw) as TestReport
    ;(assistantMessage.testReports ??= []).push(parsed)
  } catch {
    // 解析失败不影响主对话流程，静默丢弃
  }
}

/**
 * 解析 role_stage SSE 事件（P3-1 协作模式）：维护该助手消息的多角色阶段进度列表。
 * START 追加一条新阶段；DONE/FAILED 按 index 匹配已存在阶段并更新其状态与产物（找不到则补插一条）。
 */
function handleRoleStage(assistantMessage: ChatMessage, raw: string) {
  try {
    const parsed = JSON.parse(raw) as RoleStageEvent
    const stages = (assistantMessage.stages ??= [])
    if (parsed.status === 'START') {
      stages.push({ ...parsed, output: null })
      return
    }
    // DONE / FAILED：按 index 更新已有阶段的状态与产物
    const existing = stages.find((s) => s.index === parsed.index)
    if (existing) {
      existing.status = parsed.status
      existing.output = parsed.output
      existing.role = parsed.role
      existing.type = parsed.type
    } else {
      stages.push({ ...parsed })
    }
  } catch {
    // 解析失败不影响主对话流程，静默丢弃
  }
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

/** 解析 plan SSE 事件，追加一张待确认卡片并启动倒计时（P1-1 HITL）。 */
function handlePlanEvent(assistantMessage: ChatMessage, raw: string) {
  try {
    const parsed = JSON.parse(raw) as PlanEvent
    const card: PlanCard = {
      planId: parsed.planId,
      actions: parsed.actions ?? [],
      reason: parsed.reason ?? '',
      status: 'PENDING',
      remainingSeconds: parsed.timeoutSeconds ?? 300,
      submitting: false,
    }
    const plans = (assistantMessage.plans ??= [])
    plans.push(card)
    // 存入 pendingPlans 的必须是"push 进响应式数组后"的响应式代理引用，否则定时器/plan_result 修改
    // 原始对象不会触发视图更新（Vue3 响应式经典坑：修改未经代理的原对象不触发依赖收集）
    pendingPlans.set(card.planId, plans[plans.length - 1])
    ensurePlanCountdown()
  } catch {
    // 解析失败不影响主对话流程，静默丢弃
  }
}

/** 解析 plan_result SSE 事件，把对应卡片翻成终态（含服务端超时自动拒绝）。 */
function handlePlanResult(raw: string) {
  try {
    const parsed = JSON.parse(raw) as PlanResultEvent
    const card = pendingPlans.get(parsed.planId)
    if (card) {
      card.status = parsed.status
      card.submitting = false
      pendingPlans.delete(parsed.planId)
    }
  } catch {
    // 解析失败静默丢弃
  }
}

/** 启动（若未启动）统一倒计时定时器：每秒递减所有待确认卡片，归零即本地标记超时。 */
function ensurePlanCountdown() {
  if (planCountdownTimer) return
  planCountdownTimer = setInterval(() => {
    if (pendingPlans.size === 0) {
      clearInterval(planCountdownTimer!)
      planCountdownTimer = null
      return
    }
    for (const card of pendingPlans.values()) {
      card.remainingSeconds -= 1
      if (card.remainingSeconds <= 0) {
        // 本地倒计时归零：先行标记超时（服务端也会补发 plan_result=TIMEOUT，二者幂等）
        card.status = 'TIMEOUT'
        card.remainingSeconds = 0
        pendingPlans.delete(card.planId)
      }
    }
  }, 1000)
}

/** 用户对某个计划卡片点批准/拒绝：调后端确认接口，成功后翻成终态。 */
async function handlePlanDecision(card: PlanCard, approved: boolean) {
  if (card.status !== 'PENDING' || card.submitting) return
  card.submitting = true
  try {
    await confirmVibeCodingPlan(props.agentCode, {
      sessionId: sessionId.value,
      planId: card.planId,
      approved,
    })
    card.status = approved ? 'APPROVED' : 'REJECTED'
    pendingPlans.delete(card.planId)
  } catch (error) {
    // 失败常见于挂起项已失效（超时/服务重启）：提示并按拒绝态收尾，避免卡片永久停在"等待确认"
    ElMessage.error('计划确认失败：' + (error instanceof Error ? error.message : String(error)))
    card.status = 'TIMEOUT'
    pendingPlans.delete(card.planId)
  } finally {
    card.submitting = false
  }
}

/** 计划卡片操作类型 → 中文标签 + Element tag 着色。 */
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
  try {
    await ElMessageBox.confirm(
      '此操作将丢弃本次会话对工作区的全部文件改动：新增文件将被删除，修改/删除的文件将恢复到对话前的状态。操作不可撤销，是否继续？',
      '撤销全部修改',
      { type: 'warning', confirmButtonText: '确认撤销', cancelButtonText: '取消' },
    )
  } catch {
    return // 用户取消
  }
  rollingBack.value = true
  try {
    const res = await rollbackVibeCoding(props.agentCode, sessionId.value)
    fileChanges.value = []
    await loadFiles()
    // Git 助手抽屉开着则刷新 diff（回滚后应无变更）
    if (gitDrawerVisible.value) await loadGitDiff()
    // 对话流插入一条系统提示（需求 §4.1.2）
    messages.value.push({
      role: 'assistant',
      text: `🔄 已撤销本次会话的全部修改（恢复 ${res.restoredFiles.length} 个文件，删除 ${res.deletedFiles.length} 个新增文件）。`,
      nodes: [],
    })
    scrollToBottom()
    ElMessage.success('已撤销本次会话的全部修改')
  } catch (error) {
    ElMessage.error('撤销失败：' + (error instanceof Error ? error.message : String(error)))
  } finally {
    rollingBack.value = false
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
  gitDiffLoading.value = true
  try {
    gitDiff.value = await getGitDiffSummary(props.agentCode, sessionId.value)
  } catch (error) {
    ElMessage.error('diff 摘要加载失败：' + (error instanceof Error ? error.message : String(error)))
  } finally {
    gitDiffLoading.value = false
  }
}

async function handleGenerateCommitMessage() {
  commitLoading.value = true
  try {
    const res = await generateCommitMessage(props.agentCode, { sessionId: sessionId.value, style: commitStyle.value })
    commitMessageText.value = res.message
  } catch (error) {
    ElMessage.error('commit message 生成失败：' + (error instanceof Error ? error.message : String(error)))
  } finally {
    commitLoading.value = false
  }
}

async function handleGeneratePrDescription() {
  prLoading.value = true
  try {
    const res = await generatePrDescription(props.agentCode, sessionId.value)
    prDescriptionText.value = res.description
  } catch (error) {
    ElMessage.error('PR description 生成失败：' + (error instanceof Error ? error.message : String(error)))
  } finally {
    prLoading.value = false
  }
}

/** 触发 AI 代码审查：对本轮 diff 输出结构化审查意见。 */
async function handleReview() {
  reviewLoading.value = true
  try {
    reviewResult.value = await reviewVibeCoding(props.agentCode, sessionId.value)
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
  input.value = `请根据以下代码审查意见修复问题，并在沙箱内重新验证：\n${lines.join('\n')}`
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

/** 加载（刷新）会话 workspace 目录树。 */
async function loadFiles() {
  filesLoading.value = true
  try {
    fileNodes.value = await listWorkspaceFiles(props.agentCode, sessionId.value)
    filesLoaded.value = true
  } catch (error) {
    ElMessage.error('目录加载失败：' + (error instanceof Error ? error.message : String(error)))
  } finally {
    filesLoading.value = false
  }
}

/** 点击文件节点，打开预览抽屉并加载内容。 */
async function openFilePreview(node: WorkspaceFileNode) {
  if (node.directory) return
  previewVisible.value = true
  previewLoading.value = true
  previewFile.value = null
  editMode.value = false
  editContent.value = ''
  try {
    previewFile.value = await readWorkspaceFileContent(props.agentCode, sessionId.value, node.relativePath)
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
  if (!previewFile.value) return
  saving.value = true
  try {
    await saveWorkspaceFileContent(props.agentCode, {
      sessionId: sessionId.value,
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

/**
 * 当前全局沙箱配置（admin.sandbox.mode），代表"新会话将会使用的模式"。
 * 仅用于 newSession/挂载时的预览——一旦切到某条历史会话，标签要改成从那条会话消息里解析出的
 * "当时真正用的模式"，不能一直显示"现在的全局配置"，否则历史记录和当前配置不一致时会互相矛盾
 * （比如切到一条 local 时期的历史记录，标题却显示当前是 docker，误导用户以为这条记录也在容器里）。
 */
function loadCurrentSandboxMode() {
  getSandboxMode(props.agentCode)
    .then((res) => { sandboxMode.value = res.mode })
    .catch(() => { sandboxMode.value = null })
}

/** 从会话首条用户消息里解析出发送时用的沙箱模式，解析不出来（更早期版本的历史记录）返回 null。 */
function parseSandboxModeFromMessage(text: string): 'local' | 'docker' | null {
  const match = text.match(/^\[VibeCoding指引-(docker|local)]/)
  return match ? (match[1] as 'local' | 'docker') : null
}

onMounted(() => {
  themeStore.apply()
  loadCurrentSandboxMode()
})

onUnmounted(() => {
  abortStream?.()
  clearPendingPlans()
})

// newSession 供 WorkspaceView 上提后的工具栏"新建会话"按钮按激活 Tab 分发调用
defineExpose({ newSession })
</script>

<template>
  <div class="vibecoding-panel">
    <!-- 左列：对话区 -->
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
            <span v-if="msg.role === 'assistant' && !msg.text && msg.nodes.length === 0 && streaming && index === messages.length - 1">生成中…</span>
          </div>
        </div>
        <el-empty v-if="messages.length === 0" description="描述你想让智能体生成/修改的代码" />
      </div>
      <div v-if="attachments.length > 0" class="attachment-tags">
        <el-tag
          v-for="a in attachments"
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
          <el-button :disabled="streaming" title="上传附件（文档/表格/图片等），随消息一起发给智能体">
            <el-icon><Paperclip /></el-icon>
          </el-button>
        </el-upload>
        <el-input v-model="input" placeholder="描述需求，回车发送" :disabled="streaming" @keyup.enter="send" />
        <el-tooltip
          placement="top"
          content="开启后按 需求分析→方案设计→编码实现→自测审查 多角色顺序协作"
        >
          <el-switch
            v-model="collaborationMode"
            :disabled="streaming"
            active-text="协作模式"
            class="collaboration-switch"
          />
        </el-tooltip>
        <el-button v-if="!streaming" type="primary" :disabled="anyAttachmentUploading" @click="send">发送</el-button>
        <el-button v-else type="danger" :loading="interrupting" @click="handleInterrupt">
          {{ interrupting ? '终止中…' : '终止' }}
        </el-button>
        <el-button v-if="interrupted && !streaming" link type="primary" @click="resumeInterrupted">继续</el-button>
      </div>
    </div>

    <!-- 中列：产物文件树 -->
    <div class="artifacts-column">
      <div class="artifacts-header">
        <span>
          产物文件
          <el-tag v-if="sandboxMode" size="small" :type="sandboxMode === 'docker' ? 'warning' : 'info'" class="sandbox-mode-tag">
            {{ sandboxMode === 'docker' ? 'docker' : 'local' }}
          </el-tag>
        </span>
        <div class="artifacts-header-actions">
          <el-button link type="primary" @click="openGitAssistant">Git 助手</el-button>
          <el-button link type="primary" :loading="filesLoading" @click="loadFiles">刷新</el-button>
        </div>
      </div>

      <!-- 实时文件变更时间线 -->
      <div v-if="fileChanges.length > 0" class="file-change-timeline">
        <div class="file-change-timeline-header">
          <span class="file-change-timeline-title">本轮变更</span>
          <el-button
            link
            type="danger"
            size="small"
            :loading="rollingBack"
            :disabled="streaming"
            title="撤销本次会话的全部文件改动，恢复到对话前状态"
            @click="handleRollback"
          >
            撤销全部修改
          </el-button>
        </div>
        <el-scrollbar max-height="120px">
          <div v-for="(fc, idx) in fileChanges" :key="idx" class="file-change-item">
            <el-icon v-if="fc.operation === 'CREATE'" style="color:#67c23a"><CirclePlus /></el-icon>
            <el-icon v-else-if="fc.operation === 'MODIFY'" style="color:#e6a23c"><EditPen /></el-icon>
            <el-icon v-else style="color:#f56c6c"><Delete /></el-icon>
            <span class="file-change-path" :title="fc.path">{{ fc.path }}</span>
          </div>
        </el-scrollbar>
      </div>

      <!-- 空状态 -->
      <el-empty
        v-if="!filesLoaded"
        description="对话结束后自动刷新"
        :image-size="50"
      />
      <el-empty
        v-else-if="filesLoaded && fileNodes.length === 0"
        description="本次会话暂无产出文件"
        :image-size="50"
      />

      <!-- 目录树 -->
      <el-scrollbar v-else height="100%">
        <el-tree
          :data="fileNodes"
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
      <ChatHistorySidebar ref="historySidebar" :agent-code="agentCode" :active-session-id="sessionId" @select="openSession" />
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
              :disabled="streaming"
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
