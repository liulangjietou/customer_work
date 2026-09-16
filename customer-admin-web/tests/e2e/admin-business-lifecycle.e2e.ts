// 专用审核与行启停的业务生命周期回归。
import type { Page, Route } from '@playwright/test'
import { expect, test } from './fixtures/adminTestFixture'

const ok = (data: unknown) => ({ code: 0, data })
const permissions = ['user:view', 'user:edit', 'system-tool:view', 'system-tool:edit', 'rate-limit-rule:view', 'rate-limit-rule:edit']
const tenants = [{ tenantId: 'tenant-a', tenantName: '甲租户' }, { tenantId: 'tenant-b', tenantName: '乙租户' }]
const options = (tenantId: string) => ({ selectedTenantId: tenantId, tenants,
  roles: [{ id: tenantId === 'tenant-a' ? 71 : 72, roleName: tenantId === 'tenant-a' ? '甲客服' : '乙客服', roleCode: 'service', controlPlane: false }] })
const user = (id: number) => ({ id, username: `pending-${id}`, nickname: `待审成员 ${id}`, tenantId: 'tenant-a',
  email: `pending-${id}@example.com`, approvalStatus: 'PENDING', status: 1, roleIds: [], approvalRemark: '', createTime: '2026-09-15 08:00:00' })
async function userPage(page: Page) {
  await page.route('**/api/auth/permissions', route => route.fulfill({ json: ok(permissions) }))
  await page.route('**/api/tenant/current-view', route => route.fulfill({ json: ok({ tenantId: 'default', tenantName: '平台', crossTenantAuthority: true }) }))
  await page.route('**/api/system/user?*', route => route.fulfill({ json: ok({ total: 2, list: [user(41), user(42)] }) }))
  await page.route('**/api/system/user/approval-options*', route => route.fulfill({ json: ok(options(new URL(route.request().url()).searchParams.get('tenantId') ?? 'tenant-a')) }))
  await page.goto('/system/user')
  await expect(page.getByText('pending-41', { exact: true })).toBeVisible()
}
const review = (page: Page) => page.getByRole('dialog', { name: '注册账号审核', exact: true })
async function openReview(page: Page, id: number) {
  await page.getByRole('row').filter({ hasText: `pending-${id}` }).getByRole('button', { name: '审核', exact: true }).click()
  await expect(review(page)).toBeVisible()
}
async function sameTokenLogin(page: Page) {
  await page.evaluate(async permissions => {
    const path = '/src/store/auth.ts'; const { useAuthStore } = await import(path); const auth = useAuthStore()
    auth.applyLoginResult({ token: auth.token, nickname: '新登录', forceChangePassword: false, approvalStatus: 'APPROVED', approvalRemark: null }, 'admin')
    auth.permissions = permissions
  }, permissions)
}

test('旧审核目标的选项返回不能覆盖后来打开的审核表单', async ({ page }) => {
  await userPage(page)
  let first: Route | undefined
  let requestCount = 0
  await page.route('**/api/system/user/approval-options*', async route => {
    if (++requestCount === 1) { first = route; return }
    await route.fulfill({ json: ok(options('tenant-b')) })
  })
  await openReview(page, 41)
  await expect.poll(() => !!first).toBe(true)
  await review(page).getByRole('button', { name: '取消', exact: true }).click()
  await openReview(page, 42)
  const tenantField = review(page).locator('.el-form-item').filter({ hasText: '归属租户' })
  await expect(tenantField).toContainText('乙租户')
  await first!.fulfill({ json: ok(options('tenant-a')) })
  await page.waitForLoadState('networkidle')
  await expect(tenantField).toContainText('乙租户')
  await expect(review(page).locator('.review-user')).toContainText('pending-42')
})

test('旧审核提交的迟到响应不能关闭另一位成员的新表单', async ({ page }) => {
  await userPage(page)
  let submitted: Route | undefined
  await page.route('**/api/system/user/41/approval', route => { submitted = route })
  await openReview(page, 41)
  await review(page).locator('.el-radio-button').filter({ hasText: '拒绝' }).click()
  await review(page).getByRole('button', { name: '确认审核', exact: true }).click()
  await expect.poll(() => !!submitted).toBe(true)
  await review(page).getByRole('button', { name: '取消', exact: true }).click()
  await openReview(page, 42)
  await submitted!.fulfill({ json: ok(null) })
  await page.waitForLoadState('networkidle')
  await expect(review(page)).toBeVisible()
  await expect(review(page).locator('.review-user')).toContainText('pending-42')
  await expect(review(page).getByRole('button', { name: '确认审核', exact: true })).not.toHaveClass(/is-loading/)
})

test('同令牌重新登录废弃已打开的注册审核', async ({ page }) => {
  await userPage(page); await openReview(page, 41); await sameTokenLogin(page)
  await expect(review(page)).not.toBeVisible()
})

for (const rowAction of ['system-tool', 'rate-limit'] as const) {
  test(`${rowAction} 写入期间锁定当前行，失败后恢复原状态并允许重试`, async ({ page }) => {
    await page.route('**/api/auth/permissions', route => route.fulfill({ json: ok(permissions) }))
    let write: Route | undefined
    let writes = 0
    let enabled = true
    if (rowAction === 'system-tool') {
      await page.route('**/api/system-tool?*', route => route.fulfill({ json: ok({ total: 1, list: [{ id: 73, toolCode: 'devtoolbox', toolName: '开发工具箱', description: '纯计算工具', enabled: enabled ? 1 : 0, remark: '', updateTime: '2026-09-15' }] }) }))
      await page.route('**/api/system-tool/73', route => { writes += 1; write = route })
      await page.goto('/aiconfig/system-tool')
      const toggle = page.getByRole('switch').first()
      await page.locator('.el-switch').first().click(); await expect.poll(() => writes).toBe(1)
      await expect(toggle).toBeDisabled()
      await write!.fulfill({ json: { code: 50000, message: '保存暂时失败' } })
      await expect(toggle).toBeEnabled(); await expect(toggle).toBeChecked()
      await page.locator('.el-switch').first().click(); await expect.poll(() => writes).toBe(2)
      expect(write!.request().postDataJSON()).toMatchObject({ enabled: 0, toolName: '开发工具箱' })
      enabled = false
      await write!.fulfill({ json: ok(null) })
      await expect(toggle).toBeEnabled(); await expect(toggle).not.toBeChecked()
    } else {
      await page.route('**/api/contentguard/rate-limit-rule/page?*', route => route.fulfill({ json: ok({ total: 1, list: [{ id: 74, ruleName: '客服限流', pathPrefix: '/api/customer/', dimension: 'GLOBAL', limitCount: 60, algorithm: 'FIXED_WINDOW', windowSeconds: 60, priority: 10, enabled, updatedAtMs: Date.now() }] }) }))
      await page.route('**/api/contentguard/rate-limit-rule/74/enabled?*', route => { writes += 1; write = route })
      await page.goto('/contentguard/rate-limit')
      const toggle = page.getByRole('button', { name: '停用', exact: true })
      await toggle.click(); await expect.poll(() => writes).toBe(1)
      await expect(toggle).toBeDisabled()
      await write!.fulfill({ json: { code: 50000, message: '保存暂时失败' } })
      await expect(toggle).toBeEnabled()
      await toggle.click(); await expect.poll(() => writes).toBe(2)
      expect(new URL(write!.request().url()).searchParams.get('enabled')).toBe('false')
      enabled = false
      await write!.fulfill({ json: ok(null) })
      await expect(page.getByRole('button', { name: '启用', exact: true })).toBeVisible()
    }
    expect(writes).toBe(2)
  })
}


test('只读系统工具仍显示启停状态，不能写入', async ({ page }) => {
  await page.route('**/api/auth/permissions', route => route.fulfill({ json: ok(['system-tool:view']) }))
  await page.route('**/api/system-tool?*', route => route.fulfill({ json: ok({ total: 1, list: [{
    id: 73, toolCode: 'devtoolbox', toolName: '开发工具箱', description: '纯计算工具', enabled: 1,
  }] }) }))
  await page.goto('/aiconfig/system-tool')
  const row = page.getByRole('row').filter({ hasText: '开发工具箱' })
  await expect(row.locator('.el-tag')).toHaveText('启用')
  await expect(row.getByRole('button', { name: '编辑', exact: true })).not.toBeVisible()
  await expect(row.locator('.el-switch')).not.toBeVisible()
})

test('审核选项加载失败有原地重试，恢复前不允许提交已有租户的通过决定', async ({ page }) => {
  await userPage(page)
  let fail = true
  await page.route('**/api/system/user/approval-options*', route => route.fulfill({ json: fail
    ? { code: 50000, message: '审核选项暂不可用' } : ok(options('tenant-a')) }))
  await openReview(page, 41)
  const error = review(page).getByRole('alert').filter({ hasText: '审核选项加载失败' })
  await expect(error).toBeVisible()
  await expect(review(page).getByRole('button', { name: '确认审核', exact: true })).toBeDisabled()
  fail = false
  await error.getByRole('button', { name: '重新加载', exact: true }).click()
  await expect(error).not.toBeVisible()
  await expect(review(page).locator('.el-form-item').filter({ hasText: '归属租户' })).toContainText('甲租户')
  await expect(review(page).getByRole('button', { name: '确认审核', exact: true })).toBeEnabled()
})

for (const decision of ['existing', 'new', 'rejected'] as const) {
  test(`注册审核 ${decision} 分支保存失败保留输入，重试提交准确归属`, async ({ page }) => {
    await userPage(page)
    const payloads: unknown[] = []
    let pending: Route | undefined
    await page.route('**/api/system/user/41/approval', route => {
      expect(route.request().method()).toBe('PUT')
      payloads.push(route.request().postDataJSON())
      pending = route
    })
    await openReview(page, 41)
    await expect(review(page).locator('.el-form-item').filter({ hasText: '归属租户' })).toContainText('甲租户')
    if (decision === 'existing') {
      await review(page).locator('.el-form-item').filter({ hasText: '分配角色' }).locator('.el-select').click()
      await page.getByRole('option', { name: '甲客服 (service)', exact: true }).click()
      await review(page).locator('.review-user').click()
    } else if (decision === 'new') {
      await review(page).locator('.el-radio-button').filter({ hasText: '新开租户' }).click()
      await review(page).getByPlaceholder('字母、数字、连字符或下划线，创建后不可修改').fill('new-service')
      await review(page).getByPlaceholder('展示用名称').fill('新服务团队')
    } else {
      await review(page).locator('.el-radio-button').filter({ hasText: '拒绝' }).click()
    }
    const remark = review(page).locator('.el-form-item').filter({ hasText: '审核说明' }).locator('textarea')
    await remark.fill('已核对账号归属与服务范围')
    const submit = review(page).getByRole('button', { name: '确认审核', exact: true })
    await submit.click()
    await expect.poll(() => payloads.length).toBe(1)
    await expect(remark).toBeDisabled()
    await pending!.fulfill({ json: { code: 50000, message: '审核保存暂不可用' } })
    await expect(remark).toBeEnabled()
    await expect(remark).toHaveValue('已核对账号归属与服务范围')
    await submit.click()
    await expect.poll(() => payloads.length).toBe(2)
    expect(payloads[1]).toEqual(payloads[0])
    expect(payloads[1]).toEqual({
      decision: decision === 'rejected' ? 'REJECTED' : 'APPROVED',
      tenantId: decision === 'new' ? null : 'tenant-a',
      roleIds: decision === 'existing' ? [71] : [],
      remark: '已核对账号归属与服务范围',
      newTenant: decision === 'new'
        ? { tenantCode: 'new-service', tenantName: '新服务团队', contactEmail: 'pending-41@example.com' } : null,
    })
    await pending!.fulfill({ json: ok(null) })
    await expect(review(page)).not.toBeVisible()
  })
}

for (const item of [
  { path: '/system/user', api: '/system/user', permission: 'user:view', empty: '暂无符合条件的用户',
    row: { ...user(41), username: '验收成员', nickname: '跨区域服务团队'.repeat(30) }, marker: '验收成员' },
  { path: '/aiconfig/system-tool', api: '/system-tool', permission: 'system-tool:view', empty: '暂无系统工具',
    row: { id: 73, toolCode: 'devtoolbox', toolName: '验收工具', description: '安全纯计算工具说明'.repeat(30), enabled: 1 }, marker: '验收工具' },
  { path: '/contentguard/rate-limit', api: '/contentguard/rate-limit-rule/page', permission: 'rate-limit-rule:view', empty: '暂无符合条件的限流规则',
    row: { id: 74, ruleName: '验收规则', pathPrefix: '/api/customer/'.repeat(30), dimension: 'GLOBAL',
      limitCount: 60, algorithm: 'FIXED_WINDOW', windowSeconds: 60, priority: 10, enabled: true }, marker: '验收规则' },
]) {
  test(`${item.path} 逐页只读数据、失败重试、空态与 390px 长文本`, async ({ page }, testInfo) => {
    let state: 'failed' | 'data' | 'empty' = 'failed'
    const writes: string[] = []
    page.on('request', request => {
      if (request.url().includes('/api/') && !['GET', 'HEAD', 'OPTIONS'].includes(request.method())) writes.push(request.url())
    })
    await page.route('**/api/auth/permissions', route => route.fulfill({ json: ok([item.permission]) }))
    await page.route(`**/api${item.api}?*`, route => route.fulfill({ json: state === 'failed'
      ? { code: 50000, message: '列表暂不可用' }
      : ok({ total: state === 'data' ? 1 : 0, list: state === 'data' ? [item.row] : [] }) }))
    await page.goto(item.path)
    const error = page.locator('.crud-load-state')
    await expect(error).toBeVisible()
    await expect(page.getByText(item.empty, { exact: true })).not.toBeVisible()
    state = 'data'
    await error.getByRole('button', { name: '重新加载', exact: true }).click()
    await expect(page.getByText(item.marker, { exact: true })).toBeVisible()
    const row = page.getByRole('row').filter({ hasText: item.marker })
    await expect(row.getByRole('button', { name: /编辑|删除|审核|启用|停用/ })).toHaveCount(0)
    await page.setViewportSize({ width: 390, height: 844 })
    await expect.poll(() => page.locator('.layout-aside').evaluate(element => element.getBoundingClientRect().width)).toBeLessThanOrEqual(1)
    await expect.poll(async () => (await row.boundingBox())?.height ?? 0).toBeLessThanOrEqual(96)
    await expect(page.getByRole('columnheader', { name: '操作', exact: true })).toHaveCount(0)
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390)
    await expect(page.getByText('列表暂不可用', { exact: true })).toHaveCount(0)
    await page.screenshot({ path: testInfo.outputPath('readonly-data-390.png') })
    state = 'empty'
    await page.getByRole('button', { name: '搜索', exact: true }).click()
    await expect(page.getByText(item.empty, { exact: true })).toBeVisible()
    await expect(page.getByText(item.marker, { exact: true })).toHaveCount(0)
    expect(writes).toEqual([])
  })
}

for (const preset of ['ember', 'night'] as const) {
  test(`${preset} 审核长说明在 390px 保留输入和可到达的操作`, async ({ page }, testInfo) => {
    await userPage(page)
    await page.setViewportSize({ width: 390, height: 844 })
    await page.evaluate(async preset => {
      const path = '/src/store/theme.ts'; const { useThemeStore } = await import(path)
      useThemeStore().selectPreset(preset)
    }, preset)
    await openReview(page, 41)
    const remark = review(page).locator('.el-form-item').filter({ hasText: '审核说明' }).locator('textarea')
    const text = '账号归属和服务范围已核对，需保留完整说明。'.repeat(8)
    await remark.fill(text)
    await expect(remark).toHaveValue(text)
    const cancel = review(page).getByRole('button', { name: '取消', exact: true })
    await cancel.scrollIntoViewIfNeeded()
    expect(await review(page).evaluate(element => element.scrollWidth <= element.clientWidth + 1)).toBe(true)
    await review(page).screenshot({ path: testInfo.outputPath(`review-${preset}-390.png`) })
    await cancel.click()
    await expect(review(page)).not.toBeVisible()
  })
}
