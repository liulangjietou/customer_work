import type { Page, Route } from '@playwright/test'
import { expect, test } from './fixtures/adminTestFixture'

// MCP 弹窗跨两次打开并等待迟到响应，保留完整断言所需的执行时间。
test.setTimeout(60_000)

const ok = (data: unknown) => ({ code: 0, data })
const permissions = ['mcp:view', 'mcp:add', 'mcp:edit', 'mcp:delete',
  'knowledge-base:view', 'knowledge-base:edit']
const mcps = [61, 62].map((id, i) => ({ id, mcpName: `验收 MCP ${i ? 'B' : 'A'}`,
  mcpType: 'http', config: '{"url":"https://example.invalid/mcp"}', description: '验收服务',
  allowedSubjectTypes: ['ADMIN_USER'], status: 1, testStatus: 1, credential: null }))
const tool = (name: string) => ({ name, description: '合成测试工具', required: ['message'],
  properties: { message: { type: 'string', description: '验收输入' } } })
const snapshot = (id: number) => ({ id, capturedAt: '2026-09-15 12:00:00', driftSeverity: 'NONE',
  toolCount: id, changes: [], tools: [], contractHash: `acceptance-${id}` })
type Write = { method: string; path: string; data: unknown }

async function setup(page: Page, granted = permissions) {
  const writes: Write[] = []
  await page.route('**/api/auth/permissions', r => r.fulfill({ json: ok(granted) }))
  await page.route(/\/api\/aiconfig\/mcp(?:\/.*|\?.*)?$/, async route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    const id = Number(path.split('/')[4])
    if (request.method() === 'GET') {
      if (path === '/api/aiconfig/mcp') await route.fulfill({ json: ok({ list: mcps, total: 2 }) })
      else if (/\/mcp\/\d+$/.test(path)) await route.fulfill({ json: ok(mcps.find(m => m.id === id)) })
      else if (path.endsWith('/contract/history')) await route.fulfill({ json: ok([snapshot(id)]) })
      else await route.fallback()
      return
    }
    writes.push({ method: request.method(), path, data: request.postData() ? request.postDataJSON() : null })
    if (path.endsWith('/debug/tools')) await route.fulfill({ json: ok([tool(`tool-${id}`)]) })
    else if (path.endsWith('/debug/call')) await route.fulfill({ json: ok({ success: true, output: '合成调用结果', images: [], outputLooksBinary: false }) })
    else if (path.endsWith('/test-connectivity')) await route.fulfill({ json: ok({ testStatus: 1, message: '合成探测' }) })
    else if (path.endsWith('/contract/capture')) await route.fulfill({ json: ok(snapshot(id)) })
    else await route.fulfill({ json: ok(null) })
  })
  const response = page.waitForResponse(r => new URL(r.url()).pathname === '/api/aiconfig/mcp')
  await page.goto('/aiconfig/mcp')
  await (await response).finished()
  await expect(mcpRow(page)).toBeVisible()
  return writes
}
const mcpRow = (page: Page, name = 'A') => page.locator('.el-table__row').filter({
  has: page.getByRole('button', { name: `验收 MCP ${name}的更多操作`, exact: true }),
})
const mcpForm = (page: Page, action = '编辑') => page.getByRole('dialog', { name: `${action} MCP`, exact: true })
const debug = (page: Page, name = 'A') => page.getByRole('dialog', { name: `调试 MCP · 验收 MCP ${name}`, exact: true })
const contract = (page: Page, name = 'A') => page.getByRole('dialog', { name: `契约快照 · 验收 MCP ${name}`, exact: true })
async function menu(page: Page, action: string, name = 'A') {
  await mcpRow(page, name).getByRole('button', { name: `验收 MCP ${name}的更多操作`, exact: true }).click()
  await page.getByRole('menuitem', { name: action, exact: true }).click()
}
async function identity(page: Page) {
  await page.evaluate(async permissions => {
    const path = '/src/store/auth.ts'
    const { useAuthStore } = await import(path)
    const auth = useAuthStore()
    auth.applyLoginResult({ token: auth.token, nickname: '后续身份', forceChangePassword: false,
      approvalStatus: 'APPROVED', approvalRemark: null }, 'resource-later-user')
    auth.permissions = permissions
  }, permissions)
}
async function settle(page: Page) {
  await page.evaluate(async () => {
    const paint = () => new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve())))
    await paint()
    await Promise.all(document.getAnimations().filter(a => Number.isFinite(Number(a.effect?.getComputedTiming().endTime)))
      .map(a => a.finished.catch(() => {})))
    await paint()
  })
}
async function release(page: Page, route: Route, data: unknown) {
  const request = route.request()
  const waiting = page.waitForResponse(response => response.request() === request)
  await route.fulfill({ json: ok(data) })
  await (await waiting).finished()
  await settle(page)
}

test('MCP 迟到的编辑详情不能覆盖后打开的目标表单', async ({ page }) => {
  await setup(page)
  let held: Route | undefined
  await page.route('**/api/aiconfig/mcp/61', route => { held = route })
  await mcpRow(page).getByRole('button', { name: '编辑', exact: true }).click()
  await expect.poll(() => Boolean(held)).toBe(true)
  await mcpRow(page, 'B').getByRole('button', { name: '编辑', exact: true }).click()
  await expect(mcpForm(page).getByLabel('名称', { exact: true })).toHaveValue('验收 MCP B')
  await release(page, held!, mcps[0])
  await expect(mcpForm(page).getByLabel('名称', { exact: true })).toHaveValue('验收 MCP B')
})

test('MCP 保存失败可重试，提交期间锁定输入并仅写入一次', async ({ page }) => {
  await setup(page)
  const writes: unknown[] = []
  let held: Route | undefined
  await page.route('**/api/aiconfig/mcp/61', route => {
    if (route.request().method() === 'GET') return route.fulfill({ json: ok(mcps[0]) })
    writes.push(route.request().postDataJSON()); held = route
  })
  await mcpRow(page).getByRole('button', { name: '编辑', exact: true }).click()
  await mcpForm(page).getByLabel('名称', { exact: true }).fill('验收修改')
  await mcpForm(page).getByRole('button', { name: '保存 MCP', exact: true }).click()
  await expect.poll(() => Boolean(held)).toBe(true)
  await held!.fulfill({ json: { code: 50000, message: '验收保存失败' } })
  await expect(mcpForm(page).getByRole('button', { name: '保存 MCP', exact: true })).toBeEnabled()
  await expect(mcpForm(page).getByLabel('名称', { exact: true })).toHaveValue('验收修改')
  held = undefined
  await mcpForm(page).getByRole('button', { name: '保存 MCP', exact: true }).click()
  await expect.poll(() => Boolean(held)).toBe(true)
  await expect(mcpForm(page).getByLabel('名称', { exact: true })).toBeDisabled()
  await release(page, held!, null)
  await expect(mcpForm(page)).not.toBeVisible()
  expect(writes).toHaveLength(2)
})

test('MCP 旧保存完成不能关闭新打开的表单', async ({ page }) => {
  await setup(page)
  let held: Route | undefined
  await page.route('**/api/aiconfig/mcp/61', route => {
    if (route.request().method() === 'GET') return route.fulfill({ json: ok(mcps[0]) })
    held = route
  })
  await mcpRow(page).getByRole('button', { name: '编辑', exact: true }).click()
  await mcpForm(page).getByRole('button', { name: '保存 MCP', exact: true }).click()
  await expect.poll(() => Boolean(held)).toBe(true)
  await mcpForm(page).getByRole('button', { name: '取消', exact: true }).click()
  await mcpRow(page, 'B').getByRole('button', { name: '编辑', exact: true }).click()
  await mcpForm(page).getByLabel('名称', { exact: true }).fill('新草稿待保存')
  await release(page, held!, null)
  await expect(mcpForm(page)).toBeVisible()
  await expect(mcpForm(page).getByLabel('名称', { exact: true })).toHaveValue('新草稿待保存')
})

test('MCP 删除确认期间重新登录不能发出旧身份删除', async ({ page }) => {
  const writes = await setup(page)
  await menu(page, '删除服务')
  const confirmation = page.getByRole('dialog', { name: '提示', exact: true })
  await expect(confirmation).toBeVisible()
  await identity(page)
  if (await confirmation.isVisible()) await confirmation.getByRole('button', { name: '确定', exact: true }).click()
  await settle(page)
  expect(writes).toEqual([])
})

test('MCP 复制保留脱敏占位符约束，重新填写凭据才可创建', async ({ page }) => {
  const writes = await setup(page)
  await page.route('**/api/aiconfig/mcp/61', route => route.fulfill({ json: ok({ ...mcps[0],
    config: '{"url":"https://example.invalid/mcp","headers":{"Authorization":"__MCP_SECRET_REDACTED__"}}' }) }))
  await menu(page, '复制服务')
  await mcpForm(page, '复制').getByRole('button', { name: '保存 MCP', exact: true }).click()
  await expect(page.locator('.el-message--error')).toContainText('不能提交脱敏占位符')
  expect(writes).toEqual([])
  await mcpForm(page, '复制').getByLabel('连接配置', { exact: true }).fill(mcps[0].config)
  await mcpForm(page, '复制').getByRole('button', { name: '保存 MCP', exact: true }).click()
  await expect(mcpForm(page, '复制')).not.toBeVisible()
  expect(writes).toHaveLength(1)
  expect(writes[0]).toMatchObject({ method: 'POST', path: '/api/aiconfig/mcp', data: { mcpName: '验收 MCP A-副本' } })
})

test('MCP 调试连接的旧工具列表不得进入新服务', async ({ page }) => {
  await setup(page)
  let held: Route | undefined
  await page.route('**/api/aiconfig/mcp/61/debug/tools', route => { held = route })
  await menu(page, '调试工具')
  await expect.poll(() => Boolean(held)).toBe(true)
  await debug(page).getByRole('button', { name: '关闭', exact: true }).click()
  await expect(debug(page)).not.toBeVisible()
  await menu(page, '调试工具', 'B')
  await expect(debug(page, 'B')).toContainText('tool-62')
  await release(page, held!, [tool('old-tool-61')])
  await expect(debug(page, 'B')).toContainText('tool-62')
  await expect(debug(page, 'B')).not.toContainText('old-tool-61')
})

test('MCP 调试调用锁定原输入，失败保留参数供重试', async ({ page }) => {
  await setup(page)
  await menu(page, '调试工具')
  const input = debug(page).locator('.el-form-item').filter({ hasText: 'message' }).getByRole('textbox')
  await input.fill('验收参数')
  let held: Route | undefined
  await page.route('**/api/aiconfig/mcp/61/debug/call', route => { held = route })
  await debug(page).getByRole('button', { name: '调用', exact: true }).click()
  await expect.poll(() => Boolean(held)).toBe(true)
  await expect(input).toBeDisabled()
  await release(page, held!, { success: false, output: null, errorMessage: '验收工具失败', images: [], outputLooksBinary: false })
  await expect(input).toHaveValue('验收参数')
  await expect(input).toBeEnabled()
  await expect(debug(page)).toContainText('验收工具失败')
})

test('MCP 调试旧调用结果不得覆盖新服务参数', async ({ page }) => {
  await setup(page)
  await menu(page, '调试工具')
  await debug(page).locator('.el-form-item').filter({ hasText: 'message' }).getByRole('textbox').fill('旧参数')
  let held: Route | undefined
  await page.route('**/api/aiconfig/mcp/61/debug/call', route => { held = route })
  await debug(page).getByRole('button', { name: '调用', exact: true }).click()
  await expect.poll(() => Boolean(held)).toBe(true)
  await debug(page).getByRole('button', { name: '关闭', exact: true }).click()
  await expect(debug(page)).not.toBeVisible()
  await menu(page, '调试工具', 'B')
  const input = debug(page, 'B').locator('.el-form-item').filter({ hasText: 'message' }).getByRole('textbox')
  await input.fill('新参数尚未调用')
  await release(page, held!, { success: true, output: '旧服务的验收结果', images: [], outputLooksBinary: false })
  await expect(debug(page, 'B')).not.toContainText('旧服务的验收结果')
  await expect(input).toHaveValue('新参数尚未调用')
})

test('MCP 契约历史旧响应不能覆盖当前服务快照', async ({ page }) => {
  await setup(page)
  let held: Route | undefined
  await page.route('**/api/aiconfig/mcp/61/contract/history*', route => { held = route })
  await mcpRow(page).getByRole('button', { name: '契约', exact: true }).click()
  await expect.poll(() => Boolean(held)).toBe(true)
  await contract(page).locator('.el-dialog__headerbtn').click()
  await expect(contract(page)).not.toBeVisible()
  await mcpRow(page, 'B').getByRole('button', { name: '契约', exact: true }).click()
  await expect(contract(page, 'B')).toContainText('62')
  await release(page, held!, [snapshot(961)])
  await expect(contract(page, 'B')).not.toContainText('961')
  await expect(contract(page, 'B')).toContainText('62')
})

test('MCP 契约采集完成不能重新打开或提示旧身份内容', async ({ page }) => {
  await setup(page)
  await mcpRow(page).getByRole('button', { name: '契约', exact: true }).click()
  let held: Route | undefined
  await page.route('**/api/aiconfig/mcp/61/contract/capture', route => { held = route })
  await contract(page).getByRole('button', { name: '采集一次', exact: true }).click()
  await expect.poll(() => Boolean(held)).toBe(true)
  await identity(page)
  await release(page, held!, snapshot(61))
  await expect(contract(page)).not.toBeVisible()
  await expect(page.locator('.el-message--success')).toHaveCount(0)
})

const knowledge = { id: 71, kbName: '验收知识库', baseUrl: 'https://example.invalid/kb',
  appId: 'fixture-app', apiKeyMasked: '****', contentType: 'application/json', extraHeaders: null,
  topN: 5, scoreThreshold: 0.15, status: 1, testStatus: 1, remark: '', currentVersionId: 7100 }
async function knowledgeSetup(page: Page) {
  await page.route('**/api/auth/permissions', route => route.fulfill({ json: ok(permissions) }))
  await page.route('**/api/aiconfig/knowledge-base/page?*', route => route.fulfill({ json: ok({ list: [knowledge], total: 1 }) }))
  await page.goto('/aiconfig/knowledge-base')
}
async function knowledgeMenu(page: Page, action: string) {
  await page.getByRole('button', { name: '验收知识库的更多操作', exact: true }).click()
  await page.getByRole('menuitem', { name: action, exact: true }).click()
}

test('知识库启停请求锁定同一行，失败保留原状态并可重试', async ({ page }) => {
  await knowledgeSetup(page)
  let held: Route | undefined
  let calls = 0
  await page.route('**/api/aiconfig/knowledge-base/71/status?*', route => { calls += 1; held = route })
  await knowledgeMenu(page, '停用')
  await expect.poll(() => Boolean(held)).toBe(true)
  await page.getByRole('button', { name: '验收知识库的更多操作', exact: true }).click()
  await expect(page.getByRole('menuitem', { name: '停用', exact: true })).toHaveAttribute('aria-disabled', 'true')
  await page.getByRole('heading', { name: '验收知识库', exact: true }).click()
  await expect(page.getByRole('menuitem', { name: '停用', exact: true })).not.toBeVisible()
  await held!.fulfill({ json: { code: 50000, message: '验收状态写入失败' } })
  await settle(page)
  held = undefined
  await knowledgeMenu(page, '停用')
  await expect.poll(() => Boolean(held)).toBe(true)
  await page.route('**/api/aiconfig/knowledge-base/page?*', route => route.fulfill({ json: ok({ list: [{ ...knowledge, status: 0 }], total: 1 }) }))
  await release(page, held!, null)
  await expect(page.locator('.knowledge-card')).toContainText('已停用')
  expect(calls).toBe(2)
})

test('知识库旧身份探测结果不提示成功或重载新身份列表', async ({ page }) => {
  await knowledgeSetup(page)
  let held: Route | undefined
  await page.route('**/api/aiconfig/knowledge-base/71/test-connectivity', route => { held = route })
  await knowledgeMenu(page, '测试连通性')
  await expect.poll(() => Boolean(held)).toBe(true)
  await identity(page); await settle(page)
  let reads = 0
  await page.route('**/api/aiconfig/knowledge-base/page?*', route => { reads += 1; return route.fulfill({ json: ok({ list: [], total: 0 }) }) })
  await release(page, held!, { testStatus: 1, hitCount: 3 })
  await expect(page.locator('.el-message--success')).toHaveCount(0)
  expect(reads).toBe(0)
})


test('MCP 只读用户可查看工具定义，不能编辑参数或调用工具', async ({ page }) => {
  const writes = await setup(page, ['mcp:view'])
  await expect(mcpRow(page).getByRole('button', { name: '编辑', exact: true })).toHaveCount(0)
  await menu(page, '调试工具')
  await expect(debug(page)).toContainText('tool-61')
  await expect(debug(page).getByRole('button', { name: '调用', exact: true })).toBeDisabled()
  await expect(debug(page).locator('.el-form-item').getByRole('textbox')).toBeDisabled()
  expect(writes.filter(write => write.path.endsWith('/debug/call'))).toEqual([])
})

test('MCP 连接失败可重连，保留参数完成一次正常调用', async ({ page }) => {
  const writes = await setup(page)
  let connections = 0
  await page.route('**/api/aiconfig/mcp/61/debug/tools', route => {
    connections += 1
    return route.fulfill({ json: connections === 1
      ? { code: 50000, message: '验收连接失败' } : ok([tool('retry-tool')]) })
  })
  await menu(page, '调试工具')
  await expect(debug(page)).toContainText('连接失败')
  await debug(page).getByRole('button', { name: '重新连接 MCP', exact: true }).click()
  await expect(debug(page)).toContainText('retry-tool')
  await debug(page).locator('.el-form-item').getByRole('textbox').fill('恢复后的参数')
  await debug(page).getByRole('button', { name: '调用', exact: true }).click()
  await expect(debug(page)).toContainText('合成调用结果')
  expect(writes.filter(write => write.path.endsWith('/debug/call'))).toEqual([
    { method: 'POST', path: '/api/aiconfig/mcp/61/debug/call',
      data: { toolName: 'retry-tool', arguments: { message: '恢复后的参数' } } },
  ])
})

test('MCP 契约历史失败可刷新，采集成功后展示持久化快照', async ({ page }) => {
  const writes = await setup(page)
  let queries = 0
  await page.route('**/api/aiconfig/mcp/61/contract/history', route => {
    queries += 1
    return route.fulfill({ json: queries === 1 ? { code: 50000, message: '验收历史失败' }
      : ok([snapshot(queries === 2 ? 61 : 63)]) })
  })
  await mcpRow(page).getByRole('button', { name: '契约', exact: true }).click()
  await expect(contract(page)).toContainText('契约历史加载失败')
  await contract(page).getByRole('button', { name: '刷新', exact: true }).click()
  await expect(contract(page)).toContainText('61 个工具')
  await contract(page).getByRole('button', { name: '采集一次', exact: true }).click()
  await expect(contract(page)).toContainText('63 个工具')
  expect(writes.filter(write => write.path.endsWith('/contract/capture'))).toHaveLength(1)
})

test('MCP 删除取消不写入，确认成功后重新查询服务列表', async ({ page }) => {
  const writes = await setup(page)
  await menu(page, '删除服务')
  const confirmation = page.getByRole('dialog', { name: '提示', exact: true })
  await confirmation.getByRole('button', { name: '取消', exact: true }).click()
  await settle(page)
  expect(writes).toEqual([])
  let reloads = 0
  await page.route(/\/api\/aiconfig\/mcp\?.*/, route => {
    reloads += 1
    return route.fulfill({ json: ok({ list: [mcps[1]], total: 1 }) })
  })
  await menu(page, '删除服务')
  await confirmation.getByRole('button', { name: '确定', exact: true }).click()
  await expect(mcpRow(page)).toHaveCount(0)
  expect(reloads).toBe(1)
  expect(writes).toEqual([{ method: 'DELETE', path: '/api/aiconfig/mcp/61', data: null }])
})


test('MCP 调试在 Ocean 窄屏提供可用的参数宽度并保留关闭按钮', async ({ page }, testInfo) => {
  await page.addInitScript(() => localStorage.setItem('customer-admin-theme-selection',
    JSON.stringify({ version: 1, kind: 'preset', id: 'ocean' })))
  await setup(page)
  await menu(page, '调试工具')
  await expect(debug(page)).toContainText('tool-61')
  await debug(page).locator('.el-form-item').getByRole('textbox').fill('查询订单进度')
  await page.setViewportSize({ width: 390, height: 760 })
  await settle(page)
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'ocean')
  await page.screenshot({ path: testInfo.outputPath('mcp-debug-ocean-390.png'), animations: 'disabled' })
  const geometry = await debug(page).evaluate(element => {
    const body = element.querySelector('.debug-body')!.getBoundingClientRect()
    const detail = element.querySelector('.detail-pane')!.getBoundingClientRect()
    const panel = element.querySelector('.el-dialog')!
    const footer = element.querySelector('.el-dialog__footer')!.getBoundingClientRect()
    return { body: body.width, detail: detail.width, width: panel.clientWidth, contentWidth: panel.scrollWidth,
      footerBottom: footer.bottom, viewport: innerHeight }
  })
  expect(geometry.detail).toBeGreaterThanOrEqual(Math.min(280, geometry.body))
  expect(geometry.contentWidth).toBeLessThanOrEqual(geometry.width + 1)
  expect(geometry.footerBottom).toBeLessThanOrEqual(geometry.viewport)
})
