// @vitest-environment happy-dom
import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { Ticket, TicketPage } from '@/types/api'
import MessagesView from './Messages.vue'

const { createSessionMock, fetchTicketsMock, pushMock } = vi.hoisted(() => ({
  createSessionMock: vi.fn(),
  fetchTicketsMock: vi.fn(),
  pushMock: vi.fn(),
}))

vi.mock('@/api/ticket', () => ({ createSession: createSessionMock, fetchTickets: fetchTicketsMock }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: pushMock }) }))
vi.mock('vant', () => ({ showToast: vi.fn() }))

const PullRefreshStub = defineComponent({
  name: 'VanPullRefresh',
  props: { modelValue: Boolean, disabled: Boolean },
  emits: ['update:modelValue', 'refresh'],
  setup(_props, { slots }) {
    return () => h('div', { 'data-testid': 'pull-refresh' }, slots.default?.())
  },
})

const ListStub = defineComponent({
  name: 'VanList',
  props: { loading: Boolean, finished: Boolean },
  emits: ['update:loading', 'load'],
  setup(_props, { slots }) {
    return () => h('div', { 'data-testid': 'ticket-list' }, [slots.default?.(), slots.finished?.()])
  },
})

const globalOptions = {
  stubs: {
    AppTabbar: true,
    'van-pull-refresh': PullRefreshStub,
    'van-list': ListStub,
    'van-loading': { template: '<span></span>' },
    'van-icon': { template: '<i></i>' },
  },
}

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise
    reject = rejectPromise
  })
  return { promise, resolve, reject }
}

function ticket(id: string, title: string): Ticket {
  return {
    id,
    sessionId: `session-${id}`,
    userId: 'user-1',
    title,
    category: 'ORDER',
    priority: 'NORMAL',
    status: 'AI_SERVING',
    assignee: null,
    handoffReason: null,
    resolveNote: null,
    reopenCount: 0,
    createdAtMs: 1_777_520_000_000,
    updatedAtMs: 1_777_520_060_000,
  }
}

describe('Messages request ordering', () => {
  beforeEach(() => {
    createSessionMock.mockReset()
    fetchTicketsMock.mockReset()
    pushMock.mockReset()
  })

  it('刷新结果不会被更早发出的分页响应覆盖', async () => {
    const initial = deferred<TicketPage>()
    const staleLoadMore = deferred<TicketPage>()
    const refresh = deferred<TicketPage>()
    fetchTicketsMock
      .mockReturnValueOnce(initial.promise)
      .mockReturnValueOnce(staleLoadMore.promise)
      .mockReturnValueOnce(refresh.promise)

    const wrapper = mount(MessagesView, { global: globalOptions })
    initial.resolve({ total: 3, items: [ticket('old-1', '刷新前会话')] })
    await flushPromises()

    const list = wrapper.findComponent(ListStub)
    list.vm.$emit('update:loading', true)
    list.vm.$emit('load')
    await flushPromises()
    expect(fetchTicketsMock).toHaveBeenCalledTimes(2)

    const pullRefresh = wrapper.findComponent(PullRefreshStub)
    pullRefresh.vm.$emit('update:modelValue', true)
    pullRefresh.vm.$emit('refresh')
    await flushPromises()
    expect(fetchTicketsMock).toHaveBeenCalledTimes(3)

    refresh.resolve({ total: 1, items: [ticket('fresh-1', '刷新后会话')] })
    await flushPromises()
    staleLoadMore.resolve({ total: 3, items: [ticket('stale-2', '迟到的旧分页')] })
    await flushPromises()

    expect(wrapper.text()).toContain('刷新后会话')
    expect(wrapper.text()).not.toContain('刷新前会话')
    expect(wrapper.text()).not.toContain('迟到的旧分页')
    expect(wrapper.findAll('.conversation-card')).toHaveLength(1)
  })

  it('分页失败后由用户显式重试并追加下一页', async () => {
    fetchTicketsMock
      .mockResolvedValueOnce({ total: 2, items: [ticket('page-1', '第一页会话')] })
      .mockRejectedValueOnce(new Error('load more failed'))
      .mockResolvedValueOnce({ total: 2, items: [ticket('page-2', '第二页会话')] })
    const wrapper = mount(MessagesView, { global: globalOptions })
    await flushPromises()

    const list = wrapper.findComponent(ListStub)
    list.vm.$emit('update:loading', true)
    list.vm.$emit('load')
    await flushPromises()
    expect(wrapper.text()).toContain('更多会话加载失败')

    await wrapper.get('.load-more-error button').trigger('click')
    await flushPromises()

    expect(fetchTicketsMock).toHaveBeenCalledTimes(3)
    expect(wrapper.text()).toContain('第一页会话')
    expect(wrapper.text()).toContain('第二页会话')
    expect(wrapper.findAll('.conversation-card')).toHaveLength(2)
  })

  it('下拉刷新失败时保留当前可用列表', async () => {
    fetchTicketsMock
      .mockResolvedValueOnce({ total: 1, items: [ticket('existing-1', '当前可用会话')] })
      .mockRejectedValueOnce(new Error('refresh failed'))
    const wrapper = mount(MessagesView, { global: globalOptions })
    await flushPromises()

    const pullRefresh = wrapper.findComponent(PullRefreshStub)
    pullRefresh.vm.$emit('update:modelValue', true)
    pullRefresh.vm.$emit('refresh')
    await flushPromises()

    expect(wrapper.text()).toContain('当前可用会话')
    expect(wrapper.text()).not.toContain('会话加载失败')
    expect(wrapper.findAll('.conversation-card')).toHaveLength(1)
  })
})
