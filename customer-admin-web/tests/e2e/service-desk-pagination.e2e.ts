import { expect, test } from './fixtures/adminTestFixture'
import { deskFixture, ticket } from './fixtures/serviceDeskFixture'

test('工单队列可按 20 条分页，改变页大小回到首页并保留正在编辑的草稿', async ({ page }, testInfo) => {
  const invalidProps: string[] = []
  page.on('console', message => {
    if (message.type() === 'warning' && message.text().includes('Invalid prop:')) invalidProps.push(message.text())
  })
  await deskFixture(page)
  await page.setViewportSize({ width: 1680, height: 1050 })
  const requests: Array<{ page: number; size: number }> = []
  await page.route('**/api/ticket/page?**', r => {
    const url = new URL(r.request().url())
    const current = Number(url.searchParams.get('pageNum'))
    const size = Number(url.searchParams.get('pageSize'))
    requests.push({ page: current, size })
    const start = (current - 1) * size
    return r.fulfill({ json: { code: 0, data: {
      total: 10000, items: Array.from({ length: size }, (_, i) => ticket(`TK-${start + i + 1}`)),
    } } })
  })
  await page.goto('/ticket/user-ticket')
  await expect(page.getByText('实时在线', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '查看', exact: true })).toHaveCount(10)
  await page.getByRole('button', { name: '查看', exact: true }).first().click()
  const input = page.getByRole('textbox', { name: '回复客户', exact: true })
  await input.fill('核对中，切换分页不能丢失。')
  const pagination = page.locator('.queue-pagination')
  await pagination.locator('.btn-next').click()
  await expect.poll(() => requests.at(-1)).toEqual({ page: 2, size: 10 })
  await expect(page.getByText('售后进度 TK-11', { exact: true })).toBeVisible()
  const beforeResize = requests.length
  await pagination.locator('.el-select').click()
  await page.getByRole('option', { name: /20\s*条/ }).click()
  await expect.poll(() => requests.at(-1)).toEqual({ page: 1, size: 20 })
  await expect(page.getByRole('button', { name: '查看', exact: true })).toHaveCount(20)
  expect(requests).toHaveLength(beforeResize + 1)
  await expect(input).toHaveValue('核对中，切换分页不能丢失。')

  await page.setViewportSize({ width: 390, height: 844 })
  await page.getByRole('button', { name: '返回工单队列' }).click()
  await pagination.locator('.el-select').click()
  await page.getByRole('option', { name: /50\s*条/ }).click()
  await expect.poll(() => requests.at(-1)).toEqual({ page: 1, size: 50 })
  await expect(page.getByRole('button', { name: '查看', exact: true })).toHaveCount(50)
  await expect(pagination.locator('.btn-next')).toBeVisible()
  const bounds = await pagination.boundingBox()
  expect(bounds).not.toBeNull()
  expect(bounds!.x).toBeGreaterThanOrEqual(0)
  expect(bounds!.x + bounds!.width).toBeLessThanOrEqual(390)
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390)
  await expect(page.getByRole('option', { name: /50\s*条/ })).toBeHidden()
  await page.screenshot({ path: testInfo.outputPath('desk-pagination-ocean-390.png') })

  await pagination.locator('.el-select').click()
  await page.getByRole('option', { name: /10\s*条/ }).click()
  await expect.poll(() => requests.at(-1)).toEqual({ page: 1, size: 10 })
  await expect(page.getByRole('button', { name: '查看', exact: true })).toHaveCount(10)
  await page.getByRole('button', { name: '查看', exact: true }).first().click()
  await expect(input).toHaveValue('核对中，切换分页不能丢失。')
  expect(invalidProps, '工单标签参数必须符合组件契约，避免重复警告阻塞渲染').toEqual([])
})
