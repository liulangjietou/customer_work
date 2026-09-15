import type { Page, Route } from '@playwright/test'
import { expect, test } from './fixtures/adminTestFixture'
const ok = (data: unknown) => ({ code: 0, data })
const types = [1, 2].map(id => ({ id, dictType: `acceptance_${id}`, typeName: `验收类型${id}`, enabled: true, itemCount: 1, remark: '' }))
const itemRow = (id: number) => ({ id, dictType: `acceptance_${id}`, itemKey: `key_${id}`, itemLabel: `字典项${id}`, sort: 1, enabled: true, remark: '' })
async function permissions(page: Page, values: string[]) {
  await page.route('**/api/auth/permissions', r => r.fulfill({ json: ok(values) }))
}

test('字典子项查询失败可重试，切换父类型不接受旧子项', async ({ page }) => {
  let first: Route | undefined
  let failSecond = true
  await permissions(page, ['dict:view'])
  await page.route('**/api/dict/types', r => r.fulfill({ json: ok(types) }))
  await page.route('**/api/dict/items?*', r => {
    if (new URL(r.request().url()).searchParams.get('dictType') === 'acceptance_1') { first = r; return }
    return r.fulfill({ json: failSecond ? { code: 50000, message: '字典子项查询失败' } : ok([itemRow(2)]) })
  })
  await page.goto('/system/dict')
  await expect.poll(() => Boolean(first)).toBe(true)
  await page.getByRole('button', { name: '选择字典类型 验收类型2', exact: true }).click()
  const panel = page.locator('.item-panel')
  const error = panel.locator('.crud-load-state')
  await expect(error).toBeVisible()
  failSecond = false
  await error.getByRole('button', { name: '重新加载' }).click()
  await expect(panel.getByText('字典项2', { exact: true })).toBeVisible()
  const response = page.waitForResponse(r => r.url() === first!.request().url())
  await first!.fulfill({ json: ok([itemRow(1)]) })
  await (await response).finished()
  await expect(panel.getByText('字典项2', { exact: true })).toBeVisible()
  await expect(panel.getByText('字典项1', { exact: true })).toHaveCount(0)
})

test('字典子项创建绑定父类型，失败保留，旧保存不关闭另一父类型的新表单', async ({ page }) => {
  let held: Route | undefined
  const payloads: { type: string | null; data: Record<string, unknown> }[] = []
  await permissions(page, ['dict:view', 'dict:add'])
  await page.route('**/api/dict/types', r => r.fulfill({ json: ok(types) }))
  await page.route('**/api/dict/items?*', r => {
    if (r.request().method() === 'GET') return r.fulfill({ json: ok([]) })
    payloads.push({ type: new URL(r.request().url()).searchParams.get('dictType'), data: r.request().postDataJSON() })
    if (payloads.length === 1) return r.fulfill({ json: { code: 50000, message: '字典项保存失败' } })
    held = r
  })
  await page.goto('/system/dict')
  await page.getByRole('button', { name: '新增字典项', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '新增字典项', exact: true })
  await dialog.getByLabel('键（业务值）', { exact: true }).fill('created-key')
  await dialog.getByLabel('标签（文案）', { exact: true }).fill('待保存子项')
  await dialog.getByRole('button', { name: '保存字典项', exact: true }).click()
  await expect(page.getByText('字典项保存失败', { exact: true })).toBeVisible()
  await expect(dialog.getByLabel('标签（文案）', { exact: true })).toHaveValue('待保存子项')
  await dialog.getByRole('button', { name: '保存字典项', exact: true }).click()
  await expect.poll(() => Boolean(held)).toBe(true)
  await expect(dialog.getByLabel('标签（文案）', { exact: true })).toBeDisabled()
  await dialog.locator('.el-dialog__headerbtn').click()
  await page.getByRole('button', { name: '选择字典类型 验收类型2', exact: true }).click()
  await page.getByRole('button', { name: '新增字典项', exact: true }).click()
  await dialog.getByLabel('标签（文案）', { exact: true }).fill('另一类型的新输入')
  const response = page.waitForResponse(r => r.url() === held!.request().url() && r.request().method() === 'POST')
  await held!.fulfill({ json: ok(null) })
  await (await response).finished()
  await expect(dialog).toBeVisible()
  await expect(dialog.getByLabel('标签（文案）', { exact: true })).toHaveValue('另一类型的新输入')
  expect(payloads).toHaveLength(2)
  expect(payloads[0]).toEqual(payloads[1])
  expect(payloads[0]).toEqual({ type: 'acceptance_1', data: { itemKey: 'created-key', itemLabel: '待保存子项', sort: 1, enabled: true, remark: '' } })
})

test('配额用户改档失败保留原档且可重试，超限排行和明细一起恢复', async ({ page }) => {
  let held: Route | undefined
  let assigned = false
  let writes = 0
  let hitsFail = true
  await permissions(page, ['subject-quota:view', 'subject-quota:user-edit'])
  await page.route('**/api/subject-quota/levels', r => r.fulfill({ json: ok([
    { levelCode: 'free', levelName: '基础档', subjectType: 'USER', windowSeconds: 1800, tokenLimit: 100, requestLimit: 10, exceedAction: 'BLOCK', enabled: true },
    { levelCode: 'vip', levelName: '高级档', subjectType: 'USER', windowSeconds: 1800, tokenLimit: 1000, requestLimit: 100, exceedAction: 'BLOCK', enabled: true },
  ]) }))
  await page.route('**/api/subject-quota/users?*', r => r.fulfill({ json: ok({ list: [{ userId: 'acceptance-user', username: '验收用户', nickname: '档位验收', status: 'ACTIVE', levelCode: assigned ? 'vip' : 'free', createdAtMs: 100 }], total: 1 }) }))
  await page.route('**/api/subject-quota/users/level', r => {
    writes += 1
    expect(r.request().postDataJSON()).toEqual({ userId: 'acceptance-user', levelCode: 'vip' })
    if (writes === 1) { held = r; return }
    assigned = true
    return r.fulfill({ json: ok(null) })
  })
  await page.route('**/api/subject-quota/hits/rank?*', r => r.fulfill({ json: hitsFail ? { code: 50000, message: '排行查询失败' } : ok([{ subjectId: '排行验收主体', subjectType: 'USER', levelCode: 'vip', hitCount: 2, lastHitAtMs: 100 }]) }))
  await page.route('**/api/subject-quota/hits?*', r => r.fulfill({ json: ok([{ subjectId: '明细验收主体', subjectType: 'USER', levelCode: 'vip', limitKind: 'REQUEST', used: 101, limitValue: 100, action: 'BLOCK', resource: '/acceptance', createdAtMs: 100 }]) }))
  await page.goto('/contentguard/subject-quota')
  await page.getByRole('tab', { name: '用户分档', exact: true }).click()
  const row = page.getByRole('row').filter({ hasText: '验收用户' })
  const select = row.getByRole('combobox')
  await row.locator('.el-select').click()
  await page.getByRole('option', { name: '高级档（vip）', exact: true }).click()
  await expect.poll(() => Boolean(held)).toBe(true)
  await expect(select).toBeDisabled()
  await held!.fulfill({ json: { code: 50000, message: '用户改档失败' } })
  await expect(select).toBeEnabled()
  await expect(row).toContainText('基础档（free）')
  await row.locator('.el-select').click()
  await page.getByRole('option', { name: '高级档（vip）', exact: true }).click()
  await expect(row).toContainText('高级档（vip）')
  await page.getByRole('tab', { name: '超限记录', exact: true }).click()
  await page.getByRole('button', { name: '查询', exact: true }).click()
  const error = page.locator('.crud-load-state:visible')
  await expect(error).toBeVisible()
  await expect(page.getByText(/明细验收主体/)).toHaveCount(0)
  hitsFail = false
  await error.getByRole('button', { name: '重新加载' }).click()
  await expect(page.getByText('排行验收主体', { exact: true })).toBeVisible()
  await expect(page.getByText(/明细验收主体/)).toBeVisible()
  expect(writes).toBe(2)
})
