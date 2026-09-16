import type { Page, Route } from '@playwright/test'
import { expect, test } from './fixtures/adminTestFixture'

const ok = (data: unknown) => ({ code: 0, data })
const permissions = ['workbench-site:view', 'workbench-site:add', 'workbench-site:edit', 'workbench-site:delete']
const site = { id: 31, name: '验收站点', category: '开发', url: 'https://example.invalid/login', account: 'acceptance',
  hasPassword: true, passwordMasked: '******', enabled: true, remark: '', usernameSelector: '#user', passwordSelector: '#pass',
  submitSelector: '#login', fillMode: 'auto', submitMode: 'click', initDelayMs: 500, submitDelayMs: 300 }
const token = { id: 41, name: '验收令牌', tokenPrefix: 'fixture-', revoked: false, expireTime: null, lastUsedTime: null }

async function setup(page: Page) {
  await page.route('**/api/auth/permissions', r => r.fulfill({ json: ok(permissions) }))
  await page.route('**/api/workbench/site?*', r => r.fulfill({ json: ok({ list: [site], total: 1, pageNum: 1, pageSize: 10 }) }))
  await page.route('**/api/workbench/token', r => r.fulfill({ json: ok([token]) }))
  await page.goto('/workbench/site')
}
async function identity(page: Page) {
  await page.evaluate(async permissions => {
    const path = '/src/store/auth.ts'
    const { useAuthStore } = await import(path)
    const auth = useAuthStore()
    auth.applyLoginResult({ token: auth.token, nickname: '新身份', forceChangePassword: false,
      approvalStatus: 'APPROVED', approvalRemark: null }, 'later-user')
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
  const response = page.waitForResponse(r => r.url() === route.request().url())
  await route.fulfill({ json: ok(data) })
  await (await response).finished()
  await settle(page)
}
async function captureClipboard(page: Page) {
  await page.evaluate(() => {
    const host = window as typeof window & { acceptanceCopies: string[] }
    host.acceptanceCopies = []
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: {
      writeText: async (text: string) => { host.acceptanceCopies.push(text) },
    } })
  })
}
async function copied(page: Page) {
  return page.evaluate(() => (window as typeof window & { acceptanceCopies: string[] }).acceptanceCopies)
}
const scriptDialog = (page: Page) => page.getByRole('dialog', { name: '生成登录脚本', exact: true })
const tokensDialog = (page: Page) => page.getByRole('dialog', { name: '我的令牌', exact: true })
const tokenForm = (page: Page) => page.getByRole('dialog', { name: '新建令牌', exact: true })

test('工作台密码读取失败释放锁，可重试并只复制成功结果', async ({ page }) => {
  await setup(page); await captureClipboard(page)
  let held: Route | undefined
  let count = 0
  await page.route('**/api/workbench/site/31/secret', r => { count += 1; held = r })
  const button = page.getByRole('row').filter({ hasText: '验收站点' }).getByRole('button', { name: '复制密码', exact: true })
  await button.click(); await expect.poll(() => Boolean(held)).toBe(true)
  await expect(button).toBeDisabled()
  await held!.fulfill({ json: { code: 50000, message: '密码读取暂时失败' } })
  await expect(button).toBeEnabled(); expect(await copied(page)).toEqual([])
  held = undefined
  await button.click(); await expect.poll(() => Boolean(held)).toBe(true)
  await release(page, held!, 'synthetic-acceptance-password')
  expect(await copied(page)).toEqual(['synthetic-acceptance-password']); expect(count).toBe(2)
})
test('工作台重新登录后不能把旧身份密码复制到剪贴板', async ({ page }) => {
  await setup(page); await captureClipboard(page)
  let held: Route | undefined
  await page.route('**/api/workbench/site/31/secret', r => { held = r })
  await page.getByRole('button', { name: '复制密码', exact: true }).click()
  await expect.poll(() => Boolean(held)).toBe(true)
  await identity(page); await release(page, held!, 'old-identity-fixture-password')
  expect(await copied(page)).toEqual([])
})
test('工作台脚本生成失败保留用途并可重试下载完整文件', async ({ page }, testInfo) => {
  await setup(page)
  let held: Route | undefined
  const payloads: unknown[] = []
  await page.route('**/api/workbench/script/generate', r => { payloads.push(r.request().postDataJSON()); held = r })
  await page.getByRole('button', { name: '生成登录脚本', exact: true }).click()
  await scriptDialog(page).getByRole('textbox').fill('我的验收脚本')
  await scriptDialog(page).getByRole('button', { name: '生成并下载', exact: true }).click()
  await expect.poll(() => Boolean(held)).toBe(true)
  await expect(scriptDialog(page).getByRole('textbox')).toBeDisabled()
  await held!.fulfill({ json: { code: 50000, message: '脚本生成暂时失败' } })
  await expect(scriptDialog(page).getByRole('textbox')).toHaveValue('我的验收脚本')
  await expect(scriptDialog(page).getByRole('button', { name: '生成并下载', exact: true })).toBeEnabled()
  held = undefined
  await scriptDialog(page).getByRole('button', { name: '生成并下载', exact: true }).click()
  await expect.poll(() => Boolean(held)).toBe(true)
  const script = '// ==UserScript==\n// @name 验收脚本\n// ==/UserScript==\nvoid 0;'
  const downloading = page.waitForEvent('download')
  await release(page, held!, script)
  const download = await downloading
  expect(download.suggestedFilename()).toBe('workbench-login.user.js')
  const stream = await download.createReadStream()
  const chunks: Buffer[] = []
  for await (const chunk of stream) chunks.push(Buffer.from(chunk))
  expect(Buffer.concat(chunks).toString('utf8')).toBe(script)
  await download.saveAs(testInfo.outputPath('workbench-login.user.js'))
  await expect(scriptDialog(page)).not.toBeVisible()
  expect(payloads).toEqual([{ name: '我的验收脚本', expireDays: 90 }, { name: '我的验收脚本', expireDays: 90 }])
})
test('工作台旧身份脚本生成完成不触发下载', async ({ page }) => {
  await setup(page)
  let held: Route | undefined
  const downloads: string[] = []
  page.on('download', d => downloads.push(d.suggestedFilename()))
  await page.route('**/api/workbench/script/generate', r => { held = r })
  await page.getByRole('button', { name: '生成登录脚本', exact: true }).click()
  await scriptDialog(page).getByRole('button', { name: '生成并下载', exact: true }).click()
  await expect.poll(() => Boolean(held)).toBe(true)
  await identity(page); await release(page, held!, '// synthetic old identity token')
  expect(downloads).toEqual([])
})
test('工作台令牌列表失败可以原地重试', async ({ page }) => {
  await setup(page)
  let fail = true
  await page.route('**/api/workbench/token', r => r.fulfill({ json: fail
    ? { code: 50000, message: '令牌查询暂时失败' } : ok([token]) }))
  await page.getByRole('button', { name: '我的令牌', exact: true }).click()
  await expect(tokensDialog(page).getByRole('button', { name: '重新加载', exact: true })).toBeVisible()
  fail = false
  await tokensDialog(page).getByRole('button', { name: '重新加载', exact: true }).click()
  await expect(tokensDialog(page)).toContainText('验收令牌')
})
test('工作台令牌签发失败保留输入并释放锁，重试仅显示本次明文', async ({ page }) => {
  await setup(page)
  let held: Route | undefined
  let count = 0
  await page.route('**/api/workbench/token', r => {
    if (r.request().method() === 'POST') { held = r; count += 1; return }
    return r.fulfill({ json: ok([token]) })
  })
  await page.getByRole('button', { name: '我的令牌', exact: true }).click()
  await tokensDialog(page).getByRole('button', { name: '新建令牌', exact: true }).click()
  await tokenForm(page).getByRole('textbox').fill('新建用途')
  await tokenForm(page).getByRole('button', { name: '生成令牌', exact: true }).click()
  await expect.poll(() => Boolean(held)).toBe(true)
  await expect(tokenForm(page).getByRole('textbox')).toBeDisabled()
  await held!.fulfill({ json: { code: 50000, message: '令牌签发暂时失败' } })
  await expect(tokenForm(page).getByRole('textbox')).toHaveValue('新建用途')
  await expect(tokenForm(page).getByRole('button', { name: '生成令牌', exact: true })).toBeEnabled()
  held = undefined
  await tokenForm(page).getByRole('button', { name: '生成令牌', exact: true }).click()
  await expect.poll(() => Boolean(held)).toBe(true)
  await release(page, held!, { ...token, token: 'new-synthetic-token' })
  await expect(tokenForm(page).getByRole('textbox')).toHaveValue('new-synthetic-token')
  await tokenForm(page).getByRole('button', { name: '我已保存', exact: true }).click()
  await expect(tokenForm(page)).not.toBeVisible(); expect(count).toBe(2)
})
test('工作台旧身份的签发结果不能显示一次性明文', async ({ page }) => {
  await setup(page)
  let held: Route | undefined
  await page.route('**/api/workbench/token', r => {
    if (r.request().method() === 'POST') { held = r; return }
    return r.fulfill({ json: ok([token]) })
  })
  await page.getByRole('button', { name: '我的令牌', exact: true }).click()
  await tokensDialog(page).getByRole('button', { name: '新建令牌', exact: true }).click()
  await tokenForm(page).getByRole('textbox').fill('旧身份用途')
  await tokenForm(page).getByRole('button', { name: '生成令牌', exact: true }).click()
  await expect.poll(() => Boolean(held)).toBe(true)
  await identity(page); await release(page, held!, { ...token, token: 'old-synthetic-token' })
  const values = await page.locator('input').evaluateAll(inputs => inputs.map(input => (input as HTMLInputElement).value))
  expect(values).not.toContain('old-synthetic-token')
})
test('工作台吊销确认期间重新登录不执行旧身份请求', async ({ page }) => {
  await setup(page)
  let writes = 0
  await page.route('**/api/workbench/token/41', r => { writes += 1; return r.fulfill({ json: ok(null) }) })
  await page.getByRole('button', { name: '我的令牌', exact: true }).click()
  await tokensDialog(page).getByRole('row').filter({ hasText: '验收令牌' }).getByRole('button', { name: '吊销', exact: true }).click()
  const prompt = page.getByRole('dialog', { name: '提示', exact: true })
  await identity(page)
  await prompt.getByRole('button', { name: '确定', exact: true }).click(); await settle(page)
  expect(writes).toBe(0)
})


test('工作台吊销失败保留有效状态，等待时锁定且可重试', async ({ page }) => {
  await setup(page)
  let held: Route | undefined
  let revoked = false
  let writes = 0
  await page.route('**/api/workbench/token', r => r.fulfill({ json: ok([{ ...token, revoked }]) }))
  await page.route('**/api/workbench/token/41', r => { held = r; writes += 1 })
  await page.getByRole('button', { name: '我的令牌', exact: true }).click()
  const row = tokensDialog(page).getByRole('row').filter({ hasText: '验收令牌' })
  const button = row.getByRole('button', { name: '吊销', exact: true })
  const confirm = async () => {
    await button.click()
    await page.getByRole('dialog', { name: '提示', exact: true }).getByRole('button', { name: '确定', exact: true }).click()
    await expect.poll(() => Boolean(held)).toBe(true)
  }
  await confirm()
  await expect(button).toBeDisabled()
  await held!.fulfill({ json: { code: 50000, message: '吊销暂时失败' } })
  await expect(button).toBeEnabled()
  await expect(row.getByText('有效', { exact: true })).toBeVisible()
  held = undefined
  await confirm()
  revoked = true
  await release(page, held!, null)
  await expect(row.getByText('已吊销', { exact: true })).toBeVisible()
  await expect(row.getByRole('button', { name: '吊销', exact: true })).toHaveCount(0)
  expect(writes).toBe(2)
})

const importDialog = (page: Page) => page.getByRole('dialog', { name: '从 ScriptCat 脚本导入', exact: true })
const importedScript = '// ==UserScript==\n// @name 本地验收站点\n// @match https://example.invalid/*\n// ==/UserScript==\nconst username = "acceptance-user";\nconst password = "synthetic-password";'
async function openImport(page: Page) {
  await page.getByRole('button', { name: '新增站点', exact: true }).click()
  await page.getByRole('dialog', { name: '新增站点', exact: true }).getByRole('button', { name: '点此导入自动预填', exact: true }).click()
  await expect(importDialog(page)).toBeVisible()
}

test('工作台本地脚本文件读取后可预填新增站点并保留待核对字段', async ({ page }) => {
  await setup(page)
  await openImport(page)
  await importDialog(page).locator('input[type=file]').setInputFiles({ name: 'acceptance.user.js', mimeType: 'text/javascript', buffer: Buffer.from(importedScript) })
  await expect(importDialog(page).getByRole('textbox')).toHaveValue(importedScript)
  await importDialog(page).getByRole('button', { name: '解析并预填', exact: true }).click()
  const form = page.getByRole('dialog', { name: '新增站点', exact: true })
  for (const [label, value] of Object.entries({ 名称: '本地验收站点', 地址: 'https://example.invalid', 账号: 'acceptance-user', 密码: 'synthetic-password' })) {
    const field = form.locator('.el-form-item').filter({ has: page.locator('.el-form-item__label', { hasText: new RegExp(`^${label}$`) }) })
    await expect(field.locator('input').first()).toHaveValue(value)
  }
  await expect(form).toBeVisible()
})

test('工作台旧身份的文件读取完成不能覆盖后来重新打开的导入内容', async ({ page }) => {
  await setup(page)
  await page.evaluate(() => {
    const original = File.prototype.text
    const host = window as typeof window & { fileReadStarted?: boolean; releaseFileRead?: () => void }
    File.prototype.text = async function () {
      const text = await original.call(this)
      if (this.name === 'delayed.user.js') {
        host.fileReadStarted = true
        await new Promise<void>(resolve => { host.releaseFileRead = resolve })
      }
      return text
    }
  })
  await openImport(page)
  await importDialog(page).locator('input[type=file]').setInputFiles({ name: 'delayed.user.js', mimeType: 'text/javascript', buffer: Buffer.from(importedScript) })
  await expect.poll(() => page.evaluate(() => (window as typeof window & { fileReadStarted?: boolean }).fileReadStarted)).toBe(true)
  await identity(page)
  await settle(page)
  if (await importDialog(page).isVisible()) await importDialog(page).getByRole('button', { name: '取消', exact: true }).click()
  await openImport(page)
  await importDialog(page).getByRole('textbox').fill('新身份尚未解析的草稿')
  await page.evaluate(() => (window as typeof window & { releaseFileRead?: () => void }).releaseFileRead?.())
  await settle(page)
  await expect(importDialog(page).getByRole('textbox')).toHaveValue('新身份尚未解析的草稿')
})

for (const scenario of [
  { kind: 'script', theme: 'ember', title: '生成登录脚本' },
  { kind: 'token', theme: 'night', title: '一次性令牌' },
  { kind: 'import', theme: 'ocean', title: '本地脚本导入' },
] as const) {
  test(`工作台${scenario.title}在 ${scenario.theme} 390px 下保留输入、提示与操作入口`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width: 390, height: 844 })
    await page.addInitScript(theme => localStorage.setItem('customer-admin-theme-selection',
      JSON.stringify({ version: 1, kind: 'preset', id: theme })), scenario.theme)
    await setup(page)
    if (scenario.kind === 'script') {
      await page.getByRole('button', { name: '生成登录脚本', exact: true }).click()
      await scriptDialog(page).getByRole('textbox').fill('客服知识库工作台登录脚本')
      await expect(scriptDialog(page).getByRole('button', { name: '生成并下载', exact: true })).toBeEnabled()
    } else if (scenario.kind === 'token') {
      await page.route('**/api/workbench/token', r => r.fulfill({ json: ok(r.request().method() === 'POST'
        ? { ...token, token: 'wbt_synthetic_once_visible_acceptance' } : [token]) }))
      await page.getByRole('button', { name: '我的令牌', exact: true }).click()
      await tokensDialog(page).getByRole('button', { name: '新建令牌', exact: true }).click()
      await tokenForm(page).getByRole('textbox').fill('客服验收令牌')
      await tokenForm(page).getByRole('button', { name: '生成令牌', exact: true }).click()
      await expect(tokenForm(page).getByRole('textbox')).toHaveValue('wbt_synthetic_once_visible_acceptance')
      await expect(tokenForm(page).getByText('此令牌仅显示一次，请立即复制保存；关闭后无法再次查看。')).toBeVisible()
      await expect(tokenForm(page).getByRole('button', { name: '我已保存', exact: true })).toBeEnabled()
    } else {
      await openImport(page)
      await importDialog(page).getByRole('textbox').fill(importedScript)
      await expect(importDialog(page).getByRole('button', { name: '解析并预填', exact: true })).toBeEnabled()
    }
    const active = scenario.kind === 'script' ? scriptDialog(page) : scenario.kind === 'token' ? tokenForm(page) : importDialog(page)
    await expect(page.locator('html')).toHaveAttribute('data-theme', scenario.theme)
    await expect(page.locator('.el-message')).toHaveCount(0)
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true)
    expect(await active.evaluate(element => element.scrollWidth <= element.clientWidth + 1)).toBe(true)
    await page.screenshot({ path: testInfo.outputPath(`workbench-${scenario.kind}-${scenario.theme}-390.png`), animations: 'disabled' })
  })
}
