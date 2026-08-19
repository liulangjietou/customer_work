import { defineStore } from 'pinia'
import {
  getSandboxMode,
  interruptVibeCoding,
  listWorkspaceFiles,
  streamDiagnosis,
  streamRefactor,
  streamSandboxCommand,
  streamVibeCoding,
} from '@/api/vibecoding'
import { getChatSessionMessages } from '@/api/chat'
import { generateUuid } from '@/utils/uuid'
import { ANSWER_KIND, appendChatStreamNode, parseChatStreamPayload } from '@/utils/traceTimeline'
import { createPlanCard, type PlanCard } from '@/utils/planCard'
import { revokeAttachmentPreviews, type MessageAttachmentVM } from '@/utils/attachment'
import type { TraceNode } from '@/components/TraceTimeline.vue'
import type { SseHandlers } from '@/utils/sse'
import type {
  CommandOutputEvent,
  CommandResultEvent,
  ExecutionMode,
  FileChangeEvent,
  LiveSession,
  PlanEvent,
  PlanResultEvent,
  RefactorTaskRequest,
  RoleStageEvent,
  TestReport,
  WorkspaceFileNode,
} from '@/types/api'
import { previewOfMessages } from '@/store/chatConversations'

// Plan Mode 确认卡片类型（P1-1 HITL）已提到 utils/planCard.ts 与 chatConversations store 共用，
// 此处重新导出维持既有 import 路径（`import type { PlanCard } from '@/store/vibeConversations'`）不必改动。
export type { PlanCard }

export interface VibeChatMessage {
  role: 'user' | 'assistant'
  text: string
  nodes: TraceNode[]
  /** 这条助手消息是一次失败的结果（额度用尽、后端异常等），UI 渲染成提示样式而不是正常回答。 */
  failed?: boolean
  // 本条助手消息内累积的沙箱编译/测试报告（P0-3），按到达顺序渲染成测试报告卡片时间线
  testReports?: TestReport[]
  // 本条助手消息内的 Plan Mode 确认卡片（P1-1），高风险操作待人工确认
  plans?: PlanCard[]
  // 协作模式多角色阶段进度（P3-1），按 role_stage 事件到达顺序累积
  stages?: RoleStageEvent[]
  // 该条消息携带的附件（用户消息才有）；历史消息来自后端，新发送消息由 send() 本地拼装并转移 previewUrl 所有权
  attachments?: MessageAttachmentVM[]
}

/** localId 是前端本地生成的临时 key；status 驱动 loading/失败态展示，失败附件不参与拼接。 */
export interface VibeAttachmentItem {
  localId: string
  id?: string
  name: string
  content: string
  status: 'uploading' | 'success' | 'failed'
  errorMessage?: string
  mimeType?: string
  fileSize?: number
  /** 图片附件本地 objectURL（零后端请求的即时缩略图）；发送时所有权转移给消息对象，移除/放弃时需 revoke。 */
  previewUrl?: string
}

export interface CommandHistoryItem {
  id: string
  command: string
  output: string
  status: 'RUNNING' | 'SUCCESS' | 'FAILED' | 'CANCELLED'
  exitCode: number | null
  durationMs: number | null
  startedAt: number
  testReport?: TestReport
}

type CodingStreamStarter = (handlers: SseHandlers) => () => void

/**
 * 单个 VibeCoding 会话的全部状态（比 chat 多：文件变更时间线、产物树、Plan 待确认、沙箱模式）。
 * 提到全局 store 的动机同 chatConversations：会话与组件生命周期解耦，切页面/切智能体都不丢。
 */
export interface VibeConversation {
  sessionId: string
  messages: VibeChatMessage[]
  input: string
  attachments: VibeAttachmentItem[]
  streaming: boolean
  interrupting: boolean
  interrupted: boolean
  abort: (() => void) | null
  fileChanges: Array<FileChangeEvent & { time: number }>
  pendingPlans: Map<string, PlanCard>
  fileNodes: WorkspaceFileNode[]
  filesLoading: boolean
  filesLoaded: boolean
  sandboxMode: 'local' | 'docker' | null
  rollingBack: boolean
  commandHistory: CommandHistoryItem[]
  commandRunning: boolean
  commandAbort: (() => void) | null
  /** 执行模式（会话内记忆，默认 auto），随每条消息一起发给后端。 */
  mode: ExecutionMode
}

interface AgentVibeState {
  conversations: Record<string, VibeConversation>
  activeId: string
}

export function createVibeConversation(sessionId: string, messages: VibeChatMessage[] = []): VibeConversation {
  return {
    sessionId,
    messages,
    input: '',
    attachments: [],
    streaming: false,
    interrupting: false,
    interrupted: false,
    abort: null,
    fileChanges: [],
    pendingPlans: new Map(),
    fileNodes: [],
    filesLoading: false,
    filesLoaded: false,
    sandboxMode: null,
    rollingBack: false,
    commandHistory: [],
    commandRunning: false,
    commandAbort: null,
    mode: 'auto',
  }
}

/** 从会话首条用户消息里解析出发送时用的沙箱模式，解析不出来（更早期版本的历史记录）返回 null。 */
export function parseSandboxModeFromMessage(text: string): 'local' | 'docker' | null {
  const match = text.match(/^\[VibeCoding指引-(docker|local)]/)
  return match ? (match[1] as 'local' | 'docker') : null
}

/**
 * VibeCoding 面板的会话状态（全局，跨组件生命周期存活），设计同 {@link useChatConversationsStore}。
 * Plan 倒计时是全局单定时器扫全部会话的 pendingPlans，不随组件卸载中断。
 */
export const useVibeConversationsStore = defineStore('vibeConversations', {
  state: () => ({
    byAgent: {} as Record<string, AgentVibeState>,
    historyVersion: {} as Record<string, number>,
    planCountdownTimer: null as ReturnType<typeof setInterval> | null,
  }),
  getters: {
    activeOf: (state) => (agentCode: string): VibeConversation | undefined => {
      const agent = state.byAgent[agentCode]
      return agent ? agent.conversations[agent.activeId] : undefined
    },
    liveSessionsOf: (state) => (agentCode: string): LiveSession[] => {
      const agent = state.byAgent[agentCode]
      if (!agent) return []
      return Object.values(agent.conversations)
        .map((c) => ({
          sessionId: c.sessionId,
          preview: previewOfMessages(c.messages),
          messageCount: c.messages.length,
          streaming: c.streaming,
        }))
        .filter((c) => c.messageCount > 0 || c.streaming)
    },
    activeIdOf: (state) => (agentCode: string): string => state.byAgent[agentCode]?.activeId ?? '',
  },
  actions: {
    /** 确保某智能体的状态已初始化，并给初始空会话拉一次全局沙箱模式。 */
    ensureAgent(agentCode: string) {
      if (this.byAgent[agentCode]) return
      const sid = generateUuid()
      this.byAgent[agentCode] = { conversations: { [sid]: createVibeConversation(sid) }, activeId: sid }
      this.loadCurrentSandboxMode(agentCode)
    },

    /** 新建会话（空白空闲会话直接复用，不 abort 进行中的旧会话）。新会话用当前全局沙箱配置。 */
    newSession(agentCode: string) {
      this.ensureAgent(agentCode)
      const agent = this.byAgent[agentCode]
      const cur = agent.conversations[agent.activeId]
      if (cur && cur.messages.length === 0 && !cur.streaming) {
        cur.input = ''
        // 复用空白会话相当于放弃这些待发送附件（不会再被发送），立即 revoke 图片本地 objectURL 防泄漏
        revokeAttachmentPreviews(cur.attachments)
        cur.attachments = []
        cur.fileChanges = []
        cur.fileNodes = []
        cur.filesLoaded = false
        this.loadCurrentSandboxMode(agentCode)
        return
      }
      const sid = generateUuid()
      agent.conversations[sid] = createVibeConversation(sid)
      agent.activeId = sid
      this.loadCurrentSandboxMode(agentCode)
    },

    /**
     * 当前全局沙箱配置（admin.sandbox.mode），代表"新会话将会使用的模式"，写入当前激活会话。
     * 一旦切到历史会话，标签改为从那条会话消息里解析出的"当时真正用的模式"，两者含义不同不能互替。
     */
    loadCurrentSandboxMode(agentCode: string) {
      const agent = this.byAgent[agentCode]
      if (!agent) return
      const sid = agent.activeId
      getSandboxMode(agentCode)
        .then((res) => { const c = this.byAgent[agentCode]?.conversations[sid]; if (c) c.sandboxMode = res.mode })
        .catch(() => { const c = this.byAgent[agentCode]?.conversations[sid]; if (c) c.sandboxMode = null })
    },

    /** 打开会话：store 已有直接切激活（保留实时增量与文件树）；纯历史会话才回源拉消息 + 产物文件。 */
    async openSession(agentCode: string, targetSessionId: string): Promise<void> {
      this.ensureAgent(agentCode)
      const agent = this.byAgent[agentCode]
      if (agent.conversations[targetSessionId]) {
        agent.activeId = targetSessionId
        return
      }
      const history = await getChatSessionMessages(agentCode, targetSessionId)
      const conv = createVibeConversation(
        targetSessionId,
        history.map((msg) => ({
          role: msg.role,
          text: msg.text,
          nodes: [],
          // 历史附件没有本地 previewUrl，图片缩略图交给 MessageAttachments 组件按需拉后端 blob
          attachments: msg.attachments.length > 0 ? msg.attachments : undefined,
        })),
      )
      const firstUserMessage = history.find((msg) => msg.role === 'user')
      conv.sandboxMode = firstUserMessage ? parseSandboxModeFromMessage(firstUserMessage.text) : null
      agent.conversations[targetSessionId] = conv
      agent.activeId = targetSessionId
      // 切到历史会话时该会话可能已有产物文件，无需等用户手动点"刷新"
      this.loadFiles(agentCode, targetSessionId)
    },

    /** 加载（刷新）指定会话的 workspace 目录树。 */
    async loadFiles(agentCode: string, sessionId: string) {
      const conv = this.byAgent[agentCode]?.conversations[sessionId]
      if (!conv) return
      conv.filesLoading = true
      try {
        conv.fileNodes = await listWorkspaceFiles(agentCode, sessionId)
        conv.filesLoaded = true
      } catch (error) {
        ElMessage.error('目录加载失败：' + (error instanceof Error ? error.message : String(error)))
      } finally {
        conv.filesLoading = false
      }
    },

    /** 发送当前激活会话的输入；SSE 事件按 (agentCode, sessionId) 写回原会话，切走不受影响。 */
    send(
      agentCode: string,
      collaboration: boolean,
      buildMessage: (conv: VibeConversation, text: string) => string,
      onScroll?: () => void,
    ) {
      const agent = this.byAgent[agentCode]
      const conv = agent?.conversations[agent.activeId]
      if (!conv) return
      const text = conv.input.trim()
      // 输入框内容自动去首尾空白：纯空白输入被拦下时框里的空格也一并清掉，避免"有空格但发不出去"的困惑
      conv.input = text
      const successfulAttachments = conv.attachments.filter((a) => a.status === 'success' && a.id)
      // 有解析成功的附件时允许"只发附件不写文字"（正文即附件内容，满足后端 message 非空要求）
      if ((!text && successfulAttachments.length === 0) || conv.streaming || conv.attachments.some((a) => a.status === 'uploading')) return
      conv.interrupted = false
      const messageToSend = buildMessage(conv, text)
      const attachmentIds = successfulAttachments.length > 0 ? successfulAttachments.map((a) => a.id as string) : undefined
      // previewUrl 所有权从待发送区转移给消息对象（不 revoke）——见下方 conv.attachments 清空前的说明
      conv.messages.push({
        role: 'user',
        text,
        nodes: [],
        attachments: successfulAttachments.length > 0
          ? successfulAttachments.map((a) => ({
              id: a.id as string,
              fileName: a.name,
              mimeType: a.mimeType ?? '',
              fileSize: a.fileSize ?? 0,
              parseStatus: 'SUCCESS',
              previewUrl: a.previewUrl,
            }))
          : undefined,
      })
      conv.messages.push({ role: 'assistant', text: '', nodes: [], testReports: [] })
      // 同 chat store：push 完再取，拿响应式代理而不是原始对象
      const assistantMessage = conv.messages[conv.messages.length - 1]
      conv.input = ''
      conv.attachments = []
      conv.streaming = true
      const sid = conv.sessionId

      this.bindCodingStream(agentCode, conv, assistantMessage, (handlers) =>
        streamVibeCoding(agentCode, {
          sessionId: sid,
          message: messageToSend,
          collaboration,
          mode: conv.mode,
          attachmentIds,
        }, handlers), onScroll)
      onScroll?.()
    },

    /** P2-1：把诊断任务接入与普通对话相同的消息、文件变更、测试报告和计划确认时间线。 */
    sendDiagnosis(agentCode: string, log: string, onScroll?: () => void) {
      const text = log.trim()
      if (!text) return
      this.startSpecialTask(agentCode, `/diagnose\n${text}`, (sessionId, handlers) =>
        streamDiagnosis(agentCode, sessionId, text, handlers), onScroll)
    },

    /** P2-2：服务端任务级 plan 的事件也走同一个处理器，现有 PlanConfirmCard 可直接确认。 */
    sendRefactor(agentCode: string, request: Omit<RefactorTaskRequest, 'sessionId'>, onScroll?: () => void) {
      const targets = request.targetFiles.length > 0 ? `\n目标文件：${request.targetFiles.join(', ')}` : ''
      const display = `/refactor ${request.taskType}${targets}\n${request.description}`
      this.startSpecialTask(agentCode, display, (sessionId, handlers) =>
        streamRefactor(agentCode, { ...request, sessionId }, handlers), onScroll)
    },

    startSpecialTask(
      agentCode: string,
      displayText: string,
      starter: (sessionId: string, handlers: SseHandlers) => () => void,
      onScroll?: () => void,
    ) {
      const agent = this.byAgent[agentCode]
      const conv = agent?.conversations[agent.activeId]
      if (!conv || conv.streaming) return
      conv.interrupted = false
      conv.messages.push({ role: 'user', text: displayText, nodes: [] })
      conv.messages.push({ role: 'assistant', text: '', nodes: [], testReports: [] })
      const assistantMessage = conv.messages[conv.messages.length - 1]
      conv.streaming = true
      this.bindCodingStream(agentCode, conv, assistantMessage,
        (handlers) => starter(conv.sessionId, handlers), onScroll)
      onScroll?.()
    },

    /** 普通对话、诊断、重构共用唯一 SSE 状态机，避免新入口的事件契约逐步漂移。 */
    bindCodingStream(
      agentCode: string,
      conv: VibeConversation,
      assistantMessage: VibeChatMessage,
      starter: CodingStreamStarter,
      onScroll?: () => void,
    ) {
      const sid = conv.sessionId
      conv.abort = starter({
        onEvent: (event) => {
          const c = this.byAgent[agentCode]?.conversations[sid]
          if (!c) return
          const isActive = () => this.byAgent[agentCode]?.activeId === sid
          if (event.event === 'done') {
            c.streaming = false
            // 对话结束后自动刷新该会话文件目录树
            this.loadFiles(agentCode, sid)
            return
          }
          if (event.event === 'file_change') {
            try {
              const parsed = JSON.parse(event.data) as FileChangeEvent
              c.fileChanges.push({ ...parsed, time: Date.now() })
            } catch { /* 解析失败不影响主对话流程，静默丢弃 */ }
            return
          }
          if (event.event === 'test_report') {
            try {
              const parsed = JSON.parse(event.data) as TestReport
              ;(assistantMessage.testReports ??= []).push(parsed)
            } catch { /* 静默丢弃 */ }
            if (isActive()) onScroll?.()
            return
          }
          if (event.event === 'role_stage') {
            this.applyRoleStage(assistantMessage, event.data)
            if (isActive()) onScroll?.()
            return
          }
          if (event.event === 'plan') {
            this.applyPlanEvent(c, assistantMessage, event.data)
            if (isActive()) onScroll?.()
            return
          }
          if (event.event === 'plan_result') {
            try {
              const parsed = JSON.parse(event.data) as PlanResultEvent
              const card = c.pendingPlans.get(parsed.planId)
              if (card) {
                card.status = parsed.status
                card.submitting = false
                c.pendingPlans.delete(parsed.planId)
              }
            } catch { /* 静默丢弃 */ }
            return
          }
          if (event.event.startsWith('node:')) {
            const kind = event.event.slice('node:'.length)
            const payload = parseChatStreamPayload(event.data)
            appendChatStreamNode(assistantMessage.nodes, kind, payload.text, payload.source, payload.subagentName)
          } else if (event.event === 'message') {
            const payload = parseChatStreamPayload(event.data)
            if (payload.source) {
              // 带 source 的正文增量是子Agent 内部产出，复用 ANSWER kind 挂进对应嵌套面板
              appendChatStreamNode(assistantMessage.nodes, ANSWER_KIND, payload.text, payload.source, payload.subagentName)
            } else {
              assistantMessage.text += payload.text
            }
          }
          // 其余未知事件静默忽略（需求 §5.5 向后兼容）
          if (isActive()) onScroll?.()
        },
        onError: (error) => {
          const c = this.byAgent[agentCode]?.conversations[sid]
          if (c) {
            c.streaming = false
            c.interrupting = false
          }
          // 与对话面板同理：失败信息要落在对话流里，紧跟用户刚发出的那句话
          const text = error instanceof Error ? error.message : String(error)
          assistantMessage.text = text
          assistantMessage.failed = true
        },
        onComplete: () => {
          const c = this.byAgent[agentCode]?.conversations[sid]
          if (c) {
            c.streaming = false
            if (c.interrupting) {
              c.interrupting = false
              c.interrupted = true
            }
          }
          this.historyVersion[agentCode] = (this.historyVersion[agentCode] ?? 0) + 1
        },
      })
    },

    /** P1-2：执行命令并保留当前会话最近 20 条历史。 */
    executeCommand(agentCode: string, rawCommand: string) {
      const agent = this.byAgent[agentCode]
      const conv = agent?.conversations[agent.activeId]
      const command = rawCommand.trim()
      if (!conv || !command || conv.commandRunning || conv.streaming) return
      const item: CommandHistoryItem = {
        id: generateUuid(),
        command,
        output: '',
        status: 'RUNNING',
        exitCode: null,
        durationMs: null,
        startedAt: Date.now(),
      }
      conv.commandHistory.unshift(item)
      conv.commandHistory = conv.commandHistory.slice(0, 20)
      // push 后重新取响应式代理，后续 SSE 增量才能立即反映到终端视图。
      const current = conv.commandHistory[0]
      conv.commandRunning = true
      conv.commandAbort = streamSandboxCommand(agentCode, conv.sessionId, command, {
        onEvent: (event) => {
          if (event.event === 'command_output') {
            try {
              current.output += (JSON.parse(event.data) as CommandOutputEvent).text
            } catch { /* 非法增量不影响命令终态 */ }
            return
          }
          if (event.event === 'test_report') {
            try { current.testReport = JSON.parse(event.data) as TestReport } catch { /* 静默降级 */ }
            return
          }
          if (event.event === 'command_result') {
            try {
              const result = JSON.parse(event.data) as CommandResultEvent
              current.exitCode = result.exitCode
              current.durationMs = result.durationMs
              current.status = result.success ? 'SUCCESS' : 'FAILED'
            } catch {
              current.status = 'FAILED'
            }
            return
          }
          if (event.event === 'command_error') {
            try {
              const error = JSON.parse(event.data) as { message: string }
              current.output += `\n${error.message}\n`
            } catch { /* 静默降级 */ }
            current.status = 'FAILED'
          }
          if (event.event === 'done') {
            conv.commandRunning = false
            conv.commandAbort = null
          }
        },
        onError: (error) => {
          current.status = 'FAILED'
          current.output += `\n${error instanceof Error ? error.message : String(error)}\n`
          conv.commandRunning = false
          conv.commandAbort = null
        },
        onComplete: () => {
          conv.commandRunning = false
          conv.commandAbort = null
        },
      })
    },

    stopCommand(agentCode: string) {
      const conv = this.activeOf(agentCode)
      if (!conv?.commandRunning) return
      conv.commandAbort?.()
      const running = conv.commandHistory.find((item) => item.status === 'RUNNING')
      if (running) running.status = 'CANCELLED'
      conv.commandRunning = false
      conv.commandAbort = null
    },

    /** role_stage 事件（P3-1）：START 追加新阶段；DONE/FAILED 按 index 更新（找不到则补插）。 */
    applyRoleStage(assistantMessage: VibeChatMessage, raw: string) {
      try {
        const parsed = JSON.parse(raw) as RoleStageEvent
        const stages = (assistantMessage.stages ??= [])
        if (parsed.status === 'START') {
          stages.push({ ...parsed, output: null })
          return
        }
        const existing = stages.find((s) => s.index === parsed.index)
        if (existing) {
          existing.status = parsed.status
          existing.output = parsed.output
          existing.role = parsed.role
          existing.type = parsed.type
        } else {
          stages.push({ ...parsed })
        }
      } catch { /* 静默丢弃 */ }
    },

    /** plan 事件（P1-1 HITL）：追加待确认卡片并确保全局倒计时在跑。 */
    applyPlanEvent(conv: VibeConversation, assistantMessage: VibeChatMessage, raw: string) {
      try {
        const parsed = JSON.parse(raw) as PlanEvent
        const card = createPlanCard(parsed)
        const plans = (assistantMessage.plans ??= [])
        plans.push(card)
        // 存响应式代理引用（push 后再取），否则定时器/plan_result 改原始对象视图不更新
        conv.pendingPlans.set(card.planId, plans[plans.length - 1])
        this.ensurePlanCountdown()
      } catch { /* 静默丢弃 */ }
    },

    /** 全局单定时器：每秒扫全部智能体全部会话的待确认卡片递减，归零本地标记超时；无剩余时自停。 */
    ensurePlanCountdown() {
      if (this.planCountdownTimer) return
      this.planCountdownTimer = setInterval(() => {
        let anyPending = false
        for (const agent of Object.values(this.byAgent)) {
          for (const conv of Object.values(agent.conversations)) {
            for (const card of conv.pendingPlans.values()) {
              anyPending = true
              card.remainingSeconds -= 1
              if (card.remainingSeconds <= 0) {
                // 本地倒计时归零：先行标记超时（服务端也会补发 plan_result=TIMEOUT，二者幂等）
                card.status = 'TIMEOUT'
                card.remainingSeconds = 0
                conv.pendingPlans.delete(card.planId)
              }
            }
          }
        }
        if (!anyPending && this.planCountdownTimer) {
          clearInterval(this.planCountdownTimer)
          this.planCountdownTimer = null
        }
      }, 1000)
    },

    /** 点击"终止"：只通知后端安全中断，不 abort 前端连接（同 chat store 的注释）。 */
    async interrupt(agentCode: string) {
      const agent = this.byAgent[agentCode]
      const conv = agent?.conversations[agent.activeId]
      if (!conv) return
      conv.interrupting = true
      try {
        await interruptVibeCoding(agentCode, conv.sessionId)
      } catch (error) {
        conv.interrupting = false
        ElMessage.error('终止失败：' + (error instanceof Error ? error.message : String(error)))
      }
    },
  },
})
