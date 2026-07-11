import { defineStore } from 'pinia'
import router from '@/router'
import { fetchMenuRoutes, fetchMenuVersion } from '@/api/menu'
import { staticRouteComponents } from '@/router/component-map'
import { useAuthStore } from '@/store/auth'
import type { MenuNode } from '@/types/api'

const POLL_INTERVAL_MS = 2000

interface MenuState {
  tree: MenuNode[]
  version: number
  routesRegistered: boolean
  pollTimer: ReturnType<typeof setInterval> | null
  collapsed: boolean
}

export const useMenuStore = defineStore('menu', {
  state: (): MenuState => ({
    tree: [],
    version: -1,
    routesRegistered: false,
    pollTimer: null,
    collapsed: false,
  }),
  actions: {
    /** 登录后首次进入：拉权限点 + 菜单树 + 注册动态路由 + 起版本号轮询。 */
    async bootstrap() {
      const auth = useAuthStore()
      await auth.loadPermissions()
      await this.refreshMenu()
      this.routesRegistered = true
      this.startPolling()
    },
    /** 拉取最新菜单树并（重新）注册动态路由；智能体 CRUD/启停操作后主动调用，≤1s 内生效。 */
    async refreshMenu() {
      this.tree = await fetchMenuRoutes()
      this.registerRoutes(this.tree)
      this.version = await fetchMenuVersion()
    },
    registerRoutes(nodes: MenuNode[]) {
      for (const node of nodes) {
        if (node.children && node.children.length > 0) {
          this.registerRoutes(node.children)
          continue
        }
        if (node.dynamic || !node.path) {
          continue // 动态智能体节点复用 router/index.ts 里已注册的通配路由 workspace/:agentCode
        }
        const loader = staticRouteComponents[node.path]
        if (!loader) {
          continue
        }
        router.addRoute('Layout', {
          path: node.path.replace(/^\//, ''),
          name: `menu-${node.id}`,
          component: loader,
          meta: { title: node.name },
        })
      }
    },
    startPolling() {
      this.stopPolling()
      this.pollTimer = setInterval(async () => {
        try {
          const latest = await fetchMenuVersion()
          if (latest !== this.version) {
            await this.refreshMenu()
          }
        } catch {
          // 轮询失败静默忽略，不打断已登录用户的当前操作
        }
      }, POLL_INTERVAL_MS)
    },
    stopPolling() {
      if (this.pollTimer) {
        clearInterval(this.pollTimer)
        this.pollTimer = null
      }
    },
    reset() {
      this.tree = []
      this.version = -1
      this.routesRegistered = false
      this.stopPolling()
      this.collapsed = false
    },
    toggleCollapsed() {
      this.collapsed = !this.collapsed
    },
  },
})
