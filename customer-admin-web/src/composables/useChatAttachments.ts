import { ElMessage } from 'element-plus'
import type { UploadRequestOptions } from 'element-plus'
import { parseChatAttachment } from '@/api/chat'
import { generateUuid } from '@/utils/uuid'
import { isImageMimeType, revokeAttachmentPreviews } from '@/utils/attachment'

// 与 starter AttachmentParseService 的白名单/大小限制保持一致（后端 customer-work.attachment.max-file-size-mb=10）
export const ATTACHMENT_ACCEPT =
  '.md,.txt,.csv,.tsv,.json,.xml,.yaml,.yml,.toml,.proto,.properties,.ini,.conf,.cfg,.log,.env,.sql,.sh,.bash,.zsh,.bat,.ps1,.java,.kt,.kts,.groovy,.gradle,.scala,.py,.js,.ts,.jsx,.tsx,.vue,.css,.scss,.less,.c,.h,.cpp,.hpp,.cs,.go,.rs,.rb,.php,.swift,.lua,.r,.dart,.html,.pdf,.doc,.docx,.xls,.xlsx,.ppt,.pptx,.png,.jpg,.jpeg,.bmp,.webp'
const MAX_ATTACHMENT_SIZE_BYTES = 10 * 1024 * 1024
const ACCEPT_EXTENSIONS = new Set(ATTACHMENT_ACCEPT.split(','))

/** Chat/VibeCoding 两个 store 的附件项结构相同，组合式函数按结构类型约束，不依赖具体 store。 */
export interface AttachmentItem {
  localId: string
  id?: string
  name: string
  content: string
  status: 'uploading' | 'success' | 'failed'
  errorMessage?: string
  /** 文件 MIME 类型：选中/粘贴时就地取自 File.type，上传成功后用后端返回值校正。 */
  mimeType?: string
  fileSize?: number
  /** 图片附件的本地 objectURL（零后端请求的即时缩略图），非图片恒为 undefined。
   * 所有权随消息发送转移给消息对象；移除/放弃时由调用方负责 revoke，见 revokeAttachmentPreviews。 */
  previewUrl?: string
}

interface AttachmentConversation {
  sessionId: string
  attachments: AttachmentItem[]
}

/**
 * 附件上传组合式函数：把"回形针按钮选文件"和"输入框 ⌘/Ctrl+V 粘贴文件（截图/CSV 等）"
 * 收敛到同一条 parseChatAttachment 上传链路，Chat 与 VibeCoding 两个面板共用。
 *
 * getConversation 取"发起上传时刻"的会话：上传是异步的，期间用户可能切走会话，
 * 结果必须回填到原会话而不是当前激活会话（在 uploadFile 入口一次性捕获 conv 引用）。
 */
export function useChatAttachments(options: {
  agentCode: () => string
  channel: string
  getConversation: () => AttachmentConversation | undefined
}) {
  /** 前端先拦超限文件，与后端 max-file-size-mb 对齐，减少无谓上传请求。 */
  function beforeAttachmentUpload(file: File) {
    if (file.size > MAX_ATTACHMENT_SIZE_BYTES) {
      ElMessage.error(`附件 ${file.name} 超过 10MB，已跳过上传`)
      return false
    }
    return true
  }

  /** 粘贴路径没有 el-upload 的 accept 过滤，按同一份白名单校验扩展名。 */
  function isAcceptedFile(file: File): boolean {
    const dot = file.name.lastIndexOf('.')
    if (dot < 0) {
      return false
    }
    return ACCEPT_EXTENSIONS.has(file.name.slice(dot).toLowerCase())
  }

  async function uploadFile(file: File) {
    const conv = options.getConversation()
    if (!conv) return
    // 图片附件立即用本地 File 生成 objectURL 做缩略图——零后端请求，不等上传结果；
    // 非图片该字段留空，待发送区走既有芯片样式。
    const previewUrl = isImageMimeType(file.type) ? URL.createObjectURL(file) : undefined
    const attachment: AttachmentItem = {
      localId: generateUuid(),
      name: file.name,
      content: '',
      status: 'uploading',
      mimeType: file.type,
      fileSize: file.size,
      previewUrl,
    }
    conv.attachments.push(attachment)
    try {
      const result = await parseChatAttachment(options.agentCode(), file, options.channel, conv.sessionId)
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
      // 以后端落库的 mimeType/fileSize 为准做一次校正（一般与本地 File 值一致，兜底服务端归一化差异）
      target.mimeType = result.mimeType || target.mimeType
      target.fileSize = result.fileSize ?? target.fileSize
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

  /** el-upload 的 http-request 回调（回形针按钮路径）。 */
  function handleAttachmentUpload(uploadOptions: UploadRequestOptions) {
    return uploadFile(uploadOptions.file as File)
  }

  /**
   * 输入框粘贴事件：剪贴板里带文件（截图、Finder 里复制的 CSV 等）就走附件上传；
   * 纯文本粘贴不拦截，保持原生输入行为。
   */
  function handleAttachmentPaste(event: ClipboardEvent) {
    const files = Array.from(event.clipboardData?.files ?? [])
    if (files.length === 0) {
      return
    }
    // 阻止浏览器把文件名等降级文本插进输入框
    event.preventDefault()
    for (const file of files) {
      if (!isAcceptedFile(file)) {
        ElMessage.error(`不支持的附件类型：${file.name}`)
        continue
      }
      if (!beforeAttachmentUpload(file)) {
        continue
      }
      uploadFile(file)
    }
  }

  /** 移除待发送区某个附件：图片附件的本地 objectURL 尚未转移给任何消息，立即 revoke 防泄漏。 */
  function removeAttachment(localId: string) {
    const conv = options.getConversation()
    if (!conv) return
    const target = conv.attachments.find((a) => a.localId === localId)
    if (target) {
      revokeAttachmentPreviews([target])
    }
    conv.attachments = conv.attachments.filter((a) => a.localId !== localId)
  }

  return {
    beforeAttachmentUpload,
    handleAttachmentUpload,
    handleAttachmentPaste,
    removeAttachment,
  }
}
