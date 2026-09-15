import type { Page, Route } from '@playwright/test'
import { expect, test } from './fixtures/adminTestFixture'
const ok = (data: unknown) => ({ code: 0, data })
async function permissions(page: Page, values: string[]) {
  await page.route('**/api/auth/permissions', r => r.fulfill({ json: ok(values) }))
}
async function relogin(page: Page, values: string[]) {
  await page.evaluate(async nextPermissions => {
    const modulePath = '/src/store/auth.ts'
    const { useAuthStore } = await import(modulePath)
    const auth = useAuthStore()
    auth.applyLoginResult({ token: auth.token, nickname: '新登录', forceChangePassword: false,
      approvalStatus: 'APPROVED', approvalRemark: null }, 'new-user')
    auth.permissions = nextPermissions
  }, values)
}
async function settled(page: Page) {
  await page.evaluate(() => new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))))
}
const dead = { id: 'acceptance-dead', type: 'NOTIFICATION', bizKey: '验收死信', payload: '{}', status: 'ABANDONED', attempts: 4, lastError: 'temporary', nextRetryAtMs: 0, createdAtMs: 100, finishedAtMs: 0 }
const cache = { id: 201, scopeId: 'acceptance-scope', intent: 'consult', question: '验收缓存问题', answer: '验收答案', hitCount: 1, createdAtMs: 100, lastHitAtMs: 100 }
const confirmations = [
  { path: '/ops/dead-letter', values: ['dead-letter:view', 'dead-letter:reopen'], endpoint: '/ops/dead-letter/acceptance-dead/reopen', method: 'POST', start: '重开', title: '重开死信', confirm: '重开' },
  { path: '/ops/semantic-cache', values: ['semantic-cache:view', 'semantic-cache:evict'], endpoint: '/ops/semantic-cache/201', method: 'DELETE', start: '删除', title: '删除缓存条目', confirm: '删除' },
]
for (const item of confirmations) test(`${item.path} 确认框等待期间重登，确认不应发出旧写入`, async ({ page }) => {
  let writes = 0
  await permissions(page, item.values)
  await page.route('**/api/ops/dead-letter/list?*', r => r.fulfill({ json: ok([dead]) }))
  await page.route('**/api/ops/dead-letter/stats', r => r.fulfill({ json: ok({ ABANDONED: 1 }) }))
  await page.route('**/api/ops/semantic-cache/scopes?*', r => r.fulfill({ json: ok([{ scopeId: cache.scopeId, entries: 1 }]) }))
  await page.route('**/api/ops/semantic-cache/list?*', r => r.fulfill({ json: ok([cache]) }))
  await page.route(url => url.pathname === '/api' + item.endpoint, r => {
    writes += 1
    return r.fulfill({ json: ok(item.method === 'POST' ? { ...dead, status: 'PENDING' } : true) })
  })
  await page.goto(item.path)
  await page.getByRole('button', { name: item.start, exact: true }).click()
  const dialog = page.getByRole('dialog', { name: item.title, exact: true })
  await expect(dialog).toBeVisible()
  await relogin(page, item.values)
  await dialog.getByRole('button', { name: item.confirm, exact: true }).click()
  await expect(dialog).not.toBeVisible()
  await settled(page)
  expect(writes).toBe(0)
})

const policies = [301, 302].map((id, index) => ({ id, policyName: `验收策略${index + 1}`, scopeType: 'TENANT', scopeKey: null,
  availabilityTarget: 0.99, latencyTarget: 0.95, latencyThresholdMs: 3000, shortWindowMinutes: 5, longWindowMinutes: 60,
  minimumSampleCount: 100, burnRateThreshold: 2, enabled: true, lastEvaluatedAt: null, lastEvaluationStatus: null, lastEvaluationError: null, updateTime: '2026-09-15 12:00:00' }))
async function slo(page: Page, values: string[]) {
  await permissions(page, values)
  await page.route('**/api/slo/policies', r => r.request().method() === 'GET' ? r.fulfill({ json: ok(policies) }) : r.fallback())
  await page.goto('/system/slo')
}

test('SLO 旧保存不能关闭新策略，且新建不能沿用编辑 id', async ({ page }, testInfo) => {
  let held: Route | undefined
  const writes: Record<string, unknown>[] = []
  await page.route('**/api/slo/policies', r => {
    if (r.request().method() === 'GET') return r.fallback()
    writes.push(r.request().postDataJSON())
    if (writes.length === 1) { held = r; return }
    return r.fulfill({ json: ok(303) })
  })
  await slo(page, ['slo:view', 'slo:edit'])
  await page.getByRole('row').filter({ hasText: '验收策略1' }).getByRole('button', { name: '编辑', exact: true }).click()
  const edit = page.getByRole('dialog', { name: '编辑 SLO 策略', exact: true })
  await edit.getByRole('button', { name: '保存策略', exact: true }).click()
  await expect.poll(() => Boolean(held)).toBe(true)
  await edit.locator('.el-dialog__headerbtn').click()
  await page.getByRole('button', { name: '新建策略', exact: true }).click()
  const create = page.getByRole('dialog', { name: '新建 SLO 策略', exact: true })
  await create.getByLabel('策略名称', { exact: true }).fill('全新策略')
  const response = page.waitForResponse(r => r.url() === held!.request().url() && r.request().method() === 'POST')
  await held!.fulfill({ json: ok(301) })
  await (await response).finished()
  await settled(page)
  await expect(create).toBeVisible()
  await expect(create.getByLabel('策略名称', { exact: true })).toHaveValue('全新策略')
  await create.getByRole('button', { name: '保存策略', exact: true }).click()
  await expect(create).not.toBeVisible()
  expect(writes).toHaveLength(2)
  expect(writes[0].id).toBe(301)
  expect(writes[1].id).toBeUndefined()
  await page.getByRole('button', { name: '新建策略', exact: true }).click()
  await page.setViewportSize({ width: 390, height: 844 })
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true)
  await expect(page.locator('.el-message')).toHaveCount(0)
  await page.screenshot({ animations: 'disabled', path: testInfo.outputPath('slo-policy-390.png') })
})

test('SLO 评估迟到结果不能在新身份下打开弹窗', async ({ page }) => {
  let held: Route | undefined
  await page.route('**/api/slo/policies/301/evaluate', r => { held = r })
  const values = ['slo:view', 'slo:evaluate']
  await slo(page, values)
  await page.getByRole('row').filter({ hasText: '验收策略1' }).getByRole('button', { name: '立即评估', exact: true }).click()
  await expect.poll(() => Boolean(held)).toBe(true)
  await relogin(page, values)
  const window = { windowMinutes: 5, total: 100, good: 99, bad: 1, availabilityGood: 99, latencyGood: 99,
    availabilityRatio: 0.99, latencyRatio: 0.99, remainingErrorBudget: 0, burnRate: 1 }
  const response = page.waitForResponse(r => r.url() === held!.request().url())
  await held!.fulfill({ json: ok({ policyId: 301, policyName: '验收策略1', scopeType: 'TENANT', scopeKey: null,
    evaluatedAt: '2026-09-15 12:00:00', status: 'HEALTHY', minimumSampleCount: 100,
    shortWindow: window, longWindow: { ...window, windowMinutes: 60 }, alertCreated: false, alertTransition: 'NONE' }) })
  await (await response).finished()
  await settled(page)
  await expect(page.getByRole('dialog', { name: '错误预算评估结果', exact: true })).not.toBeVisible()
})

test('缓存清空绑定原分区，完成后保留等待期间新选的分区', async ({ page }, testInfo) => {
  let held: Route | undefined
  const cleared: string[] = []
  const scopes = ['alpha-scope', 'beta-scope']
  await permissions(page, ['semantic-cache:view', 'semantic-cache:evict'])
  await page.route('**/api/ops/semantic-cache/scopes?*', r => r.fulfill({ json: ok(scopes.map(scopeId => ({ scopeId, entries: 1 }))) }))
  await page.route('**/api/ops/semantic-cache/list?*', r => {
    const scopeId = new URL(r.request().url()).searchParams.get('scopeId')!
    return r.fulfill({ json: ok([{ ...cache, scopeId, question: scopeId + ' 的问题' }]) })
  })
  await page.route('**/api/ops/semantic-cache/scope/*', r => { cleared.push(r.request().url().split('/').at(-1)!); held = r })
  await page.goto('/ops/semantic-cache')
  await page.getByRole('button', { name: '清空该分区', exact: true }).click()
  await page.getByRole('dialog', { name: '清空分区 alpha-scope 的全部缓存', exact: true })
    .getByRole('button', { name: '清空', exact: true }).click()
  await expect.poll(() => Boolean(held)).toBe(true)
  await page.locator('.filter-card .el-select').click()
  await page.getByRole('option', { name: 'beta-scope（1 条）', exact: true }).click()
  await expect(page.getByRole('cell', { name: 'beta-scope 的问题', exact: true })).toBeVisible()
  const response = page.waitForResponse(r => r.url() === held!.request().url())
  await held!.fulfill({ json: ok(1) })
  await (await response).finished()
  await settled(page)
  expect(cleared).toEqual(['alpha-scope'])
  await expect(page.locator('.filter-card .el-select')).toContainText('beta-scope')
  await expect(page.getByRole('cell', { name: 'beta-scope 的问题', exact: true })).toBeVisible()
  await page.setViewportSize({ width: 390, height: 844 })
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true)
  await expect(page.locator('.el-message')).toHaveCount(0)
  await page.screenshot({ animations: 'disabled', path: testInfo.outputPath('cache-action-390.png') })
})
