import { beforeEach, describe, expect, it, vi } from 'vitest'
import { streamVibeCoding } from './vibecoding'

const transport = vi.hoisted(() => ({ stream: vi.fn() }))
vi.mock('@/utils/sse', () => ({ streamSse: transport.stream }))
vi.mock('./request', () => ({ request: vi.fn(), LLM_TIMEOUT_MS: 60_000 }))

beforeEach(() => vi.clearAllMocks())

describe('编码工作区请求契约', () => {
  it.each(['请总结附件', ''])('将原文 %j 与模型材料分别发给后端', (rawInput) => {
    const handlers = { onEvent: vi.fn() }
    streamVibeCoding(
      'test-agent',
      {
        sessionId: 'session-1',
        message: '【附件材料】完整报告及运行指引',
        rawInput,
        collaboration: true,
        mode: 'plan',
        attachmentIds: ['attachment-1'],
      },
      handlers,
    )
    expect(transport.stream).toHaveBeenCalledWith(
      '/workspace/test-agent/vibecoding/stream',
      {
        sessionId: 'session-1',
        message: '【附件材料】完整报告及运行指引',
        rawInput,
        collaboration: true,
        mode: 'plan',
        attachmentIds: ['attachment-1'],
      },
      handlers,
    )
  })
})
