// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import CustomerBusinessProgressPanel from './CustomerBusinessProgressPanel.vue'

enableAutoUnmount(afterEach)
const { fetchApprovals, fetchTicket, push } = vi.hoisted(() => ({
  fetchApprovals: vi.fn(), fetchTicket: vi.fn(), push: vi.fn(),
}))
vi.mock('@/api/businessProgress', () => ({ fetchRefundApprovals: fetchApprovals }))
vi.mock('@/api/ticket', () => ({ fetchTicketDetail: fetchTicket }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push }) }))
const PopupStub = defineComponent({
  props: { show: Boolean }, emits: ['keydown', 'update:show'],
  setup(props, { attrs, slots, emit }) {
    return () => props.show ? h('div', { ...attrs, role: 'dialog',
      onKeydown: (event: KeyboardEvent) => emit('keydown', event),
    }, slots.default?.()) : null
  },
})
const approval = {
  id: 'approval-1', orderId: 'order/1', amount: '99.80', approvalStatus: 'APPROVED',
  executionStatus: 'PENDING', createdAtMs: 1789086000000, decidedAtMs: 1789086010000,
}
const detail = { ticket: { id: 'ticket-1', sessionId: 'session-1', title: '退款核对',
  status: 'WAITING_CONFIRM', updatedAtMs: 1789086010000 }, events: [] }
function pending<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => { resolve = done })
  return { resolve, promise }
}
function mountPanel(props = {}) {
  return mount(CustomerBusinessProgressPanel, {
    props: { open: true, sessionId: 'session-1', ticketId: 'ticket-1', identityKey: 'user-1', ...props },
    attachTo: document.body, global: { stubs: { VanPopup: PopupStub } },
  })
}
describe('当前会话办理进度', () => {
  beforeEach(() => {
    fetchTicket.mockReset().mockResolvedValue(detail)
    fetchApprovals.mockReset().mockResolvedValue({ total: 1, items: [approval] })
    push.mockReset()
  })
  afterEach(() => document.body.replaceChildren())
  it('分开展示工单与审批，批准不冒充已执行或到账，订单入口保留实际编号', async () => {
    const wrapper = mountPanel()
    await flushPromises()
    expect(fetchApprovals).toHaveBeenCalledWith('session-1', 1, 10)
    expect(wrapper.text()).toContain('待您确认')
    expect(wrapper.text()).toContain('已批准，执行待核对')
    expect(wrapper.text()).toContain('99.80')
    expect(wrapper.text()).not.toMatch(/已到账|已原路退回|退款成功/)
    await wrapper.get('[data-action="view-order"]').trigger('click')
    expect(push).toHaveBeenCalledWith({ path: '/orders/order%2F1', query: { ticketId: 'ticket-1' } })
  })
  it.each([404, 503])('审批 HTTP %s 与无记录不同，工单可独立查看并能原地重试', async (status) => {
    fetchApprovals.mockRejectedValueOnce({ response: { status, data: { message: 'internal-secret' } } })
    const wrapper = mountPanel()
    await flushPromises()
    expect(wrapper.text()).toContain('待您确认')
    expect(wrapper.get('[data-section="refund"] [role="alert"]').text()).toContain(
      status === 404 ? '当前会话不可访问' : '退款进度暂时无法加载',
    )
    expect(wrapper.text()).not.toContain('暂无退款审批记录')
    expect(wrapper.text()).not.toContain('internal-secret')
    await wrapper.get('[data-action="retry-refunds"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('已批准，执行待核对')
  })
  it('空目录不被描述为业务未办理，工单已关闭不显示成已解决', async () => {
    fetchApprovals.mockResolvedValueOnce({ total: 0, items: [] })
    fetchTicket.mockResolvedValueOnce({ ...detail, ticket: { ...detail.ticket, status: 'CLOSED' } })
    const wrapper = mountPanel()
    await flushPromises()
    expect(wrapper.text()).toContain('暂无退款审批记录')
    expect(wrapper.text()).toContain('已关闭')
    expect(wrapper.text()).not.toContain('已解决')
    expect(wrapper.find('[data-action="view-order"]').exists()).toBe(false)
  })
  it('切换会话立即清空旧记录，迟到请求不得覆盖新会话', async () => {
    const deferred = pending<unknown>()
    fetchApprovals.mockReturnValueOnce(deferred.promise)
    const wrapper = mountPanel()
    await flushPromises()
    fetchTicket.mockResolvedValueOnce({ ...detail, ticket: { ...detail.ticket, id: 'ticket-2', sessionId: 'session-2' } })
    fetchApprovals.mockResolvedValueOnce({ total: 0, items: [] })
    await wrapper.setProps({ sessionId: 'session-2', ticketId: 'ticket-2' })
    await flushPromises()
    deferred.resolve({ total: 1, items: [approval] })
    await flushPromises()
    expect(fetchApprovals).toHaveBeenLastCalledWith('session-2', 1, 10)
    expect(wrapper.text()).not.toContain('99.80')
    expect(wrapper.text()).toContain('暂无退款审批记录')
  })
  it('分页只读取对应页，刷新清除过时状态并重新核对当前页', async () => {
    fetchApprovals.mockResolvedValueOnce({ total: 11, items: [approval] })
    const wrapper = mountPanel()
    await flushPromises()
    fetchApprovals.mockResolvedValueOnce({ total: 11, items: [approval] })
    await wrapper.get('[data-action="next-page"]').trigger('click')
    await flushPromises()
    expect(fetchApprovals).toHaveBeenLastCalledWith('session-1', 2, 10)
    const deferred = pending<unknown>()
    fetchApprovals.mockReturnValueOnce(deferred.promise)
    await wrapper.get('[data-action="refresh"]').trigger('click')
    expect(wrapper.text()).not.toContain('99.80')
    deferred.resolve({ total: 11, items: [{ ...approval, executionStatus: 'EXECUTED' }] })
    await flushPromises()
    expect(wrapper.text()).toContain('处理已执行')
    expect(wrapper.text()).toContain('到账情况请核对支付渠道记录')
  })
  it('记录减少使当前页超出范围时回到有效页，不把用户困在空页', async () => {
    fetchApprovals.mockResolvedValueOnce({ total: 11, items: [approval] })
    const wrapper = mountPanel()
    await flushPromises()
    fetchApprovals.mockResolvedValueOnce({ total: 1, items: [] })
    await wrapper.get('[data-action="next-page"]').trigger('click')
    await flushPromises()
    expect(fetchApprovals).toHaveBeenLastCalledWith('session-1', 1, 10)
    expect(wrapper.text()).toContain('99.80')
  })
  it('更换身份关闭并清除，迟到数据不能复活', async () => {
    const deferred = pending<unknown>()
    fetchApprovals.mockReturnValueOnce(deferred.promise)
    const wrapper = mountPanel()
    await flushPromises()
    await wrapper.setProps({ identityKey: 'user-2' })
    deferred.resolve({ total: 1, items: [approval] })
    await flushPromises()
    expect(wrapper.emitted('update:open')).toEqual([[false]])
    expect(wrapper.text()).not.toContain('99.80')
  })
  it('Escape 关闭恢复入口焦点，服务端文本按纯文本显示', async () => {
    fetchTicket.mockResolvedValueOnce({ ...detail, ticket: { ...detail.ticket, title: '<img src=x onerror=alert(1)>' } })
    const trigger = document.createElement('button')
    document.body.appendChild(trigger)
    trigger.focus()
    const wrapper = mountPanel({ trigger })
    await flushPromises()
    expect(wrapper.text()).toContain('<img src=x onerror=alert(1)>')
    expect(wrapper.find('img').exists()).toBe(false)
    await wrapper.get('[role="dialog"]').trigger('keydown', { key: 'Escape' })
    await wrapper.setProps({ open: false })
    await flushPromises()
    expect(document.activeElement).toBe(trigger)
  })
})
