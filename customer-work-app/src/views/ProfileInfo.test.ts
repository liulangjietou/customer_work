// @vitest-environment happy-dom
import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { UserInfo } from '@/types/api'
import ProfileInfoView from './ProfileInfo.vue'

const { fetchMeMock, showToastMock, uploadAvatarMock } = vi.hoisted(() => ({
  fetchMeMock: vi.fn(),
  showToastMock: vi.fn(),
  uploadAvatarMock: vi.fn(),
}))

vi.mock('@/api/auth', () => ({ fetchMe: fetchMeMock, uploadAvatar: uploadAvatarMock }))
vi.mock('vue-router', () => ({ useRouter: () => ({ back: vi.fn() }) }))
vi.mock('vant', () => ({ showToast: showToastMock }))

const UserAvatarStub = defineComponent({
  name: 'UserAvatar',
  props: { src: { type: String, default: '' }, name: { type: String, default: '' } },
  setup(props) {
    return () => h('span', { class: 'user-avatar-stub', 'data-src': props.src }, props.name)
  },
})

const globalOptions = {
  config: { errorHandler: vi.fn() },
  stubs: {
    UserAvatar: UserAvatarStub,
    'van-icon': { template: '<i></i>' },
    'van-loading': { template: '<span></span>' },
  },
}

const profile: UserInfo = {
  userId: 'user-1',
  username: 'RichardFyoung',
  nickname: 'RichardFyoung',
  phone: '13800000000',
  avatarUrl: 'https://example.test/old-avatar.png',
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

async function mountProfileInfo() {
  const wrapper = mount(ProfileInfoView, { global: globalOptions })
  await flushPromises()
  return wrapper
}

async function selectAvatar(
  wrapper: Awaited<ReturnType<typeof mountProfileInfo>>,
  file: File,
) {
  const input = wrapper.get('.avatar-upload-input')
  Object.defineProperty(input.element, 'files', { configurable: true, value: [file] })
  await input.trigger('change')
  await flushPromises()
  return input
}

describe('ProfileInfo avatar upload', () => {
  beforeEach(() => {
    fetchMeMock.mockReset().mockResolvedValue(profile)
    showToastMock.mockReset()
    uploadAvatarMock.mockReset()
  })

  it('文件选择器有可读名称，并在前端拦截非图片与超限图片', async () => {
    const wrapper = await mountProfileInfo()
    const input = wrapper.get('.avatar-upload-input')
    expect(input.attributes('aria-label')).toBe('更换头像')

    await selectAvatar(wrapper, new File(['not an image'], 'avatar.txt', { type: 'text/plain' }))
    await selectAvatar(
      wrapper,
      new File([new Uint8Array(2 * 1024 * 1024 + 1)], 'avatar.png', { type: 'image/png' }),
    )

    expect(uploadAvatarMock).not.toHaveBeenCalled()
    expect(showToastMock).toHaveBeenNthCalledWith(1, '仅支持 png/jpg/jpeg/gif 格式')
    expect(showToastMock).toHaveBeenNthCalledWith(2, '图片大小不能超过 2MB')
  })

  it('合法图片上传期间禁用选择器，成功后立即更新头像', async () => {
    const upload = deferred<string>()
    uploadAvatarMock.mockReturnValue(upload.promise)
    const wrapper = await mountProfileInfo()
    const file = new File(['image'], 'avatar.png', { type: 'image/png' })
    const input = wrapper.get('.avatar-upload-input')
    Object.defineProperty(input.element, 'files', { configurable: true, value: [file] })

    await input.trigger('change')
    expect(input.attributes('disabled')).toBeDefined()

    upload.resolve('https://example.test/new-avatar.png')
    await flushPromises()

    expect(uploadAvatarMock).toHaveBeenCalledWith(file)
    expect(input.attributes('disabled')).toBeUndefined()
    expect(wrapper.get('.user-avatar-stub').attributes('data-src')).toBe('https://example.test/new-avatar.png')
    expect(showToastMock).toHaveBeenCalledWith('头像已更新')
  })

  it('上传失败会解除提交锁，且允许再次选择同一文件', async () => {
    uploadAvatarMock.mockRejectedValueOnce(new Error('upload unavailable')).mockResolvedValueOnce('new-avatar.png')
    const wrapper = await mountProfileInfo()
    const file = new File(['image'], 'avatar.png', { type: 'image/png' })

    const input = await selectAvatar(wrapper, file)
    expect(input.attributes('disabled')).toBeUndefined()

    await selectAvatar(wrapper, file)
    expect(uploadAvatarMock).toHaveBeenCalledTimes(2)
    expect(wrapper.get('.user-avatar-stub').attributes('data-src')).toBe('new-avatar.png')
  })
})
