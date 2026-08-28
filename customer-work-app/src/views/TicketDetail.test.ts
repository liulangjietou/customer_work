// @vitest-environment happy-dom
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { TicketDetail } from '@/types/api'
import TicketDetailView from './TicketDetail.vue'

const { fetchTicketDetailMock } = vi.hoisted(() => ({ fetchTicketDetailMock: vi.fn() }))

vi.mock('@/api/ticket', () => ({
  closeTicket: vi.fn(),
  confirmTicket: vi.fn(),
  fetchTicketDetail: fetchTicketDetailMock,
  reopenTicket: vi.fn(),
  rejectTicket: vi.fn(),
}))

vi.mock('vue-router', () => ({
  useRouter: () => ({ back: vi.fn(), push: vi.fn() }),
}))

vi.mock('vant', () => ({ showToast: vi.fn() }))

const detail: TicketDetail = {
  ticket: {
    id: 'TK-20260828-01',
    sessionId: 'session-1',
    userId: 'user-1',
    title: '物流状态查询',
    category: '订单物流',
    priority: 'NORMAL',
    status: 'PROCESSING',
    assignee: '客服 07',
    handoffReason: '需要核对物流节点',
    resolveNote: null,
    reopenCount: 0,
    createdAtMs: 1_777_520_000_000,
    updatedAtMs: 1_777_520_060_000,
  },
  events: [],
}

const globalOptions = {
  stubs: {
    'van-nav-bar': { template: '<header></header>' },
    'van-loading': { template: '<span><slot /></span>' },
    'van-button': { emits: ['click'], template: '<button type="button" @click="$emit(\'click\')"><slot /></button>' },
    'van-tag': { template: '<span><slot /></span>' },
    'van-cell-group': { template: '<section><slot /></section>' },
    'van-cell': { template: '<div></div>' },
    'van-steps': { template: '<section><slot /></section>' },
    'van-step': { template: '<div><slot /></div>' },
    'van-dialog': { template: '<div><slot /></div>' },
    'van-field': { template: '<textarea></textarea>' },
  },
}

describe('TicketDetail', () => {
  beforeEach(() => {
    fetchTicketDetailMock.mockReset()
  })

  it('加载失败时提供原地重试，并能恢复详情内容', async () => {
    fetchTicketDetailMock.mockRejectedValueOnce(new Error('network unavailable')).mockResolvedValueOnce(detail)
    const wrapper = mount(TicketDetailView, {
      props: { id: detail.ticket.id },
      global: globalOptions,
    })
    await flushPromises()

    expect(wrapper.text()).toContain('工单没有加载成功')
    expect(wrapper.text()).toContain('重新加载')

    await wrapper.get('.state-error button').trigger('click')
    await flushPromises()

    expect(fetchTicketDetailMock).toHaveBeenCalledTimes(2)
    expect(wrapper.get('h1').text()).toBe('物流状态查询')
    expect(wrapper.text()).toContain('暂无流转记录')
  })
})
