import { describe, expect, it, vi } from 'vitest'
import { fetchRefundApprovals } from './businessProgress'

const { request } = vi.hoisted(() => ({ request: vi.fn() }))
vi.mock('@/api/request', () => ({ request }))

describe('businessProgress API', () => {
  it('使用授权会话的编码路径和服务端页码，保持平铺结构与禁止缓存', async () => {
    const page = { total: 3, items: [] }
    request.mockResolvedValueOnce(page)
    await expect(fetchRefundApprovals('uU1:conv/one', 2, 10)).resolves.toEqual(page)
    expect(request).toHaveBeenCalledExactlyOnceWith({
      url: '/customer/user/sessions/uU1%3Aconv%2Fone/refund-approvals',
      method: 'get', params: { page: 2, size: 10 },
      headers: { 'Cache-Control': 'no-store' }, silentError: true,
    })
  })

  it('读取失败继续抛出，不把办理状态未知改为空记录', async () => {
    const error = { response: { status: 503 } }
    request.mockRejectedValueOnce(error)
    await expect(fetchRefundApprovals('session')).rejects.toBe(error)
  })
})
