// @vitest-environment happy-dom
import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { OrderView } from '@/types/api'
import OrderListView from './OrderList.vue'

const { fetchOrdersMock, pushMock } = vi.hoisted(() => ({ fetchOrdersMock: vi.fn(), pushMock: vi.fn() }))

vi.mock('@/api/order', () => ({ fetchOrders: fetchOrdersMock }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: pushMock }) }))

const PullRefreshStub = defineComponent({
  name: 'VanPullRefresh',
  props: { modelValue: Boolean, disabled: Boolean },
  emits: ['update:modelValue', 'refresh'],
  setup(_props, { slots }) {
    return () => h('div', { 'data-testid': 'pull-refresh' }, slots.default?.())
  },
})

const globalOptions = {
  stubs: {
    AppTabbar: true,
    'van-pull-refresh': PullRefreshStub,
    'van-loading': { template: '<span></span>' },
    'van-icon': { template: '<i></i>' },
  },
}

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((resolvePromise) => {
    resolve = resolvePromise
  })
  return { promise, resolve }
}

function order(orderId: string, productName: string): OrderView {
  return {
    orderId,
    productId: `SKU-${orderId}`,
    productName,
    amount: '299.00',
    status: '已发货',
    receiverAddr: '上海市示例路 88 号',
    logisticsTrace: null,
    createdAtMs: 1_777_520_060_000,
  }
}

describe('OrderList request ordering', () => {
  beforeEach(() => {
    fetchOrdersMock.mockReset()
    pushMock.mockReset()
  })

  it('较早的首次请求迟到时不会覆盖刷新结果', async () => {
    const initial = deferred<OrderView[]>()
    const refresh = deferred<OrderView[]>()
    fetchOrdersMock.mockReturnValueOnce(initial.promise).mockReturnValueOnce(refresh.promise)

    const wrapper = mount(OrderListView, { global: globalOptions })
    const pullRefresh = wrapper.findComponent(PullRefreshStub)
    pullRefresh.vm.$emit('update:modelValue', true)
    pullRefresh.vm.$emit('refresh')
    await flushPromises()

    refresh.resolve([order('new-1', '刷新后的订单')])
    await flushPromises()
    initial.resolve([order('old-1', '迟到的旧订单')])
    await flushPromises()

    expect(wrapper.text()).toContain('刷新后的订单')
    expect(wrapper.text()).not.toContain('迟到的旧订单')
    expect(wrapper.findAll('.order-card')).toHaveLength(1)
  })

  it('首次加载失败后可以原地重试恢复订单', async () => {
    fetchOrdersMock
      .mockRejectedValueOnce(new Error('orders unavailable'))
      .mockResolvedValueOnce([order('retry-1', '重试恢复的订单')])
    const wrapper = mount(OrderListView, { global: globalOptions })
    await flushPromises()

    expect(wrapper.text()).toContain('订单加载失败')
    await wrapper.get('.state-panel .primary-button').trigger('click')
    await flushPromises()

    expect(fetchOrdersMock).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('重试恢复的订单')
    expect(wrapper.findAll('.order-card')).toHaveLength(1)
  })
})
