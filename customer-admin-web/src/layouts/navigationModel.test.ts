import { describe, expect, it } from 'vitest'
import type { MenuNode } from '@/types/api'
import {
  buildNavigationCommands,
  buildNavigationSections,
  filterNavigationNodes,
  findMenuTrail,
  resolveNavigationSectionKey,
  searchNavigationCommands,
  type NavigationCommand,
} from './navigationModel'

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

function fixtureTree(): MenuNode[] {
  return [
    node({
      id: 1,
      name: '系统管理',
      path: '/system',
      permCode: 'system',
      children: [node({ id: 10, name: '用户管理', path: '/system/user', permCode: 'user' })],
    }),
    node({
      id: 2,
      name: 'AI 配置',
      path: '/aiconfig',
      permCode: 'aiconfig',
      children: [node({ id: 20, name: '模型配置', path: '/aiconfig/model', permCode: 'model' })],
    }),
    node({
      id: 3,
      name: '智能体工作区',
      path: '/workspace',
      permCode: 'workspace',
      children: [node({
        id: 300,
        name: 'OA 考勤与周报',
        path: '/workspace/oa-assistant',
        icon: '/agent-icon.png',
        iconType: 'image',
        agentCode: 'oa-assistant',
        capabilities: ['chat', 'vibecoding'],
        dynamic: true,
      })],
    }),
    node({
      id: 160,
      name: '我的工作台',
      permCode: 'workbench',
      children: [
        node({ id: 150, name: '开发者工具箱', path: '/system/devtools', permCode: 'devtools' }),
        node({
          id: 123,
          name: '资金对账',
          path: '/sql/query?defineKey=repayment',
          permCode: 'audit:view',
        }),
        node({
          id: 124,
          name: '库存报表',
          path: '/sql/query?defineKey=inventory',
          permCode: 'inventory:view',
        }),
      ],
    }),
    node({
      id: 270,
      name: '监控大屏',
      path: '/monitor',
      permCode: 'monitor:view',
      children: [node({
        id: 194,
        name: '智能体耗时统计',
        path: '/system/agent-call-stats',
        permCode: 'agent-call-stats:view',
      })],
    }),
    node({ id: 999, name: '未来能力', path: '/future/overview', permCode: 'future' }),
  ]
}

describe('buildNavigationSections', () => {
  it('按稳定权限码分组、提升动态智能体，同时不改写后端原树', () => {
    const tree = fixtureTree()
    const originalWorkspaceChildren = tree[2].children

    const sections = buildNavigationSections(tree)

    expect(sections.map((section) => section.key)).toEqual(['overview', 'agents', 'build', 'operate', 'settings'])
    const agents = sections.find((section) => section.key === 'agents')!
    expect(agents.menuNodes).toBe(originalWorkspaceChildren)
    expect(agents.menuNodes[0]).toMatchObject({
      path: '/workspace/oa-assistant',
      agentCode: 'oa-assistant',
      capabilities: ['chat', 'vibecoding'],
      dynamic: true,
      iconType: 'image',
    })
    expect(tree[2].children).toBe(originalWorkspaceChildren)
    expect(tree[2].children).toHaveLength(1)
  })

  it('未知一级菜单进入总览兜底且不丢失，空权限分区不占导航位', () => {
    const sections = buildNavigationSections(fixtureTree())
    expect(sections.find((section) => section.key === 'overview')?.menuNodes.map((item) => item.name))
      .toEqual(['首页', '我的工作台', '未来能力'])
    expect(sections.some((section) => section.key === 'operate')).toBe(true)
    expect(sections.some((section) => section.key === 'govern')).toBe(false)
  })

  it('没有启用中的智能体时保留 workspace 空态入口', () => {
    const tree = [node({ id: 3, name: '智能体工作区', path: '/workspace', permCode: 'workspace' })]
    const agents = buildNavigationSections(tree).find((section) => section.key === 'agents')!
    expect(agents.itemCount).toBe(0)
    expect(agents.menuNodes).toEqual(tree)
  })

  it('标准 workspace 与未来一级入口同分区时，提升智能体但不丢未来入口', () => {
    const workspace = node({
      id: 3,
      name: '智能体工作区',
      path: '/workspace',
      permCode: 'workspace',
      children: [node({
        id: 301,
        name: 'OA 助手',
        path: '/workspace/oa-assistant',
        agentCode: 'oa-assistant',
        dynamic: true,
      })],
    })
    const futureEntry = node({ id: 302, name: '智能体市场', path: '/workspace/market' })

    const agents = buildNavigationSections([workspace, futureEntry]).find((section) => section.key === 'agents')!

    expect(agents.menuNodes.map((item) => item.name)).toEqual(['OA 助手', '智能体市场'])
    expect(agents.itemCount).toBe(2)
  })

  it('十个真实一级菜单按输入顺序映射到固定六类', () => {
    const roots = [
      node({ id: 270, name: '监控大屏', path: '/monitor', permCode: 'monitor:view' }),
      node({ id: 1, name: '系统管理', path: '/system', permCode: 'system' }),
      node({ id: 2, name: 'AI 配置', path: '/aiconfig', permCode: 'aiconfig' }),
      node({ id: 3, name: '智能体工作区', path: '/workspace', permCode: 'workspace' }),
      node({ id: 4, name: 'Projects', path: '/project', permCode: 'project' }),
      node({ id: 231, name: '运营闭环', path: '/ops', permCode: 'ops' }),
      node({ id: 110, name: 'SQL配置管理', permCode: 'sql-config' }),
      node({ id: 160, name: '我的工作台', permCode: 'workbench' }),
      node({ id: 130, name: '客服工单', path: '/ticket', permCode: 'ticket' }),
      node({ id: 204, name: '内容风控', path: '/contentguard', permCode: 'contentguard' }),
    ]

    const sections = buildNavigationSections(roots)

    expect(sections.map((section) => section.key)).toEqual([
      'overview', 'agents', 'build', 'operate', 'govern', 'settings',
    ])
    expect(sections.find((section) => section.key === 'build')?.sourceNodes.map((item) => item.permCode))
      .toEqual(['aiconfig', 'project', 'sql-config'])
    expect(sections.find((section) => section.key === 'operate')?.sourceNodes.map((item) => item.permCode))
      .toEqual(['monitor:view', 'ops', 'ticket'])
  })

  it('菜单热刷新后基于新树重算，不复用旧分区', () => {
    const before = buildNavigationSections([node({ id: 1, name: '系统管理', permCode: 'system' })])
    const after = buildNavigationSections([node({ id: 204, name: '内容风控', permCode: 'contentguard' })])
    expect(before.some((section) => section.key === 'settings')).toBe(true)
    expect(before.some((section) => section.key === 'govern')).toBe(false)
    expect(after.some((section) => section.key === 'settings')).toBe(false)
    expect(after.some((section) => section.key === 'govern')).toBe(true)
  })
})

describe('resolveNavigationSectionKey', () => {
  it.each([
    ['/workspace/oa-assistant', '/workspace/oa-assistant', 'agents'],
    ['/aiconfig/model', '/aiconfig/model', 'build'],
    ['/sql/query?defineKey=repayment', '/sql/query', 'overview'],
    ['/system/devtools', '/system/devtools', 'overview'],
    ['/system/agent-call-stats', '/system/agent-call-stats', 'operate'],
    ['/system/user', '/system/user', 'settings'],
  ])('将 %s 归入 %s', (fullPath, path, expected) => {
    const sections = buildNavigationSections(fixtureTree())
    expect(resolveNavigationSectionKey(sections, fullPath, path)).toBe(expected)
  })

  it('未知路由回到总览兜底', () => {
    const sections = buildNavigationSections(fixtureTree())
    expect(resolveNavigationSectionKey(sections, '/unknown', '/unknown')).toBe('overview')
  })

  it.each([
    ['/workspace/new-agent', 'agents'],
    ['/project/new', 'build'],
    ['/ops/new', 'operate'],
    ['/contentguard/new', 'govern'],
    ['/system/new', 'settings'],
    ['/workbench/new', 'overview'],
  ])('菜单树尚未包含 %s 时按路径安全兜底', (path, expected) => {
    expect(resolveNavigationSectionKey(buildNavigationSections([]), path, path)).toBe(expected)
  })
})

describe('filterNavigationNodes', () => {
  it('命中后代时只克隆祖先路径，不修改输入树', () => {
    const tree = fixtureTree()
    const filtered = filterNavigationNodes(tree, '模型')
    expect(filtered).toHaveLength(1)
    expect(filtered[0].name).toBe('AI 配置')
    expect(filtered[0].children.map((child) => child.name)).toEqual(['模型配置'])
    expect(tree[1].children).toHaveLength(1)
  })

  it('父节点直接命中时保留完整子树', () => {
    const filtered = filterNavigationNodes(fixtureTree(), 'AI 配置')
    expect(filtered).toHaveLength(1)
    expect(filtered[0].children.map((child) => child.name)).toEqual(['模型配置'])
  })

  it('空查询复用原树引用，完全无匹配时返回空列表', () => {
    const tree = fixtureTree()
    expect(filterNavigationNodes(tree, '   ')).toBe(tree)
    expect(filterNavigationNodes(tree, '不存在的菜单')).toEqual([])
  })
})

describe('navigation commands', () => {
  it('只输出可导航结果，动态智能体和 SQL 完整 query 均保留', () => {
    const commands = buildNavigationCommands(buildNavigationSections(fixtureTree()))
    expect(commands.some((command) => command.title === 'AI 配置')).toBe(false)
    expect(commands.find((command) => command.title === 'OA 考勤与周报')).toMatchObject({
      path: '/workspace/oa-assistant',
      dynamic: true,
    })
    expect(commands.find((command) => command.title === '资金对账')?.path)
      .toBe('/sql/query?defineKey=repayment')
    expect(commands.find((command) => command.title === '库存报表')?.path)
      .toBe('/sql/query?defineKey=inventory')
  })

  it('支持大小写、祖先标题和能力关键词，并按标题匹配优先', () => {
    const commands = buildNavigationCommands(buildNavigationSections(fixtureTree()))
    expect(searchNavigationCommands(commands, '  OA  ')[0].title).toBe('OA 考勤与周报')
    expect(searchNavigationCommands(commands, '我的工作台').map((command) => command.title))
      .toContain('资金对账')
    expect(searchNavigationCommands(commands, 'VIBECODING')[0].title).toBe('OA 考勤与周报')
  })

  it('支持多关键词全匹配、空结果和自定义上限', () => {
    const commands = buildNavigationCommands(buildNavigationSections(fixtureTree()))
    expect(searchNavigationCommands(commands, 'OA chat').map((command) => command.title))
      .toEqual(['OA 考勤与周报'])
    expect(searchNavigationCommands(commands, '不存在的入口')).toEqual([])
    expect(searchNavigationCommands(commands, '', 1)).toHaveLength(1)
  })

  it('workspace 无动态智能体时仍可搜索空态入口，同路径命令保留第一项', () => {
    const emptyWorkspace = node({ id: 3, name: '智能体工作区', path: '/workspace', permCode: 'workspace' })
    const duplicatePathRoots = [
      node({ id: 501, name: '未来入口 A', path: '/future/shared' }),
      node({ id: 502, name: '未来入口 B', path: '/future/shared' }),
    ]

    const commands = buildNavigationCommands(buildNavigationSections([emptyWorkspace, ...duplicatePathRoots]))

    expect(commands.find((command) => command.path === '/workspace')?.title).toBe('智能体工作区')
    expect(commands.filter((command) => command.path === '/future/shared')).toHaveLength(1)
    expect(commands.find((command) => command.path === '/future/shared')?.title).toBe('未来入口 A')
  })

  it('标题精确、前缀、包含和元数据命中按明确权重排序', () => {
    const command = (key: string, title: string, trail: string[] = []): NavigationCommand => ({
      key,
      title,
      path: `/${key}`,
      sectionKey: 'overview',
      sectionTitle: '工作总览',
      trail,
      dynamic: false,
      keywords: '',
    })
    const commands = [
      command('metadata', 'Console', ['Agent Operations']),
      command('contains', 'Smart Agent'),
      command('prefix', 'Agent Builder'),
      command('exact', 'Agent'),
    ]

    expect(searchNavigationCommands(commands, 'agent').map((item) => item.key))
      .toEqual(['exact', 'prefix', 'contains', 'metadata'])
  })
})

describe('findMenuTrail', () => {
  it('SQL 报表优先按 fullPath 返回完整祖先链', () => {
    expect(findMenuTrail(
      fixtureTree(),
      '/sql/query?defineKey=repayment',
      '/sql/query',
    )).toEqual(['我的工作台', '资金对账'])
  })

  it('SQL 报表严格区分 defineKey，未知 query 不误命中第一项', () => {
    expect(findMenuTrail(
      fixtureTree(),
      '/sql/query?defineKey=inventory',
      '/sql/query',
    )).toEqual(['我的工作台', '库存报表'])
    expect(findMenuTrail(
      fixtureTree(),
      '/sql/query?defineKey=unknown',
      '/sql/query',
    )).toBeNull()
  })
})
