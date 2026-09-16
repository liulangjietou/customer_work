import type { Page, Route } from '@playwright/test'
import { expect, test } from './fixtures/adminTestFixture'
const ok = (data: unknown) => ({ code: 0, data })
const permissions = ['sql-define:view', 'sql-define:add', 'sql-define:edit', 'sql-datasource:view']
const definition = (id: number) => ({ id, defineKey: `report-${id}`, datasourceId: 7, datasourceName: '验收数据源',
  sqlDescribe: `报表-${id}`, querySql: 'SELECT 1', countSql: '', autoLoad: false, enabled: true, remark: '', updateTime: '2026-09-15 10:00:00' })
const kinds = [
  { name: '参数', open: '参数配置', path: 'params', create: '新增参数', edit: '编辑参数', save: '保存参数', label: '参数名',
    field: 'paramName', extra: '描述', extraField: 'paramDesc', extraValue: '新参数说明',
    row: (id: number) => ({ id, defineId: 901, paramName: `param-${id}`, paramDesc: '原参数说明', paramType: 'STRING',
      dateFormat: '', required: false, defaultValue: '', dropDown: '', isPageNum: false, isPageSize: false, sort: 0 }) },
  { name: '转换器', open: '列转换器', path: 'transforms', create: '新增转换器', edit: '编辑转换器', save: '保存转换器', label: '列名',
    field: 'fieldName', extra: '配置', extraField: 'transformConfig', extraValue: 'yyyy-MM-dd',
    row: (id: number) => ({ id, defineId: 901, fieldName: `field-${id}`, transformType: 'DATE_FORMAT', transformConfig: 'MM-dd' }) },
]
async function settle(page: Page) {
  await page.evaluate(async () => {
    const paint = () => new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve())))
    await paint()
    await Promise.all(document.getAnimations().filter(a => Number.isFinite(Number(a.effect?.getComputedTiming().endTime)))
      .map(a => a.finished.catch(() => {})))
    await paint()
  })
}
async function release(page: Page, held: Route, data: unknown) {
  const response = page.waitForResponse(r => r.url() === held.request().url())
  await held.fulfill({ json: ok(data) }); await (await response).finished(); await settle(page)
}
async function identity(page: Page) {
  await page.evaluate(async permissions => {
    const modulePath = '/src/store/auth.ts'; const { useAuthStore } = await import(modulePath); const auth = useAuthStore()
    auth.applyLoginResult({ token: auth.token, nickname: '新身份', forceChangePassword: false,
      approvalStatus: 'APPROVED', approvalRemark: null }, 'later-user'); auth.permissions = permissions
  }, permissions)
}
async function setup(page: Page) {
  await page.route('**/api/auth/permissions', r => r.fulfill({ json: ok(permissions) }))
  await page.route('**/api/sql/define?*', r => r.fulfill({ json: ok({ list: [definition(901), definition(902)], total: 2, pageNum: 1, pageSize: 10 }) }))
  await page.route('**/api/sql/datasource/all', r => r.fulfill({ json: ok([{ id: 7, name: '验收数据源', enabled: true }]) }))
}
const parent = (page: Page, kind: typeof kinds[number], id: number) => page.getByRole('dialog', { name: `${kind.open} · report-${id}`, exact: true })
async function openParent(page: Page, kind: typeof kinds[number], id: number) {
  await page.getByRole('row').filter({ hasText: `report-${id}` }).getByRole('button', { name: kind.open, exact: true }).click()
  await expect(parent(page, kind, id)).toBeVisible()
}
function field(page: Page, title: string, label: string) {
  return page.getByRole('dialog', { name: title, exact: true }).locator('.el-form-item')
    .filter({ has: page.locator('.el-form-item__label', { hasText: new RegExp(`^${label}$`) }) }).getByRole('textbox')
}
for (const kind of kinds) {
  test(`SQL ${kind.name}查询切换父定义后不接受旧列表`, async ({ page }) => {
    await setup(page); let held: Route | undefined
    await page.route(`**/api/sql/define/901/${kind.path}`, r => { held = r })
    await page.route(`**/api/sql/define/902/${kind.path}`, r => r.fulfill({ json: ok([{ ...kind.row(92), defineId: 902 }]) }))
    await page.goto('/sql/define'); await openParent(page, kind, 901); await expect.poll(() => Boolean(held)).toBe(true)
    await parent(page, kind, 901).getByRole('button', { name: '关闭', exact: true }).click(); await settle(page)
    await openParent(page, kind, 902); await expect(parent(page, kind, 902)).toContainText(kind.name === '参数' ? 'param-92' : 'field-92')
    await release(page, held!, [kind.row(91)])
    await expect(parent(page, kind, 902)).not.toContainText(kind.name === '参数' ? 'param-91' : 'field-91')
  })
  test(`SQL ${kind.name}查询失败保留目标并可重试`, async ({ page }) => {
    await setup(page); let fail = true
    await page.route(`**/api/sql/define/901/${kind.path}`, r => r.fulfill({ json: fail ? { code: 50000, message: '子表查询暂时失败' } : ok([kind.row(91)]) }))
    await page.goto('/sql/define'); await openParent(page, kind, 901)
    await expect(parent(page, kind, 901).getByRole('button', { name: '重新加载', exact: true })).toBeVisible()
    fail = false; await parent(page, kind, 901).getByRole('button', { name: '重新加载', exact: true }).click()
    await expect(parent(page, kind, 901)).toContainText(kind.name === '参数' ? 'param-91' : 'field-91')
  })
  test(`SQL ${kind.name}旧新增完成不关闭新父定义的新表单`, async ({ page }) => {
    await setup(page); let held: Route | undefined
    for (const id of [901, 902]) await page.route(`**/api/sql/define/${id}/${kind.path}`, r => {
      if (r.request().method() === 'POST') { held = r; return }
      return r.fulfill({ json: ok([]) })
    })
    await page.goto('/sql/define'); await openParent(page, kind, 901)
    await parent(page, kind, 901).getByRole('button', { name: kind.create, exact: true }).click()
    await field(page, kind.create, kind.label).fill('old_field'); await field(page, kind.create, kind.extra).fill(kind.extraValue)
    await page.getByRole('dialog', { name: kind.create, exact: true }).getByRole('button', { name: kind.save, exact: true }).click()
    await expect.poll(() => Boolean(held)).toBe(true)
    await page.getByRole('dialog', { name: kind.create, exact: true }).getByRole('button', { name: '取消', exact: true }).click(); await settle(page)
    await parent(page, kind, 901).getByRole('button', { name: '关闭', exact: true }).click(); await settle(page)
    await openParent(page, kind, 902); await parent(page, kind, 902).getByRole('button', { name: kind.create, exact: true }).click()
    await field(page, kind.create, kind.label).fill('new_field')
    await release(page, held!, null)
    await expect(page.getByRole('dialog', { name: kind.create, exact: true })).toBeVisible()
    await expect(field(page, kind.create, kind.label)).toHaveValue('new_field')
  })
  test(`SQL ${kind.name}编辑失败保留输入且重试绑定原父子记录`, async ({ page }) => {
    await setup(page); let fail = true; const writes: unknown[] = []
    await page.route(`**/api/sql/define/901/${kind.path}`, r => r.fulfill({ json: ok([kind.row(91)]) }))
    await page.route(`**/api/sql/define/901/${kind.path}/91`, r => {
      expect(r.request().method()).toBe('PUT'); writes.push(r.request().postDataJSON())
      return r.fulfill({ json: fail ? { code: 50000, message: '子表保存暂时失败' } : ok(null) })
    })
    await page.goto('/sql/define'); await openParent(page, kind, 901)
    await parent(page, kind, 901).getByRole('row').filter({ hasText: kind.name === '参数' ? 'param-91' : 'field-91' }).getByRole('button', { name: '编辑', exact: true }).click()
    await field(page, kind.edit, kind.extra).fill(kind.extraValue)
    const dialog = page.getByRole('dialog', { name: kind.edit, exact: true }); const save = dialog.getByRole('button', { name: kind.save, exact: true })
    await save.click(); await expect(page.getByText('子表保存暂时失败', { exact: true })).toBeVisible()
    await expect(dialog).toBeVisible(); await expect(field(page, kind.edit, kind.extra)).toHaveValue(kind.extraValue)
    fail = false; await save.click(); await expect(dialog).not.toBeVisible()
    expect(writes).toHaveLength(2); for (const data of writes) expect(data).toMatchObject({ [kind.extraField]: kind.extraValue })
  })
  test(`SQL ${kind.name}删除确认期间重登不发送旧请求`, async ({ page }) => {
    await setup(page); let writes = 0
    await page.route(`**/api/sql/define/901/${kind.path}`, r => r.fulfill({ json: ok([kind.row(91)]) }))
    await page.route(`**/api/sql/define/901/${kind.path}/91`, r => { writes += 1; return r.fulfill({ json: ok(null) }) })
    await page.goto('/sql/define'); await openParent(page, kind, 901)
    await parent(page, kind, 901).getByRole('row').filter({ hasText: kind.name === '参数' ? 'param-91' : 'field-91' }).getByRole('button', { name: '删除', exact: true }).click()
    const prompt = page.getByRole('dialog', { name: '提示', exact: true })
    await identity(page); await prompt.getByRole('button', { name: '确定', exact: true }).click(); await settle(page)
    expect(writes).toBe(0)
  })
}
test('SQL 复制确认期间重登不能借新身份复制旧定义', async ({ page }) => {
  await setup(page); let writes = 0
  await page.route('**/api/sql/define/901/copy', r => { writes += 1; return r.fulfill({ json: ok(null) }) })
  await page.goto('/sql/define')
  await page.getByRole('row').filter({ hasText: 'report-901' }).getByRole('button', { name: '复制', exact: true }).click()
  const prompt = page.getByRole('dialog', { name: '提示', exact: true })
  await identity(page); await prompt.getByRole('button', { name: '确定', exact: true }).click(); await settle(page)
  expect(writes).toBe(0)
})
test('SQL 复制失败释放锁，可重试且只刷新已完成结果', async ({ page }) => {
  await setup(page); let held: Route | undefined; let count = 0
  await page.route('**/api/sql/define/901/copy', r => { count += 1; held = r })
  await page.goto('/sql/define')
  const button = page.getByRole('row').filter({ hasText: 'report-901' }).getByRole('button', { name: '复制', exact: true })
  const confirm = async () => { await button.click(); await page.getByRole('dialog', { name: '提示', exact: true }).getByRole('button', { name: '确定', exact: true }).click(); await expect.poll(() => Boolean(held)).toBe(true) }
  await confirm(); await expect(button).toBeDisabled()
  await held!.fulfill({ json: { code: 50000, message: '复制暂时失败' } }); await expect(button).toBeEnabled()
  held = undefined; await confirm(); await release(page, held!, null)
  expect(count).toBe(2); await expect(button).toBeEnabled()
})

for (const kind of kinds) test(`SQL ${kind.name}删除失败保留记录并可按原关联重试`, async ({ page }) => {
  await setup(page); let held: Route | undefined; let deleted = false; let writes = 0
  await page.route(`**/api/sql/define/901/${kind.path}`, r => r.fulfill({ json: ok(deleted ? [] : [kind.row(91)]) }))
  await page.route(`**/api/sql/define/901/${kind.path}/91`, r => { expect(r.request().method()).toBe('DELETE'); writes += 1; held = r })
  await page.goto('/sql/define'); await openParent(page, kind, 901)
  const record = parent(page, kind, 901).getByRole('row').filter({ hasText: kind.name === '参数' ? 'param-91' : 'field-91' })
  const button = record.getByRole('button', { name: '删除', exact: true })
  const confirm = async () => {
    await button.click()
    await page.getByRole('dialog', { name: '提示', exact: true }).getByRole('button', { name: '确定', exact: true }).click()
    await expect.poll(() => Boolean(held)).toBe(true)
  }
  await confirm(); await expect(button).toBeDisabled()
  await held!.fulfill({ json: { code: 50000, message: '子表删除暂时失败' } })
  await expect(button).toBeEnabled(); await expect(record).toBeVisible()
  held = undefined; await confirm(); deleted = true; await release(page, held!, null)
  await expect(record).not.toBeVisible(); expect(writes).toBe(2)
})

test('SQL 数据源选项失败时不能保存定义，可原地重新加载', async ({ page }) => {
  await setup(page); let fail = true
  await page.route('**/api/sql/datasource/all', r => r.fulfill({ json: fail ? { code: 50000, message: '数据源选项暂时失败' }
    : ok([{ id: 7, name: '恢复数据源', enabled: true }]) }))
  await page.goto('/sql/define'); await page.getByRole('button', { name: '新建 SQL 定义', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '新建 SQL 定义', exact: true })
  await expect(dialog.getByRole('button', { name: '重新加载', exact: true })).toBeVisible()
  await expect(dialog.getByRole('button', { name: '保存定义', exact: true })).toBeDisabled()
  fail = false; await dialog.getByRole('button', { name: '重新加载', exact: true }).click()
  await dialog.locator('.el-form-item').filter({ has: page.locator('.el-form-item__label', { hasText: /^数据源$/ }) }).locator('.el-select__wrapper').click()
  await expect(page.getByRole('option', { name: '恢复数据源', exact: true })).toBeVisible()
  await expect(dialog.getByRole('button', { name: '保存定义', exact: true })).toBeEnabled()
})
test('SQL 数据源选项重登后重新查询并忽略旧身份响应', async ({ page }) => {
  await setup(page); let held: Route | undefined; let reads = 0
  await page.route('**/api/sql/datasource/all', r => {
    if (++reads === 1) { held = r; return }
    return r.fulfill({ json: ok([{ id: 8, name: '新身份数据源', enabled: true }]) })
  })
  await page.goto('/sql/define'); await expect.poll(() => Boolean(held)).toBe(true)
  await identity(page); await release(page, held!, [{ id: 7, name: '旧身份数据源', enabled: true }])
  await page.getByRole('button', { name: '新建 SQL 定义', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '新建 SQL 定义', exact: true })
  await dialog.locator('.el-form-item').filter({ has: page.locator('.el-form-item__label', { hasText: /^数据源$/ }) }).locator('.el-select__wrapper').click()
  await expect(page.getByRole('option', { name: '旧身份数据源', exact: true })).toHaveCount(0)
  await expect(page.getByRole('option', { name: '新身份数据源', exact: true })).toBeVisible()
})


test('SQL 参数表单在 Ocean 390px 下保留说明与保存入口', async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await setup(page)
  await page.route('**/api/sql/define/901/params', r => r.fulfill({ json: ok([kinds[0].row(91)]) }))
  await page.goto('/sql/define')
  await openParent(page, kinds[0], 901)
  await parent(page, kinds[0], 901).getByRole('row').filter({ hasText: 'param-91' }).getByRole('button', { name: '编辑', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '编辑参数', exact: true })
  await field(page, '编辑参数', '描述').fill('筛选当前客户可见的工单，保留原查询条件')
  const save = dialog.getByRole('button', { name: '保存参数', exact: true })
  await save.scrollIntoViewIfNeeded()
  await expect(save).toBeEnabled()
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'ocean')
  await expect(page.locator('.el-message')).toHaveCount(0)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true)
  expect(await dialog.evaluate(el => el.scrollWidth <= el.clientWidth + 1)).toBe(true)
  await page.screenshot({ path: testInfo.outputPath('sql-params-ocean-390.png'), animations: 'disabled' })
})
