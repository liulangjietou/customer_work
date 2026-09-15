import { createPinia, setActivePinia } from 'pinia'
import { createVNode, type App, type DirectiveBinding, type ObjectDirective, type VNode } from 'vue'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { useAuthStore } from '@/store/auth'
import { installPermissionDirective } from './permission'

vi.mock('@/api/auth', () => ({ fetchMyPermissions: vi.fn(), logout: vi.fn() }))

let directive: ObjectDirective<HTMLElement, string>
let element: HTMLElement
let binding: DirectiveBinding<string>
let attributes: Set<string>
const vnode = createVNode('button') as VNode<Node, HTMLElement>

beforeEach(() => {
  vi.stubGlobal('localStorage', { getItem: () => null })
  setActivePinia(createPinia())
  installPermissionDirective({ directive: (_name: string, installed: ObjectDirective<HTMLElement, string>) => {
    directive = installed
  } } as unknown as App)
  attributes = new Set()
  element = { toggleAttribute: vi.fn((name: string, force: boolean) => {
    if (force) attributes.add(name)
    else attributes.delete(name)
    return force
  }) } as unknown as HTMLElement
  binding = { value: 'role:add', oldValue: null, arg: undefined, modifiers: {},
    instance: null, dir: directive }
  directive.mounted!(element, binding, vnode, null)
})
afterEach(() => {
  directive.beforeUnmount!(element, binding, vnode, null)
  vi.unstubAllGlobals()
})

it('入口随授权和撤销更新，不依赖重新挂载', () => {
  expect(attributes.has('data-permission-denied')).toBe(true)
  useAuthStore().permissions = ['role:add']
  expect(attributes.has('data-permission-denied')).toBe(false)
  useAuthStore().permissions = ['role:view']
  expect(attributes.has('data-permission-denied')).toBe(true)
})

it('绑定的权限代码变化后按新权限判断', () => {
  useAuthStore().permissions = ['role:add']
  directive.updated!(element, { ...binding, value: 'role:delete' }, vnode, vnode)
  expect(attributes.has('data-permission-denied')).toBe(true)
  useAuthStore().permissions = ['role:delete']
  expect(attributes.has('data-permission-denied')).toBe(false)
})

it('卸载入口后停止观察权限，旧节点不再收到更新', () => {
  directive.beforeUnmount!(element, binding, vnode, null)
  const calls = vi.mocked(element.toggleAttribute).mock.calls.length
  useAuthStore().permissions = ['role:add']
  expect(vi.mocked(element.toggleAttribute).mock.calls).toHaveLength(calls)
})
