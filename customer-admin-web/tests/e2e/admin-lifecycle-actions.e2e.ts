import type { Page, Route } from '@playwright/test'
import { expect, test } from './fixtures/adminTestFixture'
const ok = (data: unknown) => ({ code: 0, data })
const date = '2026-09-15 10:00:00'
const tenant = { id: 801, tenantCode: 'acceptance', tenantName: '验收租户', status: 'ACTIVE', reserved: false, createTime: date }
const schedule = (id: number) => ({ id, taskCode: `task-${id}`, taskName: `定时任务${id}`, agentId: 7, agentName: '服务助手',
  prompt: '仅浏览器夹具', cron: '0 0 9 * * ?', enabled: true, scheduleMode: 'internal', createTime: date })
const task = (id: string) => ({ taskId: id, parentAgentCode: 'service-agent', subAgentId: 'readonly-helper',
  status: 'RUNNING', result: `任务内容-${id}`, createdAt: date, costMs: 1250 })
const project = (id: number) => ({ id, projectName: `项目${id}`, description: '验收项目', sessionCount: 1, createTime: date })
const session = (id: number) => ({ agentCode: 'service-agent', agentName: '服务助手', sessionId: `session-${id}`,
  preview: `项目会话-${id}`, messageCount: 3, stale: false, lastMessageTime: date })
async function relogin(page: Page, permissions: string[]) {
  await page.evaluate(async permissions => {
    const modulePath = '/src/store/auth.ts'
    const { useAuthStore } = await import(modulePath)
    const auth = useAuthStore()
    auth.applyLoginResult({ token: auth.token, nickname: '新登录身份', forceChangePassword: false,
      approvalStatus: 'APPROVED', approvalRemark: null }, 'new-user')
    auth.permissions = permissions
  }, permissions)
}
async function paint(page: Page) {
  await page.evaluate(() => new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))))
}
async function release(page: Page, held: Route, data: unknown) {
  const response = page.waitForResponse(r => r.url() === held.request().url())
  await held.fulfill({ json: ok(data) })
  await (await response).finished()
  await paint(page)
}
const confirmations = [
  { path: '/system/tenant', api: '/tenant/page', action: '/tenant/801/status', row: tenant, shape: 'list',
    marker: '验收租户', permissions: ['tenant:view', 'tenant:edit'], button: '冻结', dialog: '冻结确认', response: null },
  { path: '/aiconfig/scheduled-task', api: '/aiconfig/scheduled-task/page', action: '/aiconfig/scheduled-task/803/trigger',
    row: schedule(803), shape: 'records', marker: '定时任务803', permissions: ['scheduler:view', 'scheduler:trigger'],
    button: '手动触发', dialog: '手动触发', response: { id: 901, status: 'SUCCESS', output: '仅受控夹具结果', costMs: 5 } },
  { path: '/aiconfig/agent-task', api: '/aiconfig/agent-task/page', action: '/aiconfig/agent-task/acceptance-task/cancel',
    row: task('acceptance-task'), shape: 'records', marker: 'acceptance-task', permissions: ['agent-task:view', 'agent-task:cancel'],
    button: '取消', dialog: '取消任务', response: null },
]
confirmations.push(
  { ...confirmations[0], row: { ...tenant, status: 'SUSPENDED' }, button: '恢复', dialog: '恢复确认' },
  { ...confirmations[0], button: '退租', dialog: '退租确认' },
)
for (const item of confirmations) test(`${item.path} ${item.button}确认框期间重登后不发送旧业务操作`, async ({ page }) => {
  const writes: string[] = []
  await page.route('**/api/auth/permissions', r => r.fulfill({ json: ok(item.permissions) }))
  await page.route(url => url.pathname === '/api' + item.api, r => r.fulfill({ json: ok({ total: 1, current: 1, size: 10,
    [item.shape]: [item.row] }) }))
  await page.route(url => url.pathname === '/api' + item.action, r => {
    writes.push(r.request().method()); return r.fulfill({ json: ok(item.response) })
  })
  await page.goto(item.path)
  await page.getByRole('row').filter({ hasText: item.marker }).getByRole('button', { name: item.button, exact: true }).click()
  const dialog = page.getByRole('dialog', { name: item.dialog, exact: true })
  await expect(dialog).toBeVisible()
  await relogin(page, item.permissions)
  await dialog.getByRole('button', { name: '确定', exact: true }).click()
  await expect(dialog).not.toBeVisible()
  await paint(page)
  expect(writes).toEqual([])
})

test('租户冻结故障保留原状态，写入期间锁定且重试成功才展示冻结', async ({ page }) => {
  let held: Route | undefined
  let frozen = false
  await page.route('**/api/auth/permissions', r => r.fulfill({ json: ok(['tenant:view', 'tenant:edit']) }))
  await page.route('**/api/tenant/page?*', r => r.fulfill({ json: ok({ total: 1, list: [{ ...tenant, status: frozen ? 'SUSPENDED' : 'ACTIVE' }] }) }))
  await page.route('**/api/tenant/801/status?*', r => { held = r })
  await page.goto('/system/tenant')
  const row = page.getByRole('row').filter({ hasText: '验收租户' })
  const action = row.getByRole('button', { name: '冻结', exact: true })
  await action.click()
  await page.getByRole('dialog', { name: '冻结确认', exact: true }).getByRole('button', { name: '确定', exact: true }).click()
  await expect.poll(() => Boolean(held)).toBe(true)
  await expect(action).toBeDisabled()
  await held!.fulfill({ json: { code: 50000, message: '冻结暂时失败' } })
  await expect(action).toBeEnabled()
  await expect(row.getByText('正常', { exact: true })).toBeVisible()
  held = undefined
  await action.click()
  await page.getByRole('dialog', { name: '冻结确认', exact: true }).getByRole('button', { name: '确定', exact: true }).click()
  await expect.poll(() => Boolean(held)).toBe(true)
  expect(new URL(held!.request().url()).searchParams.get('status')).toBe('SUSPENDED')
  frozen = true
  await release(page, held!, null)
  await expect(row.getByText('已冻结', { exact: true })).toBeVisible()
})

test('后台任务详情切换目标后，旧结果不能覆盖新任务', async ({ page }) => {
  let held: Route | undefined
  await page.route('**/api/auth/permissions', r => r.fulfill({ json: ok(['agent-task:view']) }))
  await page.route('**/api/aiconfig/agent-task/page?*', r => r.fulfill({ json: ok({ total: 2, records: [task('task-a'), task('task-b')] }) }))
  await page.route('**/api/aiconfig/agent-task/task-a', r => { held = r })
  await page.route('**/api/aiconfig/agent-task/task-b', r => r.fulfill({ json: ok(task('task-b')) }))
  await page.goto('/aiconfig/agent-task')
  await page.getByRole('row').filter({ hasText: 'task-a' }).getByRole('button', { name: '详情', exact: true }).click()
  await expect.poll(() => Boolean(held)).toBe(true)
  const drawer = page.getByRole('dialog', { name: '任务详情', exact: true })
  await drawer.locator('.el-drawer__close-btn').click()
  await expect(drawer).not.toBeVisible()
  await page.getByRole('row').filter({ hasText: 'task-b' }).getByRole('button', { name: '详情', exact: true }).click()
  await expect(drawer.getByText('任务内容-task-b', { exact: true })).toBeVisible()
  await release(page, held!, task('task-a'))
  await expect(drawer.getByText('任务内容-task-b', { exact: true })).toBeVisible()
  await expect(drawer.getByText('任务内容-task-a', { exact: true })).toHaveCount(0)
})

test('Project 会话迟到响应不能把旧项目内容放进新项目', async ({ page }) => {
  let held: Route | undefined
  await page.route('**/api/auth/permissions', r => r.fulfill({ json: ok(['workspace']) }))
  await page.route('**/api/workspace/project', r => r.fulfill({ json: ok([project(806), project(807)]) }))
  await page.route('**/api/workspace/project/806/sessions', r => { held = r })
  await page.route('**/api/workspace/project/807/sessions', r => r.fulfill({ json: ok([session(807)]) }))
  await page.goto('/project')
  await page.getByText('项目806', { exact: true }).click()
  await expect.poll(() => Boolean(held)).toBe(true)
  await page.getByRole('dialog', { name: 'Project · 项目806', exact: true }).locator('.el-drawer__close-btn').click()
  await page.getByText('项目807', { exact: true }).click()
  const drawer = page.getByRole('dialog', { name: 'Project · 项目807', exact: true })
  await expect(drawer.getByText('项目会话-807', { exact: true })).toBeVisible()
  await release(page, held!, [session(806)])
  await expect(drawer.getByText('项目会话-807', { exact: true })).toBeVisible()
  await expect(drawer.getByText('项目会话-806', { exact: true })).toHaveCount(0)
})

const run = (id: number) => ({ id, taskId: id, status: 'SUCCESS', triggerType: 'MANUAL',
  startTime: date, endTime: date, costMs: 7, output: `执行记录-${id}` })
async function schedules(page: Page, permissions = ['scheduler:view', 'scheduler:edit', 'scheduler:trigger']) {
  await page.route('**/api/auth/permissions', r => r.fulfill({ json: ok(permissions) }))
  await page.route('**/api/aiconfig/scheduled-task/page?*', r => r.fulfill({ json: ok({
    records: [schedule(803), schedule(804)], total: 2, current: 1, size: 10,
  }) }))
}

test('定时任务执行历史切换目标后丢弃旧记录', async ({ page }) => {
  await schedules(page)
  let held: Route | undefined
  await page.route('**/api/aiconfig/scheduled-task/803/runs?*', r => { held = r })
  await page.route('**/api/aiconfig/scheduled-task/804/runs?*', r => r.fulfill({ json: ok({ records: [run(804)], total: 1 }) }))
  await page.goto('/aiconfig/scheduled-task')
  await page.getByRole('row').filter({ hasText: '定时任务803' }).getByRole('button', { name: '执行历史', exact: true }).click()
  await expect.poll(() => Boolean(held)).toBe(true)
  await page.getByRole('dialog', { name: '执行历史 · 定时任务803', exact: true }).locator('.el-drawer__close-btn').click()
  await page.getByRole('row').filter({ hasText: '定时任务804' }).getByRole('button', { name: '执行历史', exact: true }).click()
  const drawer = page.getByRole('dialog', { name: '执行历史 · 定时任务804', exact: true })
  await expect(drawer.getByRole('button', { name: '查看输出', exact: true })).toBeVisible()
  await release(page, held!, { records: [run(803)], total: 1 })
  await drawer.getByRole('button', { name: '查看输出', exact: true }).click()
  await expect(page.getByRole('dialog', { name: '执行输出', exact: true }).getByText('执行记录-804', { exact: true })).toBeVisible()
})

test('定时任务历史首次失败保留抽屉并可重试', async ({ page }) => {
  await schedules(page)
  let fail = true
  await page.route('**/api/aiconfig/scheduled-task/803/runs?*', r => r.fulfill({ json: fail
    ? { code: 50000, message: '历史暂时不可用' } : ok({ records: [run(803)], total: 1 }) }))
  await page.goto('/aiconfig/scheduled-task')
  await page.getByRole('row').filter({ hasText: '定时任务803' }).getByRole('button', { name: '执行历史', exact: true }).click()
  const drawer = page.getByRole('dialog', { name: '执行历史 · 定时任务803', exact: true })
  await expect(drawer.getByRole('button', { name: '重新加载', exact: true })).toBeVisible()
  await expect(drawer.getByText('暂无执行记录', { exact: true })).toHaveCount(0)
  fail = false
  await drawer.getByRole('button', { name: '重新加载', exact: true }).click()
  await expect(drawer.getByRole('button', { name: '查看输出', exact: true })).toBeVisible()
})

test('定时任务启停写入期间锁定开关，失败后保留原值并可重试', async ({ page }) => {
  await schedules(page)
  let held: Route | undefined
  await page.route('**/api/aiconfig/scheduled-task/803/disable', r => { held = r })
  await page.goto('/aiconfig/scheduled-task')
  const row = page.getByRole('row').filter({ hasText: '定时任务803' })
  const toggle = row.getByRole('switch')
  await row.locator('.el-switch').click()
  await expect.poll(() => Boolean(held)).toBe(true)
  await expect(toggle).toBeDisabled()
  await held!.fulfill({ json: { code: 50000, message: '停用暂时失败' } })
  await expect(toggle).toBeEnabled()
  await expect(toggle).toBeChecked()
  held = undefined
  await row.locator('.el-switch').click()
  await expect.poll(() => Boolean(held)).toBe(true)
  await release(page, held!, null)
  await expect(toggle).not.toBeChecked()
})

test('手动触发的迟到结果不能在重登后打开旧身份结果框', async ({ page }) => {
  const permissions = ['scheduler:view', 'scheduler:trigger']
  await schedules(page, permissions)
  let held: Route | undefined
  await page.route('**/api/aiconfig/scheduled-task/803/trigger', r => { held = r })
  await page.goto('/aiconfig/scheduled-task')
  await page.getByRole('row').filter({ hasText: '定时任务803' }).getByRole('button', { name: '手动触发', exact: true }).click()
  await page.getByRole('dialog', { name: '手动触发', exact: true }).getByRole('button', { name: '确定', exact: true }).click()
  await expect.poll(() => Boolean(held)).toBe(true)
  await relogin(page, permissions)
  await release(page, held!, run(803))
  await expect(page.getByRole('dialog', { name: '手动触发结果', exact: true })).toHaveCount(0)
})

for (const item of [
  { label: '后台任务', path: '/aiconfig/agent-task', permissions: ['agent-task:view'],
    listApi: '/aiconfig/agent-task/page', detailApi: '/aiconfig/agent-task/task-a',
    rows: { records: [task('task-a')], total: 1 }, detail: task('task-a'), title: '任务详情', marker: '任务内容-task-a' },
  { label: 'Project', path: '/project', permissions: ['workspace'], listApi: '/workspace/project',
    detailApi: '/workspace/project/806/sessions', rows: [project(806)], detail: [session(806)],
    title: 'Project · 项目806', marker: '项目会话-806' },
]) test(`${item.label} 详情失败保留原目标并可重试`, async ({ page }) => {
  let fail = true
  await page.route('**/api/auth/permissions', r => r.fulfill({ json: ok(item.permissions) }))
  await page.route(url => url.pathname === '/api' + item.listApi, r => r.fulfill({ json: ok(item.rows) }))
  await page.route(url => url.pathname === '/api' + item.detailApi, r => r.fulfill({ json: fail
    ? { code: 50000, message: '详情暂时不可用' } : ok(item.detail) }))
  await page.goto(item.path)
  if (item.label === 'Project') await page.getByText('项目806', { exact: true }).click()
  else await page.getByRole('row').filter({ hasText: 'task-a' }).getByRole('button', { name: '详情', exact: true }).click()
  const drawer = page.getByRole('dialog', { name: item.title, exact: true })
  await expect(drawer.getByRole('button', { name: '重新加载', exact: true })).toBeVisible()
  fail = false
  await drawer.getByRole('button', { name: '重新加载', exact: true }).click()
  await expect(drawer.getByText(item.marker, { exact: true })).toBeVisible()
})

test('Project 移出失败保留会话，写入时锁定并允许按原关联重试', async ({ page }) => {
  let held: Route | undefined
  let removed = false
  let writes = 0
  await page.route('**/api/auth/permissions', r => r.fulfill({ json: ok(['workspace']) }))
  await page.route('**/api/workspace/project', r => r.fulfill({ json: ok([project(806)]) }))
  await page.route('**/api/workspace/project/806/sessions', r => r.fulfill({ json: ok(removed ? [] : [session(806)]) }))
  await page.route('**/api/workspace/project/806/sessions/service-agent/session-806', r => {
    expect(r.request().method()).toBe('DELETE')
    writes += 1
    held = r
  })
  await page.goto('/project')
  await page.getByText('项目806', { exact: true }).click()
  const drawer = page.getByRole('dialog', { name: 'Project · 项目806', exact: true })
  const remove = drawer.getByRole('button', { name: '移出', exact: true })
  await remove.click()
  await expect.poll(() => writes).toBe(1)
  await expect(remove).toBeDisabled()
  await held!.fulfill({ json: { code: 50000, message: '移出暂时失败' } })
  await expect(remove).toBeEnabled()
  await expect(drawer.getByText('项目会话-806', { exact: true })).toBeVisible()
  await remove.click()
  await expect.poll(() => writes).toBe(2)
  removed = true
  await release(page, held!, null)
  await expect(drawer.getByText('项目会话-806', { exact: true })).toHaveCount(0)
})

test('Project 旧移出完成不能刷新后来打开的项目或报告新项目成功', async ({ page }) => {
  let held: Route | undefined
  let newerReads = 0
  await page.route('**/api/auth/permissions', r => r.fulfill({ json: ok(['workspace']) }))
  await page.route('**/api/workspace/project', r => r.fulfill({ json: ok([project(806), project(807)]) }))
  await page.route('**/api/workspace/project/806/sessions', r => r.fulfill({ json: ok([session(806)]) }))
  await page.route('**/api/workspace/project/807/sessions', r => {
    newerReads += 1
    return r.fulfill({ json: ok([session(807)]) })
  })
  await page.route('**/api/workspace/project/806/sessions/service-agent/session-806', r => { held = r })
  await page.goto('/project')
  await page.getByText('项目806', { exact: true }).click()
  const old = page.getByRole('dialog', { name: 'Project · 项目806', exact: true })
  await old.getByRole('button', { name: '移出', exact: true }).click()
  await expect.poll(() => Boolean(held)).toBe(true)
  await old.locator('.el-drawer__close-btn').click()
  await page.getByText('项目807', { exact: true }).click()
  const current = page.getByRole('dialog', { name: 'Project · 项目807', exact: true })
  await expect(current.getByText('项目会话-807', { exact: true })).toBeVisible()
  await release(page, held!, null)
  await expect(page.getByText('已移出项目', { exact: true })).toHaveCount(0)
  expect(newerReads).toBe(1)
  await expect(current.getByText('项目会话-807', { exact: true })).toBeVisible()
})

test('手动触发先慢后快，旧任务结果不能覆盖最近查看的任务结果', async ({ page }) => {
  await schedules(page)
  let held: Route | undefined
  await page.route('**/api/aiconfig/scheduled-task/803/trigger', r => { held = r })
  await page.route('**/api/aiconfig/scheduled-task/804/trigger', r => r.fulfill({ json: ok(run(804)) }))
  await page.goto('/aiconfig/scheduled-task')
  for (const id of [803, 804]) {
    await page.getByRole('row').filter({ hasText: `定时任务${id}` }).getByRole('button', { name: '手动触发', exact: true }).click()
    await page.getByRole('dialog', { name: '手动触发', exact: true }).getByRole('button', { name: '确定', exact: true }).click()
    if (id === 803) await expect.poll(() => Boolean(held)).toBe(true)
  }
  const dialog = page.getByRole('dialog', { name: '手动触发结果', exact: true })
  await expect(dialog.getByText('执行记录-804', { exact: true })).toBeVisible()
  await release(page, held!, run(803))
  await expect(dialog.getByText('执行记录-804', { exact: true })).toBeVisible()
})

test('后台任务取消失败保留运行状态，锁定期间不能重复且可重试', async ({ page }) => {
  let held: Route | undefined
  let writes = 0
  let cancelled = false
  await page.route('**/api/auth/permissions', r => r.fulfill({ json: ok(['agent-task:view', 'agent-task:cancel']) }))
  await page.route('**/api/aiconfig/agent-task/page?*', r => r.fulfill({ json: ok({ total: 1,
    records: [{ ...task('task-a'), status: cancelled ? 'CANCELLED' : 'RUNNING' }] }) }))
  await page.route('**/api/aiconfig/agent-task/task-a/cancel', r => { writes += 1; held = r })
  await page.goto('/aiconfig/agent-task')
  const row = page.getByRole('row').filter({ hasText: 'task-a' })
  const cancel = row.getByRole('button', { name: '取消', exact: true })
  for (let attempt = 1; attempt <= 2; attempt++) {
    await cancel.click()
    await page.getByRole('dialog', { name: '取消任务', exact: true }).getByRole('button', { name: '确定', exact: true }).click()
    await expect.poll(() => writes).toBe(attempt)
    await expect(cancel).toBeDisabled()
    if (attempt === 1) {
      await held!.fulfill({ json: { code: 50000, message: '取消暂时失败' } })
      await expect(cancel).toBeEnabled()
      await expect(row).toContainText('RUNNING')
    } else {
      cancelled = true
      await release(page, held!, null)
      await expect(row).toContainText('CANCELLED')
      await expect(cancel).toBeDisabled()
    }
  }
})


test('手动触发接口失败后释放行锁，重试成功才显示结果', async ({ page }) => {
  await schedules(page, ['scheduler:view', 'scheduler:trigger'])
  let held: Route | undefined
  let requests = 0
  await page.route('**/api/aiconfig/scheduled-task/803/trigger', r => { requests += 1; held = r })
  await page.goto('/aiconfig/scheduled-task')
  const button = page.getByRole('row').filter({ hasText: '定时任务803' }).getByRole('button', { name: '手动触发', exact: true })
  const confirm = async () => {
    await button.click()
    await page.getByRole('dialog', { name: '手动触发', exact: true }).getByRole('button', { name: '确定', exact: true }).click()
    await expect.poll(() => Boolean(held)).toBe(true)
  }
  await confirm()
  await expect(button).toBeDisabled()
  await held!.fulfill({ json: { code: 50000, message: '触发暂时失败' } })
  await expect(button).toBeEnabled()
  await expect(page.getByRole('dialog', { name: '手动触发结果', exact: true })).not.toBeVisible()
  held = undefined
  await confirm()
  await release(page, held!, run(803))
  const result = page.getByRole('dialog', { name: '手动触发结果', exact: true })
  await expect(result).toContainText('执行记录-803')
  await expect(button).toBeEnabled()
  expect(requests).toBe(2)
})
