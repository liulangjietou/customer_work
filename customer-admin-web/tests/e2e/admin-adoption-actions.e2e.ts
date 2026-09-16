import type { Page, Route } from '@playwright/test'
import { expect, test } from './fixtures/adminTestFixture'

const ok = (data: unknown) => ({ code: 0, data })
const permissions = ['badcase:view', 'badcase:adopt', 'sensitive-word:view', 'sensitive-word:add']
const row = (id: string) => ({ id, source: 'NEGATIVE_FEEDBACK', sessionId: `session-${id}`, messageId: null,
  userInput: `用户问题-${id}`, agentReply: `原回答-${id}`, signalHash: null, detail: '用户反馈', status: 'PENDING',
  adoptedKnowledgeId: null, adoptedEvalCaseId: null, handledBy: null, handledAtMs: 0, ignoreReason: null,
  createdAtMs: 1789408800000, pending: true })
async function identity(page: Page) {
  await page.evaluate(async permissions => {
    const modulePath = '/src/store/auth.ts'
    const { useAuthStore } = await import(modulePath)
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
async function release(page: Page, held: Route, data: unknown) {
  const response = page.waitForResponse(r => r.url() === held.request().url())
  await held.fulfill({ json: ok(data) }); await (await response).finished(); await settle(page)
}
async function setupBadcase(page: Page) {
  await page.route('**/api/auth/permissions', r => r.fulfill({ json: ok(permissions) }))
  await page.route('**/api/badcase/page?*', r => r.fulfill({ json: ok({ list: [row('case-a'), row('case-b')], total: 2, pageNum: 1, pageSize: 20 }) }))
  await page.goto('/ops/badcase')
}
const drawer = (page: Page) => page.getByRole('dialog', { name: 'badcase 筛选与回流', exact: true })
async function openCase(page: Page, id: string) {
  await page.getByRole('row').filter({ hasText: `用户问题-${id}` }).getByRole('button', { name: '筛选', exact: true }).click()
  await expect(drawer(page)).toContainText(`用户问题-${id}`)
}
const adoptions = [
  { name: '知识', endpoint: 'adopt-knowledge', submit: '补进知识库', marker: '已补进知识库（条目 #71）',
    fields: { 条目标题: '保留标题', 条目内容: '正确答案', 关键词: '验收关键词' }, result: { adoptedKnowledgeId: 71 } },
  { name: '评测', endpoint: 'adopt-eval-case', submit: '加入评测集', marker: '已加入评测集（用例 acceptance-case）',
    fields: { 用例编号: 'acceptance-case', 期望: 'consult' }, result: { adoptedEvalCaseId: 'acceptance-case' } },
]
async function fillAdoption(page: Page, fields: Record<string, string>) {
  for (const [label, value] of Object.entries(fields)) {
    await drawer(page).locator('.el-form-item').filter({ has: page.locator('.el-form-item__label', { hasText: new RegExp(`^${label}$`) }) })
      .getByRole('textbox').fill(value)
  }
}
for (const item of adoptions) {
  test(`Badcase ${item.name}采纳失败保留输入，提交时锁定，原目标可重试`, async ({ page }) => {
    await setupBadcase(page)
    let held: Route | undefined; let count = 0
    await page.route(`**/api/badcase/case-a/${item.endpoint}`, r => { count += 1; held = r })
    await openCase(page, 'case-a'); await fillAdoption(page, item.fields)
    const button = drawer(page).getByRole('button', { name: item.submit, exact: true })
    await button.click(); await expect.poll(() => Boolean(held)).toBe(true)
    await expect(button).toBeDisabled()
    await held!.fulfill({ json: { code: 50000, message: '采纳暂时失败' } })
    await expect(button).toBeEnabled()
    for (const [label, value] of Object.entries(item.fields)) {
      const field = drawer(page).locator('.el-form-item').filter({ has: page.locator('.el-form-item__label', { hasText: new RegExp(`^${label}$`) }) })
      await expect(field.getByRole('textbox')).toHaveValue(value)
    }
    await expect(drawer(page)).toBeVisible()
    held = undefined; await button.click(); await expect.poll(() => Boolean(held)).toBe(true)
    await release(page, held!, { ...row('case-a'), ...item.result, status: 'RESOLVED', handledBy: 'checker', handledAtMs: 1789408800000 })
    await expect(drawer(page)).toContainText(item.marker)
    expect(count).toBe(2)
  })
  test(`Badcase ${item.name}旧采纳返回不能覆盖后来打开的另一条记录`, async ({ page }) => {
    await setupBadcase(page)
    let held: Route | undefined
    await page.route(`**/api/badcase/case-a/${item.endpoint}`, r => { held = r })
    await openCase(page, 'case-a'); await fillAdoption(page, item.fields)
    await drawer(page).getByRole('button', { name: item.submit, exact: true }).click()
    await expect.poll(() => Boolean(held)).toBe(true)
    await drawer(page).getByRole('button', { name: '关闭此对话框', exact: true }).click(); await settle(page)
    await openCase(page, 'case-b')
    await release(page, held!, { ...row('case-a'), ...item.result, status: 'RESOLVED' })
    await expect(drawer(page)).toContainText('用户问题-case-b')
    await expect(drawer(page)).not.toContainText(item.marker)
  })
}
test('Badcase 忽略确认期间重登不能借新身份执行旧操作', async ({ page }) => {
  await setupBadcase(page); let writes = 0
  await page.route('**/api/badcase/case-a/ignore?*', r => { writes += 1; return r.fulfill({ json: ok(row('case-a')) }) })
  await page.getByRole('row').filter({ hasText: '用户问题-case-a' }).getByRole('button', { name: '忽略', exact: true }).click()
  const prompt = page.getByRole('dialog', { name: '忽略这条 badcase', exact: true })
  await prompt.getByRole('textbox').fill('原身份原因'); await identity(page)
  await prompt.getByRole('button', { name: '忽略', exact: true }).click(); await settle(page)
  expect(writes).toBe(0)
})
test('Badcase 忽略旧记录完成不能关闭后来打开的另一条详情', async ({ page }) => {
  await setupBadcase(page); let held: Route | undefined
  await page.route('**/api/badcase/case-a/ignore?*', r => { held = r })
  await page.getByRole('row').filter({ hasText: '用户问题-case-a' }).getByRole('button', { name: '忽略', exact: true }).click()
  const prompt = page.getByRole('dialog', { name: '忽略这条 badcase', exact: true })
  await prompt.getByRole('textbox').fill('验收原因'); await prompt.getByRole('button', { name: '忽略', exact: true }).click()
  await expect.poll(() => Boolean(held)).toBe(true); await openCase(page, 'case-b')
  await release(page, held!, { ...row('case-a'), status: 'IGNORED' })
  await expect(drawer(page)).toBeVisible(); await expect(drawer(page)).toContainText('用户问题-case-b')
})

async function setupWords(page: Page) {
  await page.route('**/api/auth/permissions', r => r.fulfill({ json: ok(permissions) }))
  await page.goto('/contentguard/sensitive-word')
}
for (const endpoint of ['categories', 'actions']) {
  test(`敏感词${endpoint}选项读取失败不能保存，可原地重试`, async ({ page }) => {
    await page.route('**/api/auth/permissions', r => r.fulfill({ json: ok(permissions) }))
    let fail = true
    await page.route('**/api/contentguard/sensitive-word/categories', r => r.fulfill({ json: endpoint === 'categories' && fail
      ? { code: 50000, message: '词表类目暂时失败' } : ok(['CUSTOM', 'COMPETITOR']) }))
    await page.route('**/api/contentguard/sensitive-word/actions', r => r.fulfill({ json: endpoint === 'actions' && fail
      ? { code: 50000, message: '词表动作暂时失败' } : ok(['BLOCK', 'MASK']) }))
    await page.goto('/contentguard/sensitive-word')
    await page.getByRole('button', { name: '新增敏感词', exact: true }).click()
    const dialog = page.getByRole('dialog', { name: '新增敏感词', exact: true })
    await expect(dialog.getByRole('button', { name: '保存敏感词', exact: true })).toBeDisabled()
    await expect(dialog.getByRole('button', { name: '重新加载', exact: true })).toBeVisible()
    fail = false
    await dialog.getByRole('button', { name: '重新加载', exact: true }).click()
    await expect(dialog.getByRole('button', { name: '保存敏感词', exact: true })).toBeEnabled()
    const category = dialog.locator('.el-form-item').filter({ has: page.locator('.el-form-item__label', { hasText: /^类目$/ }) })
    await category.locator('.el-select__wrapper').click()
    await expect(page.getByRole('option', { name: '竞品', exact: true })).toBeVisible()
  })
}
test('敏感词重登后重新读取选项，旧类目不能污染当前表单', async ({ page }) => {
  await page.route('**/api/auth/permissions', r => r.fulfill({ json: ok(permissions) }))
  let held: Route | undefined
  let reads = 0
  await page.route('**/api/contentguard/sensitive-word/categories', r => {
    if (++reads === 1) { held = r; return }
    return r.fulfill({ json: ok(['CUSTOM', 'COMPETITOR']) })
  })
  await page.route('**/api/contentguard/sensitive-word/actions', r => r.fulfill({ json: ok(['BLOCK', 'MASK']) }))
  await page.goto('/contentguard/sensitive-word')
  await expect.poll(() => Boolean(held)).toBe(true)
  await identity(page)
  await release(page, held!, ['CUSTOM', 'OLD_CATEGORY'])
  await page.getByRole('button', { name: '新增敏感词', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '新增敏感词', exact: true })
  await dialog.locator('.el-form-item').filter({ has: page.locator('.el-form-item__label', { hasText: /^类目$/ }) })
    .locator('.el-select__wrapper').click()
  await expect(page.getByRole('option', { name: 'OLD_CATEGORY', exact: true })).toHaveCount(0)
  await expect(page.getByRole('option', { name: '竞品', exact: true })).toBeVisible()
})
const importDialog = (page: Page) => page.getByRole('dialog', { name: '批量导入敏感词', exact: true })
async function openImport(page: Page, text: string) {
  await page.getByRole('button', { name: '批量导入', exact: true }).click()
  await importDialog(page).getByRole('textbox').fill(text)
}
test('敏感词导入失败保留原输入，写入时锁定并允许重试', async ({ page }) => {
  await setupWords(page); let held: Route | undefined; const payloads: unknown[] = []
  await page.route('**/api/contentguard/sensitive-word/import', r => { payloads.push(r.request().postDataJSON()); held = r })
  await openImport(page, '测试词甲,CUSTOM,BLOCK\n测试词乙,COMPETITOR,MASK')
  const button = importDialog(page).getByRole('button', { name: '确认导入', exact: true })
  await button.click(); await expect.poll(() => Boolean(held)).toBe(true)
  await expect(importDialog(page).getByRole('textbox')).toBeDisabled()
  await held!.fulfill({ json: { code: 50000, message: '批量导入暂时失败' } })
  await expect(button).toBeEnabled()
  await expect(importDialog(page).getByRole('textbox')).toHaveValue('测试词甲,CUSTOM,BLOCK\n测试词乙,COMPETITOR,MASK')
  held = undefined; await button.click(); await expect.poll(() => Boolean(held)).toBe(true)
  await release(page, held!, 2); await expect(importDialog(page)).not.toBeVisible()
  expect(payloads).toEqual([['测试词甲,CUSTOM,BLOCK', '测试词乙,COMPETITOR,MASK'], ['测试词甲,CUSTOM,BLOCK', '测试词乙,COMPETITOR,MASK']])
})
test('敏感词旧导入完成不能清空重新打开的导入内容', async ({ page }) => {
  await setupWords(page); let held: Route | undefined
  await page.route('**/api/contentguard/sensitive-word/import', r => { held = r })
  await openImport(page, '旧批次'); await importDialog(page).getByRole('button', { name: '确认导入', exact: true }).click()
  await expect.poll(() => Boolean(held)).toBe(true)
  await importDialog(page).getByRole('button', { name: '取消', exact: true }).click(); await settle(page)
  await openImport(page, '新批次必须保留'); await release(page, held!, 1)
  await expect(importDialog(page)).toBeVisible(); await expect(importDialog(page).getByRole('textbox')).toHaveValue('新批次必须保留')
})
test('敏感词重新登录后丢弃旧导出，不生成下载文件', async ({ page }) => {
  await setupWords(page); let held: Route | undefined; const downloads: string[] = []
  page.on('download', d => downloads.push(d.suggestedFilename()))
  await page.route('**/api/contentguard/sensitive-word/export', r => { held = r })
  await page.getByRole('button', { name: '导出', exact: true }).click(); await expect.poll(() => Boolean(held)).toBe(true)
  await identity(page); await release(page, held!, ['旧租户词条,CUSTOM,BLOCK'])
  expect(downloads).toEqual([])
})
test('敏感词导出失败可重试，成功文件包含完整导入格式', async ({ page }, testInfo) => {
  await setupWords(page); let attempts = 0
  const lines = ['测试词甲,CUSTOM,BLOCK', '测试词乙,COMPETITOR,MASK']
  await page.route('**/api/contentguard/sensitive-word/export', r => r.fulfill({ json: ++attempts === 1
    ? { code: 50000, message: '导出暂时失败' } : ok(lines) }))
  await page.getByRole('button', { name: '导出', exact: true }).click()
  await expect(page.getByText('导出暂时失败', { exact: true })).toBeVisible()
  const downloading = page.waitForEvent('download')
  await page.getByRole('button', { name: '导出', exact: true }).click()
  const download = await downloading; const stream = await download.createReadStream()
  const chunks: Buffer[] = []; for await (const chunk of stream) chunks.push(Buffer.from(chunk))
  expect(Buffer.concat(chunks).toString('utf8')).toBe(lines.join('\n'))
  await download.saveAs(testInfo.outputPath('sensitive-words.csv'))
})


test('Badcase 采纳表单在 Ember 390px 下保持输入与操作可用', async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.addInitScript(() => localStorage.setItem('customer-admin-theme-selection', JSON.stringify({ version: 1, kind: 'preset', id: 'ember' })))
  await setupBadcase(page)
  await openCase(page, 'case-a')
  await fillAdoption(page, adoptions[0].fields)
  const button = drawer(page).getByRole('button', { name: '补进知识库', exact: true })
  await button.scrollIntoViewIfNeeded()
  await expect(button).toBeEnabled()
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'ember')
  await expect(page.locator('.el-message')).toHaveCount(0)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true)
  expect(await drawer(page).evaluate(el => el.scrollWidth <= el.clientWidth + 1)).toBe(true)
  await page.screenshot({ path: testInfo.outputPath('badcase-ember-390.png'), animations: 'disabled' })
})

test('敏感词批量导入在 Night 390px 下保留完整输入与确认入口', async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.addInitScript(() => localStorage.setItem('customer-admin-theme-selection', JSON.stringify({ version: 1, kind: 'preset', id: 'night' })))
  await setupWords(page)
  const input = '验收词条甲,CUSTOM,BLOCK\n验收词条乙,COMPETITOR,MASK\n需要人工复核的词条,CUSTOM,REVIEW'
  await openImport(page, input)
  await expect(importDialog(page).getByRole('textbox')).toHaveValue(input)
  await expect(importDialog(page).getByRole('button', { name: '确认导入', exact: true })).toBeEnabled()
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'night')
  await expect(page.locator('.el-message')).toHaveCount(0)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true)
  expect(await importDialog(page).evaluate(el => el.scrollWidth <= el.clientWidth + 1)).toBe(true)
  await page.screenshot({ path: testInfo.outputPath('word-import-night-390.png'), animations: 'disabled' })
})
