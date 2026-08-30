export interface AdminMenuNode {
  id: number
  name: string
  path: string | null
  icon: string | null
  iconType: 'library' | 'image' | null
  permCode: string | null
  sort: number | null
  agentCode: string | null
  capabilities: string[] | null
  dynamic: boolean
  children: AdminMenuNode[]
}

export interface StaticRouteCase {
  path: string
  menuTitle: string
  readySelector: string
}

/**
 * 与 src/router/component-map.ts 一一对应的 41 个静态业务路由。
 * 这里刻意显式列举，不从生产映射动态推导：新增页面时，E2E 必须先补 fixture 和挂载断言。
 */
export const STATIC_ROUTE_CASES: readonly StaticRouteCase[] = [
  { path: '/system/user', menuTitle: '成员与身份（服务端菜单）', readySelector: '.layout-main > .page' },
  { path: '/system/role', menuTitle: '角色管理', readySelector: '.layout-main > .page' },
  { path: '/system/log', menuTitle: '操作日志', readySelector: '.layout-main > .page' },
  { path: '/system/ai-audit', menuTitle: 'AI 审计', readySelector: '.layout-main > .page' },
  { path: '/system/agent-call-stats', menuTitle: '调用统计', readySelector: '.layout-main > .page' },
  { path: '/system/menu', menuTitle: '菜单管理', readySelector: '.layout-main > .page' },
  { path: '/system/devtools', menuTitle: '开发者工具箱', readySelector: '.layout-main > .devtoolbox-page' },
  { path: '/system/login-image', menuTitle: '登录页图片', readySelector: '.layout-main > .login-image-page' },
  { path: '/system/dict', menuTitle: '字典管理', readySelector: '.layout-main > .page' },
  { path: '/system/tenant', menuTitle: '租户管理', readySelector: '.layout-main > .page' },
  { path: '/system/billing', menuTitle: '计费中心', readySelector: '.layout-main > .page' },
  { path: '/system/config-version', menuTitle: '配置版本', readySelector: '.layout-main > .page' },
  { path: '/system/slo', menuTitle: 'SLO 管理', readySelector: '.layout-main > .slo-page' },
  { path: '/aiconfig/model', menuTitle: '模型管理', readySelector: '.layout-main > .modelops-page' },
  { path: '/aiconfig/mcp', menuTitle: 'MCP 管理', readySelector: '.layout-main > .page' },
  { path: '/aiconfig/skill', menuTitle: 'Skill 管理', readySelector: '.layout-main > .page' },
  { path: '/aiconfig/agent', menuTitle: '智能体管理', readySelector: '.layout-main > .page' },
  { path: '/aiconfig/system-tool', menuTitle: '系统工具', readySelector: '.layout-main > .page' },
  { path: '/aiconfig/knowledge-base', menuTitle: '知识库', readySelector: '.layout-main > .page' },
  { path: '/aiconfig/scheduled-task', menuTitle: '定时任务', readySelector: '.layout-main > .page' },
  { path: '/aiconfig/agent-task', menuTitle: '智能体任务', readySelector: '.layout-main > .page' },
  { path: '/ops/eval', menuTitle: '评测中心', readySelector: '.layout-main > .eval-center' },
  { path: '/ops/badcase', menuTitle: 'Badcase 管理', readySelector: '.layout-main > .badcase-review' },
  { path: '/ops/semantic-cache', menuTitle: '语义缓存', readySelector: '.layout-main > .semantic-cache-board' },
  { path: '/ops/prompt-version', menuTitle: 'Prompt 版本', readySelector: '.layout-main > .prompt-version-board' },
  { path: '/ops/csat', menuTitle: '满意度分析', readySelector: '.layout-main > .csat-board' },
  { path: '/ops/knowledge-gap', menuTitle: '知识缺口', readySelector: '.layout-main > .knowledge-gap-board' },
  { path: '/ops/dead-letter', menuTitle: '死信处理', readySelector: '.layout-main > .dead-letter-board' },
  { path: '/ops/business-outcome', menuTitle: '业务结果', readySelector: '.layout-main > .outcome-board' },
  { path: '/contentguard/sensitive-word', menuTitle: '敏感词库', readySelector: '.layout-main > .page' },
  { path: '/contentguard/rate-limit', menuTitle: '限流规则', readySelector: '.layout-main > .page' },
  { path: '/contentguard/hit-log', menuTitle: '命中看板', readySelector: '.layout-main > .page' },
  { path: '/contentguard/subject-quota', menuTitle: '主体配额', readySelector: '.layout-main > .page' },
  { path: '/aiconfig/channel-robot', menuTitle: '渠道机器人', readySelector: '.layout-main > .page' },
  { path: '/ticket/user-ticket', menuTitle: '客服工单', readySelector: '.layout-main > .page' },
  { path: '/ticket/user-order', menuTitle: '用户订单', readySelector: '.layout-main > .page' },
  { path: '/project', menuTitle: '项目管理', readySelector: '.layout-main > .page' },
  { path: '/sql/datasource', menuTitle: '数据源', readySelector: '.layout-main > .page' },
  { path: '/sql/define', menuTitle: 'SQL 定义', readySelector: '.layout-main > .page' },
  { path: '/workbench/site', menuTitle: '内网工作台', readySelector: '.layout-main > .page' },
  { path: '/workbench/sql-console', menuTitle: 'SQL 客户端', readySelector: '.layout-main > .page' },
] as const

export const SQL_REPORT_PATH = '/sql/query?defineKey=e2e-report'
export const SQL_REPORT_MENU_TITLE = '资金对账演示'

let nextMenuId = 1000

function leaf(route: StaticRouteCase, sort: number): AdminMenuNode {
  return {
    id: nextMenuId++,
    name: route.menuTitle,
    path: route.path,
    icon: 'Document',
    iconType: 'library',
    permCode: `e2e:${route.path.slice(1).replaceAll('/', ':')}`,
    sort,
    agentCode: null,
    capabilities: null,
    dynamic: false,
    children: [],
  }
}

function routes(...paths: string[]): StaticRouteCase[] {
  return paths.map((path) => {
    const route = STATIC_ROUTE_CASES.find((candidate) => candidate.path === path)
    if (!route) throw new Error(`E2E menu references an unknown static route: ${path}`)
    return route
  })
}

function branch(
  name: string,
  permCode: string,
  sort: number,
  routeCases: StaticRouteCase[],
  icon = 'Folder',
): AdminMenuNode {
  return {
    id: nextMenuId++,
    name,
    path: null,
    icon,
    iconType: 'library',
    permCode,
    sort,
    agentCode: null,
    capabilities: null,
    dynamic: false,
    children: routeCases.map((route, index) => leaf(route, index + 1)),
  }
}

/** 后端菜单的真实层级形状；六个生命周期分区都有数据，且包含 query 与动态工作区节点。 */
export function buildAdminMenuTree(): AdminMenuNode[] {
  nextMenuId = 1000

  const workbench = branch('我的工作台', 'workbench', 1, routes(
    '/system/devtools',
    '/workbench/site',
    '/workbench/sql-console',
  ), 'Suitcase')
  workbench.children.push({
    id: nextMenuId++,
    name: SQL_REPORT_MENU_TITLE,
    path: SQL_REPORT_PATH,
    icon: 'DataAnalysis',
    iconType: 'library',
    permCode: 'sql-query:e2e-report',
    sort: 4,
    agentCode: null,
    capabilities: null,
    dynamic: false,
    children: [],
  })

  const workspace: AdminMenuNode = {
    id: nextMenuId++,
    name: '智能体工作区',
    path: '/workspace',
    icon: 'Cpu',
    iconType: 'library',
    permCode: 'workspace',
    sort: 2,
    agentCode: null,
    capabilities: null,
    dynamic: false,
    children: [{
      id: nextMenuId++,
      name: 'Java 智能体',
      path: '/workspace/java-assistant',
      icon: 'Monitor',
      iconType: 'library',
      permCode: 'workspace:java-assistant',
      sort: 1,
      agentCode: 'java-assistant',
      capabilities: ['chat', 'vibecoding'],
      dynamic: true,
      children: [],
    }],
  }

  const project = leaf(routes('/project')[0], 4)
  project.permCode = 'project'

  return [
    workbench,
    workspace,
    branch('AI 配置', 'aiconfig', 3, routes(
      '/aiconfig/model',
      '/aiconfig/mcp',
      '/aiconfig/skill',
      '/aiconfig/agent',
      '/aiconfig/system-tool',
      '/aiconfig/knowledge-base',
      '/aiconfig/scheduled-task',
      '/aiconfig/agent-task',
      '/aiconfig/channel-robot',
    ), 'Cpu'),
    project,
    branch('SQL 配置', 'sql-config', 5, routes('/sql/datasource', '/sql/define'), 'Coin'),
    branch('监控大屏', 'monitor:view', 6, routes('/system/agent-call-stats'), 'TrendCharts'),
    branch('运营闭环', 'ops', 7, routes(
      '/ops/eval',
      '/ops/badcase',
      '/ops/semantic-cache',
      '/ops/prompt-version',
      '/ops/csat',
      '/ops/knowledge-gap',
      '/ops/dead-letter',
      '/ops/business-outcome',
    ), 'DataAnalysis'),
    branch('客服工单', 'ticket', 8, routes('/ticket/user-ticket', '/ticket/user-order'), 'Service'),
    branch('内容风控', 'contentguard', 9, routes(
      '/contentguard/sensitive-word',
      '/contentguard/rate-limit',
      '/contentguard/hit-log',
      '/contentguard/subject-quota',
    ), 'Lock'),
    branch('系统管理', 'system', 10, routes(
      '/system/user',
      '/system/role',
      '/system/log',
      '/system/ai-audit',
      '/system/menu',
      '/system/login-image',
      '/system/dict',
      '/system/tenant',
      '/system/billing',
      '/system/config-version',
      '/system/slo',
    ), 'Setting'),
  ]
}
