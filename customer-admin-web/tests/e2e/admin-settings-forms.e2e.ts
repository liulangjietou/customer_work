import type { Page, Route } from '@playwright/test'
import { expect, test } from './fixtures/adminTestFixture'

const ok = (data: unknown) => ({ code: 0, data })
const cases = [
  { path: '/system/menu', endpoint: '/system/menu/tree', write: '/system/menu/901', method: 'PUT',
    permission: 'menu:edit', view: 'menu:view', key: 'permName', label: '名称', dialog: '编辑菜单节点', save: '保存菜单',
    row: { id: 901, parentId: 0, permCode: 'acceptance:view', permName: '第一目标', type: 1, path: '/fixture',
      icon: 'Document', iconType: 'library', sort: 1, children: [] } },
  { path: '/system/dict', endpoint: '/dict/types', write: '/dict/types/901', method: 'PUT',
    permission: 'dict:edit', view: 'dict:view', key: 'typeName', label: '类型名称', dialog: '编辑字典类型', save: '保存类型',
    row: { id: 901, dictType: 'acceptance_state', typeName: '第一目标', enabled: true, itemCount: 0, remark: '', createdAtMs: 1789444800000, updatedAtMs: 1789444800000 } },
  { path: '/contentguard/subject-quota', endpoint: '/subject-quota/levels', write: '/subject-quota/levels', method: 'POST',
    permission: 'subject-quota:level-edit', view: 'subject-quota:view', key: 'levelName', label: '等级名称', dialog: '编辑等级', save: '保存配额层级',
    row: { tenantId: 'default', levelCode: 'acceptance-first', levelName: '第一目标', subjectType: 'USER', windowSeconds: 1800,
      tokenLimit: 50000, requestLimit: 100, exceedAction: 'BLOCK', enabled: true, remark: '' } },
]

for (const item of cases) {
  async function setup(page: Page) {
    await page.route('**/api/auth/permissions', r => r.fulfill({ json: ok([item.permission, item.view]) }))
    await page.route('**/api/dict/items?*', r => r.fulfill({ json: ok([]) }))
    await page.route(url => url.pathname === '/api' + item.endpoint, r => {
      if (r.request().method() !== 'GET') return r.fallback()
      return r.fulfill({ json: ok([item.row, { ...item.row, id: 902, levelCode: 'acceptance-second', dictType: 'acceptance_second', [item.key]: '第二目标' }]) })
    })
    await page.goto(item.path)
    const target = (name: string) => item.path === '/system/menu'
      ? page.locator('.tree-node').filter({ hasText: name })
      : page.getByRole('row').filter({ hasText: name })
    if (item.path === '/system/menu') await target('第一目标').hover()
    await target('第一目标').getByRole('button', { name: '编辑', exact: true }).click()
    return { dialog: page.getByRole('dialog', { name: item.dialog, exact: true }), target }
  }

  test(`${item.path} 保存锁定输入，失败保留并可以重试`, async ({ page }) => {
    let held: Route | undefined
    const payloads: unknown[] = []
    await page.route(url => url.pathname === '/api' + item.write, r => {
      if (r.request().method() !== item.method) return r.fallback()
      payloads.push(r.request().postDataJSON())
      if (payloads.length === 1) { held = r; return }
      return r.fulfill({ json: ok(null) })
    })
    const { dialog } = await setup(page)
    const name = dialog.getByLabel(item.label, { exact: true })
    await name.fill('待保存的新名称')
    const save = dialog.getByRole('button', { name: item.save, exact: true })
    await save.click()
    await expect.poll(() => Boolean(held)).toBe(true)
    await expect(save).toBeDisabled()
    await expect(name).toBeDisabled()
    await held!.fulfill({ json: { code: 50000, message: '设置保存失败' } })
    await expect(save).toBeEnabled()
    await expect(name).toHaveValue('待保存的新名称')
    await save.click()
    await expect(dialog).not.toBeVisible()
    expect(payloads).toHaveLength(2)
    expect(payloads[0]).toEqual(payloads[1])
    expect(payloads[1]).toMatchObject({ [item.key]: '待保存的新名称' })
  })

  test(`${item.path} 旧保存不能关闭新目标表单`, async ({ page }) => {
    let held: Route | undefined
    let publishes = 0
    if (item.path === '/system/menu') await page.route('**/api/system/menu/publish', r => {
      publishes += 1
      return r.fulfill({ json: publishes === 1 ? { code: 50000, message: '菜单发布失败' } : ok(null) })
    })
    await page.route(url => url.pathname === '/api' + item.write, r => {
      if (r.request().method() !== item.method) return r.fallback()
      held = r
    })
    const { dialog, target } = await setup(page)
    await dialog.getByRole('button', { name: item.save, exact: true }).click()
    await expect.poll(() => Boolean(held)).toBe(true)
    await dialog.locator('.el-dialog__headerbtn').click()
    if (item.path === '/system/menu') await target('第二目标').hover()
    await target('第二目标').getByRole('button', { name: '编辑', exact: true }).click()
    await expect(dialog.getByLabel(item.label, { exact: true })).toHaveValue('第二目标')
    const response = page.waitForResponse(r => r.url() === held!.request().url() && r.request().method() === item.method)
    await held!.fulfill({ json: ok(null) })
    await (await response).finished()
    await page.evaluate(async () => {
      await new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve())))
      await Promise.all(document.getAnimations().filter(a => Number.isFinite(a.effect?.getComputedTiming().endTime)).map(a => a.finished.catch(() => {})))
    })
    await expect(dialog).toBeVisible()
    await expect(dialog.getByLabel(item.label, { exact: true })).toHaveValue('第二目标')
    await expect(dialog.getByRole('button', { name: item.save, exact: true })).toBeEnabled()
    if (item.path === '/system/menu') {
      await dialog.locator('.el-dialog__headerbtn').click()
      const publish = page.getByRole('button', { name: '发布', exact: true })
      await expect(publish).toBeEnabled()
      await publish.click()
      await expect(page.getByText('菜单发布失败', { exact: true })).toBeVisible()
      await expect(publish).toBeEnabled()
      await publish.click()
      await expect(publish).toBeDisabled()
      expect(publishes).toBe(2)
    }
  })
}
