import { SseHttpError } from '@/utils/sseHttpError'
export { SseHttpError } from '@/utils/sseHttpError'
import { useAuthStore } from '@/store/auth'
import { parseSseBlock, type SseEvent } from '@/utils/sseParser'

export type { SseEvent }

export interface SseHandlers {
  onEvent: (event: SseEvent) => void
  onError?: (error: unknown) => void
  onComplete?: () => void
}

/** POST 流式请求需要携带登录头；返回取消函数供聊天和编码工作区使用。 */
export function streamSse(path: string, body: unknown, handlers: SseHandlers): () => void {
  const controller = new AbortController()
  const auth = useAuthStore()
  const baseUrl = import.meta.env.VITE_API_BASE_URL as string

  fetch(`${baseUrl}${path}`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
      Authorization: auth.token ?? '',
    },
    body: JSON.stringify(body),
    signal: controller.signal,
  })
    .then(async (response) => {
      const contentType = response.headers.get('content-type')?.split(';')[0]?.trim().toLowerCase()
      if (!response.ok || !response.body || contentType !== 'text/event-stream') {
        // 业务异常可能返回 HTTP 200 + Result JSON，必须先校验协议，不能将空流当作成功。
        const body = await response
          .json()
          .then((parsed: { code?: number; message?: string }) => parsed)
          .catch(() => null)
        throw new SseHttpError(
          body?.message || `SSE 请求失败: HTTP ${response.status}`,
          response.status,
          body?.code,
        )
      }
      const reader = response.body.getReader()
      const decoder = new TextDecoder('utf-8')
      let buffer = ''

      try {
        for (;;) {
          const { value, done } = await reader.read()
          if (done) {
            break
          }
          buffer += decoder.decode(value, { stream: true })

          // 分隔符可能跨网络块到达；只切事件边界，不改动正文中的空格和换行。
          let separator: RegExpExecArray | null
          while ((separator = /\r?\n\r?\n/.exec(buffer)) !== null) {
            const rawEvent = buffer.slice(0, separator.index)
            buffer = buffer.slice(separator.index + separator[0].length)
            const parsed = parseSseBlock(rawEvent)
            if (parsed) {
              handlers.onEvent(parsed)
            }
          }
        }
      } finally {
        reader.releaseLock()
      }
      handlers.onComplete?.()
    })
    .catch((error) => {
      if (controller.signal.aborted) {
        return
      }
      handlers.onError?.(error)
    })

  return () => controller.abort()
}
