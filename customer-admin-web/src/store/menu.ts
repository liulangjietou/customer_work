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
  routeDisposers: Array<() => void>
  routeGeneration: number
  refreshRequestId: number
  pollTimer: ReturnType<typeof setInterval> | null
  collapsed: boolean
}

export const useMenuStore = defineStore('menu', {
  state: (): MenuState => ({
    tree: [],
    version: -1,
    routesRegistered: false,
    routeDisposers: [],
    routeGeneration: 0,
    refreshRequestId: 0,
    pollTimer: null,
    collapsed: false,
  }),
  actions: {
    /** 登录后首次进入：拉权限点 + 菜单树 + 注册动态路由 + 起版本号轮询。 */
    async bootstrap() {
      const auth = useAuthStore()
      const generation = this.routeGeneration
      await auth.loadPermissions()
      if (generation !== this.routeGeneration) {
        return
      }
      const refreshed = await this.refreshMenu()
      if (!refreshed || generation !== this.routeGeneration) {
        return
      }
      this.routesRegistered = true
      this.startPolling()
    },
    /** 拉取最新菜单树并（重新）注册动态路由；智能体 CRUD/启停操作后主动调用，≤1s 内生效。 */
    async refreshMenu() {
      const generation = this.routeGeneration
      const requestId = ++this.refreshRequestId
      const [nextTree, nextVersion] = await Promise.all([fetchMenuRoutes(), fetchMenuVersion()])
      // reset 或更新的 refresh 已发生时，晚到的旧请求不得把上一账号/旧版本路由重新注册回来。
      if (generation !== this.routeGeneration || requestId !== this.refreshRequestId) {
        return false
      }

      this.unregisterRoutes()
      this.tree = nextTree
      try {
        this.registerRoutes(nextTree)
        this.version = nextVersion
        return true
      } catch (error) {
        // 路由投影必须整体成功；部分注册失败时回收本轮已注册项，避免留下不可见的权限入口。
        this.unregisterRoutes()
        this.tree = []
        this.version = -1
        this.routesRegistered = false
        throw error
      }
    },
    registerRoutes(nodes: MenuNode[]) {
      const seenNames = new Set<string>()
      const seenPaths = new Set<string>()
      const visit = (items: MenuNode[]) => {
        for (const node of items) {
          if (node.children && node.children.length > 0) {
            visit(node.children)
            continue
          }
          if (node.dynamic || !node.path) {
            continue // 动态智能体节点复用 router/index.ts 里已注册的通配路由 workspace/:agentCode
          }
          const loader = staticRouteComponents[node.path]
          if (!loader) {
            continue
          }
          const routeName = `menu-${node.id}`
          const routePath = node.path.replace(/^\//, '')
          if (seenNames.has(routeName) || seenPaths.has(routePath)) {
            continue
          }
          seenNames.add(routeName)
          seenPaths.add(routePath)
          const disposeRoute = router.addRoute('Layout', {
            path: routePath,
            name: routeName,
            component: loader,
            meta: { title: node.name },
          })
          this.routeDisposers.push(disposeRoute)
        }
      }
      visit(nodes)
    },
    /** 仅注销本 store 动态注册的菜单路由，固定 Home/Workspace/SqlQuery 路由不在此生命周期内。 */
    unregisterRoutes() {
      for (let index = this.routeDisposers.length - 1; index >= 0; index -= 1) {
        this.routeDisposers[index]()
      }
      this.routeDisposers = []
    },
    startPolling() {
      this.stopPolling()
      this.pollTimer = setInterval(async () => {
        const generation = this.routeGeneration
        try {
          const latest = await fetchMenuVersion()
          // stopPolling 无法取消已经发出的请求；会话 reset 后，旧轮询不能再触发一次 refresh。
          if (generation !== this.routeGeneration || !this.routesRegistered) {
            return
          }
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
      // 先使所有在途 refresh 失效，再回收已注册路由，防止旧响应晚到后复活上一账号入口。
      this.routeGeneration += 1
      this.refreshRequestId += 1
      this.unregisterRoutes()
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
