import type { Page, Route } from '@playwright/test'
import { expect, test } from './fixtures/adminTestFixture'

const ok = (data: unknown) => ({ code: 0, data })
const view = (crossTenantAuthority: boolean) => ({ userTenantId: 'default', effectiveTenantId: 'default', crossTenantAuthority })
const role = (id: number, roleName: string, controlPlane = false) => ({ id, roleName, roleCode: `role-${id}`, controlPlane })
const agent = (id: number, agentName: string) => ({ id, agentName, agentCode: `agent-${id}`, status: 1 })
const cases = [
  { label: '用户角色', path: '/system/user', api: '/api/system/role',
    permissions: ['user:view', 'user:add', 'user:edit', 'role:view'],
    open: '新建用户', title: '新建用户', field: '角色', save: '保存用户', theme: 'Ember 暖焰',
    data: role(71, '恢复角色'), name: '恢复角色', old: role(72, '旧身份角色'), oldName: '旧身份角色' },
  { label: '定时任务智能体', path: '/aiconfig/scheduled-task', api: '/api/aiconfig/agent',
    permissions: ['scheduler:view', 'scheduler:add', 'agent:view'],
    open: '新建定时任务', title: '新建定时任务', field: '关联智能体', save: '保存任务', theme: 'Night 夜航',
    data: agent(71, '恢复智能体'), name: '恢复智能体', old: agent(72, '旧身份智能体'), oldName: '旧身份智能体' },
]

async function identity(page: Page, permissions: string[]) {
  await page.evaluate(async permissions => {
    const modulePath = '/src/store/auth.ts'
    const { useAuthStore } = await import(modulePath)
    const auth = useAuthStore()
    auth.applyLoginResult({ token: auth.token, nickname: '新身份', forceChangePassword: false,
      approvalStatus: 'APPROVED', approvalRemark: null }, 'new-user')
    auth.permissions = permissions
  }, permissions)
}
async function finish(page: Page, held: Route, data: unknown) {
  const response = page.waitForResponse(r => r.url() === held.request().url())
  await held.fulfill({ json: ok(data) })
  await (await response).finished()
  await page.evaluate(() => new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))))
}

for (const item of cases) {
  test(`${item.label} 选项失败可重试，恢复前不能提交依赖选项的表单`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width: 390, height: 844 })
    let fail = true
    await page.route('**/api/auth/permissions', r => r.fulfill({ json: ok(item.permissions) }))
    await page.route('**/api/tenant/current-view', r => r.fulfill({ json: ok(view(false)) }))
    await page.route(url => url.pathname === item.api, r => r.fulfill({ json: fail
      ? { code: 50000, message: '选项加载暂时失败' } : ok({ list: [item.data], total: 1 }) }))
    await page.goto(item.path)
    await expect(page.locator('.el-message')).toHaveCount(0)
    await page.getByLabel('选择界面主题', { exact: true }).click()
    await page.getByRole('option').filter({ hasText: item.theme }).click()
    await page.getByRole('button', { name: item.open, exact: true }).click()
    const dialog = page.getByRole('dialog', { name: item.title, exact: true })
    await expect(dialog.getByRole('button', { name: '重新加载', exact: true })).toBeVisible()
    await expect(dialog.getByRole('button', { name: item.save, exact: true })).toBeDisabled()
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true)
    await expect(page.locator('.el-message')).toHaveCount(0)
    await page.screenshot({ path: testInfo.outputPath('options-error-390.png'), animations: 'disabled' })
    fail = false
    await dialog.getByRole('button', { name: '重新加载', exact: true }).click()
    await dialog.locator('.el-form-item').filter({ hasText: item.field }).locator('.el-select__wrapper').click()
    await expect(page.getByRole('option', { name: item.name, exact: true })).toBeVisible()
    await expect(dialog.getByRole('button', { name: item.save, exact: true })).toBeEnabled()
  })

  test(`${item.label} 同令牌重登后不能接收旧身份的选项`, async ({ page }) => {
    let held: Route | undefined
    let count = 0
    await page.route('**/api/auth/permissions', r => r.fulfill({ json: ok(item.permissions) }))
    await page.route('**/api/tenant/current-view', r => r.fulfill({ json: ok(view(false)) }))
    await page.route(url => url.pathname === item.api, r => {
      if (++count === 1) { held = r; return }
      return r.fulfill({ json: ok({ list: [item.data], total: 1 }) })
    })
    await page.goto(item.path)
    await expect.poll(() => Boolean(held)).toBe(true)
    await identity(page, item.permissions)
    await finish(page, held!, { list: [item.old], total: 1 })
    await page.getByRole('button', { name: item.open, exact: true }).click()
    const field = page.getByRole('dialog', { name: item.title, exact: true })
      .locator('.el-form-item').filter({ hasText: item.field })
    await field.locator('.el-select__wrapper').click()
    await expect(page.getByRole('option', { name: item.oldName, exact: true })).toHaveCount(0)
    await expect(page.getByRole('option', { name: item.name, exact: true })).toBeVisible()
  })
}

test('用户控制面选项不能因旧身份的迟到授权而启用', async ({ page }) => {
  const permissions = cases[0].permissions
  const held: Route[] = []
  let changed = false
  await page.route('**/api/auth/permissions', r => r.fulfill({ json: ok(permissions) }))
  await page.route('**/api/tenant/current-view', r => {
    if (!changed) { held.push(r); return }
    return r.fulfill({ json: ok(view(false)) })
  })
  await page.route('**/api/system/role?*', r => r.fulfill({ json: ok({ list: [role(73, '控制面角色', true)], total: 1 }) }))
  await page.goto('/system/user')
  await expect.poll(() => held.length).toBeGreaterThan(0)
  changed = true
  await identity(page, permissions)
  for (const request of held) await finish(page, request, view(true))
  await page.getByRole('button', { name: '新建用户', exact: true }).click()
  const field = page.getByRole('dialog', { name: '新建用户', exact: true }).locator('.el-form-item').filter({ hasText: '角色' })
  await field.locator('.el-select__wrapper').click()
  await expect(page.getByRole('option', { name: '控制面角色', exact: true })).toHaveAttribute('aria-disabled', 'true')
})
