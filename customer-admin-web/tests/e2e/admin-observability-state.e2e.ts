import type { Page, Route } from '@playwright/test'
import { expect, test } from './fixtures/adminTestFixture'
const ok = (data: unknown) => ({ code: 0, data })
const marker = '当前可观测记录'
const callRow = { id: 801, requestId: 'acceptance-request', sessionId: 'acceptance-session', sessionType: 'EVALUATION',
  agentCode: 'acceptance-agent', agentName: '验收智能体', username: 'acceptance-user', question: marker, answerPreview: '验收响应',
  startTime: '2026-09-15 10:00:00', durationMs: 12, modelMs: 10, toolMs: 2, mcpMs: 0, skillMs: 0,
  totalTokens: 20, inputTokens: 10, outputTokens: 10, modelCostStatus: 'UNAVAILABLE', modelSegmentCount: 1,
  settledCostSegmentCount: 0, unsettledCostSegmentCount: 1 }
const hitRow = { id: 901, direction: 'INBOUND', action: 'BLOCK', words: ['验收词'], categories: ['ACCEPTANCE'], hitCount: 1,
  agentName: '验收智能体', sessionId: 'acceptance-session', userId: 'acceptance-user', snippet: marker, createdAtMs: 1789441200000 }
const cases = [
  { path: '/system/agent-call-stats', permission: 'agent-call-stats:view', endpoint: '/agent-call-stats/page', payload: { rows: [callRow], total: 1 }, search: '搜索' },
  { path: '/contentguard/hit-log', permission: 'sensitive-hit-log:view', endpoint: '/contentguard/hit-log/page', payload: { list: [hitRow], total: 1 }, search: '查询' },
]
async function relogin(page: Page, permission: string) {
  await page.evaluate(async p => {
    const modulePath = '/src/store/auth.ts'
    const { useAuthStore } = await import(modulePath)
    const auth = useAuthStore()
    auth.applyLoginResult({ token: auth.token, nickname: '新登录', forceChangePassword: false, approvalStatus: 'APPROVED', approvalRemark: null }, 'new-user')
    auth.permissions = [p]
  }, permission)
}
for (const item of cases) {
  test(`${item.path} 主查询故障可恢复，成功结果保留，390px 只读无写入口`, async ({ page }, testInfo) => {
    let failed = true
    await page.route('**/api/auth/permissions', r => r.fulfill({ json: ok([item.permission]) }))
    // 正向截图的汇总与一条列表记录保持一致，避免公共空夹具显示误导性的零指标。
    if (item.permission === 'agent-call-stats:view') {
      await page.route('**/api/agent-call-stats/summary?*', r => r.fulfill({ json: ok({
        totalCalls: 1, avgDurationMs: 12, maxDurationMs: 12, avgModelMs: 10, avgToolMs: 2,
        avgMcpMs: 0, avgSkillMs: 0, totalTokens: 20, avgTotalTokens: 20,
        inputTokens: 10, cachedTokens: 0, cacheHitRate: 0,
      }) }))
    } else {
      await page.route('**/api/contentguard/hit-log/stats*', r => r.fulfill({ json: ok({
        total: 1, byAction: [{ label: 'BLOCK', total: 1 }], byDirection: [{ label: 'INBOUND', total: 1 }],
        topWords: [{ label: '验收词', total: 1 }], trend: [{ label: '2026-09-15', total: 1 }], trendGranularity: 'day',
      }) }))
    }
    await page.route(url => url.pathname === '/api' + item.endpoint, r => r.fulfill({ json: failed ? { code: 50000, message: '记录暂不可读' } : ok(item.payload) }))
    await page.goto(item.path)
    await expect(page.locator('.crud-load-state').first()).toBeVisible()
    failed = false
    await page.locator('.crud-load-state').first().getByRole('button').click()
    await expect(page.getByText(marker, { exact: true })).toBeVisible()
    failed = true
    await page.getByRole('button', { name: item.search, exact: true }).click()
    await expect(page.locator('.crud-load-state').first()).toBeVisible()
    await expect(page.getByText(marker, { exact: true })).toBeVisible()
    failed = false
    await page.locator('.crud-load-state').first().getByRole('button').click()
    await expect(page.locator('.crud-load-state')).toHaveCount(0)
    await expect(page.getByRole('button', { name: '删除', exact: true })).toHaveCount(0)
    await page.setViewportSize({ width: 390, height: 844 })
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth)).toBe(390)
    await expect(page.locator('.el-message')).toHaveCount(0)
    await page.screenshot({ path: testInfo.outputPath('observability-state-390.png'), fullPage: true, animations: 'disabled' })
  })
  test(`${item.path} 同令牌重登不能显示旧身份的迟到主查询`, async ({ page }) => {
    let held: Route | undefined
    let calls = 0
    await page.route('**/api/auth/permissions', r => r.fulfill({ json: ok([item.permission]) }))
    await page.route(url => url.pathname === '/api' + item.endpoint, r => {
      calls += 1
      if (calls === 1) { held = r; return }
      return r.fulfill({ json: ok(item.endpoint.includes('agent-call-stats') ? { rows: [], total: 0 } : { list: [], total: 0 }) })
    })
    await page.goto(item.path)
    await expect.poll(() => Boolean(held)).toBe(true)
    await relogin(page, item.permission)
    const response = page.waitForResponse(r => r.url() === held!.request().url())
    await held!.fulfill({ json: ok(item.payload) })
    await (await response).finished()
    await page.evaluate(() => new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))))
    await expect(page.getByText(marker, { exact: true })).toHaveCount(0)
  })
}
test('调用统计只读权限不请求无权智能体选项，也不出现删除动作', async ({ page }) => {
  let options = 0
  await page.route('**/api/auth/permissions', r => r.fulfill({ json: ok(['agent-call-stats:view']) }))
  await page.route('**/api/aiconfig/agent?*', r => { options += 1; return r.fulfill({ json: ok({ list: [], total: 0 }) }) })
  await page.route('**/api/agent-call-stats/page?*', r => r.fulfill({ json: ok({ rows: [callRow], total: 1 }) }))
  await page.goto('/system/agent-call-stats')
  await expect(page.getByText(marker, { exact: true })).toBeVisible()
  expect(options).toBe(0)
  await expect(page.getByRole('button', { name: '删除', exact: true })).toHaveCount(0)
})

for (const item of cases) {
  test(`${item.path} 翻页继续使用已查询的筛选，未提交输入不会改变表格口径`, async ({ page }) => {
    const requests: URL[] = []
    await page.route('**/api/auth/permissions', r => r.fulfill({ json: ok([item.permission]) }))
    await page.route(url => url.pathname === '/api' + item.endpoint, r => {
      requests.push(new URL(r.request().url()))
      return r.fulfill({ json: ok({ ...item.payload, total: 21 }) })
    })
    await page.goto(item.path)
    await expect(page.getByText(marker, { exact: true })).toBeVisible()
    const callStats = item.endpoint.includes('agent-call-stats')
    await page.getByPlaceholder(callStats ? '用户名' : '按命中词搜索', { exact: true }).fill('UNSUBMITTED-FILTER')
    await page.locator('.el-pagination .btn-next').click()
    await expect.poll(() => requests.some(url => url.searchParams.get('pageNum') === '2')).toBe(true)
    const requested = requests.findLast(url => url.searchParams.get('pageNum') === '2')!
    expect(requested.searchParams.get(callStats ? 'username' : 'keyword') ?? '').toBe('')
  })
  test(`${item.path} 汇总失败时不把新列表与旧统计拼成一次成功查询`, async ({ page }) => {
    const callStats = item.endpoint.includes('agent-call-stats')
    let failSummary = false
    await page.route('**/api/auth/permissions', r => r.fulfill({ json: ok([item.permission]) }))
    await page.route(url => url.pathname === '/api' + item.endpoint, r => r.fulfill({ json: ok(callStats
      ? { rows: [{ ...callRow, question: failSummary ? 'NEW-PARTIAL-RESULT' : marker }], total: 1 }
      : { list: [{ ...hitRow, snippet: failSummary ? 'NEW-PARTIAL-RESULT' : marker }], total: 1 }) }))
    await page.route(url => url.pathname === (callStats ? '/api/agent-call-stats/summary' : '/api/contentguard/hit-log/stats'), r => {
      if (failSummary) return r.fulfill({ json: { code: 50000, message: '汇总暂不可读' } })
      return r.fallback()
    })
    await page.goto(item.path)
    await expect(page.getByText(marker, { exact: true })).toBeVisible()
    failSummary = true
    await page.getByRole('button', { name: item.search, exact: true }).click()
    await expect(page.locator('.crud-load-state').first()).toBeVisible()
    await expect(page.getByText(marker, { exact: true })).toBeVisible()
    await expect(page.getByText('NEW-PARTIAL-RESULT', { exact: true })).toHaveCount(0)
  })
}
