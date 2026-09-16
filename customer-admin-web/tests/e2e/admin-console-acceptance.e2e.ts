import type { Page, Route } from '@playwright/test'
import { expect, test } from './fixtures/adminTestFixture'
const ok = (data: unknown) => ({ code: 0, data })
const queryResult = { columns: ['name'], rows: [{ name: '验收SQL结果' }], total: 1, useMillis: 3 }
async function setupSql(page: Page) {
  await page.route('**/api/auth/permissions', r => r.fulfill({ json: ok(['sql-console:query']) }))
  await page.route('**/api/sql/datasource/all', r => r.fulfill({ json: ok([{ id: 91, name: '验收数据源',
    jdbcUrl: 'jdbc:mysql://localhost/acceptance', username: 'acceptance', passwordMasked: '******', enabled: true,
    remark: null, createTime: '2026-09-15 12:00:00', updateTime: '2026-09-15 12:00:00' }]) }))
  await page.route('**/api/sql/query/adhoc/databases?*', r => r.fulfill({ json: ok(['acceptance_db']) }))
  await page.route('**/api/sql/query/adhoc/tables?*', r => r.fulfill({ json: ok(['records']) }))
  await page.goto('/workbench/sql-console')
  await page.locator('.console-body .sidebar').getByRole('combobox').click()
  await page.getByRole('option', { name: '验收数据源', exact: true }).click()
  await expect(page.getByRole('button', { name: /acceptance_db/ })).toBeVisible()
  const input = page.getByPlaceholder(/^输入只读 SQL/)
  await input.fill('select name from acceptance_db.records')
  return input
}

test('SQL 控制台查询失败保留已有结果、恢复后可确认空结果', async ({ page }, testInfo) => {
  let mode: 'data' | 'error' | 'empty' = 'data'
  const payloads: unknown[] = []
  await page.route('**/api/sql/query/adhoc', r => {
    payloads.push(r.request().postDataJSON())
    return r.fulfill({ json: mode === 'error' ? { code: 50000, message: 'SQL查询暂时失败' }
      : ok(mode === 'empty' ? { ...queryResult, rows: [], total: 0 } : queryResult) })
  })
  await setupSql(page)
  const execute = page.getByRole('button', { name: '执行（Ctrl+Enter）', exact: true })
  await execute.click()
  await expect(page.getByRole('row').filter({ hasText: '验收SQL结果' })).toBeVisible()
  expect(payloads[0]).toEqual({ datasourceId: 91, sql: 'select name from acceptance_db.records' })
  mode = 'error'
  await execute.click()
  const error = page.locator('.layout-main .crud-load-state:visible').first()
  await expect(error).toContainText('已保留上次结果')
  await expect(page.getByRole('row').filter({ hasText: '验收SQL结果' })).toBeVisible()
  mode = 'data'
  await error.getByRole('button', { name: '重新加载' }).click()
  await expect(error).toHaveCount(0)
  await expect(page.getByRole('button', { name: '导出 Excel', exact: true })).toHaveCount(0)
  await page.setViewportSize({ width: 390, height: 844 })
  await expect.poll(() => page.locator('.layout-aside').evaluate(e => e.getBoundingClientRect().width)).toBeLessThanOrEqual(1)
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390)
  await expect(page.getByText('SQL查询暂时失败', { exact: true })).toHaveCount(0)
  await page.screenshot({ path: testInfo.outputPath('sql-console-390.png') })
  mode = 'empty'
  await execute.click()
  await expect(page.getByText('没有查询到数据', { exact: true })).toBeVisible()
})

test('SQL 控制台同令牌重登后丢弃旧查询结果', async ({ page }) => {
  let held: Route | undefined
  await page.route('**/api/sql/query/adhoc', r => { held = r })
  const input = await setupSql(page)
  await input.press('Control+Enter')
  await expect.poll(() => Boolean(held)).toBe(true)
  await page.evaluate(async () => {
    const modulePath = '/src/store/auth.ts'
    const { useAuthStore } = await import(modulePath)
    const auth = useAuthStore()
    auth.applyLoginResult({ token: auth.token, nickname: '新身份', forceChangePassword: false,
      approvalStatus: 'APPROVED', approvalRemark: null }, 'new-user')
    auth.permissions = ['sql-console:query']
  })
  const response = page.waitForResponse(r => r.url() === held!.request().url())
  await held!.fulfill({ json: ok(queryResult) })
  await (await response).finished()
  await page.evaluate(() => new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))))
  await expect(page.getByRole('row').filter({ hasText: '验收SQL结果' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: '执行（Ctrl+Enter）', exact: true })).toBeEnabled()
})

test('开发工具保留非法 JSON 并支持定位、修复、工具搜索及深链接', async ({ page }, testInfo) => {
  await page.route('**/api/auth/permissions', r => r.fulfill({ json: ok(['devtools:view']) }))
  await page.goto('/system/devtools?tool=invalid&review=acceptance')
  await expect(page).toHaveURL(/tool=json/)
  await expect(page).toHaveURL(/review=acceptance/)
  const input = page.locator('.code-textarea')
  await input.fill('{"name": }')
  await expect(page.locator('.error-hint')).toBeVisible()
  await page.getByRole('button', { name: '定位到输入框', exact: true }).click()
  await expect(input).toBeFocused()
  await expect(input).toHaveValue('{"name": }')
  await input.fill('{"name":"客服验收","count":2}')
  await expect(page.locator('.error-hint')).toHaveCount(0)
  await expect(page.locator('.output-scroll')).toContainText('客服验收')
  await page.getByPlaceholder('搜索工具').fill('不存在的工具')
  await expect(page.getByText('没有匹配的工具', { exact: true })).toBeVisible()
  await expect(page.locator('.output-scroll')).toContainText('客服验收')
  await page.getByPlaceholder('搜索工具').fill('')
  await page.setViewportSize({ width: 390, height: 844 })
  await expect.poll(() => page.locator('.layout-aside').evaluate(e => e.getBoundingClientRect().width)).toBeLessThanOrEqual(1)
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390)
  await page.screenshot({ path: testInfo.outputPath('devtools-390.png') })
})
