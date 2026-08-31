import {
  expect,
  test as base,
  type BrowserContext,
  type ConsoleMessage,
  type Page,
  type Route,
  type WebSocketRoute,
} from '@playwright/test'
import { LOGIN_E2E_ORIGIN } from '../loginTestEnvironment'
import { buildAdminMenuTree } from './adminRoutes'

const API_PREFIX = '/api'
const FIXED_TIMESTAMP = Date.UTC(2026, 7, 30, 10, 0, 0)
const LOCAL_HOST = new URL(LOGIN_E2E_ORIGIN).host

const pageResult = { pageNum: 1, pageSize: 20, total: 0, list: [] }
const mpPageResult = { current: 1, size: 20, total: 0, records: [] }

type FixtureValue = unknown | ((url: URL) => unknown)

interface ApiFixture {
  method: string
  path: string
  value: FixtureValue
}

export interface AdminHarness {
  unknownApiRequests: string[]
  forbiddenModelRequests: string[]
  externalRequests: string[]
  externalSockets: string[]
  pageErrors: string[]
  consoleErrors: string[]
}

function successResult(data: unknown) {
  return { code: 0, message: 'success', data, timestamp: FIXED_TIMESTAMP }
}

/**
 * 只登记页面首屏允许访问的契约。未知路径不得返回“万能空数组”，否则新增调用会被假绿掩盖。
 */
const API_FIXTURES: readonly ApiFixture[] = [
  // 登录后壳层
  { method: 'GET', path: '/auth/permissions', value: [] },
  { method: 'GET', path: '/menu/routes', value: () => buildAdminMenuTree() },
  { method: 'GET', path: '/menu/version', value: 1 },
  {
    method: 'GET',
    path: '/tenant/current-view',
    value: { userTenantId: 'default', effectiveTenantId: 'default', crossTenantAuthority: false },
  },
  { method: 'GET', path: '/message/unread-count', value: 0 },
  { method: 'GET', path: '/workspace/java-assistant/chat/sessions', value: pageResult },
  { method: 'GET', path: '/workspace/java-assistant/vibecoding/sandbox-mode', value: { mode: 'local' } },

  // 系统管理
  { method: 'GET', path: '/system/user', value: pageResult },
  { method: 'GET', path: '/system/role', value: pageResult },
  { method: 'GET', path: '/system/permission/tree', value: [] },
  { method: 'GET', path: '/system/log', value: pageResult },
  { method: 'GET', path: '/workspace/ai-coding-audit', value: pageResult },
  { method: 'GET', path: '/agent-call-stats/page', value: { total: 0, rows: [] } },
  {
    method: 'GET',
    path: '/agent-call-stats/summary',
    value: {
      totalCalls: 0,
      avgDurationMs: 0,
      maxDurationMs: 0,
      avgModelMs: 0,
      avgToolMs: 0,
      avgMcpMs: 0,
      avgSkillMs: 0,
      totalTokens: 0,
      avgTotalTokens: 0,
      inputTokens: 0,
      cachedTokens: 0,
      cacheHitRate: 0,
    },
  },
  { method: 'GET', path: '/agent-call-stats/trend', value: [] },
  { method: 'GET', path: '/system/menu/tree', value: [] },
  { method: 'GET', path: '/system/login-image', value: [] },
  { method: 'GET', path: '/dict/types', value: [] },
  { method: 'GET', path: '/dict/options/order_status', value: [] },
  { method: 'GET', path: '/tenant/page', value: pageResult },
  { method: 'GET', path: '/tenant/options', value: [] },
  { method: 'GET', path: '/billing/bill', value: [] },
  { method: 'GET', path: '/billing/reconciliation', value: [] },
  { method: 'GET', path: '/billing/forecast', value: null },
  { method: 'GET', path: '/billing/alerts', value: [] },
  { method: 'GET', path: '/config-version/page', value: pageResult },
  { method: 'GET', path: '/governance/changes', value: [] },
  { method: 'GET', path: '/slo/policies', value: [] },
  { method: 'GET', path: '/slo/alerts', value: [] },
  { method: 'GET', path: '/slo/alerts/summary', value: { openCount: 0, acknowledgedCount: 0 } },

  // AI 配置
  { method: 'GET', path: '/aiconfig/model', value: pageResult },
  { method: 'GET', path: '/aiconfig/model/asset-options', value: [] },
  { method: 'GET', path: '/aiconfig/model-routing-policies', value: [] },
  { method: 'GET', path: '/aiconfig/model-experiments', value: [] },
  { method: 'GET', path: '/aiconfig/mcp', value: pageResult },
  { method: 'GET', path: '/aiconfig/skill', value: pageResult },
  { method: 'GET', path: '/aiconfig/agent', value: pageResult },
  { method: 'GET', path: '/system-tool', value: pageResult },
  { method: 'GET', path: '/aiconfig/knowledge-base/page', value: pageResult },
  { method: 'GET', path: '/aiconfig/knowledge-base/options', value: [] },
  { method: 'GET', path: '/aiconfig/scheduled-task/page', value: mpPageResult },
  { method: 'GET', path: '/aiconfig/agent-task/page', value: mpPageResult },
  { method: 'GET', path: '/aiconfig/agent-task/statuses', value: [] },
  { method: 'GET', path: '/channel-robots/page', value: mpPageResult },

  // 运营闭环
  { method: 'GET', path: '/eval/runs', value: [] },
  { method: 'GET', path: '/eval/datasets/INTENT/cases', value: [] },
  { method: 'GET', path: '/eval/datasets/INTENT/versions', value: [] },
  { method: 'GET', path: '/eval/datasets/QUALITY/cases', value: [] },
  { method: 'GET', path: '/eval/datasets/QUALITY/versions', value: [] },
  { method: 'GET', path: '/badcase/page', value: pageResult },
  { method: 'GET', path: '/ops/semantic-cache/scopes', value: [] },
  { method: 'GET', path: '/ops/prompt-version/list', value: [] },
  {
    method: 'GET',
    path: '/ops/csat/summary',
    value: { invited: 0, answered: 0, satisfied: 0, totalScore: 0, csat: 0, responseRate: 0, averageScore: 0 },
  },
  { method: 'GET', path: '/ops/csat/list', value: [] },
  { method: 'GET', path: '/ops/knowledge-gap/top', value: [] },
  { method: 'GET', path: '/ops/dead-letter/list', value: [] },
  { method: 'GET', path: '/ops/dead-letter/stats', value: {} },
  {
    method: 'GET',
    path: '/business-outcomes/summary',
    value: {
      tenantId: 'default',
      agentCode: null,
      fromMs: FIXED_TIMESTAMP,
      toMs: FIXED_TIMESTAMP,
      generatedAtMs: FIXED_TIMESTAMP,
      dataSource: 'e2e-fixture',
      totalSessions: 0,
      successfulSessions: 0,
      successfulSessionRate: 0,
      autoResolvedProxySessions: 0,
      autoResolvedProxyRate: 0,
      handoffSessions: 0,
      handoffRate: 0,
      totalCalls: 0,
      totalTokens: null,
      tokenAvailability: { status: 'UNAVAILABLE', reason: 'fixture empty state' },
      csatInvitedSessions: 0,
      csatRespondedSessions: 0,
      csatResponseRate: 0,
      averageCsat: null,
      csatSatisfiedRate: 0,
      totalCost: null,
      costCurrency: null,
      costPerAutoResolvedSession: null,
      costAvailability: { status: 'UNAVAILABLE', reason: 'fixture empty state' },
      costPerAutoResolvedAvailability: { status: 'UNAVAILABLE', reason: 'fixture empty state' },
      definitions: {
        observedSession: 'fixture',
        successfulSession: 'fixture',
        autoResolvedProxy: 'fixture',
        handoffSession: 'fixture',
        csat: 'fixture',
        token: 'fixture',
        cost: 'fixture',
      },
    },
  },
  { method: 'GET', path: '/business-outcomes/sessions', value: { total: 0, page: 1, size: 20, records: [] } },

  // 内容治理
  { method: 'GET', path: '/contentguard/sensitive-word/page', value: pageResult },
  { method: 'GET', path: '/contentguard/sensitive-word/categories', value: [] },
  { method: 'GET', path: '/contentguard/sensitive-word/actions', value: [] },
  { method: 'GET', path: '/contentguard/rate-limit-rule/page', value: pageResult },
  { method: 'GET', path: '/contentguard/rate-limit-rule/dimensions', value: [] },
  { method: 'GET', path: '/contentguard/rate-limit-rule/algorithms', value: [] },
  { method: 'GET', path: '/contentguard/hit-log/page', value: pageResult },
  {
    method: 'GET',
    path: '/contentguard/hit-log/stats',
    value: { total: 0, byAction: [], byDirection: [], topWords: [], trend: [], trendGranularity: 'day' },
  },
  { method: 'GET', path: '/subject-quota/levels', value: [] },
  { method: 'GET', path: '/subject-quota/users', value: pageResult },
  { method: 'GET', path: '/subject-quota/admin-users', value: pageResult },
  { method: 'GET', path: '/subject-quota/hits', value: [] },
  { method: 'GET', path: '/subject-quota/hits/rank', value: [] },

  // 工单、项目、SQL 与工作台
  { method: 'GET', path: '/ticket/page', value: { total: 0, items: [] } },
  {
    method: 'GET',
    path: '/ticket/ws-credential',
    value: {
      token: 'fixture-ticket-token',
      wsUrl: LOGIN_E2E_ORIGIN.replace(/^http/, 'ws') + '/fixture-ticket',
      expiresAtMs: FIXED_TIMESTAMP + 60_000,
      agentId: 'fixture-agent',
    },
  },
  { method: 'GET', path: '/ticket/orders/page', value: { total: 0, items: [] } },
  { method: 'GET', path: '/workspace/project', value: [] },
  { method: 'GET', path: '/sql/datasource', value: pageResult },
  { method: 'GET', path: '/sql/datasource/all', value: [] },
  { method: 'GET', path: '/sql/define', value: pageResult },
  {
    method: 'GET',
    path: '/sql/query/meta',
    value: (url) => ({
      defineKey: url.searchParams.get('defineKey') ?? '',
      sqlDescribe: '资金对账演示报表',
      autoLoad: false,
      hasCountSql: false,
      params: [],
    }),
  },
  { method: 'GET', path: '/workbench/site', value: pageResult },
]

const API_FIXTURE_BY_KEY = new Map(
  API_FIXTURES.map((fixture) => [`${fixture.method} ${fixture.path}`, fixture.value] as const),
)

const FORBIDDEN_MODEL_PATHS = [
  /\/chat\/stream$/,
  /\/vibecoding\/(?:generate|review|commit-message|pr-description)$/,
  /^\/knowledge\/ask$/,
  /^\/eval\/run$/,
  /^\/aiconfig\/model\/[^/]+\/(?:test-connectivity|health-checks|certifications)$/,
]

async function fulfillJson(route: Route, data: unknown) {
  await route.fulfill({
    status: 200,
    contentType: 'application/json; charset=utf-8',
    body: JSON.stringify(successResult(data)),
  })
}

function recordConsoleError(message: ConsoleMessage, harness: AdminHarness) {
  if (message.type() === 'error') harness.consoleErrors.push(message.text())
}

export async function installFailClosedNetwork(context: BrowserContext, harness: AdminHarness) {
  await context.route('**/*', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const method = request.method().toUpperCase()

    if (url.origin !== LOGIN_E2E_ORIGIN) {
      harness.externalRequests.push(`${method} ${url.toString()}`)
      await route.abort('blockedbyclient')
      return
    }

    if (!url.pathname.startsWith(`${API_PREFIX}/`)) {
      await route.continue()
      return
    }

    const apiPath = url.pathname.slice(API_PREFIX.length)
    const requestLabel = `${method} ${apiPath}${url.search}`
    if (FORBIDDEN_MODEL_PATHS.some((pattern) => pattern.test(apiPath))) {
      harness.forbiddenModelRequests.push(requestLabel)
      await route.abort('blockedbyclient')
      return
    }

    const fixture = API_FIXTURE_BY_KEY.get(`${method} ${apiPath}`)
    if (fixture === undefined) {
      harness.unknownApiRequests.push(requestLabel)
      await route.abort('blockedbyclient')
      return
    }

    const value = typeof fixture === 'function' ? fixture(url) : fixture
    await fulfillJson(route, value)
  })

  await context.routeWebSocket('**/*', async (route: WebSocketRoute) => {
    const url = new URL(route.url())

    if (url.host !== LOCAL_HOST) {
      harness.externalSockets.push(url.toString())
      await route.close({ code: 1008, reason: 'Blocked by E2E network boundary' })
      return
    }

    const apiPath = url.pathname.startsWith(`${API_PREFIX}/`)
      ? url.pathname.slice(API_PREFIX.length)
      : url.pathname
    if (FORBIDDEN_MODEL_PATHS.some((pattern) => pattern.test(apiPath))) {
      harness.forbiddenModelRequests.push(`WS ${apiPath}${url.search}`)
      await route.close({ code: 1008, reason: 'Model calls are forbidden in E2E' })
      return
    }

    // routeWebSocket 默认不连接真实服务端；保留一个浏览器内的空实现供页面完成挂载。
    route.onMessage(() => {})
  })
}

async function installBrowserState(context: BrowserContext) {
  await context.addInitScript((allowedOrigin) => {
    // Context 脚本也会在初始 about:blank 与 popup 中执行；这些文档不可访问 localStorage。
    if (window.location.origin !== allowedOrigin) return

    localStorage.setItem('admin-token', 'admin-shell-e2e-token')
    localStorage.setItem('admin-nickname', 'E2E 管理员')
    localStorage.setItem('admin-username', 'admin-shell-e2e')
    localStorage.setItem('admin-force-change-password', 'false')
    localStorage.setItem('admin-approval-status', 'APPROVED')
    if (!localStorage.getItem('customer-admin-theme-mode')) {
      localStorage.setItem('customer-admin-theme-mode', 'light')
    }
    if (!localStorage.getItem('customer-admin-theme-color')) {
      localStorage.setItem('customer-admin-theme-color', '#3e63dd')
    }
  }, LOGIN_E2E_ORIGIN)
}

function bindPageObservers(context: BrowserContext, harness: AdminHarness) {
  const observe = (page: Page) => {
    page.on('pageerror', (error) => harness.pageErrors.push(error.message))
    page.on('console', (message) => recordConsoleError(message, harness))
  }

  context.pages().forEach(observe)
  context.on('page', observe)
}

export function createAdminHarness(): AdminHarness {
  return {
    unknownApiRequests: [],
    forbiddenModelRequests: [],
    externalRequests: [],
    externalSockets: [],
    pageErrors: [],
    consoleErrors: [],
  }
}

export const test = base.extend<{ adminHarness: AdminHarness }>({
  adminHarness: [async ({ context }, use) => {
    const harness = createAdminHarness()

    bindPageObservers(context, harness)
    await installBrowserState(context)
    await installFailClosedNetwork(context, harness)

    await use(harness)
    // 让挂载阶段已经排队的 Promise/请求进入监听器，再执行统一收尾断言。
    await new Promise((resolve) => setTimeout(resolve, 100))

    expect(harness.forbiddenModelRequests, 'E2E 不得触发真实模型、生成或流式接口').toEqual([])
    expect(harness.externalRequests, 'E2E 不得访问本地 Vite 之外的 HTTP(S) 地址').toEqual([])
    expect(harness.externalSockets, 'E2E 不得建立本地 Vite 之外的 WebSocket').toEqual([])
    expect(harness.unknownApiRequests, '存在未显式登记的 API；请补真实契约，不要加万能兜底').toEqual([])
    expect(harness.pageErrors, '页面运行时不得抛出未处理异常').toEqual([])
    expect(harness.consoleErrors, '页面控制台不得出现 error').toEqual([])
  }, { auto: true }],
})

export { expect }
