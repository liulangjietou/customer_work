import { expect, test } from './fixtures/adminTestFixture'

test('导航分组内滚动，六个分组保持可见且内容紧接当前分组', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 800 })
  await page.goto('/aiconfig/mcp')
  const navigation = page.getByRole('navigation', { name: '智能体生命周期导航' })
  const build = navigation.getByRole('button', { name: '构建', exact: true })
  const settings = navigation.getByRole('button', { name: '设置', exact: true })
  await expect(build).toHaveAttribute('aria-expanded', 'true')
  for (const label of ['总览', '智能体', '构建', '运营', '治理', '设置']) {
    const bounds = await navigation.getByRole('button', { name: label, exact: true }).boundingBox()
    expect(bounds).not.toBeNull()
    expect(bounds!.y).toBeGreaterThanOrEqual(0)
    expect(bounds!.y + bounds!.height).toBeLessThanOrEqual(800)
  }
  const groupBox = await build.boundingBox()
  const contextBox = await page.locator('.context-pane').boundingBox()
  const settingsBox = await settings.boundingBox()
  expect(contextBox!.y).toBeGreaterThanOrEqual(groupBox!.y + groupBox!.height)
  expect(contextBox!.y + contextBox!.height).toBeLessThanOrEqual(settingsBox!.y)
  await page.getByRole('menuitem', { name: '模型管理', exact: true }).click()
  await expect(page).toHaveURL(/\/aiconfig\/model$/)
  await expect(page.locator('#cw-page-title')).toHaveText('模型管理')
})

test('图表在导航引起的容器变化后与画布尺寸对齐', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.route('**/api/agent-call-stats/trend?**', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 0,
        message: 'success',
        data: [
          {
            bucket: '2026-09-10',
            count: 8,
            avgDurationMs: 800,
            avgModelMs: 500,
            avgToolMs: 200,
            avgMcpMs: 100,
            avgSkillMs: 0,
            totalTokens: 900,
          },
          {
            bucket: '2026-09-11',
            count: 12,
            avgDurationMs: 700,
            avgModelMs: 400,
            avgToolMs: 200,
            avgMcpMs: 100,
            avgSkillMs: 0,
            totalTokens: 1200,
          },
        ],
      }),
    }),
  )
  await page.goto('/home')
  const chart = page.locator('.trend-chart').first()
  await expect(chart.locator('canvas')).toBeVisible()
  const width = () => chart.evaluate((element) => element.getBoundingClientRect().width)
  const difference = () =>
    chart.evaluate((element) => {
      const canvas = element.querySelector('canvas')!
      return Math.abs(element.getBoundingClientRect().width - canvas.getBoundingClientRect().width)
    })
  const expandedWidth = await width()
  await page.getByRole('button', { name: '收起导航栏', exact: true }).click()
  await expect.poll(width).toBeGreaterThan(expandedWidth + 30)
  await expect.poll(difference).toBeLessThanOrEqual(1)
  await page.getByRole('button', { name: '展开导航栏', exact: true }).click()
  await expect.poll(width).toBeLessThanOrEqual(expandedWidth + 1)
  await expect.poll(difference).toBeLessThanOrEqual(1)
})
