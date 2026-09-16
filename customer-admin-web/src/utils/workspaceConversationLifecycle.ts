import { revokeAttachmentPreviews } from './attachment'

interface DisposableConversation {
  abort: (() => void) | null
  commandAbort?: (() => void) | null
  attachments: Array<{ previewUrl?: string }>
  messages: Array<{ attachments?: Array<{ previewUrl?: string }> }>
}

/** 认证生命周期结束时释放连接和本地预览；正常切菜单、切智能体不调用。 */
export function disposeWorkspaceConversations(conversations: DisposableConversation[]) {
  for (const conversation of conversations) {
    conversation.abort?.()
    conversation.commandAbort?.()
    revokeAttachmentPreviews(conversation.attachments)
    for (const message of conversation.messages) revokeAttachmentPreviews(message.attachments ?? [])
  }
}
