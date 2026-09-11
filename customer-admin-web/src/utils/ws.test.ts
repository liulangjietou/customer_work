import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { WsClient } from './ws'

class Socket {
  static OPEN = 1
  static all: Socket[] = []
  readyState = 0
  onopen: (() => void) | null = null
  onclose: (() => void) | null = null
  onerror: (() => void) | null = null
  onmessage: ((event: { data: string }) => void) | null = null
  send = vi.fn()
  close = vi.fn(() => {
    this.readyState = 3
  })
  url: string
  constructor(url: string) {
    this.url = url
    Socket.all.push(this)
  }
  open() {
    this.readyState = 1
    this.onopen?.()
  }
}

describe('坐席 WebSocket 连接归属', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    Socket.all = []
    vi.stubGlobal('WebSocket', Socket)
    vi.stubGlobal('window', globalThis)
  })
  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  it('旧凭证连接的迟到错误不能关闭新连接或恢复旧帧', () => {
    const client = new WsClient()
    const onChat = vi.fn()
    client.on('chat', onChat)
    client.connect('ws://local/old')
    const first = Socket.all[0]!
    client.connect('ws://local/new')
    const current = Socket.all[1]!
    current.open()
    first.onmessage?.({ data: JSON.stringify({ type: 'chat', data: { content: '旧租户' } }) })
    first.onerror?.()
    expect(onChat).not.toHaveBeenCalled()
    expect(current.close).not.toHaveBeenCalled()
    expect(first.close).toHaveBeenCalledTimes(1)
    client.close()
    vi.advanceTimersByTime(60000)
    expect(Socket.all).toHaveLength(2)
  })

  it('每次连接成功都通知业务层补拉，手动关闭后不再重连', () => {
    const client = new WsClient()
    const opened = vi.fn()
    client.on('open', opened)
    client.connect('ws://local/agent')
    Socket.all[0]!.open()
    Socket.all[0]!.onclose?.()
    vi.advanceTimersByTime(1000)
    Socket.all[1]!.open()
    expect(opened).toHaveBeenCalledTimes(2)
    client.close()
    Socket.all[1]!.onclose?.()
    vi.advanceTimersByTime(60000)
    expect(Socket.all).toHaveLength(2)
  })
})
