import type { MenuNode } from '@/types/api'

/**
 * 全局导航按智能体生命周期重新分组。这里仅改变展示结构，原始菜单节点仍由后端完成权限剪枝、
 * 对外部署过滤和动态智能体注入，前端不得在此补权限或制造新业务路由。
 */
export type NavigationSectionKey =
  | 'overview'
  | 'agents'
  | 'build'
  | 'operate'
  | 'govern'
  | 'settings'

interface NavigationSectionDefinition {
  key: NavigationSectionKey
  label: string
  title: string
  icon: string
  searchPlaceholder: string
}

export interface NavigationSection extends NavigationSectionDefinition {
  /** 后端原始一级节点，用于按当前路由反推生命周期分区。 */
  sourceNodes: MenuNode[]
  /** 上下文菜单实际展示的节点；智能体分区会把 workspace 的动态子节点提升一层。 */
  menuNodes: MenuNode[]
  /** 导航轨上展示的业务入口数量。 */
  itemCount: number
}

export interface NavigationCommand {
  key: string
  title: string
  path: string
  sectionKey: NavigationSectionKey
  sectionTitle: string
  trail: string[]
  dynamic: boolean
  keywords: string
}

const SECTION_DEFINITIONS: readonly NavigationSectionDefinition[] = [
  { key: 'overview', label: '总览', title: '工作总览', icon: 'Grid', searchPlaceholder: '查找工作入口' },
  { key: 'agents', label: '智能体', title: '智能体工作区', icon: 'Cpu', searchPlaceholder: '查找智能体' },
  { key: 'build', label: '构建', title: '构建与配置', icon: 'Tools', searchPlaceholder: '查找构建能力' },
  { key: 'operate', label: '运营', title: '监控与运营', icon: 'DataAnalysis', searchPlaceholder: '查找运营能力' },
  { key: 'govern', label: '治理', title: '安全治理', icon: 'Lock', searchPlaceholder: '查找治理能力' },
  { key: 'settings', label: '设置', title: '平台设置', icon: 'Setting', searchPlaceholder: '查找系统设置' },
]

/** 后端一级菜单的稳定权限码到生命周期分区的映射，不依赖可编辑的中文名称。 */
const ROOT_SECTION_BY_PERM_CODE: Readonly<Record<string, NavigationSectionKey>> = {
  workbench: 'overview',
  workspace: 'agents',
  aiconfig: 'build',
  project: 'build',
  'sql-config': 'build',
  'monitor:view': 'operate',
  ops: 'operate',
  ticket: 'operate',
  contentguard: 'govern',
  system: 'settings',
}

const HOME_NODE: MenuNode = {
  id: -1,
  name: '首页',
  path: '/home',
  icon: 'House',
  iconType: 'library',
  permCode: '__home',
  sort: -1,
  agentCode: null,
  capabilities: null,
  dynamic: false,
  children: [],
}

function sectionKeyForPath(path: string): NavigationSectionKey {
  if (path.startsWith('/workspace')) return 'agents'
  if (path.startsWith('/aiconfig') || path.startsWith('/project') || path.startsWith('/sql')) return 'build'
  if (path.startsWith('/monitor') || path.startsWith('/ops') || path.startsWith('/ticket')) return 'operate'
  if (path.startsWith('/contentguard')) return 'govern'
  if (path.startsWith('/system')) return 'settings'
  return 'overview'
}

function sectionKeyForRoot(node: MenuNode): NavigationSectionKey {
  if (node.permCode && ROOT_SECTION_BY_PERM_CODE[node.permCode]) {
    return ROOT_SECTION_BY_PERM_CODE[node.permCode]
  }

  // 管理员可在菜单管理中新增一级目录。权限码尚未纳入映射时，按路径做保守归类；仍无法识别的
  // 节点进入“总览”兜底，既保持六类信息架构，也确保新功能不会因前端发布节奏落后而被静默隐藏。
  return sectionKeyForPath(node.path ?? '')
}

/**
 * 把后端菜单树映射为生命周期导航。函数不修改传入节点，菜单热刷新后可直接重新计算。
 */
export function buildNavigationSections(tree: MenuNode[]): NavigationSection[] {
  const sourceBuckets = new Map<NavigationSectionKey, MenuNode[]>()
  for (const definition of SECTION_DEFINITIONS) {
    sourceBuckets.set(definition.key, [])
  }
  for (const node of tree) {
    sourceBuckets.get(sectionKeyForRoot(node))?.push(node)
  }

  return SECTION_DEFINITIONS.flatMap((definition) => {
    const sourceNodes = sourceBuckets.get(definition.key) ?? []
    let menuNodes = sourceNodes
    let itemCount = sourceNodes.length

    if (definition.key === 'overview') {
      menuNodes = [HOME_NODE, ...sourceNodes]
      itemCount = menuNodes.length
    } else if (definition.key === 'agents') {
      const canonicalWorkspace = sourceNodes.length === 1 && sourceNodes[0].permCode === 'workspace'
        ? sourceNodes[0]
        : null
      if (canonicalWorkspace && canonicalWorkspace.children.length > 0) {
        // 真实后端的标准形态直接复用只读 children 引用，避免每次 computed 求值制造新数组。
        menuNodes = canonicalWorkspace.children
        itemCount = canonicalWorkspace.children.length
      } else {
        const visibleNodes: MenuNode[] = []
        let visibleEntryCount = 0
        for (const node of sourceNodes) {
          const children = node.children ?? []
          if (node.permCode === 'workspace' && children.length > 0) {
            // 标准 workspace 根节点只负责承载动态智能体，提升一层避免重复层级。
            visibleNodes.push(...children)
            visibleEntryCount += children.length
          } else {
            // 未来新增的 /workspace/** 一级入口仍要保留，不能因与标准 workspace 同分区而被 flatMap 丢掉。
            visibleNodes.push(node)
            if (node.permCode !== 'workspace') visibleEntryCount += 1
          }
        }
        // 尚无启用中的智能体时保留 /workspace 空态入口，但计数仍为 0。
        menuNodes = visibleNodes
        itemCount = visibleEntryCount
      }
    }

    const shouldShow = definition.key === 'overview' || sourceNodes.length > 0
    return shouldShow ? [{ ...definition, sourceNodes, menuNodes, itemCount }] : []
  })
}

function menuPathMatches(nodePath: string | null, fullPath: string, path: string): boolean {
  if (!nodePath) return false
  if (nodePath === fullPath) return true
  // 带 query 的 SQL 报表必须按 fullPath 精确匹配，不能退化成裸 path 后让多个报表一起激活。
  return !nodePath.includes('?') && nodePath === path
}

function containsPath(nodes: MenuNode[], fullPath: string, path: string): boolean {
  return nodes.some((node) => (
    menuPathMatches(node.path, fullPath, path)
    || (node.children?.length > 0 && containsPath(node.children, fullPath, path))
  ))
}

/** 按当前完整路由反推生命周期分区，先信任后端树的真实归属，再用路径前缀兜底。 */
export function resolveNavigationSectionKey(
  sections: NavigationSection[],
  fullPath: string,
  path: string,
): NavigationSectionKey {
  if (path === '/home') return 'overview'
  const matched = sections.find((section) => containsPath(section.sourceNodes, fullPath, path))
  if (matched) return matched.key

  return sectionKeyForPath(path)
}

function nodeMatchesQuery(node: MenuNode, normalizedQuery: string): boolean {
  const searchable = [
    node.name,
    node.path,
    node.permCode,
    node.agentCode,
    ...(node.capabilities ?? []),
  ].filter(Boolean).join(' ').toLocaleLowerCase()
  return searchable.includes(normalizedQuery)
}

/** 上下文菜单过滤：命中父节点时保留完整子树，命中后代时只克隆祖先路径，不改写原树。 */
export function filterNavigationNodes(nodes: MenuNode[], query: string): MenuNode[] {
  const normalizedQuery = query.trim().toLocaleLowerCase()
  if (!normalizedQuery) return nodes

  return nodes.flatMap((node) => {
    if (nodeMatchesQuery(node, normalizedQuery)) return [node]
    const children = filterNavigationNodes(node.children ?? [], normalizedQuery)
    return children.length > 0 ? [{ ...node, children }] : []
  })
}

/** 查找菜单祖先链；SQL 报表先匹配完整 query，普通页面再按 path 匹配。 */
export function findMenuTrail(
  nodes: MenuNode[],
  fullPath: string,
  path: string,
  trail: string[] = [],
): string[] | null {
  for (const node of nodes) {
    const nextTrail = [...trail, node.name]
    if (menuPathMatches(node.path, fullPath, path)) return nextTrail
    const found = findMenuTrail(node.children ?? [], fullPath, path, nextTrail)
    if (found) return found
  }
  return null
}

function collectCommands(
  nodes: MenuNode[],
  section: NavigationSection,
  trail: string[],
  commands: NavigationCommand[],
) {
  for (const node of nodes) {
    const nextTrail = [...trail, node.name]
    const hasChildren = (node.children?.length ?? 0) > 0
    // 目录节点在 router 中没有对应组件，不作为搜索结果；workspace 空态是预注册的真实路由。
    if (node.path && (!hasChildren || node.permCode === 'workspace')) {
      commands.push({
        key: `${section.key}:${node.id}:${node.path}`,
        title: node.name,
        path: node.path,
        sectionKey: section.key,
        sectionTitle: section.title,
        trail: nextTrail,
        dynamic: node.dynamic,
        keywords: [node.permCode, node.agentCode, ...(node.capabilities ?? [])].filter(Boolean).join(' '),
      })
    }
    collectCommands(node.children ?? [], section, nextTrail, commands)
  }
}

/** 生成真实可导航的命令列表，同一路径只保留第一项，避免提升智能体节点后产生重复结果。 */
export function buildNavigationCommands(sections: NavigationSection[]): NavigationCommand[] {
  const commands: NavigationCommand[] = []
  for (const section of sections) {
    collectCommands(section.menuNodes, section, [], commands)
  }
  const firstCommandByPath = new Map<string, NavigationCommand>()
  for (const command of commands) {
    if (!firstCommandByPath.has(command.path)) {
      firstCommandByPath.set(command.path, command)
    }
  }
  return Array.from(firstCommandByPath.values())
}

/** 简单、可预测的关键词排序：标题精确/前缀优先，其次才是祖先、路径和能力字段命中。 */
export function searchNavigationCommands(
  commands: NavigationCommand[],
  query: string,
  limit = 12,
): NavigationCommand[] {
  const normalizedQuery = query.trim().toLocaleLowerCase()
  if (!normalizedQuery) return commands.slice(0, limit)
  const terms = normalizedQuery.split(/\s+/).filter(Boolean)

  return commands
    .map((command) => {
      const title = command.title.toLocaleLowerCase()
      const haystack = [title, command.sectionTitle, command.trail.join(' '), command.path, command.keywords]
        .join(' ')
        .toLocaleLowerCase()
      if (!terms.every((term) => haystack.includes(term))) return null
      const score = title === normalizedQuery ? 100 : title.startsWith(normalizedQuery) ? 60 : title.includes(normalizedQuery) ? 40 : 10
      return { command, score }
    })
    .filter((item): item is { command: NavigationCommand; score: number } => item !== null)
    .sort((left, right) => right.score - left.score || left.command.title.localeCompare(right.command.title, 'zh-CN'))
    .slice(0, limit)
    .map((item) => item.command)
}
