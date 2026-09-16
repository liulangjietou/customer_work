import type { Route } from '@playwright/test'
import { expect, test } from './fixtures/adminTestFixture'
const ok = (data: unknown) => ({ code: 0, data })
const time = Date.UTC(2026, 8, 15, 4)
const note = '确认事实和归属，再决定是否需要处理。'.repeat(24)
const availability = { status: 'COMPLETE', reason: '已有完整事实' }
function outcomeSummary(empty: boolean) {
  return { tenantId: 'default', agentCode: null, fromMs: time - 86400000, toMs: time, generatedAtMs: time,
    dataSource: 'acceptance-fixture', totalSessions: empty ? 0 : 1, successfulSessions: empty ? 0 : 1,
    successfulSessionRate: empty ? 0 : 1, autoResolvedProxySessions: empty ? 0 : 1,
    autoResolvedProxyRate: empty ? 0 : 1, handoffSessions: 0, handoffRate: 0, totalCalls: empty ? 0 : 1,
    totalTokens: empty ? 0 : 120, tokenAvailability: availability, csatInvitedSessions: 0, csatRespondedSessions: 0,
    csatResponseRate: 0, averageCsat: null, csatSatisfiedRate: null, totalCost: empty ? 0 : 0.012,
    costCurrency: 'CNY', costPerAutoResolvedSession: empty ? null : 0.012, costAvailability: availability,
    costPerAutoResolvedAvailability: availability,
    definitions: { observedSession: '发生过调用的会话', successfulSession: '调用成功', autoResolvedProxy: '调用成功且未转人工的代理指标', handoffSession: '有转人工事实', csat: '满意度事实', token: '模型上报', cost: '已结算成本' } }
}
const cases = [
  { path: '/ops/csat', permission: 'csat:view', primary: '/ops/csat/list', marker: 'csat-acceptance-session', search: '查询', empty: '该窗口内还没有用户评价（会话结束时会自动发出邀请）',
    rows: [{ sessionId: 'csat-acceptance-session', scopeId: 'default', score: 2, comment: note, invitedAtMs: time - 60000, submittedAtMs: time, answered: true, satisfied: false }],
    extras: (empty: boolean) => ({ '/ops/csat/summary': { invited: empty ? 0 : 1, answered: empty ? 0 : 1, satisfied: 0, totalScore: empty ? 0 : 2, csat: 0, responseRate: empty ? 0 : 1, averageScore: empty ? 0 : 2 } }) },
  { path: '/ops/dead-letter', permission: 'dead-letter:view', primary: '/ops/dead-letter/list', marker: 'dead-acceptance-key', search: '刷新', empty: '该状态下暂无死信',
    rows: [{ id: 'dead-acceptance-id', type: 'NOTIFICATION', bizKey: 'dead-acceptance-key', payload: '{"ticketId":91}', status: 'ABANDONED', attempts: 4, lastError: note, nextRetryAtMs: 0, createdAtMs: time, finishedAtMs: 0 }],
    extras: (empty: boolean) => ({ '/ops/dead-letter/stats': { PENDING: 0, SUCCEEDED: 0, ABANDONED: empty ? 0 : 1 } }) },
  { path: '/ops/semantic-cache', permission: 'semantic-cache:view', primary: '/ops/semantic-cache/list', marker: '验收缓存问题', search: '查询', empty: '该分区暂无缓存（功能默认关闭，需显式开启）',
    rows: [{ id: 92, scopeId: 'u7', intent: 'consult', question: '验收缓存问题', questionVector: null, answer: note, hitCount: 5, createdAtMs: time, lastHitAtMs: time }],
    extras: (_empty: boolean) => ({ '/ops/semantic-cache/scopes': [{ scopeId: 'u7', entries: 1 }] }) },
  { path: '/system/slo', permission: 'slo:view', primary: '/slo/policies', marker: '验收服务策略', search: '刷新', empty: '暂无 SLO 策略',
    rows: [{ id: 93, policyName: '验收服务策略', scopeType: 'TENANT', scopeKey: null, availabilityTarget: 0.99, latencyTarget: 0.95, latencyThresholdMs: 3000, shortWindowMinutes: 5, longWindowMinutes: 60, minimumSampleCount: 100, burnRateThreshold: 2, enabled: true, lastEvaluatedAt: '2026-09-15 12:00:00', lastEvaluationStatus: 'HEALTHY', lastEvaluationError: null, updateTime: '2026-09-15 12:00:00' }],
    extras: (_empty: boolean) => ({ '/slo/alerts': [], '/slo/alerts/summary': { openCount: 0, acknowledgedCount: 0 } }) },
  { path: '/ops/business-outcome', permission: 'business-outcome:view', primary: '/business-outcomes/sessions', marker: 'outcome-acceptance-session', search: '查询', empty: '该窗口内暂无会话记录',
    rows: [{ sessionId: 'outcome-acceptance-session', agentCodes: 'service-agent', firstCallAtMs: time, lastCallAtMs: time, callCount: 1, successful: true, handedOff: false, autoResolvedProxy: true, totalTokens: 120, tokenAvailability: availability, modelCost: 0.012, costCurrency: 'CNY', costAvailability: availability, csatScore: null }],
    extras: (empty: boolean) => ({ '/business-outcomes/summary': outcomeSummary(empty) }) },
  { path: '/system/billing', permission: 'billing:view', primary: '/billing/bill', marker: 'acceptance-model', search: '查询', empty: '该区间暂无账单',
    rows: [{ tenantId: 'default', provider: 'acceptance-provider', modelName: 'acceptance-model', callCount: 1, inputTokens: 100, outputTokens: 20, cachedTokens: 0, totalTokens: 120, modelSegmentCount: 1, settledSegmentCount: 1, unsettledSegmentCount: 0, pricingStatus: 'COMPLETE', sourceMaxCallLogId: 94, currency: 'CNY', amount: 0.012 }],
    extras: (_empty: boolean) => ({ '/billing/reconciliation': [] }) },
]
for (const item of cases) {
  test(`${item.path} 实际数据、失败保留、重试和窄屏只读验收`, async ({ page }, testInfo) => {
    let state: 'error' | 'data' | 'empty' = 'error'
    const writes: string[] = []
    await page.route('**/api/auth/permissions', r => r.fulfill({ json: ok([item.permission]) }))
    await page.route(url => url.pathname === '/api' + item.primary, r => {
      const rows = state === 'empty' ? [] : item.rows
      const data = item.path === '/ops/business-outcome' ? { records: rows, total: rows.length, page: 1, size: 20 } : rows
      return r.fulfill({ json: state === 'error' ? { code: 50000, message: '验收查询故障' } : ok(data) })
    })
    for (const path of Object.keys(item.extras(false))) {
      await page.route(url => url.pathname === '/api' + path, r => r.fulfill({ json: ok((item.extras(state === 'empty') as Record<string, unknown>)[path]) }))
    }
    page.on('request', r => {
      if (new URL(r.url()).pathname.startsWith('/api/') && !['GET', 'HEAD', 'OPTIONS'].includes(r.method())) writes.push(r.url())
    })
    await page.goto(item.path)
    const error = page.locator('.layout-main .crud-load-state').filter({ visible: true }).first()
    await expect(error).toBeVisible()
    await expect(page.locator('.layout-main').getByText(item.empty, { exact: true })).toHaveCount(0)
    await expect(page.locator('.layout-main .stats:visible, .layout-main .summary-row:visible, .layout-main .metric-grid:visible')).toHaveCount(0)
    state = 'data'
    await error.getByRole('button', { name: '重新加载' }).click()
    const record = page.getByRole('row').filter({ hasText: item.marker })
    await expect(record).toBeVisible()
    await expect(record.getByRole('button', { name: /^(编辑|删除|重开|评估|确认)$/ })).toHaveCount(0)
    state = 'error'
    await page.getByRole('button', { name: item.search, exact: true }).click()
    await expect(error).toContainText('已保留上次结果')
    await expect(record).toBeVisible()
    state = 'data'
    await error.getByRole('button', { name: '重新加载' }).click()
    await expect(page.locator('.layout-main .crud-load-state')).not.toBeVisible()
    await page.setViewportSize({ width: 390, height: 844 })
    await expect.poll(() => page.locator('.layout-aside').evaluate(e => e.getBoundingClientRect().width)).toBeLessThanOrEqual(1)
    await record.scrollIntoViewIfNeeded()
    expect((await record.boundingBox())!.height).toBeLessThanOrEqual(120)
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390)
    await expect(page.getByText('验收查询故障', { exact: true })).toHaveCount(0)
    await page.screenshot({ path: testInfo.outputPath('dashboard-state-390.png') })
    state = 'empty'
    await page.getByRole('button', { name: item.search, exact: true }).click()
    await expect(page.locator('.layout-main').getByText(item.empty, { exact: true })).toBeVisible()
    expect(writes).toEqual([])
  })
}

// 成功响应也必须绑定发起身份；全局请求层只处理旧身份的错误提示，不能替代页面清理。
for (const item of cases) {
  test(`${item.path} 同令牌重新登录后不接受旧查询成功结果`, async ({ page }) => {
    let held: Route | undefined
    await page.route('**/api/auth/permissions', r => r.fulfill({ json: ok([item.permission]) }))
    for (const path of Object.keys(item.extras(false))) {
      await page.route(url => url.pathname === '/api' + path, r => r.fulfill({ json: ok((item.extras(false) as Record<string, unknown>)[path]) }))
    }
    await page.route(url => url.pathname === '/api' + item.primary, r => { held = r })
    await page.goto(item.path)
    await expect.poll(() => !!held).toBe(true)
    await page.evaluate(async permission => {
      const path = '/src/store/auth.ts'
      const { useAuthStore } = await import(path)
      const auth = useAuthStore()
      auth.applyLoginResult({ token: auth.token, nickname: '后来登录的账号', forceChangePassword: false,
        approvalStatus: 'APPROVED', approvalRemark: null }, 'new-user')
      auth.permissions = [permission]
    }, item.permission)
    const response = page.waitForResponse(r => r.url() === held!.request().url())
    const data = item.path === '/ops/business-outcome'
      ? { records: item.rows, total: item.rows.length, page: 1, size: 20 } : item.rows
    await held!.fulfill({ json: ok(data) })
    await (await response).finished()
    await page.evaluate(() => new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))))
    await expect(page.getByRole('row').filter({ hasText: item.marker })).toHaveCount(0)
    await expect(page.locator('.layout-main .el-loading-mask:visible')).toHaveCount(0)
  })
}

test.describe('账单本地日历', () => {
  test.use({ timezoneId: 'Asia/Shanghai' })
  test('默认区间从本地月初开始，包含本地当天', async ({ page }) => {
    await page.clock.setFixedTime(new Date('2026-09-15T00:30:00+08:00'))
    await page.route('**/api/auth/permissions', r => r.fulfill({ json: ok(['billing:view']) }))
    const requests: URL[] = []
    await page.route(url => url.pathname === '/api/billing/bill', r => {
      requests.push(new URL(r.request().url()))
      return r.fulfill({ json: ok([]) })
    })
    await page.goto('/system/billing')
    await expect.poll(() => requests.length).toBe(1)
    expect(requests[0].searchParams.get('from')).toBe('2026-09-01')
    expect(requests[0].searchParams.get('to')).toBe('2026-09-15')
  })
})
