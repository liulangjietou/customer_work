import { defineStore } from 'pinia'
import type { RouteLocationNormalizedLoaded } from 'vue-router'
import router from '@/router'
import { useMenuStore } from '@/store/menu'
import type { MenuNode } from '@/types/api'

/** 首页固定常驻、不可关闭，作为"关完所有标签后兜底落脚点"。 */
const HOME_KEY = '/home'

export interface TabItem {
  /** 标签唯一标识，用完整路径（含 query），区分同一路由不同参数（如 workspace/:agentCode）。 */
  key: string
  title: string
  fullPath: string
  closable: boolean
}

/** 按 path 精确匹配菜单树，取节点展示名；分组节点、叶子节点都参与匹配。 */
function findTitleByPath(nodes: MenuNode[], path: string): string | undefined {
  for (const node of nodes) {
    if (node.path && node.path === path) {
      return node.name
    }
    if (node.children && node.children.length > 0) {
      const found = findTitleByPath(node.children, path)
      if (found) {
        return found
      }
    }
  }
  return undefined
}

interface TabsState {
  tabs: TabItem[]
  activeKey: string
}

export const useTabsStore = defineStore('tabs', {
  state: (): TabsState => ({
    tabs: [{ key: HOME_KEY, title: '首页', fullPath: HOME_KEY, closable: false }],
    activeKey: HOME_KEY,
  }),
  actions: {
    /** 路由变化时调用：已存在同 key 标签则只切换激活态，否则追加新标签。 */
    openTab(route: RouteLocationNormalizedLoaded) {
      const key = route.fullPath
      this.activeKey = key
      if (this.tabs.some((t) => t.key === key)) {
        return
      }
      const menuStore = useMenuStore()
      // SQL 报表菜单节点的 path 存的是含 query 的完整链接（/sql/query?defineKey=xxx），
      // 所以标题查找先按 fullPath 精确匹配；其它页面 node.path 不带 query，fullPath===path，
      // 这一步天然是原来的行为，找不到再按 path 匹配作为兜底，最后才落到路由元信息/路由名。
      const title =
        findTitleByPath(menuStore.tree, route.fullPath) ||
        findTitleByPath(menuStore.tree, route.path) ||
        (route.meta.title as string | undefined) ||
        (route.name ? String(route.name) : route.path)
      this.tabs.push({ key, title, fullPath: route.fullPath, closable: key !== HOME_KEY })
    },
    /** 关闭标签：若关的是当前激活标签，自动切到右侧相邻标签（没有则切左侧，都没有则回首页）。 */
    closeTab(key: string) {
      const idx = this.tabs.findIndex((t) => t.key === key)
      if (idx === -1) {
        return
      }
      this.tabs.splice(idx, 1)
      if (this.activeKey !== key) {
        return
      }
      const next = this.tabs[idx] || this.tabs[idx - 1]
      if (next) {
        this.activeKey = next.key
        router.push(next.fullPath)
      } else {
        this.activeKey = HOME_KEY
        router.push(HOME_KEY)
      }
    },
    /** 关闭除 key 外的所有可关闭标签（首页等 closable=false 的标签天然保留），并激活 key。 */
    closeOthers(key: string) {
      const target = this.tabs.find((t) => t.key === key)
      if (!target) {
        return
      }
      this.tabs = this.tabs.filter((t) => t.key === key || !t.closable)
      this.activeKey = key
      router.push(target.fullPath)
    },
    /** 关闭 key 右侧的全部标签；若当前激活标签在被关闭之列，则激活态回落到 key。 */
    closeToRight(key: string) {
      const idx = this.tabs.findIndex((t) => t.key === key)
      if (idx === -1) {
        return
      }
      const removed = this.tabs.slice(idx + 1)
      if (removed.length === 0) {
        return
      }
      this.tabs = this.tabs.slice(0, idx + 1)
      if (removed.some((t) => t.key === this.activeKey)) {
        this.activeKey = key
        router.push(this.tabs[idx].fullPath)
      }
    },
    reset() {
      this.tabs = [{ key: HOME_KEY, title: '首页', fullPath: HOME_KEY, closable: false }]
      this.activeKey = HOME_KEY
    },
  },
})
