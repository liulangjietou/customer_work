import { beforeEach, describe, expect, it, vi } from 'vitest'
import * as api from './customerAnswerSources'

const { request } = vi.hoisted(() => ({ request: vi.fn() }))
vi.mock('@/api/request', () => ({ request }))

describe('customerAnswerSources API', () => {
  beforeEach(() => {
    request.mockReset()
  })

  it('列表使用编码后的会话与消息路径，返回平铺结果且要求不缓存', async () => {
    expect(api.fetchCustomerAnswerSources).toBeTypeOf('function')
    request.mockResolvedValue([])
    await expect(api.fetchCustomerAnswerSources('session:/one', 'MSG?two')).resolves.toEqual([])
    expect(request).toHaveBeenCalledWith({
      url: '/customer/user/sessions/session%3A%2Fone/messages/MSG%3Ftwo/sources',
      method: 'get',
      headers: { 'Cache-Control': 'no-store' },
      silentError: true,
    })
  })

  it('第 0 条来源保留真实索引，503 向面板抛出而不是降级为空原文', async () => {
    expect(api.fetchCustomerAnswerSourcePreview).toBeTypeOf('function')
    const error = { response: { status: 503 } }
    request.mockRejectedValue(error)
    await expect(api.fetchCustomerAnswerSourcePreview('session-1', 'message-1', 0)).rejects.toBe(
      error,
    )
    expect(request).toHaveBeenCalledWith({
      url: '/customer/user/sessions/session-1/messages/message-1/sources/0',
      method: 'get',
      headers: { 'Cache-Control': 'no-store' },
      silentError: true,
    })
  })
})
