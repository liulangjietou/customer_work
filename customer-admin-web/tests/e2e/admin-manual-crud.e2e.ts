import type { Page, Route } from '@playwright/test'
import { expect, test } from './fixtures/adminTestFixture'
const ok = (data: unknown) => ({ code: 0, data })
const cases = [
  { path: '/system/tenant', api: '/tenant/page', write: '/tenant', shape: 'list', name: '租户名称', key: 'tenantName', dialog: '编辑租户', save: '保存租户', permission: 'tenant',
    row: { id: 81, tenantCode: 'review-tenant', tenantName: '第一目标', status: 'ACTIVE', contactName: '', contactEmail: '', contactPhone: '', remark: '', expireTime: null, reserved: false } },
  { path: '/aiconfig/scheduled-task', api: '/aiconfig/scheduled-task/page', write: '/aiconfig/scheduled-task/81', shape: 'records', name: '任务名称', key: 'taskName', dialog: '编辑定时任务', save: '保存任务', permission: 'scheduler',
    row: { id: 81, taskCode: 'review-task', taskName: '第一目标', agentId: 7, agentName: '服务助手', prompt: '查询当前服务状态', cron: '0 0 9 * * ?', enabled: true, remark: '', scheduleMode: 'internal' } },
  { path: '/aiconfig/channel-robot', api: '/channel-robots/page', write: '/channel-robots/81', shape: 'records', name: '机器人名称', key: 'robotName', dialog: '编辑渠道机器人', save: '保存机器人', permission: 'channel-robot',
    row: { id: 81, channelType: 'dingtalk', robotName: '第一目标', appKey: 'review-app', robotCode: 'review-robot', hasSecret: true, hasEncodingAesKey: false, callbackMode: 'plaintext', agentCode: 'service-agent', sessionMode: 'continuous', status: 1, remark: '' } },
  { path: '/project', api: '/workspace/project', write: '/workspace/project/81', shape: 'array', name: '名称', key: 'projectName', dialog: '编辑 Project', save: '保存 Project', permission: '',
    row: { id: 81, projectName: '第一目标', description: '', sessionCount: 0 } },
]
for (const item of cases) {
  async function setup(page: Page) {
    const permissions = item.permission ? ['view', 'edit', 'add', 'delete'].map(p => `${item.permission}:${p}`) : ['workspace']
    await page.route('**/api/auth/permissions', r => r.fulfill({ json: ok(permissions) }))
    await page.route('**/api/aiconfig/agent?*', r => r.fulfill({ json: ok({ list: [{ id: 7, agentCode: 'service-agent', agentName: '服务助手', status: 1 }], total: 1 }) }))
    const rows = [item.row, { ...item.row, id: 82, [item.key]: '第二目标' }]
    await page.route(url => url.pathname === '/api' + item.api, r => r.fulfill({ json: ok(item.shape === 'array' ? rows : item.shape === 'records' ? { records: rows, total: 2 } : { list: rows, total: 2 }) }))
    await page.goto(item.path)
    await page.getByRole('row').filter({ hasText: '第一目标' }).getByRole('button', { name: '编辑', exact: true }).click()
    return page.getByRole('dialog', { name: item.dialog, exact: true })
  }
  test(`${item.path} 保存锁定表单，失败保留输入且重试请求不变`, async ({ page }) => {
    let held: Route | undefined
    const payloads: unknown[] = []
    await page.route(url => url.pathname === '/api' + item.write, r => {
      if (r.request().method() !== 'PUT') return r.fallback()
      payloads.push(r.request().postDataJSON())
      if (payloads.length === 1) { held = r; return }
      return r.fulfill({ json: ok(null) })
    })
    const dialog = await setup(page)
    await dialog.getByLabel(item.name, { exact: true }).fill('可恢复的新名称')
    const save = dialog.getByRole('button', { name: item.save, exact: true })
    await save.click()
    await expect.poll(() => !!held).toBe(true)
    await expect(save).toBeDisabled()
    await expect(dialog.getByLabel(item.name, { exact: true })).toBeDisabled()
    await held!.fulfill({ json: { code: 50000, message: '当前保存失败' } })
    await expect(save).toBeEnabled()
    await expect(dialog.getByLabel(item.name, { exact: true })).toHaveValue('可恢复的新名称')
    await save.click()
    await expect(dialog).not.toBeVisible()
    expect(payloads).toHaveLength(2)
    expect(payloads[0]).toEqual(payloads[1])
    expect(payloads[1]).toMatchObject({ [item.key]: '可恢复的新名称' })
  })
  test(`${item.path} 旧保存完成不能关闭后来打开的编辑目标`, async ({ page }) => {
    let held: Route | undefined
    await page.route(url => url.pathname === '/api' + item.write, r => {
      if (r.request().method() !== 'PUT') return r.fallback()
      held = r
    })
    let dialog = await setup(page)
    await dialog.getByRole('button', { name: item.save, exact: true }).click()
    await expect.poll(() => !!held).toBe(true)
    await dialog.locator('.el-dialog__headerbtn').click()
    await page.getByRole('row').filter({ hasText: '第二目标' }).getByRole('button', { name: '编辑', exact: true }).click()
    dialog = page.getByRole('dialog', { name: item.dialog, exact: true })
    await expect(dialog.getByLabel(item.name, { exact: true })).toHaveValue('第二目标')
    const response = page.waitForResponse(r => r.url() === held!.request().url() && r.request().method() === 'PUT')
    await held!.fulfill({ json: ok(null) })
    await (await response).finished()
    await page.evaluate(async () => {
      await new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve())))
      await Promise.all(document.getAnimations().filter(a => Number.isFinite(a.effect?.getComputedTiming().endTime)).map(a => a.finished.catch(() => {})))
    })
    await expect(dialog).toBeVisible()
    await expect(dialog.getByLabel(item.name, { exact: true })).toHaveValue('第二目标')
    await expect(dialog.getByRole('button', { name: item.save, exact: true })).toBeEnabled()
  })
}
