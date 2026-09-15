import type { Page, Route } from '@playwright/test'
import { expect, test } from './fixtures/adminTestFixture'
const ok = (data: unknown) => ({ code: 0, data })
const permissions = ['config-version:view', 'config-version:rollback', 'config-version:gray', 'governance:view', 'governance:approve']
const date = '2026-09-15 10:00:00'
const version = (id: number) => ({ id, configType: 'AGENT', targetCode: `agent-${id}`, targetId: id, version: id - 800,
  content: null, contentHash: 'a'.repeat(64), publishScope: 'ALL', grayTenants: null, dataId: `agent-${id}`,
  status: 'PUBLISHED', sourceVersion: null, remark: null, createTime: date })
const approval = (id: string) => ({ id, changeType: 'CONFIG_ROLLBACK', targetKey: `target-${id}`, payloadHash: 'b'.repeat(64),
  makerId: 8, makerName: 'another-maker', checkerId: null, checkerName: null, status: 'PENDING', decisionReason: null,
  resultJson: null, failureCode: null, expiresAt: '2026-09-16 10:00:00', decidedAt: null, executedAt: null, createTime: date, updateTime: date })
const audit = (id: string) => [{ sequenceNo: 1, eventType: 'CREATED', actorId: 8, actorName: 'another-maker',
  detail: `审计内容-${id}`, previousHash: '0'.repeat(64), eventHash: 'c'.repeat(64), retentionUntil: '2027-09-15 10:00:00', createTime: date }]
async function setup(page: Page, tenantFailure = false) {
  await page.route('**/api/auth/permissions', r => r.fulfill({ json: ok(permissions) }))
  await page.route('**/api/tenant/current-view', r => r.fulfill({ json: ok({ userTenantId: 'default', effectiveTenantId: 'default', crossTenantAuthority: true }) }))
  await page.route('**/api/tenant/options', r => r.fulfill({ json: tenantFailure
    ? { code: 50000, message: '租户选项暂时失败' }
    : ok([{ id: 11, tenantCode: 'acceptance-tenant', tenantName: '验收目标租户', status: 'ACTIVE' }]) }))
  await page.route('**/api/config-version/page?*', r => r.fulfill({ json: ok({ total: 2, list: [version(801), version(802)], pageNum: 1, pageSize: 10 }) }))
  await page.route('**/api/governance/changes', r => r.fulfill({ json: ok([approval('a'), approval('b')]) }))
  await page.goto('/system/config-version')
  await expect(page.getByRole('row').filter({ hasText: 'agent-801' })).toBeVisible()
}
async function relogin(page: Page) {
  await page.evaluate(async permissions => {
    const modulePath = '/src/store/auth.ts'
    const { useAuthStore } = await import(modulePath)
    const auth = useAuthStore()
    auth.applyLoginResult({ token: auth.token, nickname: '后来登录', forceChangePassword: false,
      approvalStatus: 'APPROVED', approvalRemark: null }, 'later-user')
    auth.permissions = permissions
  }, permissions)
}
async function settle(page: Page) {
  await page.evaluate(async () => {
    const paint = () => new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve())))
    await paint()
    // 对话框离场期间仍可见；等有限动画结束，避免把正在关闭的新表单误判为保留成功。
    const animations = document.getAnimations().filter(animation =>
      Number.isFinite(Number(animation.effect?.getComputedTiming().endTime)))
    await Promise.all(animations.map(animation => animation.finished.catch(() => {})))
    await paint()
  })
}
async function release(page: Page, held: Route, data: unknown) {
  const response = page.waitForResponse(r => r.url() === held.request().url())
  await held.fulfill({ json: ok(data) })
  await (await response).finished()
  await settle(page)
}
for (const action of ['rollback', 'approve', 'reject'] as const) test(`配置${action} 确认期间重登不能借新身份发送旧请求`, async ({ page }) => {
  let writes = 0
  const endpoint = action === 'rollback' ? '/api/config-version/801/rollback' : `/api/governance/changes/a/${action}`
  await page.route(url => url.pathname === endpoint, r => { writes += 1; return r.fulfill({ json: ok(approval('a')) }) })
  await setup(page)
  const row = page.getByRole('row').filter({ hasText: action === 'rollback' ? 'agent-801' : 'target-a' })
  await row.getByRole('button', { name: action === 'rollback' ? '回滚至此' : action === 'approve' ? '通过' : '拒绝', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: action === 'rollback' ? '回滚到 v1' : action === 'approve' ? '复核通过' : '拒绝变更', exact: true })
  await dialog.getByRole('textbox').fill('原登录身份的复核依据')
  await relogin(page)
  await dialog.getByRole('button', { name: action === 'rollback' ? '创建安全回滚任务' : '确定', exact: true }).click()
  await expect(dialog).not.toBeVisible()
  await settle(page)
  expect(writes).toBe(0)
})
async function openGray(page: Page, id: number) {
  await page.getByRole('row').filter({ hasText: `agent-${id}` }).getByRole('button', { name: '灰度', exact: true }).click()
  return page.getByRole('dialog', { name: `灰度发布 v${id - 800}`, exact: true })
}
async function fillGray(page: Page, id: number) {
  const dialog = await openGray(page, id)
  await dialog.locator('.el-select__wrapper').click()
  await page.getByRole('option', { name: '验收目标租户（acceptance-tenant）', exact: true }).click()
  await dialog.getByPlaceholder('建议写清灰度目的，事后翻历史时最有用', { exact: true }).fill('原目标灰度说明')
  return dialog
}
test('灰度提交完成不能关闭后来打开的另一版本表单', async ({ page }) => {
  let held: Route | undefined
  await page.route('**/api/config-version/801/gray', r => { held = r })
  await setup(page)
  const first = await fillGray(page, 801)
  await first.getByRole('button', { name: '创建安全灰度任务', exact: true }).click()
  await expect.poll(() => Boolean(held)).toBe(true)
  expect(held!.request().postDataJSON()).toEqual({ tenantCodes: ['acceptance-tenant'], remark: '原目标灰度说明' })
  await first.getByRole('button', { name: '取消', exact: true }).click()
  const second = await openGray(page, 802)
  await second.getByPlaceholder('建议写清灰度目的，事后翻历史时最有用', { exact: true }).fill('后来版本未保存说明')
  await release(page, held!, approval('a'))
  await expect(second).toBeVisible()
  await expect(second.getByPlaceholder('建议写清灰度目的，事后翻历史时最有用', { exact: true })).toHaveValue('后来版本未保存说明')
})
test('灰度保存锁定重复提交，失败保留输入，重试仍绑定原版本和租户', async ({ page }) => {
  let held: Route | undefined
  const writes: unknown[] = []
  await page.route('**/api/config-version/801/gray', r => { writes.push(r.request().postDataJSON()); held = r })
  await setup(page)
  const dialog = await fillGray(page, 801)
  const save = dialog.getByRole('button', { name: '创建安全灰度任务', exact: true })
  await save.click()
  await expect.poll(() => Boolean(held)).toBe(true)
  await expect(save).toBeDisabled()
  await held!.fulfill({ json: { code: 50000, message: '灰度请求暂时失败' } })
  await expect(save).toBeEnabled()
  await expect(dialog.getByPlaceholder('建议写清灰度目的，事后翻历史时最有用', { exact: true })).toHaveValue('原目标灰度说明')
  held = undefined
  await save.click()
  await expect.poll(() => Boolean(held)).toBe(true)
  await release(page, held!, { ...approval('gray-a'), changeType: 'CONFIG_GRAY_RELEASE' })
  await expect(dialog).not.toBeVisible()
  expect(writes).toEqual([
    { tenantCodes: ['acceptance-tenant'], remark: '原目标灰度说明' },
    { tenantCodes: ['acceptance-tenant'], remark: '原目标灰度说明' },
  ])
})
test('审计链先查目标A后查目标B，旧结果不能覆盖当前内容', async ({ page }) => {
  let held: Route | undefined
  await page.route('**/api/governance/changes/a/audit', r => { held = r })
  await page.route('**/api/governance/changes/b/audit', r => r.fulfill({ json: ok(audit('b')) }))
  await setup(page)
  await page.getByRole('row').filter({ hasText: 'target-a' }).getByRole('button', { name: '审计链', exact: true }).click()
  await expect.poll(() => Boolean(held)).toBe(true)
  const dialog = page.getByRole('dialog', { name: '审批审计哈希链', exact: true })
  // 若实现已在请求开始时打开弹窗，先关闭它再切换目标。
  if (await dialog.isVisible()) await dialog.locator('.el-dialog__headerbtn').click()
  await page.getByRole('row').filter({ hasText: 'target-b' }).getByRole('button', { name: '审计链', exact: true }).click()
  await expect(dialog.getByText('another-maker · 审计内容-b', { exact: true })).toBeVisible()
  await release(page, held!, audit('a'))
  await expect(dialog.getByText('another-maker · 审计内容-b', { exact: true })).toBeVisible()
})
test('版本对比在重新登录后不能显示先前请求返回的脱敏快照', async ({ page }) => {
  const held: Route[] = []
  await page.route(url => /^\/api\/config-version\/80[12]$/.test(url.pathname), r => { held.push(r) })
  await setup(page)
  for (const id of [801, 802]) await page.getByRole('row').filter({ hasText: `agent-${id}` }).locator('.el-checkbox').click()
  await page.getByRole('button', { name: '对比选中两版', exact: true }).click()
  await expect.poll(() => held.length).toBe(2)
  await relogin(page)
  for (const r of held) {
    const id = Number(new URL(r.request().url()).pathname.split('/').pop())
    await release(page, r, { ...version(id), content: `OLD-IDENTITY-CONTENT-${id}` })
  }
  await expect(page.getByRole('dialog', { name: '版本对比', exact: true })).not.toBeVisible()
})

test('审批列表刷新失败展示可重试状态并保留已有审批', async ({ page }) => {
  await setup(page)
  let fail = true
  await page.route('**/api/governance/changes', r => r.fulfill({ json: fail
    ? { code: 50000, message: '审批列表暂时失败' } : ok([approval('fresh')]) }))
  const card = page.locator('.approval-card')
  await card.getByRole('button', { name: '刷新', exact: true }).click()
  await expect(card.getByRole('button', { name: '重新加载', exact: true })).toBeVisible()
  await expect(card.getByText('target-a', { exact: true })).toBeVisible()
  fail = false
  await card.getByRole('button', { name: '重新加载', exact: true }).click()
  await expect(card.getByText('target-fresh', { exact: true })).toBeVisible()
  await expect(card.getByText('target-a', { exact: true })).toHaveCount(0)
})

test('审批列表重新登录后清除旧审批并忽略迟到的旧查询', async ({ page }) => {
  await setup(page)
  let held: Route | undefined
  let count = 0
  await page.route('**/api/governance/changes', r => {
    if (++count === 1) { held = r; return }
    return r.fulfill({ json: ok([approval('fresh')]) })
  })
  const card = page.locator('.approval-card')
  await card.getByRole('button', { name: '刷新', exact: true }).click()
  await expect.poll(() => Boolean(held)).toBe(true)
  await relogin(page)
  await expect(card.getByText('target-a', { exact: true })).toHaveCount(0)
  await release(page, held!, [approval('old-identity')])
  await expect(card.getByText('target-old-identity', { exact: true })).toHaveCount(0)
  await card.getByRole('button', { name: '刷新', exact: true }).click()
  await expect(card.getByText('target-fresh', { exact: true })).toBeVisible()
})

test('灰度租户选项失败可原地重试，恢复前不能提交', async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await setup(page, true)
  const dialog = await openGray(page, 801)
  await expect(dialog.getByRole('button', { name: '重新加载', exact: true })).toBeVisible()
  await expect(dialog.getByRole('button', { name: '创建安全灰度任务', exact: true })).toBeDisabled()
  await expect(page.locator('.el-message')).toHaveCount(0)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true)
  await page.screenshot({ path: testInfo.outputPath('gray-options-error-390.png'), animations: 'disabled' })
  await page.route('**/api/tenant/options', r => r.fulfill({ json: ok([
    { id: 11, tenantCode: 'acceptance-tenant', tenantName: '验收目标租户', status: 'ACTIVE' },
  ]) }))
  await dialog.getByRole('button', { name: '重新加载', exact: true }).click()
  await dialog.locator('.el-select__wrapper').click()
  await expect(page.getByRole('option', { name: '验收目标租户（acceptance-tenant）', exact: true })).toBeVisible()
})


test('版本对比一侧失败不展示半份快照，重试恢复原来两版', async ({ page }) => {
  let fail = true
  const requests: number[] = []
  await page.route(url => /^\/api\/config-version\/80[12]$/.test(url.pathname), r => {
    const id = Number(new URL(r.request().url()).pathname.split('/').pop())
    requests.push(id)
    return r.fulfill({ json: fail && id === 802 ? { code: 50000, message: '版本快照暂时失败' }
      : ok({ ...version(id), content: `版本内容-${id}` }) })
  })
  await setup(page)
  for (const id of [801, 802]) await page.getByRole('row').filter({ hasText: `agent-${id}` }).locator('.el-checkbox').click()
  await page.getByRole('button', { name: '对比选中两版', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '版本对比', exact: true })
  await expect(dialog.getByRole('button', { name: '重新加载', exact: true })).toBeVisible()
  await expect(dialog.locator('.diff-body')).toHaveCount(0)
  fail = false
  await dialog.getByRole('button', { name: '重新加载', exact: true }).click()
  await expect(dialog.locator('.diff-body')).toContainText('版本内容-801')
  await expect(dialog.locator('.diff-body')).toContainText('版本内容-802')
  await expect(dialog.locator('.diff-head')).toContainText('1 行有差异')
  expect(requests.sort()).toEqual([801, 801, 802, 802])
})
