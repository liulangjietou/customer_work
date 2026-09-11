import { defineStore } from 'pinia'
import { reactive, watch } from 'vue'
import { getTicketMessageReceipt, replyTicket } from '@/api/user-ticket'
import { getRequestErrorMessage } from '@/api/request'
import { useAuthStore } from './auth'
import type { TicketMessageVO } from '@/types/ticket'

export interface PendingTicketReply {
  clientMsgId: string
  content: string
  createdAtMs: number
  draftVersion: number
  status: 'SENDING' | 'UNKNOWN' | 'REJECTED'
  error: string
  busy: boolean
}

interface TicketDraft {
  content: string
  version: number
  pending: PendingTicketReply[]
  saved: TicketMessageVO[]
}

/** 只保留当前登录与租户的内存草稿；按工单更新，迟到回执不能清空另一张工单或后来编辑的内容。 */
export const useTicketReplies = defineStore('ticketReplies', () => {
  const auth = useAuthStore()
  const drafts = reactive(new Map<string, TicketDraft>())
  let identity = ''
  let currentAgent = ''
  let generation = 0

  function bindIdentity(tenantId: string, agentId: string) {
    const next =
      auth.token && tenantId && agentId ? JSON.stringify([auth.token, tenantId, agentId]) : ''
    if (next === identity) return
    identity = next
    currentAgent = agentId
    generation += 1
    drafts.clear()
  }

  watch(
    () => auth.token,
    () => bindIdentity('', ''),
    { flush: 'sync' },
  )

  function state(ticketId: string): TicketDraft {
    if (!drafts.has(ticketId))
      drafts.set(ticketId, { content: '', version: 0, pending: [], saved: [] })
    return drafts.get(ticketId)!
  }

  function setDraft(ticketId: string, content: string) {
    const draft = state(ticketId)
    draft.content = content
    draft.version += 1
  }

  function applySaved(ticketId: string, pending: PendingTicketReply, message: TicketMessageVO) {
    if (
      !message ||
      !Number.isSafeInteger(message.id) ||
      message.id <= 0 ||
      !message.messageId ||
      message.ticketId !== ticketId ||
      message.senderType !== 'AGENT' ||
      message.senderId !== currentAgent ||
      message.content !== pending.content
    ) {
      throw new Error('返回记录与本次回复不一致，请核对会话记录。')
    }
    const draft = state(ticketId)
    draft.saved = [...draft.saved.filter((item) => item.messageId !== message.messageId), message]
    draft.pending = draft.pending.filter((item) => item.clientMsgId !== pending.clientMsgId)
    if (draft.version === pending.draftVersion && draft.content === pending.content)
      setDraft(ticketId, '')
  }

  function definitelyRejected(error: unknown): boolean {
    const response = error as { code?: number; response?: { data?: { code?: number } } } | null
    const code = response?.response?.data?.code ?? response?.code
    // 权限、入口参数、不存在及工单状态冲突均来自服务端明确拒绝，其他失败保持未知。
    return [20001, 30001, 30002, 30003, 40010].includes(code ?? 0)
  }

  async function post(ticketId: string, pending: PendingTicketReply) {
    if (!identity || pending.busy) return
    const version = generation
    pending.busy = true
    pending.status = 'SENDING'
    pending.error = ''
    try {
      const saved = await replyTicket(ticketId, {
        content: pending.content,
        clientMsgId: pending.clientMsgId,
      })
      if (version === generation) applySaved(ticketId, pending, saved)
    } catch (error) {
      if (version !== generation) return
      pending.status = definitelyRejected(error) ? 'REJECTED' : 'UNKNOWN'
      pending.error = getRequestErrorMessage(error, '暂时无法确认回复是否保存，请先查询结果。')
    } finally {
      if (version === generation) pending.busy = false
    }
  }

  /** 新发送先登记本地状态，稳定标识在每次重试中保持不变。 */
  async function send(ticketId: string) {
    if (!identity) return
    const draft = state(ticketId)
    if (!draft.content.trim() || draft.pending.some((item) => item.content === draft.content))
      return
    const pending: PendingTicketReply = reactive({
      clientMsgId: crypto.randomUUID(),
      content: draft.content,
      createdAtMs: Date.now(),
      draftVersion: draft.version,
      status: 'SENDING',
      error: '',
      busy: false,
    })
    draft.pending.push(pending)
    await post(ticketId, pending)
  }

  /** 查询先于重发；只有成功查询且记录缺失时，用户明确重试才会再次提交。 */
  async function reconcile(ticketId: string, pending: PendingTicketReply, allowResend = false) {
    if (!identity || pending.busy) return
    const version = generation
    pending.busy = true
    try {
      const receipt = await getTicketMessageReceipt(ticketId, pending.clientMsgId)
      if (version !== generation) return
      if (!receipt || receipt.clientMsgId !== pending.clientMsgId)
        throw new Error('回执标识不一致，请重新查询。')
      if (receipt.message) {
        applySaved(ticketId, pending, receipt.message)
      } else {
        pending.status = 'REJECTED'
        pending.error = '未找到这条回复的保存记录，原文已保留。'
        if (allowResend) {
          pending.busy = false
          await post(ticketId, pending)
        }
      }
    } catch (error) {
      if (version !== generation) return
      pending.status = 'UNKNOWN'
      pending.error = getRequestErrorMessage(error, '回执查询失败，请稍后再查。')
    } finally {
      if (version === generation) pending.busy = false
    }
  }

  async function reconcileTicket(ticketId: string) {
    await Promise.all(
      state(ticketId)
        .pending.filter((item) => !item.busy)
        .map((item) => reconcile(ticketId, item)),
    )
  }

  /** 仅明确未受理的记录可移除；未知结果必须继续留在对话中供核对。 */
  function dismissRejected(ticketId: string, clientMsgId: string) {
    const draft = state(ticketId)
    draft.pending = draft.pending.filter(
      (item) => item.clientMsgId !== clientMsgId || item.status !== 'REJECTED',
    )
  }

  return { state, setDraft, bindIdentity, send, reconcile, reconcileTicket, dismissRejected }
})
