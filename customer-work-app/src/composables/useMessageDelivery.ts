import { onScopeDispose, reactive, watch, type Ref } from 'vue'
import { showToast } from 'vant'
import { fetchMessageReceipt } from '@/api/ticket'
import type { ChatMessage, WsChatAccepted, WsErrorMessage } from '@/types/api'
import { chatSocket } from '@/utils/ws'

const RECEIPT_TIMEOUT_MS = 15_000
const UNKNOWN_MESSAGE = '受理状态尚未确认，请核对后再重试。'

interface PendingMessage {
  message: ChatMessage
  wireContent: string
  onAccepted: () => void
  version: number
  identity: string
  timer?: ReturnType<typeof setTimeout>
}

interface DeliveryContext {
  sessionId: Ref<string>
  ticketId: Ref<string | null>
  messages: Ref<ChatMessage[]>
  userId: () => string | null
  identity: () => string
}

/** 管理输入受理；网络写出、服务端落库、AI 回复是三个独立阶段。 */
export function useMessageDelivery(context: DeliveryContext) {
  const pending = new Map<string, PendingMessage>()
  let disposed = false

  function clearTimer(entry: PendingMessage) {
    if (entry.timer !== undefined) clearTimeout(entry.timer)
    entry.timer = undefined
  }

  function belongsHere(entry: PendingMessage) {
    return !disposed && entry.identity === context.identity()
      && entry.message.sessionId === context.sessionId.value && entry.message.senderId === context.userId()
  }

  function accept(raw: unknown): boolean {
    if (!raw || typeof raw !== 'object') return false
    const receipt = raw as WsChatAccepted
    if (typeof receipt.clientMsgId !== 'string' || typeof receipt.messageId !== 'string'
      || !Number.isFinite(receipt.id) || receipt.id <= 0) return false
    const entry = pending.get(receipt.clientMsgId)
    if (!entry || !belongsHere(entry) || receipt.sessionId !== entry.message.sessionId) return false
    clearTimer(entry)
    entry.version++
    Object.assign(entry.message, {
      id: receipt.id, messageId: receipt.messageId, ticketId: receipt.ticketId,
      createdAtMs: receipt.ts, deliveryStatus: 'ACCEPTED', deliveryError: undefined,
    })
    // 历史请求可能比回执先回来；保留原气泡并移除同一持久化消息的第二份投影。
    context.messages.value = context.messages.value.filter(message => message === entry.message || message.messageId !== receipt.messageId)
    pending.delete(receipt.clientMsgId)
    entry.onAccepted()
    return true
  }

  async function reconcileOne(clientMsgId: string): Promise<'accepted' | 'missing' | 'unknown' | 'conflict'> {
    const entry = pending.get(clientMsgId)
    if (!entry || !belongsHere(entry)) return 'unknown'
    const version = ++entry.version
    clearTimer(entry)
    try {
      const receipt = await fetchMessageReceipt(entry.message.sessionId, clientMsgId)
      if (!belongsHere(entry) || pending.get(clientMsgId) !== entry || version !== entry.version) return 'unknown'
      const saved = receipt.message
      if (saved) {
        if (saved.sessionId !== entry.message.sessionId || saved.senderType !== 'USER'
          || saved.senderId !== entry.message.senderId || saved.content !== entry.wireContent) {
          entry.message.deliveryStatus = 'REJECTED'
          entry.message.deliveryError = '原标识对应的消息内容不一致，请核对原消息。'
          return 'conflict'
        }
        return accept({ clientMsgId, id: saved.id, messageId: saved.messageId, sessionId: saved.sessionId, ticketId: saved.ticketId, ts: saved.createdAtMs })
          ? 'accepted' : 'unknown'
      }
      entry.message.deliveryStatus = 'REJECTED'
      entry.message.deliveryError = '尚未受理，原消息可以重试。'
      return 'missing'
    } catch {
      if (belongsHere(entry) && pending.get(clientMsgId) === entry && version === entry.version) {
        entry.message.deliveryStatus = 'UNKNOWN'
        entry.message.deliveryError = UNKNOWN_MESSAGE
      }
      return 'unknown'
    }
  }

  function transmit(clientMsgId: string, entry: PendingMessage) {
    clearTimer(entry)
    entry.version++
    entry.message.deliveryStatus = 'SENDING'
    entry.message.deliveryError = undefined
    const sent = chatSocket.send({ type: 'chat', data: {
      sessionId: entry.message.sessionId, content: entry.wireContent, clientMsgId,
    } })
    if (sent) entry.timer = setTimeout(() => { void reconcileOne(clientMsgId) }, RECEIPT_TIMEOUT_MS)
    else {
      entry.message.deliveryStatus = 'UNKNOWN'
      entry.message.deliveryError = '连接尚未恢复，原消息已保留。'
    }
    return sent
  }

  function submit(wireContent: string, displayContent: string, onAccepted: () => void): string | null {
    const clientMsgId = typeof crypto !== 'undefined' && 'randomUUID' in crypto
      ? crypto.randomUUID() : `${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
    const message = reactive<ChatMessage>({
      id: 0, messageId: `local-${clientMsgId}`, clientMsgId, sessionId: context.sessionId.value,
      ticketId: context.ticketId.value ?? '', senderType: 'USER', senderId: context.userId(),
      content: displayContent, createdAtMs: Date.now(), deliveryStatus: 'SENDING',
    })
    const entry: PendingMessage = { message, wireContent, onAccepted, version: 0, identity: context.identity() }
    pending.set(clientMsgId, entry)
    context.messages.value.push(message)
    if (transmit(clientMsgId, entry)) return clientMsgId
    pending.delete(clientMsgId)
    context.messages.value = context.messages.value.filter(item => item !== message)
    showToast('连接尚未就绪，草稿已保留')
    return null
  }

  async function retry(message: ChatMessage) {
    const id = message.clientMsgId
    if (!id || !pending.has(id)) return
    // 已保存的请求只补回执；只有明确查不到时，才按用户本次点击沿用原标识发送。
    if (await reconcileOne(id) !== 'missing') return
    const entry = pending.get(id)
    if (entry && belongsHere(entry)) transmit(id, entry)
  }

  function handleError(error: WsErrorMessage): boolean {
    const entry = error.clientMsgId ? pending.get(error.clientMsgId) : undefined
    if (!entry || !belongsHere(entry) || error.sessionId !== entry.message.sessionId) return false
    clearTimer(entry)
    entry.version++
    entry.message.deliveryStatus = error.acceptance === 'REJECTED' ? 'REJECTED' : 'UNKNOWN'
    entry.message.deliveryError = error.message || UNKNOWN_MESSAGE
    return true
  }

  function disconnected() {
    for (const entry of pending.values()) {
      if (!belongsHere(entry) || entry.message.deliveryStatus !== 'SENDING') continue
      clearTimer(entry)
      entry.version++
      entry.message.deliveryStatus = 'UNKNOWN'
      entry.message.deliveryError = UNKNOWN_MESSAGE
    }
  }

  async function reconcile() {
    for (const id of [...pending.keys()]) await reconcileOne(id)
  }

  function isPending(wireContent: string): boolean {
    return context.messages.value.some(message => message.clientMsgId && message.deliveryStatus !== 'ACCEPTED'
      && pending.get(message.clientMsgId)?.wireContent === wireContent)
  }

  function mergeHistory(list: ChatMessage[]): ChatMessage[] {
    const current = new Map(context.messages.value.map(message => [message.messageId, message]))
    const combined = new Map<string, ChatMessage>(current)
    for (const message of list) {
      const previous = current.get(message.messageId)
      combined.set(message.messageId, { ...message, clientMsgId: previous?.clientMsgId, deliveryStatus: previous?.deliveryStatus })
    }
    return [...combined.values()].sort((left, right) => left.createdAtMs - right.createdAtMs || left.id - right.id)
  }

  function clear() {
    for (const entry of pending.values()) clearTimer(entry)
    pending.clear()
  }
  watch(context.sessionId, clear)
  onScopeDispose(() => { disposed = true; clear() })
  return { submit, accept, retry, handleError, disconnected, reconcile, isPending, mergeHistory }
}
