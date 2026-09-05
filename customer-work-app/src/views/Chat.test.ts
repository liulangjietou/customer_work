// @vitest-environment happy-dom
import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { ChatMessage, FeedbackType, TicketDetail, UserQuota } from '@/types/api'
import ChatView from './Chat.vue'

const {
  fetchMessagesMock,
  fetchMyQuotaMock,
  fetchSessionFeedbackMock,
  fetchTicketDetailMock,
  uploadAttachmentMock,
  sendMock,
  submitFeedbackMock,
  wsHandlers,
} = vi.hoisted(() => ({
  fetchMessagesMock: vi.fn(),
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
    sendMock.mockReset()
    submitFeedbackMock
      .mockReset()
      .mockImplementation(({ type }: { type: FeedbackType }) =>
        Promise.resolve(savedFeedback(type)),
      )
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

  it('发送前裁剪空格，使用当前会话构造 WebSocket 帧，并立即更新本地消息', async () => {
    const wrapper = await mountReadyChat()
    const input = wrapper.get('.message-input')

    await input.setValue('  请帮我查询物流  ')
    await wrapper.get('.send-button').trigger('click')
    await flushPromises()

    expect(sendMock).toHaveBeenCalledTimes(1)
    expect(sendMock).toHaveBeenCalledWith({
      type: 'chat',
      data: { sessionId: 'session-1', content: '请帮我查询物流' },
    })
    expect(wrapper.text()).toContain('请帮我查询物流')
    expect(wrapper.findAll('.row-USER')).toHaveLength(1)
    expect((input.element as HTMLInputElement).value).toBe('')
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
