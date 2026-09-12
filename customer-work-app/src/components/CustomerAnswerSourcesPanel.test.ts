// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type {
  CustomerAnswerSource,
  CustomerAnswerSourcePreview,
} from '@/types/customerAnswerSources'
import CustomerAnswerSourcesPanel from './CustomerAnswerSourcesPanel.vue'

enableAutoUnmount(afterEach)

// 单元测试只替换弹层容器；真实 Vant 弹层、遮罩与滚动在浏览器用例覆盖。
const PopupStub = defineComponent({
  props: { show: Boolean },
  emits: ['keydown', 'update:show'],
  setup(props, { attrs, slots, emit }) {
    return () =>
      props.show
        ? h(
            'div',
            {
              ...attrs,
              role: 'dialog',
              onKeydown: (event: KeyboardEvent) => emit('keydown', event),
            },
            slots.default?.(),
          )
        : null
  },
})

const { fetchSources, fetchPreview } = vi.hoisted(() => ({
  fetchSources: vi.fn(),
  fetchPreview: vi.fn(),
}))

vi.mock('@/api/customerAnswerSources', () => ({
  fetchCustomerAnswerSources: fetchSources,
  fetchCustomerAnswerSourcePreview: fetchPreview,
}))

const source: CustomerAnswerSource = {
  sourceIndex: 2,
  status: 'AVAILABLE',
  title: '退货办理说明',
  knowledgeBase: '售后政策',
  versionNo: 7,
  sourceVersion: '2026-09-01',
}
const preview: CustomerAnswerSourcePreview = {
  ...source,
  content: '退货需要核对签收时间。\n<img src="https://invalid.example/a.png">',
}
const unavailable: CustomerAnswerSourcePreview = {
  sourceIndex: 2,
  status: 'UNAVAILABLE',
  title: null,
  knowledgeBase: null,
  versionNo: null,
  sourceVersion: null,
  content: null,
}

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((complete) => {
    resolve = complete
  })
  return { promise, resolve }
}

function mountPanel(extra = {}) {
  return mount(CustomerAnswerSourcesPanel, {
    props: {
      open: true,
      sessionId: 'session-1',
      messageId: 'message-1',
      identityKey: 'user-1',
      ...extra,
    },
    attachTo: document.body,
    global: { stubs: { VanPopup: PopupStub } },
  })
}

describe('CustomerAnswerSourcesPanel', () => {
  beforeEach(() => {
    fetchSources.mockReset().mockResolvedValue([source])
    fetchPreview.mockReset().mockResolvedValue(preview)
  })

  afterEach(() => {
    document.body.replaceChildren()
  })

  it('打开时核对保存的来源，再按消息内索引读取历史段落，正文只显示文本', async () => {
    const wrapper = mountPanel()
    await flushPromises()
    expect(fetchSources).toHaveBeenCalledWith('session-1', 'message-1')
    expect(wrapper.text()).toContain('退货办理说明')
    expect(wrapper.text()).toContain('知识库版本 7')
    expect(wrapper.text()).toContain('来源版本 2026-09-01')
    await wrapper.get('[data-source-index="2"]').trigger('click')
    await flushPromises()
    expect(fetchPreview).toHaveBeenCalledWith('session-1', 'message-1', 2)
    expect(wrapper.get('pre').text()).toBe(preview.content)
    expect(wrapper.find('img').exists()).toBe(false)
    expect(wrapper.find('a').exists()).toBe(false)
    wrapper.unmount()
  })

  it('旧消息空列表与已失效来源分别显示，不补造原文入口或版本', async () => {
    fetchSources.mockResolvedValueOnce([])
    const wrapper = mountPanel()
    await flushPromises()
    expect(wrapper.text()).toContain('这条答复没有可打开的参考资料')
    expect(wrapper.find('[data-source-index]').exists()).toBe(false)
    fetchSources.mockResolvedValueOnce([
      unavailable,
      { ...source, sourceIndex: 3, title: null, versionNo: null, sourceVersion: null },
    ])
    await wrapper.get('[data-action="refresh-sources"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('资料已不可用')
    expect(wrapper.find('[data-source-index="2"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('知识库版本')
    expect(wrapper.text()).not.toContain('来源版本')
    expect(wrapper.text()).toContain('未命名文档')
    expect(fetchPreview).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it.each([404, 503])('列表 HTTP %s 不冒充空结果，原地重试重新读取', async (status) => {
    fetchSources.mockRejectedValueOnce({
      response: { status, data: { message: 'internal-trace-secret' } },
    })
    const wrapper = mountPanel()
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain(
      status === 404 ? '参考资料当前不可访问' : '参考资料暂时无法加载',
    )
    expect(wrapper.text()).not.toContain('没有可打开的参考资料')
    expect(wrapper.text()).not.toContain('internal-trace-secret')
    expect(wrapper.find('[data-action="refresh-sources"]').exists()).toBe(false)
    await wrapper.get('[data-action="retry"]').trigger('click')
    await flushPromises()
    expect(fetchSources).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain(source.title)
    wrapper.unmount()
  })

  it.each(['unavailable', 'not-found'])(
    '再次核对时先清正文与元数据，撤权 %s 不能恢复旧内容',
    async (outcome) => {
      const wrapper = mountPanel()
      await flushPromises()
      await wrapper.get('[data-source-index="2"]').trigger('click')
      await flushPromises()
      expect(wrapper.get('pre').text()).toContain('退货需要核对')
      const pending = deferred<CustomerAnswerSourcePreview>()
      fetchPreview.mockReturnValueOnce(pending.promise)
      await wrapper.get('[data-action="refresh-preview"]').trigger('click')
      expect(wrapper.find('pre').exists()).toBe(false)
      expect(wrapper.text()).not.toContain(source.title)
      pending.resolve(unavailable)
      await flushPromises()
      expect(wrapper.text()).toContain('资料已不可用')
      expect(wrapper.find('[data-action="refresh-preview"]').exists()).toBe(false)
      if (outcome === 'not-found') {
        fetchPreview.mockRejectedValueOnce({ response: { status: 404 } })
        await wrapper.get('[data-action="retry"]').trigger('click')
        await flushPromises()
        expect(wrapper.text()).toContain('参考资料当前不可访问')
      }
      expect(wrapper.find('pre').exists()).toBe(false)
      expect(wrapper.text()).not.toContain(source.knowledgeBase)
      wrapper.unmount()
    },
  )

  it('切换消息和会话立即清除旧正文，迟到响应不得串到新目标', async () => {
    const pending = deferred<CustomerAnswerSourcePreview>()
    fetchPreview.mockReturnValueOnce(pending.promise)
    const wrapper = mountPanel()
    await flushPromises()
    await wrapper.get('[data-source-index="2"]').trigger('click')
    fetchSources.mockResolvedValueOnce([{ ...source, title: '另一个会话的资料' }])
    await wrapper.setProps({ sessionId: 'session-2', messageId: 'message-2' })
    await flushPromises()
    pending.resolve(preview)
    await flushPromises()
    expect(fetchSources).toHaveBeenLastCalledWith('session-2', 'message-2')
    expect(wrapper.text()).toContain('另一个会话的资料')
    expect(wrapper.find('pre').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('退货需要核对')
    wrapper.unmount()
  })

  it('返回列表会清空正文并重新核验列表，不沿用旧标题', async () => {
    const wrapper = mountPanel()
    await flushPromises()
    await wrapper.get('[data-source-index="2"]').trigger('click')
    await flushPromises()
    const pending = deferred<CustomerAnswerSource[]>()
    fetchSources.mockReturnValueOnce(pending.promise)
    await wrapper.get('[data-action="back"]').trigger('click')
    expect(wrapper.find('pre').exists()).toBe(false)
    expect(wrapper.text()).not.toContain(source.title)
    pending.resolve([{ ...source, title: '重新核验的历史标题' }])
    await flushPromises()
    expect(fetchSources).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('重新核验的历史标题')
    expect(document.activeElement).toBe(wrapper.get('[data-source-index="2"]').element)
    wrapper.unmount()
  })

  it('Escape 关闭后恢复入口焦点，迟到列表不复活；再次打开重新读', async () => {
    const pending = deferred<CustomerAnswerSource[]>()
    fetchSources.mockReturnValueOnce(pending.promise)
    const trigger = document.createElement('button')
    document.body.appendChild(trigger)
    trigger.focus()
    const wrapper = mountPanel({ trigger })
    await flushPromises()
    await wrapper.get('[role="dialog"]').trigger('keydown', { key: 'Escape' })
    expect(wrapper.emitted('update:open')).toEqual([[false]])
    await wrapper.setProps({ open: false })
    pending.resolve([source])
    await flushPromises()
    expect(document.activeElement).toBe(trigger)
    expect(wrapper.text()).not.toContain(source.title)
    await wrapper.setProps({ open: true })
    await flushPromises()
    expect(fetchSources).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain(source.title)
    wrapper.unmount()
  })

  it('登录主体变化立即关闭并丢弃迟到原文', async () => {
    const pending = deferred<CustomerAnswerSourcePreview>()
    fetchPreview.mockReturnValueOnce(pending.promise)
    const wrapper = mountPanel()
    await flushPromises()
    await wrapper.get('[data-source-index="2"]').trigger('click')
    await wrapper.setProps({ identityKey: 'user-2' })
    pending.resolve(preview)
    await flushPromises()
    expect(wrapper.emitted('update:open')).toEqual([[false]])
    expect(wrapper.text()).not.toContain('退货需要核对')
    expect(fetchSources).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })
})
