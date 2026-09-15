import type { Page, Route } from '@playwright/test'
import { expect, test } from './fixtures/adminTestFixture'

const ok = (data: unknown) => ({ code: 0, data })
const longNote = '验收长文本需要保留完整含义并可阅读。'.repeat(18)
const nodes = [{ id: 901, parentId: 0, permCode: 'fixture:view', permName: '验收菜单节点', type: 1,
  path: '/fixture', sort: 1, icon: 'Document', iconType: 'library', children: [] }]
const types = [{ id: 902, dictType: 'acceptance_state', typeName: '验收字典类型', enabled: true,
  remark: longNote, itemCount: 1, createdAtMs: 1789444800000, updatedAtMs: 1789444800000 }]
const images = [{ id: 903, imageName: '验收登录图片', imageUrl: '/acceptance-login.svg', sortOrder: 1,
  enabled: true, createTime: '2026-09-15 12:00:00', updateTime: '2026-09-15 12:00:00' }]
const levels = [{ levelCode: 'acceptance-level', levelName: '验收额度等级', subjectType: 'USER',
  windowSeconds: 1800, tokenLimit: 50000, requestLimit: 100, exceedAction: 'BLOCK', enabled: true, remark: longNote }]
const cases = [
  { path: '/system/menu', endpoint: '/system/menu/tree', permission: 'menu:view', marker: '验收菜单节点', data: nodes },
  { path: '/system/dict', endpoint: '/dict/types', permission: 'dict:view', marker: '验收字典类型', data: types },
  { path: '/system/login-image', endpoint: '/system/login-image', permission: 'login-image:view', marker: '验收登录图片', data: images },
  { path: '/contentguard/subject-quota', endpoint: '/subject-quota/levels', permission: 'subject-quota:view', marker: '验收额度等级', data: levels },
]

async function installExtras(page: Page) {
  await page.route('**/api/dict/items?*', r => r.fulfill({ json: ok([]) }))
  await page.route('**/acceptance-login.svg', r => r.fulfill({ contentType: 'image/svg+xml',
    body: '<svg xmlns="http://www.w3.org/2000/svg" width="1280" height="720"><rect width="1280" height="720" fill="#dddddd"/><text x="80" y="200" font-size="60">Acceptance fixture</text></svg>' }))
}

for (const item of cases) {
  test(`${item.path} 查询故障可恢复、只读数据与窄屏`, async ({ page }, testInfo) => {
    let state: 'error' | 'data' | 'empty' = 'error'
    const writes: string[] = []
    await page.route('**/api/auth/permissions', r => r.fulfill({ json: ok([item.permission]) }))
    await installExtras(page)
    await page.route(url => url.pathname === '/api' + item.endpoint, r => r.fulfill({
      json: state === 'error' ? { code: 50000, message: '设置查询暂时失败' } : ok(state === 'empty' ? [] : item.data),
    }))
    page.on('request', request => {
      if (new URL(request.url()).pathname.startsWith('/api/') && !['GET', 'HEAD', 'OPTIONS'].includes(request.method())) writes.push(request.url())
    })
    await page.goto(item.path)
    const main = page.locator('.layout-main')
    const error = main.locator('.crud-load-state:visible').first()
    await expect(error).toBeVisible()
    state = 'data'
    await error.getByRole('button', { name: '重新加载' }).click()
    await expect(main.getByText(item.marker, { exact: false }).first()).toBeVisible()
    await expect(main.getByRole('button', { name: /^(编辑|删除|上传图片|新增等级|新增根菜单|新增字典类型)$/ })).toHaveCount(0)
    await expect(main.getByRole('switch')).toHaveCount(0)
    if (item.path === '/system/menu') await expect(main.locator('[draggable="true"]')).toHaveCount(0)
    state = 'error'
    await main.getByRole('button', { name: '刷新', exact: true }).first().click()
    await expect(error).toContainText('已保留上次结果')
    await expect(main.getByText(item.marker, { exact: false }).first()).toBeVisible()
    state = 'data'
    await error.getByRole('button', { name: '重新加载' }).click()
    await expect(main.locator('.crud-load-state:visible')).toHaveCount(0)
    await page.setViewportSize({ width: 390, height: 844 })
    await expect.poll(() => page.locator('.layout-aside').evaluate(e => e.getBoundingClientRect().width)).toBeLessThanOrEqual(1)
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390)
    await expect(page.getByText('设置查询暂时失败', { exact: true })).toHaveCount(0)
    await page.screenshot({ path: testInfo.outputPath('settings-state-390.png') })
    state = 'empty'
    await main.getByRole('button', { name: '刷新', exact: true }).first().click()
    await expect(main.getByText(item.marker, { exact: false })).toHaveCount(0)
    await expect(main.locator('.el-empty:visible, .el-table__empty-block:visible, .el-tree__empty-block:visible').first()).toBeVisible()
    expect(writes).toEqual([])
  })

  test(`${item.path} 同令牌重登不恢复旧设置`, async ({ page }) => {
    let held: Route | undefined
    await page.route('**/api/auth/permissions', r => r.fulfill({ json: ok([item.permission]) }))
    await installExtras(page)
    await page.route(url => url.pathname === '/api' + item.endpoint, r => { held = r })
    await page.goto(item.path)
    await expect.poll(() => Boolean(held)).toBe(true)
    await page.evaluate(async permission => {
      const modulePath = '/src/store/auth.ts'
      const { useAuthStore } = await import(modulePath)
      const auth = useAuthStore()
      auth.applyLoginResult({ token: auth.token, nickname: '新登录身份', forceChangePassword: false,
        approvalStatus: 'APPROVED', approvalRemark: null }, 'new-user')
      auth.permissions = [permission]
    }, item.permission)
    const response = page.waitForResponse(r => r.url() === held!.request().url())
    await held!.fulfill({ json: ok(item.data) })
    await (await response).finished()
    await page.evaluate(() => new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))))
    await expect(page.locator('.layout-main').getByText(item.marker, { exact: false })).toHaveCount(0)
    await expect(page.locator('.layout-main .el-loading-mask:visible')).toHaveCount(0)
  })
}
