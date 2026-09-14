import { expect, test } from './fixtures/adminTestFixture'

test('知识试评按离线评测展示和筛选，不进入不存在的工作区会话', async ({ page }) => {
  const queries: URL[] = []
  await page.route('**/api/agent-call-stats/page?**', (route) => {
    queries.push(new URL(route.request().url()))
    return route.fulfill({ json: { code: 0, data: { total: 1, rows: [{
      id: 42, requestId: 'trial-request', sessionId: 'trial-session', sessionType: 'EVALUATION',
      agentCode: 'java-assistant', agentName: '知识试评', username: '知识试评',
      question: '冻结用例', answerPreview: '候选答复', startTime: '2026-09-14 10:00:00',
      durationMs: 12, modelMs: 10, toolMs: 2, mcpMs: 0, skillMs: 0,
      totalTokens: 20, inputTokens: 10, outputTokens: 10, modelCostStatus: 'UNAVAILABLE',
      modelSegmentCount: 1, settledCostSegmentCount: 0, unsettledCostSegmentCount: 1,
    }] } } })
  })
  await page.goto('/system/agent-call-stats')
  const row = page.locator('.el-table__row').filter({ hasText: '冻结用例' })
  await expect(row.getByText('离线评测', { exact: true })).toBeVisible()
  await expect(row.getByRole('button', { name: '打开会话', exact: true })).toHaveCount(0)
  await expect(row.getByRole('button', { name: '耗时详情', exact: true })).toBeVisible()
  await page.locator('.el-select').filter({ has: page.getByText('会话类型', { exact: true }) }).click()
  await page.getByRole('option', { name: '离线评测', exact: true }).click()
  await page.getByRole('button', { name: '搜索', exact: true }).click()
  await expect.poll(() => queries.at(-1)?.searchParams.get('sessionType')).toBe('EVALUATION')
})
