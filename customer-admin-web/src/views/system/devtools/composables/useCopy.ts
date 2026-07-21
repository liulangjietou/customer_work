/**
 * 复制文本到剪贴板，统一走这一处（fast fail 原则里"防御式编程只留一处"的体现）。
 *
 * `navigator.clipboard` 要求安全上下文（HTTPS 或 localhost），局域网 IP + 纯 HTTP 访问时该 API
 * 不存在（与 utils/uuid.ts 里 crypto.randomUUID 踩过的坑同源）；退化到 execCommand('copy') 兜底，
 * 两者都不可用才提示用户手动复制。
 */
export async function copyText(text: string, label = '内容'): Promise<void> {
  if (!text) {
    ElMessage.info('没有可复制的内容')
    return
  }
  if (navigator.clipboard && window.isSecureContext) {
    try {
      await navigator.clipboard.writeText(text)
      ElMessage.success(`${label}已复制`)
      return
    } catch {
      // 落到下面的兼容兜底方案
    }
  }
  try {
    const textarea = document.createElement('textarea')
    textarea.value = text
    textarea.style.position = 'fixed'
    textarea.style.opacity = '0'
    document.body.appendChild(textarea)
    textarea.focus()
    textarea.select()
    const ok = document.execCommand('copy')
    document.body.removeChild(textarea)
    if (ok) {
      ElMessage.success(`${label}已复制`)
    } else {
      ElMessage.error('复制失败，请手动选择文本复制')
    }
  } catch {
    ElMessage.error('复制失败，请手动选择文本复制')
  }
}
