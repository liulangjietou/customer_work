import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import type { MenuNode } from '@/types/api'

const mocks = vi.hoisted(() => ({
  addRoute: vi.fn(),
  fetchMenuRoutes: vi.fn(),
  fetchMenuVersion: vi.fn(),
  loadPermissions: vi.fn(),
}))

vi.mock('@/router', () => ({
  default: { addRoute: mocks.addRoute },
}))

vi.mock('@/api/menu', () => ({
  fetchMenuRoutes: mocks.fetchMenuRoutes,
  fetchMenuVersion: mocks.fetchMenuVersion,
}))

vi.mock('@/store/auth', () => ({
  useAuthStore: () => ({ loadPermissions: mocks.loadPermissions }),
}))

import { useMenuStore } from './menu'

function menuNode(id: number, path: string, children: MenuNode[] = []): MenuNode {
  return {
    id,
    name: `菜单${id}`,
    path,
    icon: null,
    iconType: null,
    permCode: null,
    sort: id,
    agentCode: null,
    capabilities: null,
    dynamic: false,
    children,
  }
}

describe('menu store 动态路由生命周期', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('刷新时注销旧路由，并按名称和路径去重注册新投影', async () => {
    const disposers: Array<ReturnType<typeof vi.fn>> = []
    mocks.addRoute.mockImplementation(() => {
      const dispose = vi.fn()
      disposers.push(dispose)
      return dispose
    })
    mocks.fetchMenuRoutes
      .mockResolvedValueOnce([
        menuNode(1, '/system/user'),
        menuNode(2, '/system/user'),
        menuNode(1, '/system/role'),
      ])
      .mockResolvedValueOnce([menuNode(3, '/system/role')])
    mocks.fetchMenuVersion.mockResolvedValueOnce(1).mockResolvedValueOnce(2)

    const store = useMenuStore()
    await expect(store.refreshMenu()).resolves.toBe(true)

    expect(mocks.addRoute).toHaveBeenCalledTimes(1)
    expect(mocks.addRoute).toHaveBeenLastCalledWith('Layout', expect.objectContaining({
      path: 'system/user',
      name: 'menu-1',
    }))

    await expect(store.refreshMenu()).resolves.toBe(true)

    expect(disposers[0]).toHaveBeenCalledOnce()
    expect(mocks.addRoute).toHaveBeenCalledTimes(2)
    expect(mocks.addRoute).toHaveBeenLastCalledWith('Layout', expect.objectContaining({
      path: 'system/role',
      name: 'menu-3',
    }))

    store.reset()
    expect(disposers[1]).toHaveBeenCalledOnce()
    expect(store.routeDisposers).toEqual([])
  })

  it('reset 后忽略晚到的旧请求，不能复活上一会话路由', async () => {
    let resolveRoutes!: (nodes: MenuNode[]) => void
    mocks.fetchMenuRoutes.mockReturnValue(new Promise<MenuNode[]>((resolve) => {
      resolveRoutes = resolve
    }))
    mocks.fetchMenuVersion.mockResolvedValue(1)
    mocks.addRoute.mockReturnValue(vi.fn())

    const store = useMenuStore()
    const refreshing = store.refreshMenu()
    store.reset()
    resolveRoutes([menuNode(1, '/system/user')])

    await expect(refreshing).resolves.toBe(false)
    expect(mocks.addRoute).not.toHaveBeenCalled()
    expect(store.tree).toEqual([])
    expect(store.routesRegistered).toBe(false)
  })

  it('本轮部分注册失败时回收已添加路由并保持 fail-closed', async () => {
    const firstDisposer = vi.fn()
    mocks.addRoute
      .mockReturnValueOnce(firstDisposer)
      .mockImplementationOnce(() => {
        throw new Error('route registration failed')
      })
    mocks.fetchMenuRoutes.mockResolvedValue([
      menuNode(1, '/system/user'),
      menuNode(2, '/system/role'),
    ])
    mocks.fetchMenuVersion.mockResolvedValue(1)

    const store = useMenuStore()
    await expect(store.refreshMenu()).rejects.toThrow('route registration failed')

    expect(firstDisposer).toHaveBeenCalledOnce()
    expect(store.tree).toEqual([])
    expect(store.version).toBe(-1)
    expect(store.routesRegistered).toBe(false)
    expect(store.routeDisposers).toEqual([])
  })
})
