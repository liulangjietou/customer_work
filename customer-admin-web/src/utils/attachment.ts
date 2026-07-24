import type { ChatMessageAttachment } from '@/types/api'

/**
 * 对话附件预览共用的纯函数：判定是否图片、格式化文件大小、批量释放本地 objectURL。
 * Chat/VibeCoding 两个面板与其共用组件都依赖这里，避免各处判断逻辑漂移。
 */

/**
 * 消息气泡里展示用的附件视图模型：在后端 ChatMessageAttachment 基础上加 previewUrl——
 * 仅刚发送成功的图片消息才有（复用待发送区已创建的本地 objectURL，省一次后端请求）；
 * 历史消息该字段恒为空，走 MessageAttachments 组件里按需的后端 blob 懒加载。
 */
export interface MessageAttachmentVM extends ChatMessageAttachment {
  previewUrl?: string
}

/** 判定图片：mimeType 以 image/ 开头（含 svg），展示走 blob objectURL 是安全的。 */
export function isImageMimeType(mimeType?: string | null): boolean {
  return !!mimeType && mimeType.startsWith('image/')
}

/** 文件大小人性化展示（B/KB/MB），非法值兜底显示空串。 */
export function formatFileSize(bytes?: number | null): string {
  if (bytes == null || Number.isNaN(bytes) || bytes < 0) {
    return ''
  }
  if (bytes < 1024) {
    return `${bytes}B`
  }
  if (bytes < 1024 * 1024) {
    return `${(bytes / 1024).toFixed(1)}KB`
  }
  return `${(bytes / 1024 / 1024).toFixed(1)}MB`
}

/** 批量释放待发送区图片附件的本地 objectURL（移除/放弃时调用），已发送的附件所有权转移给消息对象，不在此列。 */
export function revokeAttachmentPreviews(items: Array<{ previewUrl?: string }>): void {
  for (const item of items) {
    if (item.previewUrl) {
      URL.revokeObjectURL(item.previewUrl)
    }
  }
}
