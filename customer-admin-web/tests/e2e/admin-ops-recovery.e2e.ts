import type { Page, Route } from '@playwright/test'
import { expect, test } from './fixtures/adminTestFixture'
const ok = (data: unknown) => ({ code: 0, data })
async function release(page: Page, held: Route, data: unknown) {
  const response = page.waitForResponse(r => r.url() === held.request().url())
  await held.fulfill({ json: ok(data) })
  await (await response).finished()
  await page.evaluate(() => new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))))
}
for (const kind of ['dead', 'cache'] as const) test(`${kind} 失败保留记录，重试锁定操作并在成功后刷新`, async ({ page }) => {
  const isDead = kind === 'dead'
  const permissions = isDead ? ['dead-letter:view', 'dead-letter:reopen'] : ['semantic-cache:view', 'semantic-cache:evict']
  let done = false
  let writes = 0
  let held: Route | undefined
  const dead = { id: 'recovery-dead', type: 'NOTIFICATION', bizKey: '恢复验收', payload: '{}', status: 'ABANDONED', attempts: 4, lastError: 'temporary', nextRetryAtMs: 0, createdAtMs: 100, finishedAtMs: 0 }
  const cache = { id: 211, scopeId: 'recovery-scope', intent: 'consult', question: '恢复验收', answer: '验收答案', hitCount: 1, createdAtMs: 100, lastHitAtMs: 100 }
  await page.route('**/api/auth/permissions', r => r.fulfill({ json: ok(permissions) }))
  await page.route('**/api/ops/dead-letter/list?*', r => r.fulfill({ json: ok(done ? [] : [dead]) }))
  await page.route('**/api/ops/dead-letter/stats', r => r.fulfill({ json: ok({ ABANDONED: done ? 0 : 1, PENDING: done ? 1 : 0 }) }))
  await page.route('**/api/ops/semantic-cache/scopes?*', r => r.fulfill({ json: ok(done ? [] : [{ scopeId: cache.scopeId, entries: 1 }]) }))
  await page.route('**/api/ops/semantic-cache/list?*', r => r.fulfill({ json: ok(done ? [] : [cache]) }))
  const endpoint = isDead ? '/api/ops/dead-letter/recovery-dead/reopen' : '/api/ops/semantic-cache/211'
  await page.route(url => url.pathname === endpoint, r => {
    writes += 1
    if (writes === 1) return r.fulfill({ json: { code: 50000, message: '操作暂时失败' } })
    held = r
  })
  await page.goto(isDead ? '/ops/dead-letter' : '/ops/semantic-cache')
  const row = page.getByRole('row').filter({ hasText: '恢复验收' })
  const action = row.getByRole('button', { name: isDead ? '重开' : '删除', exact: true })
  const confirm = async () => {
    await action.click()
    await page.getByRole('dialog', { name: isDead ? '重开死信' : '删除缓存条目', exact: true })
      .getByRole('button', { name: isDead ? '重开' : '删除', exact: true }).click()
  }
  await confirm()
  await expect(page.getByText('操作暂时失败', { exact: true })).toBeVisible()
  await expect(row).toBeVisible()
  await confirm()
  await expect.poll(() => Boolean(held)).toBe(true)
  await expect(action).toBeDisabled()
  expect(writes).toBe(2)
  done = true
  await release(page, held!, isDead ? { ...dead, status: 'PENDING', attempts: 0 } : true)
  await expect(row).toHaveCount(0)
})
const alert = { id: 411, policyId: 301, policyName: '历史验收策略', scopeType: 'TENANT', scopeKey: null, status: 'OPEN', shortBurnRate: 3, longBurnRate: 4, firstSeenAt: '2026-09-15 10:00:00', lastSeenAt: '2026-09-15 10:00:00' }
const permissions = ['slo:view', 'slo:ack']
async function slo(page: Page) {
  await page.route('**/api/auth/permissions', r => r.fulfill({ json: ok(permissions) }))
  await page.route('**/api/slo/alerts?*', r => r.fulfill({ json: ok([alert]) }))
  await page.goto('/system/slo')
}
test('SLO 事件故障可重试，同令牌重登关闭详情并丢弃迟到事件', async ({ page }) => {
  let attempts = 0
  let held: Route | undefined
  const event = { id: 511, alertId: 411, eventType: 'OPENED', shortBurnRate: 3, longBurnRate: 4, occurredAt: '2026-09-15 10:00:00', actorUserId: 7 }
  await page.route('**/api/slo/alerts/411/events', r => {
    attempts += 1
    if (attempts === 1) return r.fulfill({ json: { code: 50000, message: '历史事件暂不可读' } })
    if (attempts === 2) return r.fulfill({ json: ok([event]) })
    held = r
  })
  await slo(page)
  const action = page.getByRole('row').filter({ hasText: '历史验收策略' }).getByRole('button', { name: '事件', exact: true })
  await action.click()
  const dialog = page.getByRole('dialog', { name: '历史验收策略 · 状态事件', exact: true })
  await expect(dialog.locator('.crud-load-state')).toBeVisible()
  await dialog.locator('.crud-load-state').getByRole('button').click()
  await expect(dialog.getByText('OPENED', { exact: true })).toBeVisible()
  await dialog.locator('.el-dialog__headerbtn').click()
  await action.click()
  await expect.poll(() => Boolean(held)).toBe(true)
  await page.evaluate(async values => {
    const modulePath = '/src/store/auth.ts'
    const { useAuthStore } = await import(modulePath)
    const auth = useAuthStore()
    auth.applyLoginResult({ token: auth.token, nickname: '新登录', forceChangePassword: false, approvalStatus: 'APPROVED', approvalRemark: null }, 'new-user')
    auth.permissions = values
  }, permissions)
  await release(page, held!, [event])
  await expect(dialog).not.toBeVisible()
})
test('SLO 确认告警失败保留待确认，重试完成后才能展示已确认', async ({ page }) => {
  let held: Route | undefined
  let count = 0
  let acked = false
  await slo(page)
  await page.route('**/api/slo/alerts?*', r => r.fulfill({ json: ok([{ ...alert, status: acked ? 'ACKED' : 'OPEN' }]) }))
  await page.route('**/api/slo/alerts/411/ack', r => {
    count += 1
    if (count === 1) return r.fulfill({ json: { code: 50000, message: '确认暂时失败' } })
    held = r
  })
  const row = page.getByRole('row').filter({ hasText: '历史验收策略' })
  const action = row.getByRole('button', { name: '确认', exact: true })
  await action.click()
  await expect(page.getByText('确认暂时失败', { exact: true })).toBeVisible()
  await expect(row.getByText('待确认', { exact: true })).toBeVisible()
  await action.click()
  await expect.poll(() => Boolean(held)).toBe(true)
  await expect(action).toBeDisabled()
  expect(count).toBe(2)
  acked = true
  await release(page, held!, null)
  await expect(row.getByText('已确认', { exact: true })).toBeVisible()
  await expect(action).toHaveCount(0)
})
