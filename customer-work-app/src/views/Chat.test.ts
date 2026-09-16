// @vitest-environment happy-dom
import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { ChatMessage, FeedbackType, TicketDetail, UserQuota, WsChatDone } from '@/types/api'
import ChatView from './Chat.vue'
import { chatSocket } from '@/utils/ws'

const {
  fetchMessagesMock,
  fetchReceiptMock,
  fetchMyQuotaMock,
  fetchSessionFeedbackMock,
  fetchTicketDetailMock,
  uploadAttachmentMock,
  sendMock,
  submitFeedbackMock,
  wsHandlers,
} = vi.hoisted(() => ({
  fetchMessagesMock: vi.fn(),
  fetchReceiptMock: vi.fn(),
  fetchMyQuotaMock: vi.fn(),
  fetchSessionFeedbackMock: vi.fn(),
  fetchTicketDetailMock: vi.fn(),
  uploadAttachmentMock: vi.fn(),
  sendMock: vi.fn(),
  submitFeedbackMock: vi.fn(),
  wsHandlers: new Map<string, (data: unknown) => void>(),
}))

vi.mock('@/api/ticket', () => ({
  closeTicket: vi.fn(),
  confirmTicket: vi.fn(),
  createSession: vi.fn(),
  fetchMessages: fetchMessagesMock,
  fetchMessageReceipt: fetchReceiptMock,
  fetchTicketDetail: fetchTicketDetailMock,
  handoffTicket: vi.fn(),
  rejectTicket: vi.fn(),
  reopenTicket: vi.fn(),
}))

vi.mock('@/api/feedback', () => ({
  fetchSessionFeedback: fetchSessionFeedbackMock,
  submitFeedback: submitFeedbackMock,
}))

vi.mock('@/api/chat', () => ({ uploadChatAttachment: uploadAttachmentMock }))
vi.mock('@/api/quota', () => ({ fetchMyQuota: fetchMyQuotaMock }))
vi.mock('@/components/CsatSurveyCard.vue', () => ({
  default: defineComponent({ name: 'CsatSurveyCard', template: '<div></div>' }),
}))
vi.mock('@/store/auth', () => ({
  useAuthStore: () => ({ token: 'token-1', userId: 'user-1' }),
}))
vi.mock('vue-router', () => ({
  useRoute: () => ({ query: { ticketId: 'ticket-1' } }),
  useRouter: () => ({ back: vi.fn() }),
}))
vi.mock('@/utils/ws', () => ({
  chatSocket: {
    close: vi.fn(),
    connect: vi.fn(() => wsHandlers.get('open')?.(null)),
    off: vi.fn((type: string) => wsHandlers.delete(type)),
    on: vi.fn((type: string, handler: (data: unknown) => void) => wsHandlers.set(type, handler)),
    send: sendMock,
  },
}))
vi.mock('vant', () => ({
  showConfirmDialog: vi.fn(),
  showToast: vi.fn(),
}))

const FieldStub = defineComponent({
  name: 'VanField',
  inheritAttrs: false,
  props: { disabled: Boolean, modelValue: { type: String, default: '' } },
  emits: ['update:modelValue'],
  setup(props, { attrs, emit }) {
    return () =>
      h('input', {
        ...attrs,
        disabled: props.disabled,
        value: props.modelValue,
        onInput: (event: Event) =>
          emit('update:modelValue', (event.target as HTMLInputElement).value),
      })
  },
})

const ButtonStub = defineComponent({
  name: 'VanButton',
  inheritAttrs: false,
  props: { disabled: Boolean, loading: Boolean },
  emits: ['click'],
  setup(props, { attrs, emit, slots }) {
    return () =>
      h(
        'button',
        {
          ...attrs,
          disabled: props.disabled || props.loading,
          type: 'button',
          onClick: () => emit('click'),
        },
        slots.default?.(),
      )
  },
})

const SlotStub = defineComponent({
  inheritAttrs: false,
  setup(_props, { attrs, slots }) {
    return () => h('div', attrs, slots.default?.())
  },
})

const NavBarStub = defineComponent({
  name: 'VanNavBar',
  setup(_props, { slots }) {
    return () => h('header', [slots.default?.(), slots.right?.()])
  },
})

const UploaderStub = defineComponent({
  name: 'VanUploader',
  props: { afterRead: Function },
  setup(_props, { slots }) {
    return () => h('div', slots.default?.())
  },
})

const globalOptions = {
  config: { errorHandler: vi.fn() },
  stubs: {
    CsatSurveyCard: true,
    'van-action-sheet': SlotStub,
    'van-button': ButtonStub,
    'van-dialog': SlotStub,
    'van-field': FieldStub,
    'van-icon': { template: '<i></i>' },
    'van-loading': SlotStub,
    'van-nav-bar': NavBarStub,
    'van-tag': SlotStub,
    'van-uploader': UploaderStub,
  },
}

const detail: TicketDetail = {
  ticket: {
    id: 'ticket-1',
    sessionId: 'session-1',
    userId: 'user-1',
    title: '订单物流咨询',
    category: 'ORDER',
    priority: 'NORMAL',
    status: 'PROCESSING',
    assignee: '客服 07',
    handoffReason: null,
    resolveNote: null,
    reopenCount: 0,
    createdAtMs: 1_777_520_000_000,
    updatedAtMs: 1_777_520_060_000,
  },
  events: [],
}

const botMessage: ChatMessage = {
  id: 1,
  messageId: 'message-bot-1',
  sessionId: 'session-1',
  ticketId: 'ticket-1',
  senderType: 'BOT',
  senderId: null,
  content: '您的订单正在配送中。',
  createdAtMs: 1_777_520_060_000,
}

const answerEvidence = {
  finishReason: 'INTERRUPTED',
  citations: [{ knowledgeBase: '售后政策库', documentId: 'refund-policy', chunkId: 'refund-7-days', score: 0.9 }],
  taskPlan: [{ content: '核对退款进度', status: 'completed', priority: 'high' }],
}

function doneReply(overrides: Partial<WsChatDone> = {}): WsChatDone {
  return {
    id: botMessage.id, messageId: botMessage.messageId, sessionId: botMessage.sessionId,
    ticketId: botMessage.ticketId, content: botMessage.content, ts: botMessage.createdAtMs,
    usage: { inputTokens: 1, outputTokens: 1, cachedTokens: 0, totalTokens: 2, timeSeconds: 1 },
    traceId: 'trace-fixture', ...answerEvidence, ...overrides,
  }
}

const unlimitedQuota: UserQuota = {
  levelCode: null,
  windowSeconds: 3600,
  tokenUsed: 0,
  tokenLimit: 0,
  tokenRemaining: -1,
  requestUsed: 0,
  requestLimit: 0,
  requestRemaining: -1,
  limited: false,
}

function savedFeedback(type: FeedbackType) {
  return {
    messageId: botMessage.messageId,
    sessionId: botMessage.sessionId,
    type,
    comment: null,
    createdAtMs: 1_777_520_120_000,
  }
}

async function mountReadyChat() {
  const wrapper = mount(ChatView, { global: globalOptions })
  await flushPromises()
  return wrapper
}

describe('Chat', () => {
  beforeEach(() => {
    localStorage.clear()
    wsHandlers.clear()
    fetchMessagesMock.mockReset().mockResolvedValue([botMessage])
    fetchMyQuotaMock.mockReset().mockResolvedValue(unlimitedQuota)
    fetchSessionFeedbackMock.mockReset().mockResolvedValue([])
    fetchTicketDetailMock.mockReset().mockResolvedValue(detail)
    sendMock.mockReset().mockReturnValue(true)
    fetchReceiptMock.mockReset().mockResolvedValue({ message: null })
    submitFeedbackMock
      .mockReset()
      .mockImplementation(({ type }: { type: FeedbackType }) =>
        Promise.resolve(savedFeedback(type)),
      )
  })

  it.each([
    ['MODEL_STOP', '答复已生成'], ['CACHE_HIT', '答复已生成'],
    ['INTERRUPTED', '答复已中断'], ['ERROR', '答复生成失败'],
    ['QUOTA_EXCEEDED', '本轮额度不足'], ['MAX_ITERATIONS', '答复尚未完成'],
    ['TOOL_SUSPENDED', '等待后续处理'], ['FUTURE_REASON', '结束状态待核对'],
  ])('实时 %s 只陈述答复状态，不表示业务办理成功', async (finishReason, label) => {
    const wrapper = await mountReadyChat()
    wsHandlers.get('chat_done')?.(doneReply({ finishReason }))
    await flushPromises()
    expect(wrapper.get('.answer-status').text()).toContain(label)
    expect(wrapper.get('.task-plan').text()).toContain('助手计划')
    expect(wrapper.get('.task-plan').text()).toContain('实际办理结果以订单或工单为准')
    expect(wrapper.get('.task-plan').text()).toContain('助手标记完成')
    expect(wrapper.findAll('.row-BOT')).toHaveLength(1)
    expect(wrapper.text()).not.toContain('本次服务已解决')
    wrapper.unmount()
  })

  it('首连 HTTP 快照恢复答复元数据，旧消息缺失字段不冒充已完成', async () => {
    fetchMessagesMock.mockResolvedValueOnce([botMessage]).mockResolvedValue([
      botMessage,
      { ...botMessage, ...answerEvidence, id: 2, messageId: 'first-connect-evidence' },
    ])
    const wrapper = await mountReadyChat()
    const rows = wrapper.findAll('.row-BOT')
    expect(rows[0]!.get('.answer-status').text()).toContain('未记录结束状态')
    expect(rows[0]!.find('.task-plan').exists()).toBe(false)
    expect(rows[1]!.get('.answer-status').text()).toContain('答复已中断')
    expect(rows[1]!.text()).toContain('核对退款进度')
    expect(rows[1]!.text()).toContain('refund-7-days')
    wrapper.unmount()
  })

  for (const missing of [undefined, null]) {
    it(`旧 HTTP 的 ${missing} 字段不擦掉同消息 WS 已知元数据`, async () => {
      const wrapper = await mountReadyChat()
      wsHandlers.get('chat_done')?.(doneReply())
      await flushPromises()
      fetchMessagesMock.mockResolvedValue([{ ...botMessage,
        finishReason: missing, citations: missing, taskPlan: missing }])
      wsHandlers.get('open')?.({ reconnected: true })
      await flushPromises()
      expect(wrapper.get('.task-plan').text()).toContain('核对退款进度')
      expect(wrapper.get('.citations').text()).toContain('售后政策库')
      expect(wrapper.get('.answer-status').text()).toContain('答复已中断')
      expect(wrapper.findAll('.row-BOT')).toHaveLength(1)
      wrapper.unmount()
    })

    it(`旧 WS 的 ${missing} 字段不擦掉同消息 HTTP 已知元数据`, async () => {
      fetchMessagesMock.mockResolvedValue([{ ...botMessage, ...answerEvidence }])
      const wrapper = await mountReadyChat()
      wsHandlers.get('chat_done')?.({ ...doneReply(), id: undefined,
        finishReason: missing, citations: missing, taskPlan: missing })
      await flushPromises()
      expect(wrapper.get('.task-plan').text()).toContain('核对退款进度')
      expect(wrapper.get('.citations').text()).toContain('售后政策库')
      expect(wrapper.get('.answer-status').text()).toContain('答复已中断')
      expect(wrapper.findAll('.row-BOT')).toHaveLength(1)
      wrapper.unmount()
    })
  }

  it('同消息明确的空集合能清除旧清单和线索，新的结束原因替换旧值', async () => {
    fetchMessagesMock.mockResolvedValue([{ ...botMessage, ...answerEvidence }])
    const wrapper = await mountReadyChat()
    wsHandlers.get('chat_done')?.(doneReply({ finishReason: 'MODEL_STOP', citations: [], taskPlan: [] }))
    await flushPromises()
    expect(wrapper.find('.task-plan').exists()).toBe(false)
    expect(wrapper.find('.citations').exists()).toBe(false)
    expect(wrapper.get('.answer-status').text()).toContain('答复已生成')
    fetchMessagesMock.mockResolvedValue([{ ...botMessage, ...answerEvidence }])
    wsHandlers.get('open')?.({ reconnected: true })
    await flushPromises()
    expect(wrapper.get('.answer-status').text()).toContain('答复已中断')
    expect(wrapper.get('.task-plan').text()).toContain('核对退款进度')
    wrapper.unmount()
  })

  it('分页加载更早消息时恢复该轮清单和结束原因', async () => {
    const recent = Array.from({ length: 50 }, (_, index) => ({ ...botMessage,
      id: index + 2, messageId: `recent-${index + 2}`, createdAtMs: botMessage.createdAtMs + index + 1 }))
    fetchMessagesMock.mockResolvedValue(recent)
    const wrapper = await mountReadyChat()
    fetchMessagesMock.mockResolvedValueOnce([{ ...botMessage, ...answerEvidence }])
    await wrapper.get('.history-control button').trigger('click')
    await flushPromises()
    const oldest = wrapper.findAll('.row-BOT')[0]!
    expect(oldest.get('.answer-status').text()).toContain('答复已中断')
    expect(oldest.get('.task-plan').text()).toContain('核对退款进度')
    expect(oldest.get('.citations').text()).toContain('refund-7-days')
    expect(wrapper.findAll('.row-BOT')).toHaveLength(51)
    wrapper.unmount()
  })

  it('附件解析失败时保留草稿并阻止静默丢弃附件后发送', async () => {
    uploadAttachmentMock.mockResolvedValueOnce({
      parseStatus: 'FAILED',
      errorMessage: '无法解析文件',
    })
    const wrapper = await mountReadyChat()
    const input = wrapper.get('.message-input')
    await input.setValue('请参考附件')
    await wrapper.findComponent(UploaderStub).props('afterRead')!({
      file: new File(['content'], 'notes.txt'),
    })
    await flushPromises()
    expect(wrapper.get('.send-button').attributes('disabled')).toBeDefined()
    expect((input.element as HTMLInputElement).value).toBe('请参考附件')
    expect(sendMock).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('连接在点击发送时失效，保留原草稿且不伪造已发送消息', async () => {
    sendMock.mockReturnValue(false)
    const wrapper = await mountReadyChat()
    const input = wrapper.get('.message-input')
    await input.setValue('  请保留我的草稿  ')
    await wrapper.get('.send-button').trigger('click')
    await flushPromises()
    expect((input.element as HTMLInputElement).value).toBe('  请保留我的草稿  ')
    expect(wrapper.findAll('.row-USER')).toHaveLength(0)
    wrapper.unmount()
  })

  it('只有已解析附件也可以发送，回执到达后才移除附件', async () => {
    uploadAttachmentMock.mockResolvedValueOnce({ id: 'attachment-1', parseStatus: 'SUCCESS', content: '发票正文' })
    const wrapper = await mountReadyChat()
    await wrapper.findComponent(UploaderStub).props('afterRead')!({ file: new File(['content'], 'invoice.txt') })
    await flushPromises()
    expect(wrapper.get('.send-button').attributes('disabled')).toBeUndefined()
    await wrapper.get('.send-button').trigger('click')
    expect(sendMock).toHaveBeenCalledTimes(1)
    expect(wrapper.get('.attachment-chips').text()).toContain('invoice.txt')
    const command = sendMock.mock.calls[0][0].data
    wsHandlers.get('chat_accepted')?.({ clientMsgId: command.clientMsgId, id: 2, messageId: 'saved-attachment',
      sessionId: 'session-1', ticketId: 'ticket-1', ts: Date.now() })
    await flushPromises()
    expect(wrapper.find('.attachment-chips').exists()).toBe(false)
    wrapper.unmount()
  })

  it('重连补拉失败时保留收到的回复片段并明确标为中断', async () => {
    const wrapper = await mountReadyChat()
    await wrapper.get('.message-input').setValue('请帮我查物流')
    await wrapper.get('.send-button').trigger('click')
    wsHandlers.get('chat_chunk')?.({ content: '已查询到订单，正在' })
    wsHandlers.get('close')?.(null)
    fetchMessagesMock.mockRejectedValueOnce(new Error('offline history'))
    wsHandlers.get('open')?.({ reconnected: true })
    await flushPromises()
    expect(wrapper.text()).toContain('已查询到订单，正在')
    expect(wrapper.text()).toContain('断线前收到的回复片段')
    expect(wrapper.text()).toContain('重新同步')
    wrapper.unmount()
  })

  it('其他会话的流式片段不会拼接到当前正在接收的回复', async () => {
    const wrapper = await mountReadyChat()
    await wrapper.get('.message-input').setValue('当前会话的问题')
    await wrapper.get('.send-button').trigger('click')
    wsHandlers.get('chat_chunk')?.({ sessionId: 'other-session', ticketId: 'other-ticket', content: '不应显示的片段' })
    await flushPromises()
    expect(wrapper.text()).not.toContain('不应显示的片段')
    wrapper.unmount()
  })

  it('受理后的回复失败停止光标并保留片段，只提供查询核对', async () => {
    const wrapper = await mountReadyChat()
    await wrapper.get('.message-input').setValue('请继续核对')
    await wrapper.get('.send-button').trigger('click')
    const command = sendMock.mock.calls[0][0].data
    wsHandlers.get('chat_accepted')?.({ clientMsgId: command.clientMsgId, id: 2, messageId: 'saved-input',
      sessionId: 'session-1', ticketId: 'ticket-1', ts: Date.now() })
    wsHandlers.get('chat_chunk')?.({ sessionId: 'session-1', clientMsgId: command.clientMsgId, content: '已经开始核对' })
    wsHandlers.get('error')?.({ sessionId: 'session-1', clientMsgId: command.clientMsgId,
      code: 'CHAT-AI-STREAM-FAIL', acceptance: 'ACCEPTED', message: '回复尚未完成' })
    await flushPromises()
    expect(wrapper.find('.cursor').exists()).toBe(false)
    expect(wrapper.text()).toContain('已经开始核对')
    expect(wrapper.text()).toContain('出错前收到的回复片段')
    expect(sendMock).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })

  it('重连消息超过一页时补齐中间记录并保留已显示的旧记录', async () => {
    const wrapper = await mountReadyChat()
    const message = (id: number): ChatMessage => ({ ...botMessage, id, messageId: `gap-${id}`,
      content: `第 ${id} 条回复`, createdAtMs: botMessage.createdAtMs + id })
    fetchMessagesMock.mockResolvedValueOnce(Array.from({ length: 50 }, (_, index) => message(index + 52)))
      .mockResolvedValueOnce(Array.from({ length: 50 }, (_, index) => message(index + 2)))
      .mockResolvedValueOnce([botMessage])
    wsHandlers.get('open')?.({ reconnected: true })
    await flushPromises()
    expect(fetchMessagesMock).toHaveBeenCalledWith('session-1', { beforeId: 52, limit: 50 })
    expect(wrapper.text()).toContain('第 2 条回复')
    expect(wrapper.text()).toContain(botMessage.content)
    expect(wrapper.findAll('.row-BOT')).toHaveLength(101)
    wrapper.unmount()
  })

  it('首条连接建立后补齐首次 HTTP 快照与订阅之间的消息', async () => {
    const arrived: ChatMessage = { ...botMessage, id: 2, messageId: 'arrived-before-socket', content: '连接前刚收到的消息' }
    fetchMessagesMock.mockResolvedValueOnce([botMessage]).mockResolvedValueOnce([botMessage, arrived])
    const wrapper = await mountReadyChat()
    expect(wrapper.text()).toContain(arrived.content)
    wrapper.unmount()
  })

  it('加载中离开页面后，迟到的结果不能重新建立旧会话连接', async () => {
    let resolveDetail!: (value: TicketDetail) => void
    fetchTicketDetailMock.mockReturnValueOnce(new Promise<TicketDetail>(resolve => { resolveDetail = resolve }))
    vi.mocked(chatSocket.connect).mockClear()
    const wrapper = mount(ChatView, { global: globalOptions })
    wrapper.unmount()
    resolveDetail(detail)
    await flushPromises()
    expect(chatSocket.connect).not.toHaveBeenCalled()
  })

  it('核对接口失败时继续保留未知状态，不自动重发已写出的命令', async () => {
    const wrapper = await mountReadyChat()
    await wrapper.get('.message-input').setValue('受理结果未知')
    await wrapper.get('.send-button').trigger('click')
    const command = sendMock.mock.calls[0][0].data
    wsHandlers.get('error')?.({ sessionId: 'session-1', clientMsgId: command.clientMsgId,
      acceptance: 'UNKNOWN', message: '无法确认' })
    await flushPromises()
    fetchReceiptMock.mockRejectedValueOnce(new Error('receipt unavailable'))
    const retry = wrapper.findAll('button').find(button => button.text() === '核对并重试')!
    await retry.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('受理状态未知')
    expect(sendMock).toHaveBeenCalledTimes(1)
    expect((wrapper.get('.message-input').element as HTMLInputElement).value).toBe('受理结果未知')
    wrapper.unmount()
  })

  it('收到持久化回执才清空对应草稿并确认受理', async () => {
    const wrapper = await mountReadyChat()
    const input = wrapper.get('.message-input')
    await input.setValue('查询物流')
    await wrapper.get('.send-button').trigger('click')
    await flushPromises()
    expect((input.element as HTMLInputElement).value).toBe('查询物流')
    expect(wrapper.text()).toContain('发送中')
    const clientMsgId = sendMock.mock.calls[0][0].data.clientMsgId
    wsHandlers.get('chat_accepted')?.({ clientMsgId, id: 12, messageId: 'saved-user-12', sessionId: 'session-1', ticketId: 'ticket-1', ts: 123 })
    await flushPromises()
    expect((input.element as HTMLInputElement).value).toBe('')
    expect(wrapper.text()).toContain('已受理')
    expect(wrapper.findAll('.row-USER')).toHaveLength(1)
    wrapper.unmount()
  })

  it('受理回执不覆盖用户后来编辑的草稿，重复回执不增加气泡', async () => {
    const wrapper = await mountReadyChat()
    const input = wrapper.get('.message-input')
    await input.setValue('第一条消息')
    await wrapper.get('.send-button').trigger('click')
    await input.setValue('第二条还没发送的草稿')
    const receipt = { clientMsgId: sendMock.mock.calls[0][0].data.clientMsgId, id: 12, messageId: 'saved-user-12', sessionId: 'session-1', ticketId: 'ticket-1', ts: 123 }
    wsHandlers.get('chat_accepted')?.(receipt)
    wsHandlers.get('chat_accepted')?.(receipt)
    await flushPromises()
    expect((input.element as HTMLInputElement).value).toBe('第二条还没发送的草稿')
    expect(wrapper.text()).toContain('已受理')
    expect(wrapper.findAll('.row-USER')).toHaveLength(1)
    wrapper.unmount()
  })

  it('回执丢失后先核对持久化消息，已受理时不再次发送', async () => {
    const wrapper = await mountReadyChat()
    await wrapper.get('.message-input').setValue('查询物流')
    await wrapper.get('.send-button').trigger('click')
    const clientMsgId = sendMock.mock.calls[0][0].data.clientMsgId
    wsHandlers.get('error')?.({ clientMsgId, sessionId: 'session-1', code: 'NETWORK_UNKNOWN', acceptance: 'UNKNOWN', message: '受理状态未知' })
    await flushPromises()
    fetchReceiptMock.mockResolvedValueOnce({ clientMsgId, message: { ...botMessage, id: 12, messageId: 'saved-user-12', senderType: 'USER', senderId: 'user-1', content: '查询物流' } })
    const retry = wrapper.findAll('button').find(button => button.text() === '核对并重试')
    expect(retry).toBeDefined()
    await retry!.trigger('click')
    await flushPromises()
    expect(fetchReceiptMock).toHaveBeenCalledWith('session-1', clientMsgId)
    expect(sendMock).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('已受理')
    wrapper.unmount()
  })

  it('断线恢复同时核对工单快照，关闭的会话恢复为只读', async () => {
    const wrapper = await mountReadyChat()
    fetchTicketDetailMock.mockClear().mockResolvedValue({ ...detail, ticket: { ...detail.ticket, status: 'CLOSED' } })
    wsHandlers.get('open')?.({ reconnected: true })
    await flushPromises()
    expect(fetchTicketDetailMock).toHaveBeenCalledWith('ticket-1')
    expect(wrapper.find('.message-input').exists()).toBe(false)
    expect(wrapper.text()).toContain('本次会话已关闭')
    wrapper.unmount()
  })

  it.each([
    { shiftKey: true, isComposing: false },
    { shiftKey: false, isComposing: true },
  ])('换行或输入法确认不发送消息：%j', async (modifiers) => {
    const wrapper = await mountReadyChat()
    const input = wrapper.get('.message-input')
    await input.setValue('尚未完成的草稿')
    await input.trigger('keydown', { key: 'Enter', ...modifiers })
    await input.trigger('keyup', { key: 'Enter', ...modifiers })
    expect(sendMock).not.toHaveBeenCalled()
    expect((input.element as HTMLInputElement).value).toBe('尚未完成的草稿')
    wrapper.unmount()
  })

  it('保留原始输入构造 WebSocket 帧，回执确认后清理对应草稿', async () => {
    const wrapper = await mountReadyChat()
    const input = wrapper.get('.message-input')

    await input.setValue('  请帮我查询物流  ')
    await wrapper.get('.send-button').trigger('click')
    await flushPromises()

    expect(sendMock).toHaveBeenCalledTimes(1)
    expect(sendMock).toHaveBeenCalledWith({
      type: 'chat',
      data: {
        sessionId: 'session-1',
        content: '  请帮我查询物流  ',
        // 服务端据此去重：重连后客户端重发时沿用同一个值，否则同一句话会被处理两次
        clientMsgId: expect.any(String),
      },
    })
    expect(sendMock.mock.calls[0][0].data.clientMsgId).toBeTruthy()
    expect(wrapper.text()).toContain('请帮我查询物流')
    expect(wrapper.findAll('.row-USER')).toHaveLength(1)
    wsHandlers.get('chat_accepted')?.({ clientMsgId: sendMock.mock.calls[0][0].data.clientMsgId, id: 12, messageId: 'saved-user-12', sessionId: 'session-1', ticketId: 'ticket-1', ts: 123 })
    await flushPromises()
    expect((input.element as HTMLInputElement).value).toBe('')
    wrapper.unmount()
  })

  it('每条消息带各自的 clientMsgId，不同消息不得复用同一个值', async () => {
    const wrapper = await mountReadyChat()
    const input = wrapper.get('.message-input')

    await input.setValue('第一句')
    await wrapper.get('.send-button').trigger('click')
    await flushPromises()
    await input.setValue('第二句')
    await wrapper.get('.send-button').trigger('click')
    await flushPromises()

    const first = sendMock.mock.calls[0][0].data.clientMsgId
    const second = sendMock.mock.calls[1][0].data.clientMsgId
    expect(first).toBeTruthy()
    expect(second).toBeTruthy()
    // 复用同一个值会让第二句被服务端当成重发丢掉，用户以为消息发出去了却永远等不到回复
    expect(second).not.toBe(first)
  })

  it('同类型反馈不重复提交，切换类型后更新选中状态', async () => {
    const wrapper = await mountReadyChat()
    const up = wrapper.get('button[aria-label="回复有帮助"]')
    const down = wrapper.get('button[aria-label="回复没有帮助"]')

    await up.trigger('click')
    await flushPromises()
    expect(submitFeedbackMock).toHaveBeenCalledTimes(1)
    expect(submitFeedbackMock).toHaveBeenLastCalledWith({
      sessionId: 'session-1',
      messageId: 'message-bot-1',
      type: 'UP',
    })
    expect(up.attributes('aria-pressed')).toBe('true')

    await up.trigger('click')
    await flushPromises()
    expect(submitFeedbackMock).toHaveBeenCalledTimes(1)

    await down.trigger('click')
    await flushPromises()
    expect(submitFeedbackMock).toHaveBeenCalledTimes(2)
    expect(submitFeedbackMock).toHaveBeenLastCalledWith({
      sessionId: 'session-1',
      messageId: 'message-bot-1',
      type: 'DOWN',
    })
    expect(up.attributes('aria-pressed')).toBe('false')
    expect(down.attributes('aria-pressed')).toBe('true')
  })

  it('反馈提交失败后释放提交锁，允许用户原地重试', async () => {
    submitFeedbackMock
      .mockRejectedValueOnce(new Error('network unavailable'))
      .mockResolvedValueOnce(savedFeedback('UP'))
    const wrapper = await mountReadyChat()
    const up = wrapper.get('button[aria-label="回复有帮助"]')

    await up.trigger('click')
    await flushPromises()
    expect(submitFeedbackMock).toHaveBeenCalledTimes(1)
    expect(up.attributes('aria-pressed')).toBe('false')

    await up.trigger('click')
    await flushPromises()
    expect(submitFeedbackMock).toHaveBeenCalledTimes(2)
    expect(up.attributes('aria-pressed')).toBe('true')
  })
})
