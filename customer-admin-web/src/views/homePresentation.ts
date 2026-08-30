import type { MenuNode, UserApprovalStatus } from '@/types/api'
import type { TabItem } from '@/store/tabs'
import {
  buildNavigationCommands,
  buildNavigationSections,
  type NavigationCommand,
  type NavigationSectionKey,
} from '@/layouts/navigationModel'

const HOME_PATH = '/home'
const QUICK_ENTRY_LIMIT = 4
const RECENT_TAB_LIMIT = 3

const QUICK_SECTION_ORDER: readonly NavigationSectionKey[] = [
  'agents',
  'build',
  'operate',
  'govern',
  'overview',
  'settings',
]

export interface HomeEntry {
  key: string
  title: string
  path: string
  sectionTitle: string
  sectionKey: NavigationSectionKey
  dynamic: boolean
}

export interface HomeRecentTab {
  key: string
  title: string
  path: string
}

export interface HomeSnapshot {
  availableEntryCount: number
  agentEntryCount: number
  openTabCount: number
  quickEntries: HomeEntry[]
  recentTabs: HomeRecentTab[]
}

export interface HomeAdmissionPresentation {
  eyebrow: string
  status: string
  title: string
  description: string
  note: string
}

interface HomeSnapshotInput {
  approved: boolean
  menuTree: MenuNode[]
  tabs: TabItem[]
}

/** 准入态文案只依赖登录响应，不读取菜单或标签。 */
export function buildHomeAdmissionPresentation(
  approvalStatus: UserApprovalStatus,
  approvalRemark: string | null,
): HomeAdmissionPresentation {
  if (approvalStatus === 'REJECTED') {
    return {
      eyebrow: 'ACCESS REVIEW · REJECTED',
      status: '审核未通过',
      title: '当前账号暂未获得工作台准入。',
      description: approvalRemark || '管理员尚未通过本次申请，请联系管理员确认原因后重新处理。',
      note: '驳回原因只对当前登录账号展示，业务菜单与历史工作不会在此状态下加载。',
    }
  }

  return {
    eyebrow: 'ACCESS REVIEW · PENDING',
    status: '等待审核',
    title: '申请已经提交，工作台正在等待管理员确认。',
    description: '审核通过并分配角色后，系统会按你的真实权限开放对应工作入口。',
    note: '等待期间无需重复提交。若长时间没有进展，请联系系统管理员。',
  }
}

function toHomeEntry(command: NavigationCommand): HomeEntry {
  return {
    key: command.key,
    title: command.title,
    path: command.path,
    sectionTitle: command.sectionTitle,
    sectionKey: command.sectionKey,
    dynamic: command.dynamic,
  }
}

function selectQuickEntries(commands: NavigationCommand[]): HomeEntry[] {
  const selected: NavigationCommand[] = []
  const selectedPaths = new Set<string>()

  for (const sectionKey of QUICK_SECTION_ORDER) {
    const command = commands.find((item) => item.sectionKey === sectionKey && !selectedPaths.has(item.path))
    if (!command) continue
    selected.push(command)
    selectedPaths.add(command.path)
    if (selected.length === QUICK_ENTRY_LIMIT) return selected.map(toHomeEntry)
  }

  for (const command of commands) {
    if (selectedPaths.has(command.path)) continue
    selected.push(command)
    selectedPaths.add(command.path)
    if (selected.length === QUICK_ENTRY_LIMIT) break
  }

  return selected.map(toHomeEntry)
}

function tabMatchesCommand(tab: TabItem, command: NavigationCommand): boolean {
  if (tab.fullPath === command.path) return true
  if (command.path.includes('?')) return false
  return tab.fullPath.split(/[?#]/)[0] === command.path
}

/**
 * 首页只从后端已经按权限裁剪的菜单树与当前标签生成展示数据。
 * 未通过审核时先返回空快照，避免旧 Pinia 状态把上一账号的业务入口带到准入页。
 */
export function buildHomeSnapshot(input: HomeSnapshotInput): HomeSnapshot {
  if (!input.approved) {
    return {
      availableEntryCount: 0,
      agentEntryCount: 0,
      openTabCount: 0,
      quickEntries: [],
      recentTabs: [],
    }
  }

  const sections = buildNavigationSections(input.menuTree)
  const commands = buildNavigationCommands(sections).filter((command) => command.path !== HOME_PATH)
  const agentEntryCount = sections.find((section) => section.key === 'agents')?.itemCount ?? 0

  const authorizedTabs = input.tabs
    .filter((tab) => tab.closable && commands.some((command) => tabMatchesCommand(tab, command)))

  const recentTabs = authorizedTabs
    .slice(-RECENT_TAB_LIMIT)
    .reverse()
    .map((tab) => ({ key: tab.key, title: tab.title, path: tab.fullPath }))

  return {
    availableEntryCount: commands.length,
    agentEntryCount,
    openTabCount: authorizedTabs.length,
    quickEntries: selectQuickEntries(commands),
    recentTabs,
  }
}
