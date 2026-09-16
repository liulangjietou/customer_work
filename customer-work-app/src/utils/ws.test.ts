// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

class FakeSocket {
  static readonly OPEN = 1
  static instances: FakeSocket[] = []
  readyState = 0
  onopen?: () => void
  onclose?: () => void
  onerror?: () => void
  onmessage?: (event: { data: string }) => void
  send = vi.fn()
  readonly url: string
  constructor(url: string) { this.url = url; FakeSocket.instances.push(this) }
  close() { this.readyState = 3; this.onclose?.() }
}

describe('ChatSocket', () => {
  beforeEach(() => {
    vi.resetModules()
    vi.useFakeTimers()
    FakeSocket.instances = []
    vi.stubGlobal('WebSocket', FakeSocket)
  })
  afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals() })

  it('只在实际交给打开的连接后返回成功，写出异常返回失败', async () => {
    const { chatSocket } = await import('./ws')
    chatSocket.connect('token-1')
    const socket = FakeSocket.instances[0]!
    expect(chatSocket.send({ type: 'chat', data: 'draft' })).toBe(false)
    socket.readyState = FakeSocket.OPEN
    socket.onopen?.()
    expect(chatSocket.send({ type: 'chat', data: 'draft' })).toBe(true)
    socket.send.mockImplementationOnce(() => { throw new Error('connection closed') })
    expect(chatSocket.send({ type: 'chat', data: 'draft' })).toBe(false)
    chatSocket.close()
  })

  it('旧连接的延迟事件不能关闭新连接或重复重连', async () => {
    const { chatSocket } = await import('./ws')
    const events: string[] = []
    chatSocket.on('open', () => events.push('open'))
    chatSocket.on('reconnecting', () => events.push('reconnecting'))
    chatSocket.on('chat', () => events.push('chat'))
    chatSocket.connect('token-1')
    const old = FakeSocket.instances[0]!
    chatSocket.connect('token-2')
    const current = FakeSocket.instances[1]!
    current.readyState = FakeSocket.OPEN
    current.onopen?.()
    old.onopen?.()
    old.onmessage?.({ data: '{"type":"chat","data":{}}' })
    old.onclose?.()
    await vi.advanceTimersByTimeAsync(2_000)
    expect(events).toEqual(['open'])
    expect(FakeSocket.instances).toHaveLength(2)
    chatSocket.close()
  })
})
