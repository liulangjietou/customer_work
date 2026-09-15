import type { Route } from '@playwright/test'
import { expect, test } from './fixtures/adminTestFixture'
const ok = (data: unknown) => ({ code: 0, data })

test('后台任务慢轮询不叠加，主动查询的终态不会被旧轮询覆盖', async ({ page }) => {
  test.setTimeout(35_000)
  let calls = 0
  let held: Route | undefined
  const task = { id: 91, taskId: 'stable-task', parentAgentCode: 'service-agent', subAgentId: 'worker',
    status: 'RUNNING', result: '处理中', createdAt: '2026-09-15 10:00:00' }
  await page.route('**/api/auth/permissions', r => r.fulfill({ json: ok(['agent-task:view']) }))
  await page.route('**/api/aiconfig/agent-task/page?*', r => {
    calls += 1
    if (calls === 2) { held = r; return }
    return r.fulfill({ json: ok({ records: [calls === 1 ? task : { ...task, status: 'COMPLETED', result: '最新完成结果' }], total: 1 }) })
  })
  await page.goto('/aiconfig/agent-task')
  await expect(page.getByRole('row').filter({ hasText: 'stable-task' })).toContainText('RUNNING')
  await expect.poll(() => !!held, { timeout: 7_000 }).toBe(true)
  // 跨过下一个 5 秒轮询点，仍只允许一条静默查询在途。
  await page.waitForTimeout(5_500)
  expect(calls).toBe(2)
  await expect(page.locator('.data-table .el-loading-mask')).not.toBeVisible()
  await page.getByRole('button', { name: '搜索', exact: true }).click()
  await expect(page.getByRole('row').filter({ hasText: 'stable-task' })).toContainText('最新完成结果')
  const response = page.waitForResponse(r => r.url() === held!.request().url())
  await held!.fulfill({ json: ok({ records: [task], total: 1 }) })
  await (await response).finished()
  await page.waitForTimeout(5_500)
  expect(calls).toBe(3)
  await expect(page.getByRole('row').filter({ hasText: 'stable-task' })).toContainText('COMPLETED')
  await expect(page.getByRole('row').filter({ hasText: 'stable-task' })).toContainText('最新完成结果')
})
