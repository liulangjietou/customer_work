import { useAuthStore } from '@/store/auth'

export interface SseEvent {
  event: string
  data: string
}

export interface SseHandlers {
  onEvent: (event: SseEvent) => void
  onError?: (error: unknown) => void
  onComplete?: () => void
}

/**
 * 用 fetch + ReadableStream 手动解析 SSE，而不是浏览器原生 EventSource——后端聊天/VibeCoding
 * 端点是 POST + 需要携带 Authorization 头传 token，原生 EventSource 只支持 GET 且不能自定义头。
 * 返回一个 abort() 函数，供调用方中途取消。
 */
/**
 * 带业务码的流式请求错误。
 *
 * <p>调用方据此区分"这一轮为什么失败"：额度用尽（{@link #QUOTA_EXCEEDED}）该作为一条消息
 * 留在对话流里，让用户看到它紧跟在自己那句话后面；而网络中断之类的才适合一闪而过的顶部提示。</p>
 */
export class SseHttpError extends Error {
  /** 额度用尽（后端 ResultCode.QUOTA_EXCEEDED）。 */
  static readonly QUOTA_EXCEEDED = 40043

  readonly status: number
  readonly code?: number

  constructor(message: string, status: number, code?: number) {
    super(message)
    this.name = 'SseHttpError'
    this.status = status
    this.code = code
  }

  get quotaExceeded(): boolean {
    return this.code === SseHttpError.QUOTA_EXCEEDED
  }
}

export function streamSse(path: string, body: unknown, handlers: SseHandlers): () => void {
  const controller = new AbortController()
  const auth = useAuthStore()
  const baseUrl = import.meta.env.VITE_API_BASE_URL as string

  fetch(`${baseUrl}${path}`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: auth.token ?? '',
    },
    body: JSON.stringify(body),
    signal: controller.signal,
  })
    .then(async (response) => {
      if (!response.ok || !response.body) {
        // 非 2xx 的响应体同样是 Result 包装（如额度用尽返回 429 + code 40043），先把后端文案取出来：
        // 只抛 HTTP 状态码的话，对话框里显示的是"SSE 请求失败: HTTP 429"，
        // 而真正该让用户看到的"额度已用完，请稍后再试"就丢了
        const body = await response.json()
          .then((parsed: { code?: number; message?: string }) => parsed)
          .catch(() => null)
        throw new SseHttpError(body?.message || `SSE 请求失败: HTTP ${response.status}`,
          response.status, body?.code)
      }
      const reader = response.body.getReader()
      const decoder = new TextDecoder('utf-8')
      let buffer = ''

      for (;;) {
        const { value, done } = await reader.read()
        if (done) {
          break
        }
        buffer += decoder.decode(value, { stream: true })

        let separatorIndex: number
        while ((separatorIndex = buffer.indexOf('\n\n')) !== -1) {
          const rawEvent = buffer.slice(0, separatorIndex)
          buffer = buffer.slice(separatorIndex + 2)
          const parsed = parseSseBlock(rawEvent)
          if (parsed) {
            handlers.onEvent(parsed)
          }
        }
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

function parseSseBlock(block: string): SseEvent | null {
  let event = 'message'
  const dataLines: string[] = []
  for (const line of block.split('\n')) {
    if (line.startsWith('event:')) {
      event = line.slice('event:'.length).trim()
    } else if (line.startsWith('data:')) {
      dataLines.push(line.slice('data:'.length).trim())
    }
  }
  if (dataLines.length === 0) {
    return null
  }
  return { event, data: dataLines.join('\n') }
}
