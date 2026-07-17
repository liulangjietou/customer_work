// 用户端 WebSocket 封装：ws://host/ws/user?token=<JWT>，开发期经 vite proxy 转发到 8080。
// 职责：连接管理、断线指数退避重连、30s 心跳、按 type 分发消息给业务侧监听器。
// 解析宽容：未知 type 直接忽略，不抛异常，避免后端新增帧类型时前端连接被打断。

const HEARTBEAT_INTERVAL_MS = 30000
const RECONNECT_BASE_DELAY_MS = 1000
const RECONNECT_MAX_DELAY_MS = 30000

/** 内部生命周期事件，与后端业务 type 共用同一套 on() 订阅机制，方便页面统一感知连接状态。 */
export type WsLifecycleEvent = 'open' | 'close' | 'reconnecting'

type Handler = (data: unknown) => void

class ChatSocket {
  private ws: WebSocket | null = null
  private url = ''
  private handlers = new Map<string, Set<Handler>>()
  private heartbeatTimer: ReturnType<typeof setInterval> | null = null
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null
  private reconnectAttempts = 0
  private manuallyClosed = true

  connect(token: string) {
    this.manuallyClosed = false
    const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:'
    this.url = `${protocol}//${location.host}/ws/user?token=${encodeURIComponent(token)}`
    this.open()
  }

  on(type: string, handler: Handler) {
    if (!this.handlers.has(type)) {
      this.handlers.set(type, new Set())
    }
    this.handlers.get(type)!.add(handler)
  }

  off(type: string, handler: Handler) {
    this.handlers.get(type)?.delete(handler)
  }

  send(frame: { type: string; data?: unknown }) {
    if (this.ws?.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify(frame))
    }
  }

  close() {
    this.manuallyClosed = true
    this.clearHeartbeat()
    this.clearReconnectTimer()
    this.ws?.close()
    this.ws = null
  }

  private open() {
    this.ws = new WebSocket(this.url)

    this.ws.onopen = () => {
      this.reconnectAttempts = 0
      this.startHeartbeat()
      this.emit('open', null)
    }

    this.ws.onmessage = (evt: MessageEvent<string>) => {
      let frame: { type?: string; data?: unknown }
      try {
        frame = JSON.parse(evt.data)
      } catch {
        return
      }
      if (!frame.type) {
        return
      }
      this.emit(frame.type, frame.data)
    }

    this.ws.onclose = () => {
      this.clearHeartbeat()
      this.emit('close', null)
      this.scheduleReconnect()
    }

    this.ws.onerror = () => {
      this.ws?.close()
    }
  }

  private scheduleReconnect() {
    if (this.manuallyClosed) {
      return
    }
    const delay = Math.min(RECONNECT_MAX_DELAY_MS, RECONNECT_BASE_DELAY_MS * 2 ** this.reconnectAttempts)
    this.reconnectAttempts += 1
    this.emit('reconnecting', null)
    this.reconnectTimer = setTimeout(() => this.open(), delay)
  }

  private startHeartbeat() {
    this.clearHeartbeat()
    this.heartbeatTimer = setInterval(() => this.send({ type: 'ping' }), HEARTBEAT_INTERVAL_MS)
  }

  private clearHeartbeat() {
    if (this.heartbeatTimer) {
      clearInterval(this.heartbeatTimer)
      this.heartbeatTimer = null
    }
  }

  private clearReconnectTimer() {
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer)
      this.reconnectTimer = null
    }
  }

  private emit(type: string, data: unknown) {
    this.handlers.get(type)?.forEach((handler) => handler(data))
  }
}

/** 单例：整个应用共用一条用户 WS 连接，Chat.vue 进入时 connect，离开时 close。 */
export const chatSocket = new ChatSocket()
