import type { Page, Route } from '@playwright/test'
import { expect, test } from './fixtures/adminTestFixture'

const ok = (data: unknown) => ({ code: 0, data })
const permissions = ['sql-datasource:view', 'channel-robot:view', 'channel-robot:edit', 'agent:view']
const datasources = [301, 302].map(id => ({ id, name: `验收数据源 ${id}`, username: 'acceptance',
  passwordMasked: '******', jdbcUrl: 'jdbc:mysql://localhost:3306/acceptance', enabled: true }))
const robot = { id: 401, robotName: '验收渠道机器人', channelType: 'dingtalk', appKey: 'synthetic-app',
  robotCode: 'synthetic-robot', agentCode: 'acceptance-agent', status: 1, hasSecret: true,
  sessionMode: 'continuous', remark: '合成验收数据' }
const agent = (name: string) => ({ id: 7, agentCode: 'acceptance-agent', agentName: name, status: 1 })
async function settle(page: Page) {
  await page.evaluate(async () => {
    const paint = () => new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve())))
    await paint()
    await Promise.all(document.getAnimations().filter(a => Number.isFinite(Number(a.effect?.getComputedTiming().endTime)))
      .map(a => a.finished.catch(() => {})))
    await paint()
  })
}
async function identity(page: Page, granted = permissions) {
  await page.evaluate(async permissions => {
    const source = '/src/store/auth.ts'
    const { useAuthStore } = await import(source)
    const auth = useAuthStore()
    auth.applyLoginResult({ token: auth.token, nickname: '后续身份', forceChangePassword: false,
      approvalStatus: 'APPROVED', approvalRemark: null }, 'read-actions-later-user')
    auth.permissions = permissions
  }, granted)
  await settle(page)
}
async function release(page: Page, route: Route, data: unknown) {
  const request = route.request()
  const response = page.waitForResponse(r => r.request() === request)
  await route.fulfill({ json: ok(data) })
  await (await response).finished()
  await settle(page)
}
async function setup(page: Page, granted = permissions) {
  await page.route('**/api/auth/permissions', r => r.fulfill({ json: ok(granted) }))
  await page.route('**/api/sql/datasource?*', r => r.fulfill({ json: ok({ list: datasources, total: 2 }) }))
  await page.route('**/api/channel-robots/page?*', r => r.fulfill({ json: ok({ records: [robot], total: 1, current: 1, size: 10 }) }))
}
const sqlRow = (page: Page, id = 301) => page.getByRole('row').filter({ hasText: `验收数据源 ${id}` })
const robotRow = (page: Page) => page.getByRole('row').filter({ hasText: '验收渠道机器人' })

test('SQL 数据源旧身份探测完成不提示新身份操作成功', async ({ page }) => {
  await setup(page)
  let held: Route | undefined
  await page.route('**/api/sql/datasource/301/test', r => { held = r })
  await page.goto('/sql/datasource')
  await sqlRow(page).getByRole('button', { name: '测试连接', exact: true }).click()
  await expect.poll(() => Boolean(held)).toBe(true)
  await identity(page)
  await page.evaluate(() => {
    const seen: string[] = []
    Object.assign(window, { acceptanceSuccessMessages: seen })
    new MutationObserver(() => document.querySelectorAll('.el-message--success').forEach(node => {
      seen.push(node.textContent ?? '')
    })).observe(document.body, { childList: true, subtree: true })
  })
  await release(page, held!, null)
  // 记录出现过的提示，避免自动重试等到错误提示自行消失后误判通过。
  expect(await page.evaluate(() => (window as unknown as { acceptanceSuccessMessages: string[] }).acceptanceSuccessMessages)).toEqual([])
})

test('SQL 多数据源探测各自锁定，失败可重试且不解锁另一行', async ({ page }) => {
  await setup(page)
  const requests: number[] = []
  const held = new Map<number, Route>()
  await page.route('**/api/sql/datasource/*/test', r => {
    const id = Number(new URL(r.request().url()).pathname.split('/')[4])
    requests.push(id); held.set(id, r)
  })
  await page.goto('/sql/datasource')
  const a = sqlRow(page).getByRole('button', { name: '测试连接', exact: true })
  const b = sqlRow(page, 302).getByRole('button', { name: '测试连接', exact: true })
  await a.click()
  await b.click()
  await expect.poll(() => held.size).toBe(2)
  await expect(a).toBeDisabled()
  await expect(b).toBeDisabled()
  await held.get(301)!.fulfill({ json: { code: 50000, message: '验收连接暂时失败' } })
  await expect(a).toBeEnabled()
  await expect(b).toBeDisabled()
  held.delete(301)
  await a.click()
  await expect.poll(() => held.has(301)).toBe(true)
  await release(page, held.get(301)!, null)
  await expect(a).toBeEnabled()
  await expect(b).toBeDisabled()
  await release(page, held.get(302)!, null)
  await expect(b).toBeEnabled()
  expect(requests).toEqual([301, 302, 301])
})

test('渠道绑定选项旧查询迟到不能显示到重新登录后的页面', async ({ page }) => {
  await setup(page)
  let old: Route | undefined
  let reads = 0
  await page.route('**/api/aiconfig/agent?*', r => {
    reads += 1
    if (reads === 1) { old = r; return }
    return r.fulfill({ json: ok({ list: [agent('当前身份智能体')], total: 1 }) })
  })
  await page.goto('/aiconfig/channel-robot')
  await expect(robotRow(page)).toBeVisible()
  await expect.poll(() => Boolean(old)).toBe(true)
  await identity(page)
  await page.getByRole('button', { name: '搜索', exact: true }).click()
  await expect(robotRow(page)).toBeVisible()
  await release(page, old!, { list: [agent('旧身份智能体')], total: 1 })
  await expect(robotRow(page)).not.toContainText('旧身份智能体')
  await expect(robotRow(page)).toContainText('当前身份智能体')
})

test('渠道只读角色缺少智能体权限时保留绑定编码且不发越权查询', async ({ page }) => {
  await setup(page, ['channel-robot:view'])
  let reads = 0
  await page.route('**/api/aiconfig/agent?*', r => {
    reads += 1; return r.fulfill({ json: ok({ list: [], total: 0 }) })
  })
  await page.goto('/aiconfig/channel-robot')
  await expect(robotRow(page)).toContainText('acceptance-agent')
  await settle(page)
  expect(reads).toBe(0)
})

test('渠道智能体选项失败可原地重试且不丢失机器人列表', async ({ page }) => {
  await setup(page)
  let failed = true
  await page.route('**/api/aiconfig/agent?*', r => r.fulfill({ json: failed
    ? { code: 50000, message: '验收智能体选项暂时失败' }
    : ok({ list: [agent('已恢复智能体')], total: 1 }) }))
  await page.goto('/aiconfig/channel-robot')
  await expect(robotRow(page)).toBeVisible()
  const retry = page.getByRole('button', { name: '重新加载', exact: true })
  await expect(retry).toBeVisible()
  failed = false
  await retry.click()
  await expect(robotRow(page)).toContainText('已恢复智能体')
  await expect(retry).not.toBeVisible()
})
