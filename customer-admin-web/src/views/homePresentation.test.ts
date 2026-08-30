import { describe, expect, it } from 'vitest'
import type { MenuNode } from '@/types/api'
import type { TabItem } from '@/store/tabs'
import { buildHomeAdmissionPresentation, buildHomeSnapshot } from './homePresentation'

function node(overrides: Partial<MenuNode> & Pick<MenuNode, 'id' | 'name'>): MenuNode {
  return {
    path: null,
    icon: null,
    iconType: 'library',
    permCode: null,
    sort: null,
    agentCode: null,
    capabilities: null,
    dynamic: false,
    children: [],
    ...overrides,
  }
}

const tabs: TabItem[] = [
  { key: '/home', title: '首页', fullPath: '/home', closable: false },
  { key: '/workspace/service', title: '客服质检智能体', fullPath: '/workspace/service', closable: true },
  { key: '/ops/eval', title: '评测中心', fullPath: '/ops/eval', closable: true },
  { key: '/system/user', title: '已失效入口', fullPath: '/system/user', closable: true },
]

const menuTree: MenuNode[] = [
  node({
    id: 1,
    name: '智能体工作区',
    path: '/workspace',
    permCode: 'workspace',
    children: [node({
      id: 11,
      name: '客服质检智能体',
      path: '/workspace/service',
      agentCode: 'service',
      dynamic: true,
    })],
  }),
  node({
    id: 2,
    name: '运营闭环',
    path: '/ops',
    permCode: 'ops',
    children: [node({ id: 21, name: '评测中心', path: '/ops/eval' })],
  }),
  node({
    id: 3,
    name: '内容风控',
    path: '/contentguard',
    permCode: 'contentguard',
    children: [node({ id: 31, name: '命中看板', path: '/contentguard/hit-log' })],
  }),
]

describe('buildHomeSnapshot', () => {
  it('只使用当前菜单中的真实入口，并过滤已经失权的历史标签', () => {
    const snapshot = buildHomeSnapshot({ approved: true, menuTree, tabs })

    expect(snapshot.availableEntryCount).toBe(3)
    expect(snapshot.agentEntryCount).toBe(1)
    expect(snapshot.openTabCount).toBe(2)
    expect(snapshot.quickEntries.map((entry) => entry.path)).toEqual([
      '/workspace/service',
      '/ops/eval',
      '/contentguard/hit-log',
    ])
    expect(snapshot.recentTabs.map((tab) => tab.path)).toEqual(['/ops/eval', '/workspace/service'])
  })

  it('同一生命周期只先选一个代表入口，再用剩余入口补足快捷区', () => {
    const tree = [node({
      id: 4,
      name: 'AI 配置',
      path: '/aiconfig',
      permCode: 'aiconfig',
      children: [
        node({ id: 41, name: '模型管理', path: '/aiconfig/model' }),
        node({ id: 42, name: '智能体管理', path: '/aiconfig/agent' }),
      ],
    })]

    const snapshot = buildHomeSnapshot({ approved: true, menuTree: tree, tabs: [] })

    expect(snapshot.quickEntries.map((entry) => entry.path)).toEqual([
      '/aiconfig/model',
      '/aiconfig/agent',
    ])
  })

  it('未通过审核时不遍历或暴露菜单、标签中的业务入口', () => {
    const snapshot = buildHomeSnapshot({ approved: false, menuTree, tabs })

    expect(snapshot).toEqual({
      availableEntryCount: 0,
      agentEntryCount: 0,
      openTabCount: 0,
      quickEntries: [],
      recentTabs: [],
    })
  })
})

describe('buildHomeAdmissionPresentation', () => {
  it('优先展示服务端返回的驳回原因', () => {
    const presentation = buildHomeAdmissionPresentation('REJECTED', '申请信息需要补充')

    expect(presentation.status).toBe('审核未通过')
    expect(presentation.description).toBe('申请信息需要补充')
  })

  it('待审核状态不拼接任何业务入口信息', () => {
    const presentation = buildHomeAdmissionPresentation('PENDING', null)

    expect(presentation.status).toBe('等待审核')
    expect(presentation.description).toContain('真实权限')
    expect(JSON.stringify(presentation)).not.toContain('/workspace')
  })
})
