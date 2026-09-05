import { defineStore } from 'pinia'
import { getChatSessionMessages, interruptChat, streamChat } from '@/api/chat'
import { generateUuid } from '@/utils/uuid'
import {
  ANSWER_KIND,
  appendChatStreamNode,
  parseChatStreamPayload,
  type TraceNode,
} from '@/utils/traceTimeline'
import { createPlanCard, type PlanCard } from '@/utils/planCard'
import { revokeAttachmentPreviews, type MessageAttachmentVM } from '@/utils/attachment'
import { createTextChunkBatcher } from '@/utils/textChunkBatcher'
import type { ExecutionMode, LiveSession, PlanEvent, PlanResultEvent } from '@/types/api'

export interface ChatMessage {
  role: 'user' | 'assistant'
  text: string
  nodes: TraceNode[]
  /**
   * 这条助手消息是一次失败的结果（额度用尽、后端异常等）。
   *
   * UI 据此渲染成提示样式而不是正常回答——同样一段文字，看起来像"AI 说的话"
   * 还是"系统告诉你这轮没成"，差别很大。
   */
  failed?: boolean
  /** 执行错误独立于回答保存，避免流式失败覆盖已经生成的内容。 */
  error?: string
  // 本条助手消息内的 Plan Mode 确认卡片（P1-1），Manual/高风险操作待人工确认
  plans?: PlanCard[]
  // 该条消息携带的附件（用户消息才有）；历史消息来自后端，新发送消息由 send() 本地拼装并转移 previewUrl 所有权
  attachments?: MessageAttachmentVM[]
}

/** localId 是前端本地生成的临时 key，用于 v-for/移除定位（上传过程中后端 id 还不存在）；
 * status 驱动 tag 的 loading/失败态展示，失败附件不参与拼接。 */
export interface ChatAttachmentItem {
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

/**
 * 单个会话的全部状态。历史演进：最初是面板级单份 ref（切会话即覆盖丢失）→ 组件内按 sessionId 分组
 * （切菜单靠 keep-alive 保命，但切智能体时组件按 :key 重建仍会全丢）→ 现在提到 Pinia 全局 store，
 * 会话与组件生命周期彻底解耦：切页面、切智能体、组件销毁重建都不影响进行中的会话与 SSE 流。
 */
export interface ChatConversation {
  sessionId: string
  messages: ChatMessage[]
  input: string
  attachments: ChatAttachmentItem[]
  streaming: boolean
  interrupting: boolean // 已点终止，等后端真正停下来（协作式中断，不保证立即生效）
  interrupted: boolean // 上一轮是被终止结束的，可以点"继续"续跑挂起的工具调用
  abort: (() => void) | null
  /** 执行模式（会话内记忆，默认 auto），随每条消息一起发给后端。 */
  mode: ExecutionMode
  /** 当前会话待确认的 plan 卡片（planId → 卡片），供全局倒计时定时器统一扫描递减。 */
  pendingPlans: Map<string, PlanCard>
}

/** 某个智能体名下的全部会话 + 当前激活会话。 */
interface AgentChatState {
  conversations: Record<string, ChatConversation>
  activeId: string
}

const PREVIEW_MAX_LENGTH = 40

export function createChatConversation(
  sessionId: string,
  messages: ChatMessage[] = [],
): ChatConversation {
  return {
    sessionId,
    messages,
    input: '',
    attachments: [],
    streaming: false,
    interrupting: false,
    interrupted: false,
    abort: null,
    mode: 'auto',
    pendingPlans: new Map(),
  }
}

/**
 * 会话预览文案：取首条有内容的用户消息。文字为空但带了附件（只发附件不写字）时，
 * 回退成附件文件名列表——附件现在挂在结构化的 attachments 字段而不是拼进 text，
 * 不这样兜底的话纯附件消息在侧边栏会显示空白预览。
 */
export function previewOfMessages(messages: ChatMessage[]): string {
  const firstUser = messages.find(
    (m) => m.role === 'user' && (m.text.trim().length > 0 || (m.attachments?.length ?? 0) > 0),
  )
  if (!firstUser) return ''
  const text = firstUser.text.trim()
  if (!text) {
    const names = (firstUser.attachments ?? []).map((a) => a.fileName).join('、')
    return names ? `📎 ${names}` : ''
  }
  return text.length > PREVIEW_MAX_LENGTH ? text.slice(0, PREVIEW_MAX_LENGTH) + '...' : text
}

/**
 * 工作区"对话"面板的会话状态（全局，跨组件生命周期存活）。
 *
 * <p>SSE 回调闭包持有 (agentCode, sessionId) 直接写回 store 里对应会话——组件卸载不影响流的继续接收；
 * 组件只是"当前正在看哪个智能体的哪个会话"的视图。historyVersion 在每轮流结束时自增，
 * 侧边栏 watch 它来刷新已落库列表。</p>
 */
export const useChatConversationsStore = defineStore('chatConversations', {
  state: () => ({
    byAgent: {} as Record<string, AgentChatState>,
    /** 每轮对话流结束自增（按 agentCode），侧边栏 watch 它刷新后端历史列表。 */
    historyVersion: {} as Record<string, number>,
    /** Plan 卡片全局单定时器（设计同 vibeConversations），跨全部智能体全部会话统一扫描递减。 */
    planCountdownTimer: null as ReturnType<typeof setInterval> | null,
  }),
  getters: {
    /** 某智能体当前激活的会话（未初始化时 undefined，组件应先 ensureAgent）。 */
    activeOf:
      (state) =>
      (agentCode: string): ChatConversation | undefined => {
        const agent = state.byAgent[agentCode]
        return agent ? agent.conversations[agent.activeId] : undefined
      },
    /** 供历史侧边栏合并展示的内存会话（只含非空或进行中的，空白新会话不进列表）。 */
    liveSessionsOf:
      (state) =>
      (agentCode: string): LiveSession[] => {
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
    activeIdOf:
      (state) =>
      (agentCode: string): string =>
        state.byAgent[agentCode]?.activeId ?? '',
  },
  actions: {
    /** 确保某智能体的状态已初始化（至少有一个空会话作为激活会话），组件挂载时调用。 */
    ensureAgent(agentCode: string) {
      if (this.byAgent[agentCode]) return
      const sid = generateUuid()
      this.byAgent[agentCode] = {
        conversations: { [sid]: createChatConversation(sid) },
        activeId: sid,
      }
    },

    /**
     * 新建会话：不 abort 当前会话（进行中的旧会话留在 store 里后台继续流式，可从历史列表找回）。
     * 若当前激活会话本就是空白且没在跑，直接复用它，避免连点"新建"堆一堆空会话。
     */
    newSession(agentCode: string) {
      this.ensureAgent(agentCode)
      const agent = this.byAgent[agentCode]
      const cur = agent.conversations[agent.activeId]
      if (cur && cur.messages.length === 0 && !cur.streaming) {
        cur.input = ''
        // 复用空白会话相当于放弃这些待发送附件（不会再被发送），立即 revoke 图片本地 objectURL 防泄漏
        revokeAttachmentPreviews(cur.attachments)
        cur.attachments = []
        return
      }
      const sid = generateUuid()
      agent.conversations[sid] = createChatConversation(sid)
      agent.activeId = sid
    },

    /**
     * 打开会话：store 里已有（进行中或本次加载过的）直接切激活，保留实时增量不回源覆盖；
     * 只有没有的纯历史会话才回源拉消息。返回是否发生了网络加载（供组件控制 loading 态）。
     */
    async openSession(agentCode: string, targetSessionId: string): Promise<void> {
      this.ensureAgent(agentCode)
      const agent = this.byAgent[agentCode]
      if (agent.conversations[targetSessionId]) {
        agent.activeId = targetSessionId
        return
      }
      const history = await getChatSessionMessages(agentCode, targetSessionId)
      agent.conversations[targetSessionId] = createChatConversation(
        targetSessionId,
        history.map((msg) => ({
          role: msg.role,
          text: msg.text,
          nodes: [],
          // 历史附件没有本地 previewUrl，图片缩略图交给 MessageAttachments 组件按需拉后端 blob
          attachments: msg.attachments.length > 0 ? msg.attachments : undefined,
        })),
      )
      agent.activeId = targetSessionId
    },

    /**
     * 发送当前激活会话的输入。SSE 回调按 (agentCode, sessionId) 写回 store 对应会话——用户切走、
     * 组件销毁都不影响；onScroll 由组件传入，仅当该会话仍是激活会话时组件才滚动视图。
     */
    send(
      agentCode: string,
      buildMessage: (conv: ChatConversation, text: string) => string,
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
      if (
        (!text && successfulAttachments.length === 0) ||
        conv.streaming ||
        conv.attachments.some((a) => a.status === 'uploading')
      )
        return
      conv.interrupted = false
      const messageToSend = buildMessage(conv, text)
      const attachmentIds =
        successfulAttachments.length > 0
          ? successfulAttachments.map((a) => a.id as string)
          : undefined
      // 用户气泡展示原始输入 + 独立的附件区（图片缩略图/文件芯片），不把拼进正文的附件全文也显示出来
      // （那部分只是发给模型看的）。previewUrl 所有权从待发送区转移给消息对象——发送后立即清空
      // conv.attachments（见下方），这里转移完就不再由待发送区持有，也不 revoke，气泡还要接着用它。
      conv.messages.push({
        role: 'user',
        text,
        nodes: [],
        attachments:
          successfulAttachments.length > 0
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
      conv.messages.push({ role: 'assistant', text: '', nodes: [] })
      // 坑：不能拿 push 前创建的原始对象引用去改——响应式数组对存进去的对象是"读取时才转代理"，
      // 改原始对象绕过代理 setter，视图不会增量刷新。push 完再从数组里取，拿到的才是代理本身。
      const assistantMessage = conv.messages[conv.messages.length - 1]
      conv.input = ''
      conv.attachments = []
      conv.streaming = true
      const sid = conv.sessionId
      const isActive = () => this.byAgent[agentCode]?.activeId === sid
      const textBatcher = createTextChunkBatcher((chunk) => {
        assistantMessage.text += chunk
        if (isActive()) onScroll?.()
      })

      const abortStream = streamChat(
        agentCode,
        { sessionId: sid, message: messageToSend, mode: conv.mode, attachmentIds },
        {
          onEvent: (event) => {
            const c = this.byAgent[agentCode]?.conversations[sid]
            if (!c) return
            if (event.event === 'done') {
              textBatcher.flush()
              c.streaming = false
              return
            }
            if (event.event === 'plan') {
              textBatcher.flush()
              this.applyPlanEvent(c, assistantMessage, event.data)
              if (isActive()) onScroll?.()
              return
            }
            if (event.event === 'plan_result') {
              textBatcher.flush()
              try {
                const parsed = JSON.parse(event.data) as PlanResultEvent
                const card = c.pendingPlans.get(parsed.planId)
                if (card) {
                  card.status = parsed.status
                  card.submitting = false
                  c.pendingPlans.delete(parsed.planId)
                }
              } catch {
                /* 静默丢弃 */
              }
              return
            }
            if (event.event.startsWith('node:')) {
              textBatcher.flush()
              const kind = event.event.slice('node:'.length)
              const payload = parseChatStreamPayload(event.data)
              appendChatStreamNode(
                assistantMessage.nodes,
                kind,
                payload.text,
                payload.source,
                payload.subagentName,
              )
            } else if (event.event === 'message') {
              const payload = parseChatStreamPayload(event.data)
              if (payload.source) {
                // 带 source 的正文增量是子Agent 内部产出，复用 ANSWER kind 挂进对应嵌套面板
                appendChatStreamNode(
                  assistantMessage.nodes,
                  ANSWER_KIND,
                  payload.text,
                  payload.source,
                  payload.subagentName,
                )
              } else {
                textBatcher.append(payload.text)
                return
              }
            }
            // 其余未知事件静默忽略：后端新增 SSE 事件类型时旧前端不受影响（需求 §5.5 向后兼容）
            if (isActive()) onScroll?.()
          },
          onError: (error) => {
            textBatcher.flush()
            const c = this.byAgent[agentCode]?.conversations[sid]
            if (c) {
              c.streaming = false
              c.interrupting = false
            }
            // 失败信息落在对话流里，紧跟用户刚发出的那句话——飘在页面顶部的提示与它无从对应，
            // 用户看到的是自己的消息孤零零挂在那儿、没有任何回应
            const text = error instanceof Error ? error.message : String(error)
            assistantMessage.error = text
            assistantMessage.failed = true
          },
          onComplete: () => {
            textBatcher.flush()
            const c = this.byAgent[agentCode]?.conversations[sid]
            if (c) {
              c.streaming = false
              // 若这轮是用户主动点了"终止"后自然结束的，翻转成"可继续"状态，冒出继续按钮
              if (c.interrupting) {
                c.interrupting = false
                c.interrupted = true
              }
            }
            this.historyVersion[agentCode] = (this.historyVersion[agentCode] ?? 0) + 1
          },
        },
      )
      conv.abort = () => {
        textBatcher.flush()
        abortStream()
      }
      onScroll?.()
    },

    /** 点击"终止"：只通知后端安全中断（协作式），不 abort 前端连接——让 onComplete/onError 在
     * 后端真正停止、SSE 自然结束时收尾，避免界面"已停止"但后端还在跑的假象。 */
    async interrupt(agentCode: string) {
      const agent = this.byAgent[agentCode]
      const conv = agent?.conversations[agent.activeId]
      if (!conv) return
      conv.interrupting = true
      try {
        await interruptChat(agentCode, conv.sessionId)
      } catch (error) {
        conv.interrupting = false
        ElMessage.error('终止失败：' + (error instanceof Error ? error.message : String(error)))
      }
    },

    /** plan 事件（P1-1 HITL）：追加待确认卡片并确保全局倒计时在跑。设计同 vibeConversations。 */
    applyPlanEvent(conv: ChatConversation, assistantMessage: ChatMessage, raw: string) {
      try {
        const parsed = JSON.parse(raw) as PlanEvent
        const card = createPlanCard(parsed)
        const plans = (assistantMessage.plans ??= [])
        plans.push(card)
        // 存响应式代理引用（push 后再取），否则定时器/plan_result 改原始对象视图不更新
        conv.pendingPlans.set(card.planId, plans[plans.length - 1])
        this.ensurePlanCountdown()
      } catch {
        /* 静默丢弃 */
      }
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
  },
})
