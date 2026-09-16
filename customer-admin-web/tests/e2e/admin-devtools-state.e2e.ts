import type { Page, Route } from '@playwright/test'
import { expect, test } from './fixtures/adminTestFixture'
const ok = (data: unknown) => ({ code: 0, data })
const marker = '当前工具验收结果'
const cases = [
  { key: 'diff', endpoint: '/devtools/diff/text', placeholder: '粘贴改动前的内容…', input: 'before', button: '比对',
    result: { identical: false, addedLines: 1, deletedLines: 0, totalLines: 1, truncated: false,
      lines: [{ type: 'INSERT', oldLineNo: -1, newLineNo: 1, content: marker }] } },
  { key: 'convert', endpoint: '/devtools/format/convert', placeholder: '粘贴待转换的内容…', input: '{"name":"acceptance"}', button: '转换',
    result: { sourceFormat: 'json', targetFormat: 'yaml', result: marker } },
  { key: 'cron', endpoint: '/devtools/cron/explain', placeholder: '6 段：秒 分 时 日 月 周，如 0 0 2 * * ?', input: '0 0 2 * * ?', button: '解析',
    result: { expression: '0 0 2 * * ?', timezone: 'Asia/Shanghai', fields: [{ name: '秒', value: '0', range: '0-59', description: marker }], nextTimes: ['2026-09-16 02:00:00'] } },
  { key: 'jwt', endpoint: '/devtools/jwt/decode', placeholder: '粘贴 JWT（header.payload.signature）…', input: 'acceptance.header.signature', button: '解析',
    result: { algorithm: 'HS256', type: 'JWT', header: '{}', payload: '{}', issuer: marker, subject: 'acceptance', audience: null,
      jwtId: null, issuedAt: null, notBefore: null, expiresAt: null, expired: false, notYetValid: false, secondsRemaining: null, unsigned: false, signatureStatus: 'NOT_CHECKED' } },
  { key: 'cert', endpoint: '/devtools/cert/parse', placeholder: '粘贴 -----BEGIN CERTIFICATE----- 证书或证书链，也支持 -----BEGIN CERTIFICATE REQUEST----- (CSR)；可一次粘贴多段', input: '-----BEGIN CERTIFICATE REQUEST-----\nacceptance\n-----END CERTIFICATE REQUEST-----', button: '解析',
    result: { certificates: [], csrs: [{ subject: marker, publicKeyAlgorithm: 'RSA', publicKeyBits: 2048, sigAlgName: 'SHA256withRSA', subjectAlternativeNames: [] }] } },
]
async function relogin(page: Page) {
  await page.evaluate(async () => {
    const modulePath = '/src/store/auth.ts'
    const { useAuthStore } = await import(modulePath)
    const auth = useAuthStore()
    auth.applyLoginResult({ token: auth.token, nickname: '新身份', forceChangePassword: false,
      approvalStatus: 'APPROVED', approvalRemark: null }, 'new-user')
    auth.permissions = ['devtools:view']
  })
}
async function setup(page: Page, item: typeof cases[number]) {
  await page.route('**/api/auth/permissions', r => r.fulfill({ json: ok(['devtools:view']) }))
  await page.goto(`/system/devtools?tool=${item.key}`)
  await page.getByPlaceholder(item.placeholder, { exact: true }).fill(item.input)
  if (item.key === 'diff') await page.getByPlaceholder('粘贴改动后的内容…', { exact: true }).fill('after')
}
async function expectResult(page: Page, item: typeof cases[number], present = true) {
  if (item.key === 'convert') {
    await expect(page.locator('.convert-tool textarea[readonly]')).toHaveValue(present ? marker : '')
  } else {
    const value = page.locator('.layout-main').getByText(marker, { exact: true })
    if (present) await expect(value).toBeVisible()
    else await expect(value).toHaveCount(0)
  }
}
async function deliver(page: Page, route: Route, result: unknown) {
  const response = page.waitForResponse(r => r.url() === route.request().url())
  await route.fulfill({ json: ok(result) })
  await (await response).finished()
  await page.evaluate(() => new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))))
}

for (const item of cases) {
  test(`${item.key} 计算故障保留输入和上次结果，重试可恢复`, async ({ page }, testInfo) => {
    let failed = false
    await page.route(url => url.pathname === '/api' + item.endpoint, r => r.fulfill({ json: failed ? { code: 50000, message: '工具计算暂时失败' } : ok(item.result) }))
    await setup(page, item)
    await page.getByRole('button', { name: item.button, exact: true }).click()
    const main = page.locator('.layout-main')
    await expectResult(page, item)
    failed = true
    await page.getByRole('button', { name: item.button, exact: true }).click()
    await expect(main.locator('.crud-load-state')).toBeVisible()
    await expectResult(page, item)
    await expect(page.getByPlaceholder(item.placeholder, { exact: true })).toHaveValue(item.input)
    failed = false
    await main.locator('.crud-load-state').getByRole('button').click()
    await expect(main.locator('.crud-load-state')).toHaveCount(0)
    if (['diff', 'jwt', 'cert'].includes(item.key)) {
      await page.setViewportSize({ width: 390, height: 844 })
      await expect.poll(() => page.locator('.layout-aside').evaluate(e => e.getBoundingClientRect().width)).toBeLessThanOrEqual(1)
      expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390)
      await expect(page.locator('.el-message')).toHaveCount(0)
      await page.screenshot({ path: testInfo.outputPath(`${item.key}-tool-390.png`), animations: 'disabled' })
      await main.getByText(marker, { exact: true }).scrollIntoViewIfNeeded()
      await expect(main.getByText(marker, { exact: true })).toBeVisible()
      await page.screenshot({ path: testInfo.outputPath(`${item.key}-result-390.png`), animations: 'disabled' })
    }
  })

  test(`${item.key} 同令牌重登丢弃旧计算响应`, async ({ page }) => {
    let held: Route | undefined
    await page.route(url => url.pathname === '/api' + item.endpoint, r => { held = r })
    await setup(page, item)
    await page.getByRole('button', { name: item.button, exact: true }).click()
    await expect.poll(() => Boolean(held)).toBe(true)
    await relogin(page)
    await deliver(page, held!, item.result)
    await expectResult(page, item, false)
  })

  if (item.key !== 'cron') test(`${item.key} 清空后旧请求不能恢复结果`, async ({ page }) => {
    let held: Route | undefined
    await page.route(url => url.pathname === '/api' + item.endpoint, r => { held = r })
    await setup(page, item)
    await page.getByRole('button', { name: item.button, exact: true }).click()
    await expect.poll(() => Boolean(held)).toBe(true)
    await page.getByRole('button', { name: '清空', exact: true }).click()
    await deliver(page, held!, item.result)
    await expectResult(page, item, false)
    await expect(page.getByPlaceholder(item.placeholder, { exact: true })).toHaveValue('')
  })
}

for (const item of [
  { key: 'aes', placeholders: ['输入密钥', '输入 IV（16 字节）'] },
  { key: 'jwt', placeholders: ['粘贴 JWT（header.payload.signature）…', '可选，仅 HS256/HS384/HS512 可校验签名'] },
  { key: 'cert', placeholders: ['密钥库密码（不持久化，无密码留空）'] },
]) test(`${item.key} 同令牌切换身份立即清空临时秘密，不写本地存储`, async ({ page }) => {
  await page.route('**/api/auth/permissions', r => r.fulfill({ json: ok(['devtools:view']) }))
  await page.goto(`/system/devtools?tool=${item.key}`)
  if (item.key === 'cert') await page.getByText('PFX / JKS 密钥库', { exact: true }).click()
  for (const placeholder of item.placeholders) await page.getByPlaceholder(placeholder, { exact: true }).fill('SYNTHETIC-ONLY-ACCEPTANCE')
  const persisted = await page.evaluate(() => JSON.stringify({ ...localStorage }))
  expect(persisted).not.toContain('SYNTHETIC-ONLY-ACCEPTANCE')
  await relogin(page)
  if (item.key === 'cert') await page.getByText('PFX / JKS 密钥库', { exact: true }).click()
  for (const placeholder of item.placeholders) await expect(page.getByPlaceholder(placeholder, { exact: true })).toHaveValue('')
})

for (const item of cases.filter(item => ['diff', 'convert'].includes(item.key))) {
  test(`${item.key} 互换输入方向后不接受旧方向计算结果`, async ({ page }) => {
    let held: Route | undefined
    await page.route(url => url.pathname === '/api' + item.endpoint, route => { held = route })
    await setup(page, item)
    await page.getByRole('button', { name: item.button, exact: true }).click()
    await expect.poll(() => Boolean(held)).toBe(true)
    await page.getByRole('button', { name: item.key === 'diff' ? '交换两侧' : '互换方向', exact: true }).click()
    await deliver(page, held!, item.result)
    await expectResult(page, item, false)
  })
}

test('Cron 连续选择示例只接受最后一个表达式的响应', async ({ page }) => {
  const held: Route[] = []
  const item = cases.find(item => item.key === 'cron')!
  await page.route('**/api/devtools/cron/explain*', route => { held.push(route) })
  await setup(page, item)
  await page.getByText('每分钟', { exact: true }).click()
  await expect.poll(() => held.length).toBe(1)
  await page.getByText('每 5 分钟', { exact: true }).click()
  await expect.poll(() => held.length).toBe(2)
  const result = (description: string) => ({ expression: '0 */5 * * * ?', timezone: 'Asia/Shanghai',
    fields: [{ name: '秒', value: '0', range: '0-59', description }], nextTimes: ['2026-09-16 02:05:00'] })
  await deliver(page, held[1], result('后选表达式结果'))
  await expect(page.getByText('后选表达式结果', { exact: true })).toBeVisible()
  await deliver(page, held[0], result('先选表达式结果'))
  await expect(page.getByText('后选表达式结果', { exact: true })).toBeVisible()
  await expect(page.getByText('先选表达式结果', { exact: true })).toHaveCount(0)
})
