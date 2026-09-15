import type { Page, Route } from '@playwright/test'
import { expect, test } from './fixtures/adminTestFixture'
const ok = (data: unknown) => ({ code: 0, data })
const placeholder = '粘贴改动前的内容…'
async function relogin(page: Page, username: string) {
  await page.evaluate(async name => {
    const modulePath = '/src/store/auth.ts'
    const { useAuthStore } = await import(modulePath)
    const auth = useAuthStore()
    auth.applyLoginResult({ token: auth.token, nickname: name, forceChangePassword: false,
      approvalStatus: 'APPROVED', approvalRemark: null }, name)
    auth.permissions = ['devtools:view']
  }, username)
}
async function setup(page: Page) {
  await page.route('**/api/auth/permissions', r => r.fulfill({ json: ok(['devtools:view']) }))
  await page.goto('/system/devtools?tool=diff')
}
test('工具输入按主体持久化，重登另一主体不会读到原输入，再回原主体可恢复', async ({ page }) => {
  await setup(page)
  const input = page.getByPlaceholder(placeholder, { exact: true })
  await input.fill('OWNER-A-SYNTHETIC-TEXT')
  await page.reload()
  await expect(input).toHaveValue('OWNER-A-SYNTHETIC-TEXT')
  await relogin(page, 'owner-b')
  await expect(input).toHaveValue('')
  await input.fill('OWNER-B-SYNTHETIC-TEXT')
  await relogin(page, 'admin-shell-e2e')
  await expect(input).toHaveValue('OWNER-A-SYNTHETIC-TEXT')
})
test('同一主体切换租户视角，工具输入与先前租户隔离', async ({ page }) => {
  let tenant = 'tenant-a'
  await page.route('**/api/tenant/current-view', r => r.fulfill({ json: ok({ userTenantId: 'tenant-a', effectiveTenantId: tenant, crossTenantAuthority: false }) }))
  await setup(page)
  const input = page.getByPlaceholder(placeholder, { exact: true })
  await input.fill('TENANT-A-SYNTHETIC-TEXT')
  tenant = 'tenant-b'
  await page.reload()
  await expect(input).toHaveValue('')
  await input.fill('TENANT-B-SYNTHETIC-TEXT')
  tenant = 'tenant-a'
  await page.reload()
  await expect(input).toHaveValue('TENANT-A-SYNTHETIC-TEXT')
})
test('旧无归属工具存储保留原数据，不能自动读成当前用户输入', async ({ page }) => {
  await setup(page)
  await page.evaluate(() => localStorage.setItem('devtools:diff:oldText', JSON.stringify('LEGACY-OWNER-UNKNOWN')))
  await page.reload()
  await expect(page.getByPlaceholder(placeholder, { exact: true })).toHaveValue('')
  expect(await page.evaluate(() => localStorage.getItem('devtools:diff:oldText'))).toBe(JSON.stringify('LEGACY-OWNER-UNKNOWN'))
})
test('工具租户上下文读取失败时可重试，未知归属时不显示可编辑工具', async ({ page }) => {
  let failing = true
  await page.route('**/api/tenant/current-view', r => r.fulfill({ json: failing
    ? { code: 50000, message: '当前视角暂不可读' }
    : ok({ userTenantId: 'tenant-a', effectiveTenantId: 'tenant-a', crossTenantAuthority: false }) }))
  await setup(page)
  const panel = page.locator('.devtoolbox-panel')
  await expect(panel.locator('.crud-load-state')).toBeVisible()
  await expect(page.getByPlaceholder(placeholder, { exact: true })).toHaveCount(0)
  failing = false
  await panel.locator('.crud-load-state').getByRole('button').click()
  await expect(page.getByPlaceholder(placeholder, { exact: true })).toBeVisible()
})
test('租户切换器同令牌重登后不接受先前的控制面选项', async ({ page }) => {
  let held: Route | undefined
  let calls = 0
  let optionRequests = 0
  await page.route('**/api/tenant/current-view', r => {
    calls += 1
    if (calls === 1) { held = r; return }
    return r.fulfill({ json: ok({ userTenantId: 'tenant-new', effectiveTenantId: 'tenant-new', crossTenantAuthority: false }) })
  })
  await page.route('**/api/tenant/options', r => { optionRequests += 1; return r.fulfill({ json: ok([{ id: 1, tenantCode: 'foreign-tenant', tenantName: '旧控制面可见租户', status: 'ACTIVE' }]) }) })
  await page.goto('/home')
  await expect.poll(() => Boolean(held)).toBe(true)
  await relogin(page, 'new-tenant-user')
  const response = page.waitForResponse(r => r.url() === held!.request().url())
  await held!.fulfill({ json: ok({ userTenantId: 'default', effectiveTenantId: 'default', crossTenantAuthority: true }) })
  await (await response).finished()
  await page.evaluate(() => new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))))
  await expect(page.locator('.tenant-switcher')).toHaveCount(0)
  expect(optionRequests, '旧控制面响应不能借新身份继续读取控制面选项').toBe(0)
})

async function switcherPage(page: Page, view: { userTenantId: string; effectiveTenantId: string; crossTenantAuthority: boolean }) {
  await page.route('**/api/auth/permissions', route => route.fulfill({ json: ok(['devtools:view']) }))
  await page.route('**/api/tenant/current-view', route => route.fulfill({ json: ok(view) }))
  await page.route('**/api/tenant/options', route => route.fulfill({ json: ok([
    { id: 901, tenantCode: 'tenant-a', tenantName: '验收切换租户', status: 'ACTIVE' },
  ]) }))
  await page.goto('/home')
  await expect(page.locator('.tenant-switcher')).toBeVisible()
}
async function chooseTenant(page: Page) {
  await page.locator('.tenant-switcher').click()
  await page.getByRole('option', { name: '验收切换租户（tenant-a）', exact: true }).click()
}

test('租户切换失败保留原视角，可重试并在成功后加载真实目标视角', async ({ page }) => {
  const view = { userTenantId: 'default', effectiveTenantId: 'default', crossTenantAuthority: true }
  const writes: string[] = []
  let failing = true
  await page.route('**/api/tenant/switch-view?*', route => {
    writes.push(new URL(route.request().url()).searchParams.get('tenantCode') ?? '')
    if (failing) return route.fulfill({ json: { code: 50000, message: '租户切换暂时失败' } })
    view.effectiveTenantId = 'tenant-a'
    return route.fulfill({ json: ok(null) })
  })
  await switcherPage(page, view)
  await chooseTenant(page)
  await expect.poll(() => writes.length).toBe(1)
  await expect(page.locator('.tenant-switcher').getByRole('combobox')).toBeEnabled()
  await expect(page.locator('.tenant-switcher')).toContainText('自身租户（default）')
  failing = false
  const reloaded = page.waitForEvent('load')
  await chooseTenant(page)
  await reloaded
  await expect(page.locator('.tenant-switcher')).toContainText('验收切换租户（tenant-a）')
  expect(writes).toEqual(['tenant-a', 'tenant-a'])
})

test('旧租户切换写入完成不能重载后来登录的页面', async ({ page }) => {
  const view = { userTenantId: 'default', effectiveTenantId: 'default', crossTenantAuthority: true }
  let held: Route | undefined
  let navigations = 0
  await page.route('**/api/tenant/switch-view?*', route => { held = route })
  await switcherPage(page, view)
  page.on('request', request => { if (request.isNavigationRequest() && request.frame() === page.mainFrame()) navigations += 1 })
  await chooseTenant(page)
  await expect.poll(() => Boolean(held)).toBe(true)
  view.userTenantId = 'tenant-b'
  view.effectiveTenantId = 'tenant-b'
  view.crossTenantAuthority = false
  await relogin(page, 'tenant-b-user')
  await expect(page.locator('.tenant-switcher')).toHaveCount(0)
  const response = page.waitForResponse(r => r.url() === held!.request().url())
  await held!.fulfill({ json: ok(null) })
  await (await response).finished()
  await page.evaluate(() => new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))))
  expect(navigations).toBe(0)
})
