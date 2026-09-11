import { afterEach, describe, expect, it, vi } from 'vitest'
import { SseHttpError, streamSse, type SseEvent } from './sse'

vi.mock('@/store/auth', () => ({ useAuthStore: () => ({ token: 'transport-test' }) }))

afterEach(() => vi.unstubAllGlobals())

function responseOf(chunks: string[]) {
  const encoder = new TextEncoder()
  return new Response(
    new ReadableStream({
      start(controller) {
        for (const chunk of chunks) controller.enqueue(encoder.encode(chunk))
        controller.close()
      },
    }),
    { headers: { 'Content-Type': 'text/event-stream; charset=utf-8' } },
  )
}

describe('SSE 传输边界', () => {
  it('跨网络块的 CRLF 事件分隔符不会吞掉全部回答，正文空格原样保留', async () => {
    const response = responseOf([
      'event: message\r',
      '\ndata:  第一段 \r\n\r',
      '\nevent: message\ndata: 第二段\n\n',
    ])
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response))
    const events: SseEvent[] = []
    const complete = vi.fn()
    streamSse('/test/stream', {}, { onEvent: (event) => events.push(event), onComplete: complete })
    await vi.waitFor(() => expect(complete).toHaveBeenCalledOnce())
    expect(events).toEqual([
      { event: 'message', data: ' 第一段 ' },
      { event: 'message', data: '第二段' },
    ])
    expect(response.body!.locked).toBe(false)
  })

  it('HTTP 200 的业务错误 JSON 走失败回调，不能被当成空回答完成', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValue(
          new Response(JSON.stringify({ code: 40043, message: '额度已用完，请稍后再试' }), {
            headers: { 'Content-Type': 'application/json' },
          }),
        ),
    )
    const error = vi.fn()
    const complete = vi.fn()
    const event = vi.fn()
    streamSse('/test/stream', {}, { onEvent: event, onError: error, onComplete: complete })
    await vi.waitFor(() => expect(error).toHaveBeenCalledOnce())
    expect(error.mock.calls[0][0]).toBeInstanceOf(SseHttpError)
    expect(error.mock.calls[0][0]).toMatchObject({ code: 40043, message: '额度已用完，请稍后再试' })
    expect(complete).not.toHaveBeenCalled()
    expect(event).not.toHaveBeenCalled()
  })
})
