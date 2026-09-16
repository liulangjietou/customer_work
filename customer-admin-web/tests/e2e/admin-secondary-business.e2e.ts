import { expect, test } from './fixtures/adminTestFixture'

const ok = (data: unknown) => ({ code: 0, data })

test('提示词只读证据页失败可重试，比较真实两版文本，空态不重复', async ({ page }, testInfo) => {
  let state: 'failed' | 'data' | 'empty' = 'failed'
  const versions = [
    { fingerprint: 'old-service-v1', capturedAtMs: 1789400000000, length: 11, content: '服务规范\n等待确认\n保留记录' },
    { fingerprint: 'new-service-v2', capturedAtMs: 1789401000000, length: 12, content: '服务规范\n先核对归属\n保留记录' },
  ]
  await page.route('**/api/auth/permissions', route => route.fulfill({ json: ok(['prompt-version:view']) }))
  await page.route('**/api/ops/prompt-version/list?*', route => route.fulfill({ json: state === 'failed'
    ? { code: 50000, message: '版本查询失败' } : ok(state === 'data' ? versions : []) }))
  await page.goto('/ops/prompt-version')
  const board = page.locator('.prompt-version-board')
  const error = board.locator('.crud-load-state')
  await expect(error).toBeVisible()
  await expect(board.locator('.el-table__empty-text, .el-empty, .summary-row')).not.toBeVisible()
  state = 'data'
  await error.getByRole('button', { name: '重新加载' }).click()
  await expect(page.locator('.el-table__body').getByText('old-service-v1', { exact: true })).toBeVisible()
  await page.locator('.el-table__body .el-checkbox').nth(0).click()
  await page.locator('.el-table__body .el-checkbox').nth(1).click()
  await page.getByRole('button', { name: '对比选中两版' }).click()
  const drawer = page.getByRole('dialog', { name: '提示词版本对比' })
  await expect(drawer.locator('.line-removed')).toHaveText('等待确认')
  await expect(drawer.locator('.line-added')).toHaveText('先核对归属')
  await page.setViewportSize({ width: 390, height: 844 })
  await expect.poll(() => page.locator('.layout-aside').evaluate(e => e.getBoundingClientRect().width)).toBeLessThanOrEqual(1)
  await expect(drawer.locator('.diff-side')).toHaveCount(2)
  expect(await drawer.locator('.diff-side').nth(1).evaluate(e => e.getBoundingClientRect().top)).toBeGreaterThan(
    await drawer.locator('.diff-side').nth(0).evaluate(e => e.getBoundingClientRect().bottom))
  await expect(page.getByText('版本查询失败', { exact: true })).toHaveCount(0)
  await page.screenshot({ path: testInfo.outputPath('prompt-diff-390.png') })
  await drawer.locator('.el-drawer__close-btn').click()
  state = 'failed'
  await page.getByRole('button', { name: '刷新', exact: true }).click()
  await expect(error).toContainText('已保留上次结果')
  await expect(page.locator('.el-table__body').getByText('old-service-v1', { exact: true })).toBeVisible()
  state = 'empty'
  await error.getByRole('button', { name: '重新加载' }).click()
  await expect(board.locator('.el-empty')).toHaveCount(1)
  await expect(board.locator('.el-empty')).toContainText('暂无版本记录')
  await expect(board.locator('.el-table__empty-text')).toHaveCount(0)
  await expect(page.getByRole('button', { name: '对比选中两版' })).toBeDisabled()
})

test('敏感词启停等待只写一次，失败保留状态并可重试', async ({ page }) => {
  const row = { id: 74, word: '验收词条', category: 'CUSTOM', action: 'BLOCK', enabled: true, updatedAtMs: 1789441200000 }
  const writes: string[] = []
  let fail = true
  let release: () => void = () => undefined
  const pending = new Promise<void>(resolve => { release = resolve })
  await page.route('**/api/auth/permissions', route => route.fulfill({ json: ok(['sensitive-word:view', 'sensitive-word:edit']) }))
  await page.route('**/api/contentguard/sensitive-word/page?*', route => route.fulfill({ json: ok({ total: 1, list: [row] }) }))
  await page.route('**/api/contentguard/sensitive-word/74/enabled?*', async route => {
    writes.push(route.request().url())
    if (fail) {
      await pending
      return route.fulfill({ json: { code: 50000, message: '词条更新失败' } })
    }
    row.enabled = false
    return route.fulfill({ json: ok(null) })
  })
  await page.goto('/contentguard/sensitive-word')
  const record = page.getByRole('row').filter({ hasText: '验收词条' })
  await record.getByRole('button', { name: '停用', exact: true }).click()
  await expect.poll(() => writes.length).toBe(1)
  await expect(record.getByRole('button', { name: '停用', exact: true })).toBeDisabled()
  await expect(record.getByRole('button', { name: '编辑', exact: true })).toBeDisabled()
  release()
  await expect(record.getByRole('button', { name: '停用', exact: true })).toBeEnabled()
  await expect(record.getByText('启用', { exact: true })).toBeVisible()
  fail = false
  await record.getByRole('button', { name: '停用', exact: true }).click()
  await expect(record.getByRole('button', { name: '启用', exact: true })).toBeEnabled()
  expect(writes).toHaveLength(2)
  expect(writes.every(url => new URL(url).searchParams.get('enabled') === 'false')).toBe(true)
})

test('角色权限树查询失败不能清空已有授权，原地重试后保存准确权限', async ({ page }) => {
  let fail = true
  const writes: unknown[] = []
  await page.route('**/api/auth/permissions', route => route.fulfill({ json: ok(['role:view', 'role:edit']) }))
  await page.route('**/api/system/role?*', route => route.fulfill({ json: ok({ total: 1, list: [{
    id: 71, roleName: '服务角色', roleCode: 'service', status: 1, dataScope: 'TENANT',
    permissionIds: [501], controlPlane: false,
  }] }) }))
  await page.route('**/api/system/permission/tree', route => route.fulfill({ json: fail
    ? { code: 50000, message: '权限查询失败' }
    : ok([{ id: 501, parentId: 0, permName: '查看工单', permCode: 'user-ticket:view', type: 2, children: [] }]) }))
  await page.route('**/api/system/role/71', route => {
    writes.push(route.request().postDataJSON())
    return route.fulfill({ json: ok(null) })
  })
  await page.goto('/system/role')
  await page.getByRole('row').filter({ hasText: '服务角色' }).getByRole('button', { name: '编辑', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '编辑角色' })
  await expect(dialog.getByRole('button', { name: '保存角色' })).toBeDisabled()
  expect(writes).toEqual([])
  fail = false
  await dialog.getByRole('button', { name: '重新加载权限' }).click()
  await expect(dialog.getByRole('treeitem', { name: '查看工单' }).getByRole('checkbox')).toBeChecked()
  await dialog.getByRole('button', { name: '保存角色' }).click()
  await expect(dialog).not.toBeVisible()
  expect(writes).toEqual([expect.objectContaining({ roleName: '服务角色', roleCode: 'service', permissionIds: [501] })])
})
