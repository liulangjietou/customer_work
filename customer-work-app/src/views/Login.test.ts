// @vitest-environment happy-dom
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import LoginView from './Login.vue'

const { applyLoginMock, loginMock, replaceMock } = vi.hoisted(() => ({
  applyLoginMock: vi.fn(),
  loginMock: vi.fn(),
  replaceMock: vi.fn(),
}))

vi.mock('@/api/auth', () => ({ login: loginMock }))
vi.mock('@/store/auth', () => ({ useAuthStore: () => ({ applyLogin: applyLoginMock }) }))
vi.mock('vue-router', () => ({
  useRouter: () => ({ currentRoute: { value: { query: {} } }, replace: replaceMock }),
}))
vi.mock('vant', () => ({ showToast: vi.fn() }))

const globalOptions = {
  stubs: {
    'van-form': {
      emits: ['submit'],
      template: '<form @submit.prevent="$emit(\'submit\')"><slot /></form>',
    },
    'van-field': {
      props: ['modelValue', 'name', 'type', 'id'],
      emits: ['update:modelValue'],
      template:
        '<input :id="id" :name="name" :type="type || \'text\'" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
    },
    'van-checkbox': {
      props: ['modelValue'],
      emits: ['update:modelValue'],
      template:
        '<label><input type="checkbox" :checked="modelValue" @change="$emit(\'update:modelValue\', $event.target.checked)" /><slot /></label>',
    },
    'van-button': {
      props: ['nativeType'],
      template: '<button :type="nativeType || \'button\'"><slot /></button>',
    },
    'router-link': { template: '<a><slot /></a>' },
  },
}

function storedValues(): string[] {
  return Array.from({ length: localStorage.length }, (_, index) => localStorage.getItem(localStorage.key(index) || '') || '')
}

describe('Login credential persistence', () => {
  beforeEach(() => {
    localStorage.clear()
    applyLoginMock.mockReset()
    loginMock.mockReset()
    replaceMock.mockReset()
  })

  it('迁移旧凭据时只保留用户名并立即删除旧密码记录', async () => {
    localStorage.setItem(
      'cw-remembered-credential',
      window.btoa(JSON.stringify({ username: 'RichardFyoung', password: 'legacy-secret' })),
    )

    const wrapper = mount(LoginView, { global: globalOptions })
    await flushPromises()

    expect(wrapper.get('input[name="username"]').element).toHaveProperty('value', 'RichardFyoung')
    expect(wrapper.get('input[name="password"]').element).toHaveProperty('value', '')
    expect(localStorage.getItem('cw-remembered-credential')).toBeNull()
    expect(localStorage.getItem('cw-remembered-username')).toBe('RichardFyoung')
    expect(storedValues().join('|')).not.toContain('legacy-secret')
    expect(wrapper.text()).toContain('记住用户名')
    expect(wrapper.text()).not.toContain('记住用户名和密码')
  })

  it('登录后仅持久化用户名，密码不会写入 localStorage', async () => {
    loginMock.mockResolvedValue({
      token: 'token-1',
      userId: 'user-1',
      nickname: 'RichardFyoung',
      expiresAtMs: Date.now() + 60_000,
    })
    const wrapper = mount(LoginView, { global: globalOptions })
    await wrapper.get('input[name="username"]').setValue('RichardFyoung')
    await wrapper.get('input[name="password"]').setValue('new-secret')
    await wrapper.get('input[type="checkbox"]').setValue(true)

    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(loginMock).toHaveBeenCalledWith({ username: 'RichardFyoung', password: 'new-secret' })
    expect(localStorage.getItem('cw-remembered-username')).toBe('RichardFyoung')
    expect(localStorage.getItem('cw-remembered-credential')).toBeNull()
    expect(storedValues().join('|')).not.toContain('new-secret')
    expect(applyLoginMock).toHaveBeenCalledWith('token-1', 'user-1', 'RichardFyoung')
    expect(replaceMock).toHaveBeenCalledWith('/messages')
  })

  it('新旧记录同时存在时优先新用户名并清除旧密码记录', async () => {
    localStorage.setItem('cw-remembered-username', 'CurrentUser')
    localStorage.setItem(
      'cw-remembered-credential',
      window.btoa(JSON.stringify({ username: 'LegacyUser', password: 'legacy-secret' })),
    )

    const wrapper = mount(LoginView, { global: globalOptions })
    await flushPromises()

    expect(wrapper.get('input[name="username"]').element).toHaveProperty('value', 'CurrentUser')
    expect(wrapper.get('input[name="password"]').element).toHaveProperty('value', '')
    expect(localStorage.getItem('cw-remembered-credential')).toBeNull()
    expect(localStorage.getItem('cw-remembered-username')).toBe('CurrentUser')
    expect(storedValues().join('|')).not.toContain('legacy-secret')
  })

  it('兼容迁移旧版本 UTF-8 中文用户名且不恢复密码', async () => {
    const legacyJson = JSON.stringify({ username: '小明', password: '中文密码' })
    const legacyBytes = new TextEncoder().encode(legacyJson)
    const legacyBase64 = window.btoa(String.fromCharCode(...legacyBytes))
    localStorage.setItem('cw-remembered-credential', legacyBase64)

    const wrapper = mount(LoginView, { global: globalOptions })
    await flushPromises()

    expect(wrapper.get('input[name="username"]').element).toHaveProperty('value', '小明')
    expect(wrapper.get('input[name="password"]').element).toHaveProperty('value', '')
    expect(localStorage.getItem('cw-remembered-username')).toBe('小明')
    expect(localStorage.getItem('cw-remembered-credential')).toBeNull()
    expect(storedValues().join('|')).not.toContain('中文密码')
  })
})
