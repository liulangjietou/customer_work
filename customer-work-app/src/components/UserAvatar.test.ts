// @vitest-environment happy-dom
import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import UserAvatar from './UserAvatar.vue'

const globalOptions = {
  stubs: {
    'van-icon': { template: '<i data-testid="fallback-icon"></i>' },
  },
}

describe('UserAvatar', () => {
  it('没有图片时用姓名首字母提供稳定兜底', () => {
    const wrapper = mount(UserAvatar, {
      props: { name: 'Richard Fyoung' },
      global: globalOptions,
    })

    expect(wrapper.find('img').exists()).toBe(false)
    expect(wrapper.get('.avatar-initials').text()).toBe('RF')
  })

  it('图片加载失败后切换为姓名兜底', async () => {
    const wrapper = mount(UserAvatar, {
      props: { src: '/broken-avatar.png', name: 'Richard Fyoung' },
      global: globalOptions,
    })

    await wrapper.get('img').trigger('error')

    expect(wrapper.find('img').exists()).toBe(false)
    expect(wrapper.get('.avatar-initials').text()).toBe('RF')
  })

  it('头像地址更新后会重新尝试加载新图片', async () => {
    const wrapper = mount(UserAvatar, {
      props: { src: '/broken-avatar.png', name: 'Richard Fyoung' },
      global: globalOptions,
    })
    await wrapper.get('img').trigger('error')

    await wrapper.setProps({ src: '/new-avatar.png' })

    expect(wrapper.get('img').attributes('src')).toBe('/new-avatar.png')
  })

  it('姓名也为空时展示通用用户图标', () => {
    const wrapper = mount(UserAvatar, { global: globalOptions })

    expect(wrapper.find('[data-testid="fallback-icon"]').exists()).toBe(true)
  })
})
