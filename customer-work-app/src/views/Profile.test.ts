// @vitest-environment happy-dom
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { UserInfo } from '@/types/api'
import ProfileView from './Profile.vue'

const {
  authClearMock,
  fetchMeMock,
  replaceMock,
  revokeSessionsMock,
  showToastMock,
  socketCloseMock,
} = vi.hoisted(() => ({
  authClearMock: vi.fn(),
  fetchMeMock: vi.fn(),
  replaceMock: vi.fn(),
  revokeSessionsMock: vi.fn(),
  showToastMock: vi.fn(),
  socketCloseMock: vi.fn(),
}))

vi.mock('@/api/auth', () => ({ fetchMe: fetchMeMock, revokeSessions: revokeSessionsMock }))
vi.mock('@/store/auth', () => ({
  useAuthStore: () => ({ nickname: 'RichardFyoung', clear: authClearMock }),
}))
vi.mock('@/utils/ws', () => ({ chatSocket: { close: socketCloseMock } }))
vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn(), replace: replaceMock }),
}))
vi.mock('vant', () => ({ showToast: showToastMock }))

const profile: UserInfo = {
  userId: 'user-1',
  username: 'RichardFyoung',
  nickname: 'RichardFyoung',
  phone: '13800000000',
  avatarUrl: null,
}

const globalOptions = {
  stubs: {
    AppTabbar: true,
    UserAvatar: { template: '<span class="user-avatar-stub"></span>' },
    'van-icon': { template: '<i></i>' },
    'van-loading': { template: '<span></span>' },
  },
}

async function mountProfile() {
  const wrapper = mount(ProfileView, { attachTo: document.body, global: globalOptions })
  await flushPromises()
  return wrapper
}

async function openAndConfirmLogout(wrapper: Awaited<ReturnType<typeof mountProfile>>, label: string) {
  const action = wrapper.findAll('button').find((button) => button.text().includes(label))
  expect(action).toBeDefined()
  await action!.trigger('click')
  await wrapper.get('.sheet-button.danger').trigger('click')
  await flushPromises()
}

describe('Profile logout boundaries', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
    localStorage.clear()
    authClearMock.mockReset()
    fetchMeMock.mockReset().mockResolvedValue(profile)
    replaceMock.mockReset()
    revokeSessionsMock.mockReset()
    showToastMock.mockReset()
    socketCloseMock.mockReset()
  })

  it('退出当前设备不撤销其他会话，只删除旧密码记录并保留记住的用户名', async () => {
    localStorage.setItem('cw-remembered-credential', 'legacy-base64-password')
    localStorage.setItem('cw-remembered-username', 'RichardFyoung')
    const wrapper = await mountProfile()

    await openAndConfirmLogout(wrapper, '退出当前设备')

    expect(revokeSessionsMock).not.toHaveBeenCalled()
    expect(localStorage.getItem('cw-remembered-credential')).toBeNull()
    expect(localStorage.getItem('cw-remembered-username')).toBe('RichardFyoung')
    expect(authClearMock).toHaveBeenCalledOnce()
    expect(socketCloseMock).toHaveBeenCalledOnce()
    expect(replaceMock).toHaveBeenCalledWith('/login')
  })

  it('退出所有设备成功后先撤销服务端会话，再清理当前登录态', async () => {
    revokeSessionsMock.mockResolvedValue({ revoked: true, sessionEpoch: 2 })
    localStorage.setItem('cw-remembered-credential', 'legacy-base64-password')
    localStorage.setItem('cw-remembered-username', 'RichardFyoung')
    const wrapper = await mountProfile()

    await openAndConfirmLogout(wrapper, '退出所有设备')

    expect(revokeSessionsMock).toHaveBeenCalledOnce()
    expect(localStorage.getItem('cw-remembered-credential')).toBeNull()
    expect(localStorage.getItem('cw-remembered-username')).toBe('RichardFyoung')
    expect(authClearMock).toHaveBeenCalledOnce()
    expect(socketCloseMock).toHaveBeenCalledOnce()
    expect(replaceMock).toHaveBeenCalledWith('/login')
  })

  it('退出所有设备接口失败时保留登录态，并允许用户原地再次操作', async () => {
    revokeSessionsMock
      .mockRejectedValueOnce(new Error('revoke unavailable'))
      .mockResolvedValueOnce({ revoked: true, sessionEpoch: 3 })
    localStorage.setItem('cw-remembered-credential', 'legacy-base64-password')
    localStorage.setItem('cw-remembered-username', 'RichardFyoung')
    const wrapper = await mountProfile()

    await openAndConfirmLogout(wrapper, '退出所有设备')

    expect(revokeSessionsMock).toHaveBeenCalledOnce()
    expect(localStorage.getItem('cw-remembered-credential')).toBe('legacy-base64-password')
    expect(localStorage.getItem('cw-remembered-username')).toBe('RichardFyoung')
    expect(authClearMock).not.toHaveBeenCalled()
    expect(socketCloseMock).not.toHaveBeenCalled()
    expect(replaceMock).not.toHaveBeenCalled()
    expect(wrapper.get('.sheet-button.danger').attributes('disabled')).toBeUndefined()

    await wrapper.get('.sheet-button.danger').trigger('click')
    await flushPromises()

    expect(revokeSessionsMock).toHaveBeenCalledTimes(2)
    expect(authClearMock).toHaveBeenCalledOnce()
    expect(socketCloseMock).toHaveBeenCalledOnce()
    expect(replaceMock).toHaveBeenCalledWith('/login')
  })

  it('退出弹层圈闭焦点，Escape 关闭后将焦点归还触发按钮', async () => {
    const wrapper = await mountProfile()
    const trigger = wrapper.findAll('button').find((button) => button.text().includes('退出当前设备'))!
    const triggerElement = trigger.element as HTMLButtonElement
    triggerElement.focus()

    await trigger.trigger('click')
    await flushPromises()

    const confirm = wrapper.get('.sheet-button.danger')
    const cancel = wrapper.get('.sheet-button.secondary')
    expect(document.activeElement).toBe(confirm.element)
    expect(wrapper.get('.account-header').attributes('inert')).toBeDefined()

    await confirm.trigger('keydown', { key: 'Tab' })
    expect(document.activeElement).toBe(cancel.element)
    await cancel.trigger('keydown', { key: 'Tab', shiftKey: true })
    expect(document.activeElement).toBe(confirm.element)

    await confirm.trigger('keydown', { key: 'Escape' })
    await flushPromises()
    expect(wrapper.find('.logout-sheet').exists()).toBe(false)
    expect(document.activeElement).toBe(triggerElement)
  })
})
