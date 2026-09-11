/** 服务端/客户端统一约定的 WS 帧格式：{type, data}，未知 type 一律静默忽略（向后兼容）。 */
export interface WsFrame {
  type: string
  data?: unknown
}

type WsFrameHandler = (data: unknown) => void

const HEARTBEAT_INTERVAL_MS = 30000
const RECONNECT_BASE_DELAY_MS = 1000
const RECONNECT_MAX_DELAY_MS = 30000

/**
 * 通用 WebSocket 客户端：断线自动重连（指数退避，上限 30s）+ 30s 心跳 + 按 type 分发订阅。
 * 风格对齐 utils/sse.ts（同样是"业务代码只管 on/send，连接细节全封装在这层"），但协议不同——
 * SSE 是单向流用 fetch 手写解析，这里是双向长连接用原生 WebSocket。
 *
 * 用法：new WsClient() 后 connect(url)，on('chat', handler) 订阅，close() 时标记手动关闭、
 * 不再触发重连（否则用户离开页面后仍会在后台无限重连）。
 */
export class WsClient {
  private url = ''
  private socket: WebSocket | null = null
  private readonly handlers = new Map<string, WsFrameHandler[]>()
  private reconnectAttempts = 0
  private reconnectTimer: number | null = null
  private heartbeatTimer: number | null = null
  private manualClosed = false

  connect(url: string): void {
    this.close()
    this.url = url
    this.manualClosed = false
    this.reconnectAttempts = 0
    this.open()
  }

  private open(): void {
    const socket = new WebSocket(this.url)
    this.socket = socket
    const current = () => this.socket === socket && !this.manualClosed
    socket.onopen = () => {
      if (!current()) return
      this.reconnectAttempts = 0
      this.startHeartbeat()
      this.emit('open', undefined)
    }
    socket.onmessage = (event: MessageEvent<string>) => {
      if (current()) this.dispatch(event.data)
    }
    socket.onclose = (event) => {
      if (!current()) return
      this.stopHeartbeat()
      this.emit('close', { code: event?.code })
      // 无效或已过期凭证需要业务层重新取凭证，禁止拿旧身份无限重连。
      if (event?.code === 1008) this.emit('unauthorized', undefined)
      else this.scheduleReconnect()
    }
    socket.onerror = () => {
      if (current()) socket.close()
    }
  }

  private emit(type: string, data: unknown): void {
    this.handlers
      .get(type)
      ?.slice()
      .forEach((handler) => handler(data))
  }

  private dispatch(raw: string): void {
    let frame: WsFrame
    try {
      frame = JSON.parse(raw) as WsFrame
    } catch {
      return
    }
    if (!frame || typeof frame.type !== 'string') {
      return
    }
    this.emit(frame.type, frame.data)
  }

  /** 订阅某个帧类型，返回取消订阅函数，组件卸载时调用避免重复注册。 */
  on(type: string, handler: WsFrameHandler): () => void {
    const list = this.handlers.get(type) ?? []
    list.push(handler)
    this.handlers.set(type, list)
    return () => {
      const current = this.handlers.get(type)
      if (!current) {
        return
      }
      this.handlers.set(
        type,
        current.filter((h) => h !== handler),
      )
    }
  }

  /** 返回值只证明浏览器写出，业务保存以 HTTP 回执和历史为准。 */
  send(type: string, data?: unknown): boolean {
    if (this.socket?.readyState !== WebSocket.OPEN) return false
    try {
      this.socket.send(JSON.stringify({ type, data }))
      return true
    } catch {
      return false
    }
  }

  /** 连接是否处于可发送状态，供业务代码判断"WS 不可用时降级走 HTTP 接口"。 */
  isOpen(): boolean {
    return this.socket?.readyState === WebSocket.OPEN
  }

  private startHeartbeat(): void {
    this.stopHeartbeat()
    this.heartbeatTimer = window.setInterval(() => {
      this.send('ping')
    }, HEARTBEAT_INTERVAL_MS)
  }

  private stopHeartbeat(): void {
    if (this.heartbeatTimer !== null) {
      window.clearInterval(this.heartbeatTimer)
      this.heartbeatTimer = null
    }
  }

  private scheduleReconnect(): void {
    if (this.reconnectTimer !== null) window.clearTimeout(this.reconnectTimer)
    this.emit('reconnecting', undefined)
    const delay = Math.min(
      RECONNECT_BASE_DELAY_MS * 2 ** this.reconnectAttempts,
      RECONNECT_MAX_DELAY_MS,
    )
    this.reconnectAttempts += 1
    this.reconnectTimer = window.setTimeout(() => {
      this.reconnectTimer = null
      if (!this.manualClosed) {
        this.open()
      }
    }, delay)
  }

  close(): void {
    this.manualClosed = true
    if (this.reconnectTimer !== null) {
      window.clearTimeout(this.reconnectTimer)
      this.reconnectTimer = null
    }
    this.stopHeartbeat()
    this.socket?.close()
    this.socket = null
  }
}
