import type { Page, Route } from '@playwright/test'
import { expect, test } from './fixtures/adminTestFixture'

const ok = (data: unknown) => ({ code: 0, data })
async function relogin(page: Page, permissions: string[]) {
  await page.evaluate(async nextPermissions => {
    const modulePath = '/src/store/auth.ts'
    const { useAuthStore } = await import(modulePath)
    const auth = useAuthStore()
    auth.applyLoginResult({ token: auth.token, nickname: '新登录身份', forceChangePassword: false,
      approvalStatus: 'APPROVED', approvalRemark: null }, 'new-user')
    auth.permissions = nextPermissions
  }, permissions)
}
async function settled(page: Page) {
  await page.evaluate(() => new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))))
}
const nodes = [901, 902].map((id, index) => ({ id, parentId: 0, permCode: `acceptance-${id}:view`,
  permName: `菜单目标${index + 1}`, type: 1, path: '/fixture', sort: index + 1, icon: 'Document', iconType: 'library', children: [] }))

test('菜单历史切换目标后，旧记录不能覆盖新目标', async ({ page }) => {
  let first: Route | undefined
  await page.route('**/api/auth/permissions', r => r.fulfill({ json: ok(['menu:view']) }))
  await page.route('**/api/system/menu/tree', r => r.fulfill({ json: ok(nodes) }))
  const history = (menuId: number, name: string) => ({ list: [{ id: menuId, menuId, action: 'UPDATE',
    beforeSnapshot: '{}', afterSnapshot: '{}', operatorName: name, createTime: '2026-09-15 12:00:00' }], total: 1 })
  await page.route('**/api/system/menu/change-log?*', r => {
    if (new URL(r.request().url()).searchParams.get('menuId') === '901') { first = r; return }
    return r.fulfill({ json: ok(history(902, '第二目标操作人')) })
  })
  await page.goto('/system/menu')
  await page.locator('.tree-node').filter({ hasText: '菜单目标1' }).hover()
  await page.locator('.tree-node').filter({ hasText: '菜单目标1' }).getByRole('button', { name: '历史', exact: true }).click()
  await expect.poll(() => Boolean(first)).toBe(true)
  await page.locator('.el-drawer__close-btn').click()
  await page.locator('.tree-node').filter({ hasText: '菜单目标2' }).hover()
  await page.locator('.tree-node').filter({ hasText: '菜单目标2' }).getByRole('button', { name: '历史', exact: true }).click()
  const drawer = page.getByRole('dialog', { name: '变更记录 · 菜单目标2', exact: true })
  await expect(drawer.getByText('第二目标操作人', { exact: true })).toBeVisible()
  const response = page.waitForResponse(r => r.url() === first!.request().url())
  await first!.fulfill({ json: ok(history(901, '第一目标操作人')) })
  await (await response).finished()
  await settled(page)
  await expect(drawer.getByText('第二目标操作人', { exact: true })).toBeVisible()
  await expect(drawer.getByText('第一目标操作人', { exact: true })).toHaveCount(0)
})

const imageRows = [903, 904].map((id, index) => ({ id, imageName: `轮播目标${index + 1}`,
  imageUrl: `/acceptance-login-${id}.svg`, enabled: true, sortOrder: index + 1, createTime: '2026-09-15 12:00:00', updateTime: '2026-09-15 12:00:00' }))
async function setupImages(page: Page) {
  await page.route('**/api/auth/permissions', r => r.fulfill({ json: ok(['login-image:view', 'login-image:edit']) }))
  await page.route('**/api/system/login-image', r => r.fulfill({ json: ok(imageRows) }))
  await page.route('**/acceptance-login-*.svg', r => r.fulfill({ contentType: 'image/svg+xml',
    body: '<svg xmlns="http://www.w3.org/2000/svg" width="1280" height="720"><rect width="1280" height="720" fill="#ddd"/></svg>' }))
  await page.goto('/system/login-image')
}

test('轮播启停进行中锁定，失败恢复旧状态并可重试', async ({ page }) => {
  let held: Route | undefined
  const bodies: unknown[] = []
  await page.route('**/api/system/login-image/903/enabled', r => {
    bodies.push(r.request().postDataJSON())
    if (bodies.length === 1) { held = r; return }
    return r.fulfill({ json: ok(null) })
  })
  await setupImages(page)
  const toggle = page.locator('.image-card').filter({ hasText: '轮播目标1' }).getByRole('switch')
  await expect(toggle).toBeChecked()
  await page.locator('.image-card').filter({ hasText: '轮播目标1' }).locator('.el-switch').click()
  await expect.poll(() => Boolean(held)).toBe(true)
  await expect(toggle).toBeDisabled()
  await held!.fulfill({ json: { code: 50000, message: '启停暂时失败' } })
  await expect(toggle).toBeEnabled()
  await expect(toggle).toBeChecked()
  await page.locator('.image-card').filter({ hasText: '轮播目标1' }).locator('.el-switch').click()
  await expect.poll(() => bodies.length).toBe(2)
  await expect(toggle).not.toBeChecked()
  expect(bodies).toEqual([{ enabled: false }, { enabled: false }])
})

test('轮播排序在同令牌重登后不能恢复旧列表', async ({ page }) => {
  let held: Route | undefined
  await page.route('**/api/system/login-image/reorder', r => { held = r })
  await setupImages(page)
  await page.locator('.image-card').filter({ hasText: '轮播目标1' }).getByRole('button', { name: '下移', exact: true }).click()
  await expect.poll(() => Boolean(held)).toBe(true)
  expect(held!.request().postDataJSON()).toEqual({ ids: [904, 903] })
  await relogin(page, ['login-image:view', 'login-image:edit'])
  const response = page.waitForResponse(r => r.url() === held!.request().url())
  await held!.fulfill({ json: ok(null) })
  await (await response).finished()
  await settled(page)
  await expect(page.locator('.image-card')).toHaveCount(0)
})

test('HTTP 工具回车不能重复发送同一个进行中的请求', async ({ page }) => {
  const held: Route[] = []
  await page.route('**/api/auth/permissions', r => r.fulfill({ json: ok(['devtools:view']) }))
  await page.route('**/api/devtools/http/send', r => { held.push(r) })
  await page.goto('/system/devtools?tool=http')
  const url = page.getByPlaceholder('https://example.com/api/path')
  await url.fill('https://example.com/acceptance')
  await url.press('Enter')
  await expect.poll(() => held.length).toBe(1)
  await url.press('Enter')
  await settled(page)
  expect(held).toHaveLength(1)
  const response = page.waitForResponse(r => r.url() === held[0].request().url())
  await held[0].fulfill({ json: ok({ statusCode: 200, headers: {}, body: '当前HTTP响应', bodyBytes: 18,
    bodyTruncated: false, durationMs: 5, redirectLocation: null, error: null }) })
  await (await response).finished()
  await expect(page.getByText('当前HTTP响应', { exact: true })).toBeVisible()
})

test('HTTP 工具同令牌重登后丢弃旧响应', async ({ page }) => {
  let held: Route | undefined
  await page.route('**/api/auth/permissions', r => r.fulfill({ json: ok(['devtools:view']) }))
  await page.route('**/api/devtools/http/send', r => { held = r })
  await page.goto('/system/devtools?tool=http')
  await page.getByPlaceholder('https://example.com/api/path').fill('https://example.com/acceptance')
  await page.getByRole('button', { name: '发送', exact: true }).click()
  await expect.poll(() => Boolean(held)).toBe(true)
  await relogin(page, ['devtools:view'])
  const response = page.waitForResponse(r => r.url() === held!.request().url())
  await held!.fulfill({ json: ok({ statusCode: 200, headers: {}, body: '旧身份HTTP响应', bodyBytes: 21,
    bodyTruncated: false, durationMs: 5, redirectLocation: null, error: null }) })
  await (await response).finished()
  await settled(page)
  await expect(page.getByText('旧身份HTTP响应', { exact: true })).toHaveCount(0)
})


test('只读菜单不提供拖拽入口', async ({ page }) => {
  await page.route('**/api/auth/permissions', r => r.fulfill({ json: ok(['menu:view']) }))
  await page.route('**/api/system/menu/tree', r => r.fulfill({ json: ok(nodes) }))
  await page.goto('/system/menu')
  await expect(page.locator('.tree-node').filter({ hasText: '菜单目标1' })).toBeVisible()
  await expect(page.locator('.layout-main [draggable="true"]')).toHaveCount(0)
})

test('390px 菜单操作无需悬停即可打开编辑', async ({ page }) => {
  await page.route('**/api/auth/permissions', r => r.fulfill({ json: ok(['menu:view', 'menu:edit', 'menu:add', 'menu:delete']) }))
  await page.route('**/api/system/menu/tree', r => r.fulfill({ json: ok(nodes) }))
  await page.setViewportSize({ width: 390, height: 844 })
  await page.mouse.move(0, 0)
  await page.goto('/system/menu')
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390)
  const node = page.locator('.tree-node').filter({ hasText: '菜单目标1' })
  await expect(node.getByRole('button', { name: '编辑', exact: true })).toBeVisible()
  await node.getByRole('button', { name: '编辑', exact: true }).click()
  await expect(page.getByRole('dialog', { name: '编辑菜单节点', exact: true })).toBeVisible()
})

test('菜单图标旧上传完成不能修改后来打开的菜单', async ({ page }) => {
  let held: Route | undefined
  const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aPt0AAAAASUVORK5CYII=', 'base64')
  await page.route('**/api/auth/permissions', r => r.fulfill({ json: ok(['menu:view', 'menu:edit']) }))
  await page.route('**/api/system/menu/tree', r => r.fulfill({ json: ok(nodes) }))
  await page.route('**/api/system/menu/icon', r => { held = r })
  await page.route('**/acceptance-stale-icon.png', r => r.fulfill({ contentType: 'image/png', body: png }))
  await page.goto('/system/menu')
  const first = page.locator('.tree-node').filter({ hasText: '菜单目标1' })
  await first.hover()
  await first.getByRole('button', { name: '编辑', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '编辑菜单节点', exact: true })
  await dialog.getByRole('tab', { name: '上传图片', exact: true }).click()
  await dialog.locator('input[type=file]').setInputFiles({ name: 'icon.png', mimeType: 'image/png', buffer: png })
  await expect.poll(() => Boolean(held)).toBe(true)
  await dialog.locator('.el-dialog__headerbtn').click()
  const second = page.locator('.tree-node').filter({ hasText: '菜单目标2' })
  await second.hover()
  await second.getByRole('button', { name: '编辑', exact: true }).click()
  await expect(dialog.getByLabel('名称', { exact: true })).toHaveValue('菜单目标2')
  const response = page.waitForResponse(r => r.url() === held!.request().url())
  await held!.fulfill({ json: ok('/acceptance-stale-icon.png') })
  await (await response).finished()
  await settled(page)
  await expect(dialog.locator('.icon-preview img')).toHaveCount(0)
})
