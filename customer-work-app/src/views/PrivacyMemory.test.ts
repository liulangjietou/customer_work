// @vitest-environment happy-dom
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { MemoryConsent, MemoryList } from '@/types/api'
import PrivacyMemoryView from './PrivacyMemory.vue'

const {
  fetchMemoryConsentMock,
  updateMemoryConsentMock,
  fetchMyMemoriesMock,
  deleteMyMemoriesMock,
  showToastMock,
  showConfirmDialogMock,
} = vi.hoisted(() => ({
  fetchMemoryConsentMock: vi.fn(),
  updateMemoryConsentMock: vi.fn(),
  fetchMyMemoriesMock: vi.fn(),
  deleteMyMemoriesMock: vi.fn(),
  showToastMock: vi.fn(),
  showConfirmDialogMock: vi.fn(),
}))

vi.mock('@/api/privacy', () => ({
  MEMORY_CONSENT_VERSION: 'v1',
  fetchMemoryConsent: fetchMemoryConsentMock,
  updateMemoryConsent: updateMemoryConsentMock,
  fetchMyMemories: fetchMyMemoriesMock,
  deleteMyMemories: deleteMyMemoriesMock,
}))
vi.mock('vue-router', () => ({ useRouter: () => ({ back: vi.fn() }) }))
vi.mock('vant', () => ({ showToast: showToastMock, showConfirmDialog: showConfirmDialogMock }))

const globalOptions = {
  config: { errorHandler: vi.fn() },
  stubs: {
    'van-nav-bar': { template: '<div></div>' },
    'van-loading': { template: '<span></span>' },
    'van-cell-group': { template: '<div><slot /></div>' },
    'van-cell': {
      props: ['title'],
      template: '<div class="cell"><span class="cell-title">{{ title }}</span><slot name="label" /><slot name="right-icon" /></div>',
    },
    'van-tag': { template: '<span><slot /></span>' },
    'van-button': { template: '<button @click="$emit(\'click\')"><slot /></button>' },
    'van-switch': {
      props: ['modelValue'],
      template: '<button class="switch" @click="$emit(\'update:modelValue\', !modelValue)"></button>',
    },
  },
}

function consent(granted: boolean): MemoryConsent {
  return {
    granted,
    consentVersion: granted ? 'v1' : null,
    grantedAtMs: granted ? 1 : null,
    withdrawnAtMs: null,
    updatedAtMs: 1,
  }
}

const memories: MemoryList = { memories: ['常用地址：望京'], facts: ['偏好短回复'], count: 2 }

describe('PrivacyMemory', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    showConfirmDialogMock.mockResolvedValue(undefined)
  })

  /**
   * 这个页面存在的理由：生产强制 consent-required，服务端查不到同意记录时 fail-closed，
   * 而此前 H5 没有任何一处调用授权接口——长期记忆整条链路在生产静默空转。
   */
  it('进入页面即查询同意状态', async () => {
    fetchMemoryConsentMock.mockResolvedValue(consent(false))

    mount(PrivacyMemoryView, { global: globalOptions })
    await flushPromises()

    expect(fetchMemoryConsentMock).toHaveBeenCalledTimes(1)
  })

  it('未授权时不请求记忆内容', async () => {
    fetchMemoryConsentMock.mockResolvedValue(consent(false))

    mount(PrivacyMemoryView, { global: globalOptions })
    await flushPromises()

    expect(fetchMyMemoriesMock).not.toHaveBeenCalled()
  })

  it('已授权时加载已记住的内容', async () => {
    fetchMemoryConsentMock.mockResolvedValue(consent(true))
    fetchMyMemoriesMock.mockResolvedValue(memories)

    const wrapper = mount(PrivacyMemoryView, { global: globalOptions })
    await flushPromises()

    expect(fetchMyMemoriesMock).toHaveBeenCalled()
    expect(wrapper.text()).toContain('常用地址：望京')
  })

  it('开启授权会调用后端并刷新内容', async () => {
    fetchMemoryConsentMock.mockResolvedValue(consent(false))
    updateMemoryConsentMock.mockResolvedValue(consent(true))
    fetchMyMemoriesMock.mockResolvedValue(memories)

    const wrapper = mount(PrivacyMemoryView, { global: globalOptions })
    await flushPromises()
    await wrapper.find('.switch').trigger('click')
    await flushPromises()

    expect(updateMemoryConsentMock).toHaveBeenCalledWith(true)
    expect(showToastMock).toHaveBeenCalledWith('已开启个性化记忆')
  })

  /** 撤回是真删除，不是只关一个开关位——必须先让用户确认。 */
  it('关闭授权前必须二次确认', async () => {
    fetchMemoryConsentMock.mockResolvedValue(consent(true))
    fetchMyMemoriesMock.mockResolvedValue(memories)
    updateMemoryConsentMock.mockResolvedValue(consent(false))

    const wrapper = mount(PrivacyMemoryView, { global: globalOptions })
    await flushPromises()
    await wrapper.find('.switch').trigger('click')
    await flushPromises()

    expect(showConfirmDialogMock).toHaveBeenCalled()
    expect(updateMemoryConsentMock).toHaveBeenCalledWith(false)
  })

  it('用户取消确认时不调用后端', async () => {
    fetchMemoryConsentMock.mockResolvedValue(consent(true))
    fetchMyMemoriesMock.mockResolvedValue(memories)
    showConfirmDialogMock.mockRejectedValue(new Error('cancel'))

    const wrapper = mount(PrivacyMemoryView, { global: globalOptions })
    await flushPromises()
    await wrapper.find('.switch').trigger('click')
    await flushPromises()

    expect(updateMemoryConsentMock).not.toHaveBeenCalled()
  })

  it('加载失败时给出重试入口', async () => {
    fetchMemoryConsentMock.mockRejectedValue(new Error('network'))

    const wrapper = mount(PrivacyMemoryView, { global: globalOptions })
    await flushPromises()

    expect(wrapper.text()).toContain('加载失败')
  })
})
