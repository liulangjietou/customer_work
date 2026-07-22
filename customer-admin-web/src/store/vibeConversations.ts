import { defineStore } from 'pinia'
import { getSandboxMode, interruptVibeCoding, listWorkspaceFiles, streamVibeCoding } from '@/api/vibecoding'
import { getChatSessionMessages } from '@/api/chat'
import { generateUuid } from '@/utils/uuid'
import { ANSWER_KIND, appendChatStreamNode, parseChatStreamPayload } from '@/utils/traceTimeline'
import type { TraceNode } from '@/components/TraceTimeline.vue'
import type {
  FileChangeEvent,
  LiveSession,
  PlanEvent,
  PlanResultEvent,
  RoleStageEvent,
  TestReport,
  WorkspaceFileNode,
} from '@/types/api'
import { previewOfMessages } from '@/store/chatConversations'

/** Plan Mode 确认卡片（P1-1 HITL）：一条 plan 事件对应一张卡片，用户批准/拒绝或超时后翻成终态。 */
export interface PlanCard {
  planId: string
  actions: PlanEvent['actions']
  reason: string
  status: 'PENDING' | 'APPROVED' | 'REJECTED' | 'TIMEOUT'
  remainingSeconds: number
  submitting: boolean
}

export interface VibeChatMessage {
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

/** localId 是前端本地生成的临时 key；status 驱动 loading/失败态展示，失败附件不参与拼接。 */
export interface VibeAttachmentItem {
  localId: string
  id?: string
  name: string
  content: string
  status: 'uploading' | 'success' | 'failed'
  errorMessage?: string
}

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
        history.map((msg) => ({ role: msg.role, text: msg.text, nodes: [] })),
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
      const attachedNames = conv.attachments.filter((a) => a.status === 'success').map((a) => a.name)
      // 有解析成功的附件时允许"只发附件不写文字"（正文即附件内容，满足后端 message 非空要求）
      if ((!text && attachedNames.length === 0) || conv.streaming || conv.attachments.some((a) => a.status === 'uploading')) return
      conv.interrupted = false
      const messageToSend = buildMessage(conv, text)
      conv.messages.push({
        role: 'user',
        text: attachedNames.length > 0 ? `${text ? text + '\n' : ''}📎 ${attachedNames.join('、')}` : text,
        nodes: [],
      })
      conv.messages.push({ role: 'assistant', text: '', nodes: [], testReports: [] })
      // 同 chat store：push 完再取，拿响应式代理而不是原始对象
      const assistantMessage = conv.messages[conv.messages.length - 1]
      conv.input = ''
      conv.attachments = []
      conv.streaming = true
      const sid = conv.sessionId

      conv.abort = streamVibeCoding(agentCode, { sessionId: sid, message: messageToSend, collaboration }, {
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
          ElMessage.error('对话失败：' + (error instanceof Error ? error.message : String(error)))
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
      onScroll?.()
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
