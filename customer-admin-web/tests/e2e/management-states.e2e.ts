import { expect, test } from './fixtures/adminTestFixture'

test('模型请求失败时指标显示未知，重试得到空数据后才显示零', async ({ page }) => {
  let attempts = 0
  await page.route('**/api/aiconfig/model?*', (route) => {
    attempts += 1
    return route.fulfill({
      json:
        attempts === 1
          ? { code: 50000, message: '模型数据暂时不可用' }
          : { code: 0, data: { list: [], total: 0 } },
    })
  })
  await page.goto('/aiconfig/model')
  await expect(page.locator('.crud-load-state')).toBeVisible()
  await expect(page.locator('.summary-strip strong')).toHaveText(['—', '—', '—', '—'])
  await page.getByRole('button', { name: '重新加载', exact: true }).click()
  await expect(page.locator('.summary-strip strong')).toHaveText(['0', '0', '0', '0'])
})

for (const item of [
  {
    path: '/ticket/user-ticket',
    api: '/ticket/page',
    empty: '暂无符合条件的工单',
    payload: { items: [], total: 0 },
  },
  {
    path: '/aiconfig/agent',
    api: '/aiconfig/agent',
    empty: '暂无符合条件的智能体',
    payload: { list: [], total: 0 },
  },
  {
    path: '/aiconfig/mcp',
    api: '/aiconfig/mcp',
    empty: '暂无符合条件的 MCP',
    payload: { list: [], total: 0 },
  },
  {
    path: '/aiconfig/knowledge-base',
    api: '/aiconfig/knowledge-base/page',
    empty: '暂无符合条件的知识库',
    payload: { list: [], total: 0 },
  },
]) {
  test(`${item.path} 首次加载失败后可原地重试，不呈现空数据`, async ({ page }) => {
    let attempts = 0
    let failing = true
    await page.route(`**/api${item.api}?**`, async (route) => {
      // 智能体编辑器加载关联选项使用独立大页查询，不干扰列表故障场景。
      if (new URL(route.request().url()).searchParams.get('pageSize') !== '10')
        return route.fallback()
      attempts += 1
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(
          failing
            ? { code: 50000, message: '暂时无法连接数据服务' }
            : { code: 0, message: 'success', data: item.payload },
        ),
      })
    })

    await page.goto(item.path)
    const error = page.locator('.crud-load-state')
    await expect(error).toContainText('数据加载失败')
    await expect(page.getByText(item.empty, { exact: true })).not.toBeVisible()
    const attemptsBeforeRetry = attempts
    failing = false
    await error.getByRole('button', { name: '重新加载', exact: true }).click()
    await expect(error).not.toBeVisible()
    await expect(page.getByText(item.empty, { exact: true })).toBeVisible()
    expect(attempts).toBeGreaterThan(attemptsBeforeRetry)
  })
}

test('切换评测类型遇到失败时，不沿用前一类型的指标与数据集', async ({ page }) => {
  await page.route('**/api/eval/runs?*', (route) =>
    route.fulfill({
      json:
        new URL(route.request().url()).searchParams.get('type') === 'QUALITY'
          ? { code: 50000, message: '质量评测记录暂不可用' }
          : {
              code: 0,
              data: [
                {
                  runId: 'run-1',
                  evalType: 'INTENT',
                  total: 10,
                  passed: 9,
                  primaryMetric: 0.9,
                  secondaryMetric: 0.7,
                  failedCaseIds: ['case-1'],
                  failures: ['未命中'],
                  metrics: {},
                  trigger: 'MANUAL',
                  datasetSize: 10,
                  remark: '意图基线记录',
                  createdAtMs: 1789086000000,
                },
              ],
            },
    }),
  )
  await page.route('**/api/eval/datasets/INTENT/cases', (route) =>
    route.fulfill({
      json: {
        code: 0,
        data: [
          {
            caseId: 'intent-case-1',
            evalType: 'INTENT',
            input: '查询订单',
            expected: 'ORDER',
            category: '订单',
            source: 'MANUAL',
            enabled: true,
            originRef: null,
            createdAtMs: 1789086000000,
          },
        ],
      },
    }),
  )
  await page.route('**/api/eval/datasets/QUALITY/cases', (route) =>
    route.fulfill({
      json: { code: 50000, message: '质量评测用例暂不可用' },
    }),
  )
  await page.goto('/ops/eval')
  await expect(page.getByText('意图基线记录', { exact: true })).toBeVisible()
  await page.locator('.el-radio-button').filter({ hasText: '回复质量' }).click()
  await expect(page.locator('.eval-runs-error')).toContainText('数据加载失败')
  await expect(page.getByText('意图基线记录', { exact: true })).not.toBeVisible()
  await expect(page.locator('.summary-row .stat strong').first()).toHaveText('—')
  await expect(page.locator('.eval-dataset-error')).toContainText('数据加载失败')
  await expect(page.getByText('intent-case-1', { exact: true })).not.toBeVisible()
})

test('工单刷新失败保留已有记录，成功重试后替换', async ({ page }) => {
  let failNext = false
  let refreshed = false
  await page.route('**/api/ticket/page?**', async (route) => {
    const failing = failNext
    failNext = false
    const row = {
      id: 'ticket-example',
      sessionId: 'session-example',
      userId: 'customer-example',
      title: refreshed ? '配送时间已确认' : '需要核对配送时间',
      category: 'ORDER',
      priority: 'NORMAL',
      status: 'PROCESSING',
      assignee: 'seat-example',
      createdAtMs: 1789086000000,
      updatedAtMs: 1789086000000,
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(
        failing
          ? { code: 50000, message: '暂时无法连接数据服务' }
          : { code: 0, message: 'success', data: { total: 1, items: [row] } },
      ),
    })
  })
  await page.goto('/ticket/user-ticket')
  await expect(page.getByText('需要核对配送时间', { exact: true })).toBeVisible()
  await expect(page.getByText('实时在线', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '刷新', exact: true })).not.toHaveClass(/is-loading/)
  failNext = true
  await page.getByRole('button', { name: '刷新', exact: true }).click()
  await expect(page.locator('.crud-load-state')).toContainText('已保留上次结果')
  await expect(page.getByText('需要核对配送时间', { exact: true })).toBeVisible()
  refreshed = true
  await page.getByRole('button', { name: '重新加载', exact: true }).click()
  await expect(page.getByText('配送时间已确认', { exact: true })).toBeVisible()
  await expect(page.locator('.crud-load-state')).not.toBeVisible()
})
