import type { Page, Route } from '@playwright/test'
import { expect, test } from './fixtures/adminTestFixture'
const ok = (data: unknown) => ({ code: 0, data })
const values = ['billing:view', 'billing:quota-edit', 'billing:price-edit', 'billing:aggregate', 'billing:export']
const tenants = ['alpha', 'beta'].map((tenantCode, index) => ({ id: 101 + index, tenantCode, tenantName: index ? '租户乙' : '租户甲', status: 1 }))
const quota = { tenantId: 'alpha', period: 'MONTHLY', tokenLimit: 10000, amountLimit: 0, exceedAction: 'BLOCK', warnPercent: 80, enabled: true }
async function setup(page: Page) {
  await page.route('**/api/auth/permissions', r => r.fulfill({ json: ok(values) }))
  await page.route('**/api/tenant/current-view', r => r.fulfill({ json: ok({ userTenantId: 'default', effectiveTenantId: 'default', crossTenantAuthority: true }) }))
  await page.route('**/api/tenant/options', r => r.fulfill({ json: ok(tenants) }))
  await page.route('**/api/billing/price', r => r.request().method() === 'GET' ? r.fulfill({ json: ok([]) }) : r.fallback())
  await page.route('**/api/billing/overview?*', r => r.fulfill({ json: ok([{ tenantId: 'alpha', callCount: 1, totalTokens: 100, currency: 'CNY', amount: 1 }]) }))
  await page.route('**/api/billing/quota?*', r => r.request().method() === 'GET' ? r.fulfill({ json: ok(new URL(r.request().url()).searchParams.get('tenantId') === 'alpha' ? [quota] : []) }) : r.fallback())
  await page.goto('/system/billing')
  await expect(page.getByRole('tab', { name: '租户配额', exact: true })).toBeVisible()
}
async function relogin(page: Page) {
  await page.evaluate(async permissions => {
    const modulePath = '/src/store/auth.ts'
    const { useAuthStore } = await import(modulePath)
    const auth = useAuthStore()
    auth.applyLoginResult({ token: auth.token, nickname: '新登录', forceChangePassword: false,
      approvalStatus: 'APPROVED', approvalRemark: null }, 'new-user')
    auth.permissions = permissions
  }, values)
}
async function selectTenant(page: Page, placeholder: string, label: string) {
  const panel = page.getByRole('tabpanel', { name: placeholder === '选择租户' ? '租户配额' : '账单报表', exact: true })
  await panel.locator('.el-select').first().click()
  await page.getByRole('option', { name: label, exact: true }).click()
}
async function deliver(page: Page, held: Route, data: unknown) {
  const response = page.waitForResponse(r => r.url() === held.request().url() && r.request().method() === held.request().method())
  await held.fulfill({ json: ok(data) })
  await (await response).finished()
  await page.evaluate(() => new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))))
}

test('计费配额旧租户保存不能关闭新租户弹窗或改写目标', async ({ page }, testInfo) => {
  let held: Route | undefined
  const writes: Record<string, unknown>[] = []
  await page.route('**/api/billing/quota', r => {
    if (r.request().method() !== 'POST') return r.fallback()
    writes.push(r.request().postDataJSON())
    if (writes.length === 1) { held = r; return }
    return r.fulfill({ json: ok(null) })
  })
  await setup(page)
  await selectTenant(page, '选择租户', '租户甲（alpha）')
  await page.getByRole('button', { name: '编辑', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '租户配额', exact: true })
  await dialog.getByRole('button', { name: '保存配额', exact: true }).click()
  await expect.poll(() => Boolean(held)).toBe(true)
  await dialog.locator('.el-dialog__headerbtn').click()
  await selectTenant(page, '选择租户', '租户乙（beta）')
  await page.getByRole('button', { name: '新增配额', exact: true }).click()
  await expect(dialog.getByRole('textbox').first()).toHaveValue('beta')
  await deliver(page, held!, null)
  await expect(dialog).toBeVisible()
  await expect(dialog.getByRole('textbox').first()).toHaveValue('beta')
  await dialog.getByRole('button', { name: '保存配额', exact: true }).click()
  await expect(dialog).not.toBeVisible()
  expect(writes.map(row => row.tenantId)).toEqual(['alpha', 'beta'])
  await page.getByRole('button', { name: '新增配额', exact: true }).click()
  await page.setViewportSize({ width: 390, height: 844 })
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true)
  await expect(page.locator('.el-message')).toHaveCount(0)
  await page.screenshot({ animations: 'disabled', path: testInfo.outputPath('billing-quota-390.png') })
})

test('计费单价失败保留输入并可重试，旧成功不能关闭新单价', async ({ page }, testInfo) => {
  let count = 0
  let held: Route | undefined
  const writes: Record<string, unknown>[] = []
  await page.route('**/api/billing/price', r => {
    if (r.request().method() === 'GET') return r.fallback()
    count += 1
    writes.push(r.request().postDataJSON())
    if (count === 1) return r.fulfill({ json: { code: 50000, message: '单价暂时无法保存' } })
    if (count === 2) { held = r; return }
    return r.fulfill({ json: ok(502) })
  })
  await setup(page)
  await page.getByRole('tab', { name: '模型单价', exact: true }).click()
  await page.getByRole('button', { name: '新增单价', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '新增模型单价', exact: true })
  const input = dialog.getByPlaceholder('如 qwen-max', { exact: true })
  await input.fill('acceptance-model')
  await dialog.getByRole('button', { name: '保存价格', exact: true }).click()
  await expect(page.getByText('单价暂时无法保存', { exact: true })).toBeVisible()
  await expect(input).toHaveValue('acceptance-model')
  await dialog.getByRole('button', { name: '保存价格', exact: true }).click()
  await expect.poll(() => Boolean(held)).toBe(true)
  await dialog.locator('.el-dialog__headerbtn').click()
  await page.getByRole('button', { name: '新增单价', exact: true }).click()
  await input.fill('new-model')
  await deliver(page, held!, 501)
  await expect(dialog).toBeVisible()
  await expect(input).toHaveValue('new-model')
  await dialog.getByRole('button', { name: '保存价格', exact: true }).click()
  await expect(dialog).not.toBeVisible()
  expect(writes.map(row => row.modelName)).toEqual(['acceptance-model', 'acceptance-model', 'new-model'])
  await page.getByRole('button', { name: '新增单价', exact: true }).click()
  await input.fill('new-model')
  await page.setViewportSize({ width: 390, height: 844 })
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true)
  await expect(page.locator('.el-message')).toHaveCount(0)
  await page.screenshot({ animations: 'disabled', path: testInfo.outputPath('billing-price-390.png') })
})

test('计费归集在确认等待期间重新登录，不发送旧写入', async ({ page }) => {
  let writes = 0
  await page.route('**/api/billing/aggregate', r => { writes += 1; return r.fulfill({ json: ok(1) }) })
  await setup(page)
  await page.getByRole('tab', { name: '账单报表', exact: true }).click()
  await page.getByRole('button', { name: '手工归集', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '手工归集', exact: true })
  await expect(dialog).toBeVisible()
  await relogin(page)
  await dialog.getByRole('button', { name: '确定', exact: true }).click()
  await expect(dialog).not.toBeVisible()
  await page.evaluate(() => new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))))
  expect(writes).toBe(0)
})

test('账单导出使用当前已接受查询，修改筛选未查询不改变下载口径', async ({ page }) => {
  const exports: URL[] = []
  await page.route('**/api/billing/export?*', r => {
    exports.push(new URL(r.request().url()))
    return r.fulfill({ status: 200, contentType: 'text/csv', headers: { 'Content-Disposition': 'attachment; filename=billing.csv' }, body: 'tenantId,amount\nalpha,1\n' })
  })
  await setup(page)
  await page.getByRole('tab', { name: '账单报表', exact: true }).click()
  await expect(page.getByRole('cell', { name: 'alpha', exact: true })).toBeVisible()
  await selectTenant(page, '全部租户（总览）', '租户乙（beta）')
  await page.getByRole('button', { name: '导出 CSV', exact: true }).click()
  await expect.poll(() => exports.length).toBe(1)
  expect(exports[0].searchParams.get('tenantId')).toBeNull()
})
