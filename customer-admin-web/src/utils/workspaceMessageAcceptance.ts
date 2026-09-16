import type { ChatRequest, ChatMessageVO } from '@/types/api'
import type { ChatAttachmentItem, ChatMessage } from '@/store/chatConversations'
import { createChatCompletion } from './chatCompletion'
import { generateUuid } from './uuid'

export interface WorkspaceReceipt {
  clientMessageId: string
  acceptedAtMs: number
  terminal?: Record<string, unknown> | null
}

export interface PendingWorkspaceMessage {
  request: ChatRequest & { clientMessageId: string }
  input: string
  attachmentLocalIds: string[]
  assistantIndex: number
  checking: boolean
  sawExecution: boolean
  error?: string
  retry: () => void
}

interface Conversation {
  input: string
  attachments: ChatAttachmentItem[]
  messages: ChatMessage[]
  pendingMessage: PendingWorkspaceMessage | null
  streaming: boolean
  interrupting: boolean
  interrupted: boolean
}

/** 快照在首次发送时创建；核对、重试沿用同一份请求，不吸收后来编辑的内容或附件。 */
export function prepareWorkspaceMessage(conversation: Conversation, request: ChatRequest): PendingWorkspaceMessage {
  return {
    request: { ...request, clientMessageId: generateUuid(), attachmentIds: request.attachmentIds?.slice() },
    input: conversation.input,
    attachmentLocalIds: conversation.attachments.filter(file => file.status === 'success').map(file => file.localId),
    assistantIndex: conversation.messages.length + 1,
    checking: false,
    sawExecution: false,
    retry: () => {},
  }
}

/** 只清理被本次受理确认的原草稿；下一条输入和新上传附件继续留在编辑器。 */
export function confirmWorkspaceMessage(conversation: Conversation, pending: PendingWorkspaceMessage, raw: unknown) {
  let receipt: WorkspaceReceipt
  try { receipt = (typeof raw === 'string' ? JSON.parse(raw) : raw) as WorkspaceReceipt }
  catch { return false }
  if (!receipt || receipt.clientMessageId !== pending.request.clientMessageId
      || !Number.isFinite(receipt.acceptedAtMs) || receipt.acceptedAtMs <= 0) return false
  if (conversation.pendingMessage !== pending) return false
  if (conversation.input === pending.input) conversation.input = ''
  conversation.attachments = conversation.attachments.filter(file => !pending.attachmentLocalIds.includes(file.localId))
  conversation.pendingMessage = null
  return true
}

/** 明确未找到且从未收到执行片段时才重试同一标识；已受理和结果未知都不会重新执行模型。 */
export async function reconcileWorkspaceMessage(
  conversation: Conversation,
  readReceipt: (clientMessageId: string) => Promise<WorkspaceReceipt>,
  isCurrent: () => boolean,
  readHistory: () => Promise<ChatMessageVO[]>,
) {
  const pending = conversation.pendingMessage
  if (!pending || pending.checking || conversation.streaming) return
  pending.checking = true
  pending.error = undefined
  try {
    const receipt = await readReceipt(pending.request.clientMessageId)
    if (!isCurrent() || conversation.pendingMessage !== pending) return
    if (!confirmWorkspaceMessage(conversation, pending, receipt)) {
      pending.error = '回执与原消息不匹配，请保留输入并稍后核对。'
      return
    }
    const message = conversation.messages[pending.assistantIndex]
    if (message) {
      const completion = createChatCompletion(message, conversation)
      if (receipt.terminal) completion.terminal(JSON.stringify(receipt.terminal))
      completion.complete()
      if (message.historySaved && message.messageId) {
        try {
          const history = await readHistory()
          if (!isCurrent()) return
          const saved = history.find(item => item.id === message.messageId && item.role === 'assistant')
          if (saved) message.text = saved.text
          else message.error = '受理已确认，本轮正文尚未加载。请查看会话历史。'
        } catch {
          if (isCurrent()) message.error = '受理已确认，历史正文暂时无法读取。请稍后查看会话历史。'
        }
      }
    }
  } catch (error) {
    if (!isCurrent() || conversation.pendingMessage !== pending) return
    if (typeof error === 'object' && error && 'code' in error && error.code === 30003) {
      if (pending.sawExecution) {
        pending.error = '已收到执行内容，但受理记录缺失。请核对会话历史，系统不会自动重试。'
      } else {
        pending.checking = false
        pending.retry()
      }
    } else {
      pending.error = '暂时无法核对受理状态。原文和附件仍保留，请稍后再试。'
    }
  } finally {
    pending.checking = false
  }
}
