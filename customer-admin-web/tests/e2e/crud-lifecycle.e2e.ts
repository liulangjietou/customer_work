import type { Page, Route } from '@playwright/test'
import { expect, test } from './fixtures/adminTestFixture'

const ok = (data: unknown) => ({ code: 0, data })
const rolePermissions = ['role:view', 'role:add', 'role:edit', 'role:delete']
async function rolePage(page: Page) {
  await page.route('**/api/auth/permissions', route => route.fulfill({ json: ok(rolePermissions) }))
  await page.route('**/api/system/role?*', route => route.fulfill({ json: ok({ total: 1, list: [{
    id: 7, roleName: '客服角色', roleCode: 'service', remark: '仅本租户处理客服工单',
    status: 1, dataScope: 'SELF', permissionIds: [], controlPlane: false,
  }] }) }))
  await page.goto('/system/role')
  await expect(page.getByText('客服角色', { exact: true })).toBeVisible()
}

async function setPermissions(page: Page, permissions: string[]) {
  await page.evaluate(async permissions => {
    const authPath = '/src/store/auth.ts'
    const { useAuthStore } = await import(authPath)
    useAuthStore().permissions = permissions
  }, permissions)
}

async function relogin(page: Page, permissions: string[], username = 'admin', nickname = '新登录') {
  await page.evaluate(async ({ permissions, username, nickname }) => {
    const authPath = '/src/store/auth.ts'
    const { useAuthStore } = await import(authPath)
    const auth = useAuthStore()
    auth.applyLoginResult({ token: auth.token, nickname, forceChangePassword: false,
      approvalStatus: 'APPROVED', approvalRemark: null }, username)
    auth.permissions = permissions
  }, { permissions, username, nickname })
}

test('权限撤销立即收起已挂载的写入口，重新授权后恢复', async ({ page }) => {
  await rolePage(page)
  const create = page.getByRole('button', { name: '新建角色', exact: true })
  await create.click()
  await expect(page.getByRole('dialog', { name: '新建角色', exact: true })).toBeVisible()
  await setPermissions(page, ['role:view'])
  await expect(page.getByRole('dialog', { name: '新建角色', exact: true })).not.toBeVisible()
  await expect(create).not.toBeVisible()
  await setPermissions(page, rolePermissions)
  await expect(create).toBeVisible()
  await create.click()
  await expect(page.getByRole('dialog', { name: '新建角色', exact: true })).toBeVisible()
})

for (const transition of ['leave', 'same-token'] as const) {
  test(`删除确认期间 ${transition} 不再发出旧目标写请求`, async ({ page }) => {
    await rolePage(page)
    let deletes = 0
    await page.route('**/api/system/role/7', route => {
      deletes += 1
      return route.fulfill({ json: ok(null) })
    })
    await page.getByRole('button', { name: '删除', exact: true }).click()
    const confirmation = page.getByRole('dialog').filter({ hasText: '确认删除角色' })
    await expect(confirmation).toBeVisible()
    if (transition === 'same-token') await relogin(page, rolePermissions)
    else await page.evaluate(async () => {
      const routerPath = '/src/router/index.ts'
      const { default: router } = await import(routerPath)
      await router.push('/home')
    })
    await confirmation.getByRole('button', { name: '确定', exact: true }).click()
    await expect(confirmation).not.toBeVisible()
    await page.waitForLoadState('networkidle')
    expect(deletes).toBe(0)
    await expect(page.getByText('删除成功', { exact: true })).toHaveCount(0)
  })
}

test('角色保存失败保留输入，重试发送相同目标并只在成功后关闭', async ({ page }) => {
  await rolePage(page)
  const payloads: unknown[] = []
  await page.route('**/api/system/role/7', route => {
    expect(route.request().method()).toBe('PUT')
    payloads.push(route.request().postDataJSON())
    return route.fulfill({ json: payloads.length === 1
      ? { code: 50000, message: '角色保存暂不可用' } : ok(null) })
  })
  await page.getByRole('button', { name: '编辑', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '编辑角色', exact: true })
  await dialog.getByLabel('角色名称', { exact: true }).fill('客户服务组')
  await dialog.getByRole('button', { name: '保存角色', exact: true }).click()
  await expect(page.getByText('角色保存暂不可用', { exact: true })).toBeVisible()
  await expect(dialog.getByLabel('角色名称', { exact: true })).toHaveValue('客户服务组')
  await expect(dialog.getByRole('button', { name: '保存角色', exact: true })).toBeEnabled()
  await dialog.getByRole('button', { name: '保存角色', exact: true }).click()
  await expect(dialog).not.toBeVisible()
  expect(payloads).toHaveLength(2)
  expect(payloads[0]).toEqual(payloads[1])
  expect(payloads[1]).toMatchObject({ roleName: '客户服务组', roleCode: 'service', dataScope: 'SELF' })
})

for (const changedIdentity of [false, true]) {
  test(`修改本人昵称的回调只更新发起身份，重登=${changedIdentity}`, async ({ page }) => {
    const permissions = ['user:view', 'user:edit']
    await page.route('**/api/auth/permissions', route => route.fulfill({ json: ok(permissions) }))
    await page.route('**/api/system/user?*', route => route.fulfill({ json: ok({ total: 1, list: [{
      id: 9, username: 'same-user', nickname: '原昵称', tenantId: 'default', status: 1,
      approvalStatus: 'APPROVED', approvalRemark: null, roleIds: [], roleNames: [],
    }] }) }))
    let held: Route | undefined
    await page.route('**/api/system/user/9', route => { held = route })
    await page.goto('/system/user')
    await relogin(page, permissions, 'same-user', '本次登录昵称')
    await page.getByRole('button', { name: '搜索', exact: true }).click()
    await page.getByRole('button', { name: '编辑', exact: true }).click()
    const dialog = page.getByRole('dialog', { name: '编辑用户', exact: true })
    await dialog.getByLabel('昵称', { exact: true }).fill('旧请求修改的昵称')
    await dialog.getByRole('button', { name: '保存用户', exact: true }).click()
    await expect.poll(() => Boolean(held)).toBe(true)
    if (changedIdentity) await relogin(page, permissions, 'same-user', '重新登录的昵称')
    const response = page.waitForResponse('**/api/system/user/9')
    await held!.fulfill({ json: ok(null) })
    await (await response).finished()
    await page.waitForLoadState('networkidle')
    expect(await page.evaluate(() => localStorage.getItem('admin-nickname')))
      .toBe(changedIdentity ? '重新登录的昵称' : '旧请求修改的昵称')
    await expect(dialog).not.toBeVisible()
  })
}
