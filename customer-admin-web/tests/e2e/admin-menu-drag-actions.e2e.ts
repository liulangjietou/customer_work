import type { Page, Route } from '@playwright/test'
import { expect, test } from './fixtures/adminTestFixture'

const ok = (data: unknown) => ({ code: 0, data })
const nodes = [901, 902].map((id, index) => ({ id, parentId: 0, permCode: `drag-${id}:view`,
  permName: `拖拽菜单${index + 1}`, type: 1, path: '/fixture', sort: index + 1, icon: 'Document', iconType: 'library', children: [] }))
async function setup(page: Page, editable = true) {
  await page.route('**/api/auth/permissions', r => r.fulfill({ json: ok(editable ? ['menu:view', 'menu:edit'] : ['menu:view']) }))
  await page.route('**/api/system/menu/tree', r => r.fulfill({ json: ok(nodes) }))
  await page.goto('/system/menu')
  await expect(page.locator('.node-name')).toHaveText(['拖拽菜单1', '拖拽菜单2'])
}
async function dragSecondBeforeFirst(page: Page) {
  const source = page.locator('.el-tree-node__content').filter({ hasText: '拖拽菜单2' })
  const target = page.locator('.el-tree-node__content').filter({ hasText: '拖拽菜单1' })
  // 真实 HTML 拖拽至节点上边沿，避免落在中心被解释为新增子节点。
  await source.dragTo(target, { targetPosition: { x: 80, y: 3 } })
}
async function settle(page: Page) {
  await page.evaluate(() => new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))))
}
async function release(page: Page, route: Route, payload: unknown) {
  const response = page.waitForResponse(r => r.url() === route.request().url())
  await route.fulfill({ json: payload })
  await (await response).finished()
  await settle(page)
}

test('菜单实际拖拽失败恢复服务端顺序，成功后可发布且发布失败可重试', async ({ page }) => {
  await setup(page)
  let reordered = false
  let held: Route | undefined
  const requests: unknown[] = []
  let publishes = 0
  await page.route('**/api/system/menu/tree', r => r.fulfill({ json: ok(reordered ? [nodes[1], nodes[0]] : nodes) }))
  await page.route('**/api/system/menu/reorder', r => { held = r; requests.push(r.request().postDataJSON()) })
  await page.route('**/api/system/menu/publish', r => r.fulfill({ json: ++publishes === 1
    ? { code: 50000, message: '菜单发布暂时失败' } : ok(null) }))
  const publish = page.getByRole('button', { name: '发布', exact: true })
  await expect(publish).toBeDisabled()
  await dragSecondBeforeFirst(page)
  await expect.poll(() => Boolean(held)).toBe(true)
  await expect(publish).toBeDisabled()
  await release(page, held!, { code: 50000, message: '菜单移动暂时失败' })
  await expect(page.locator('.node-name')).toHaveText(['拖拽菜单1', '拖拽菜单2'])
  await expect(publish).toBeDisabled()
  held = undefined
  await dragSecondBeforeFirst(page)
  await expect.poll(() => Boolean(held)).toBe(true)
  reordered = true
  await release(page, held!, ok(null))
  await expect(page.locator('.node-name')).toHaveText(['拖拽菜单2', '拖拽菜单1'])
  await expect(publish).toBeEnabled()
  await publish.click()
  await expect(page.getByText('菜单发布暂时失败', { exact: true })).toBeVisible()
  await expect(publish).toBeEnabled()
  await publish.click()
  await expect(publish).toBeDisabled()
  expect(publishes).toBe(2)
  expect(requests).toEqual([1, 2].map(() => ({ items: [{ id: 902, parentId: 0, sort: 1 }, { id: 901, parentId: 0, sort: 2 }] })))
})

test('菜单旧身份拖拽响应不能替换新菜单或产生待发布状态', async ({ page }) => {
  await setup(page)
  let held: Route | undefined
  await page.route('**/api/system/menu/reorder', r => { held = r })
  await dragSecondBeforeFirst(page)
  await expect.poll(() => Boolean(held)).toBe(true)
  await page.route('**/api/system/menu/tree', r => r.fulfill({ json: ok([{ ...nodes[0], permName: '新身份菜单' }]) }))
  await page.evaluate(async () => {
    const path = '/src/store/auth.ts'
    const { useAuthStore } = await import(path)
    const auth = useAuthStore()
    auth.applyLoginResult({ token: auth.token, nickname: '新身份', forceChangePassword: false,
      approvalStatus: 'APPROVED', approvalRemark: null }, 'new-menu-user')
    auth.permissions = ['menu:view', 'menu:edit']
  })
  await release(page, held!, ok(null))
  await expect(page.locator('.node-name')).toHaveText(['新身份菜单'])
  await expect(page.getByRole('button', { name: '发布', exact: true })).toBeDisabled()
})

test('菜单只读账号不能通过拖拽产生排序请求或发布', async ({ page }) => {
  await setup(page, false)
  let writes = 0
  await page.route('**/api/system/menu/reorder', r => { writes += 1; return r.fulfill({ json: ok(null) }) })
  await dragSecondBeforeFirst(page)
  await settle(page)
  expect(writes).toBe(0)
  await expect(page.locator('.node-name')).toHaveText(['拖拽菜单1', '拖拽菜单2'])
  await expect(page.getByRole('button', { name: '发布', exact: true })).toHaveCount(0)
})
