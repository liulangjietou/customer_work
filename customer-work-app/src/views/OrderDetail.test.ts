// @vitest-environment happy-dom
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { OrderView } from '@/types/api'
import OrderDetailView from './OrderDetail.vue'

const { fetchOrderDetailMock } = vi.hoisted(() => ({ fetchOrderDetailMock: vi.fn() }))

vi.mock('@/api/order', () => ({ fetchOrderDetail: fetchOrderDetailMock }))
vi.mock('vue-router', () => ({
  useRouter: () => ({ back: vi.fn(), push: vi.fn(), replace: vi.fn() }),
}))

const detail: OrderView = {
  orderId: 'ORDER-20260828-01',
  productId: 'SKU-HEADPHONE-01',
  productName: '旗舰款无线降噪耳机',
  amount: '399.00',
  status: '运输中',
  receiverAddr: '上海市示例路 88 号',
  logisticsTrace: '【已揽收。】 → 【运输中。】 → 【派送中。】',
  createdAtMs: 1_777_520_060_000,
}

const globalOptions = {
  stubs: {
    'van-icon': { template: '<i></i>' },
  },
}

describe('OrderDetail', () => {
  beforeEach(() => {
    fetchOrderDetailMock.mockReset()
  })

  it('首次失败后可重新加载订单，并将最新物流节点排在最前', async () => {
    fetchOrderDetailMock.mockRejectedValueOnce(new Error('order unavailable')).mockResolvedValueOnce(detail)
    const wrapper = mount(OrderDetailView, {
      props: { id: detail.orderId },
      global: globalOptions,
    })
    await flushPromises()

    expect(wrapper.text()).toContain('订单详情加载失败')
    expect(wrapper.text()).toContain('重新加载')

    await wrapper.get('.retry-button').trigger('click')
    await flushPromises()

    expect(fetchOrderDetailMock).toHaveBeenCalledTimes(2)
    expect(fetchOrderDetailMock).toHaveBeenNthCalledWith(1, detail.orderId)
    expect(fetchOrderDetailMock).toHaveBeenNthCalledWith(2, detail.orderId)
    expect(wrapper.text()).toContain(detail.orderId)
    expect(wrapper.text()).toContain(`¥${detail.amount}`)

    const traceNodes = wrapper.findAll('.timeline-item strong').map((node) => node.text())
    expect(traceNodes).toEqual(['派送中', '运输中', '已揽收'])
    expect(wrapper.get('.timeline-item--active strong').text()).toBe('派送中')
  })
})
