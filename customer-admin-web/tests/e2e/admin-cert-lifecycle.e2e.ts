import type { Page, Route } from '@playwright/test'
import { expect, test } from './fixtures/adminTestFixture'
const ok = (data: unknown) => ({ code: 0, data })
const storeResult = { keystoreType: 'PKCS12', entries: [{ alias: 'acceptance-key', entryType: 'PRIVATE_KEY', chain: [] }] }
const fakeStore = { name: 'acceptance-only.p12', mimeType: 'application/octet-stream', buffer: Buffer.from('SYNTHETIC-KEYSTORE-FIXTURE') }
async function setup(page: Page) {
  await page.route('**/api/auth/permissions', r => r.fulfill({ json: ok(['devtools:view']) }))
  await page.goto('/system/devtools?tool=cert')
  await page.getByText('PFX / JKS 密钥库', { exact: true }).click()
}
async function relogin(page: Page) {
  await page.evaluate(async () => {
    const modulePath = '/src/store/auth.ts'
    const { useAuthStore } = await import(modulePath)
    const auth = useAuthStore()
    auth.applyLoginResult({ token: auth.token, nickname: '新登录', forceChangePassword: false, approvalStatus: 'APPROVED', approvalRemark: null }, 'new-user')
    auth.permissions = ['devtools:view']
  })
}
async function release(page: Page, held: Route, result: unknown) {
  const response = page.waitForResponse(r => r.url() === held.request().url())
  await held.fulfill({ json: ok(result) })
  await (await response).finished()
  await page.evaluate(() => new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))))
}
async function readyStore(page: Page) {
  await page.route('**/api/devtools/cert/keystore', r => r.fulfill({ json: ok(storeResult) }))
  await setup(page)
  await page.locator('.cert-tool input[type=file]').setInputFiles(fakeStore)
  await page.getByRole('button', { name: /acceptance-key.*含私钥/ }).click()
  await page.getByRole('button', { name: '导出私钥 PEM', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '导出私钥：acceptance-key', exact: true })
  await expect(dialog).toBeVisible()
  return dialog
}
test('证书库清空时废弃迟到上传结果，密码与文件标识同时清除', async ({ page }) => {
  let held: Route | undefined
  await page.route('**/api/devtools/cert/keystore', r => { held = r })
  await setup(page)
  await page.getByPlaceholder('密钥库密码（不持久化，无密码留空）', { exact: true }).fill('synthetic-password')
  await page.locator('.cert-tool input[type=file]').setInputFiles(fakeStore)
  await expect.poll(() => Boolean(held)).toBe(true)
  await page.getByRole('button', { name: '清空', exact: true }).click()
  await release(page, held!, storeResult)
  await expect(page.getByRole('button', { name: /acceptance-key.*含私钥/ })).toHaveCount(0)
  await expect(page.locator('.file-hint')).toHaveCount(0)
  await expect(page.getByPlaceholder('密钥库密码（不持久化，无密码留空）', { exact: true })).toHaveValue('')
})
test('私钥导出确认期间重新登录，不能发送旧文件与别名', async ({ page }) => {
  let exports = 0
  await page.route('**/api/devtools/cert/keystore/private-key', r => { exports += 1; return r.fulfill({ json: ok({ alias: 'acceptance-key', algorithm: 'RSA', privateKeyPem: 'SYNTHETIC-PRIVATE-KEY-ONLY' }) }) })
  const dialog = await readyStore(page)
  await relogin(page)
  await dialog.getByRole('button', { name: '确认导出', exact: true }).click()
  await expect(dialog).not.toBeVisible()
  await page.evaluate(() => new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))))
  expect(exports).toBe(0)
  await expect(page.getByRole('dialog', { name: '私钥导出', exact: true })).not.toBeVisible()
})
test('私钥导出在途清空库后，迟到明文不能重新打开显示窗口', async ({ page }) => {
  let held: Route | undefined
  await page.route('**/api/devtools/cert/keystore/private-key', r => { held = r })
  const dialog = await readyStore(page)
  await dialog.getByRole('button', { name: '确认导出', exact: true }).click()
  await expect.poll(() => Boolean(held)).toBe(true)
  await page.getByRole('button', { name: '清空', exact: true }).click()
  await release(page, held!, { alias: 'acceptance-key', algorithm: 'RSA', privateKeyPem: 'SYNTHETIC-PRIVATE-KEY-ONLY' })
  await expect(page.getByRole('dialog', { name: '私钥导出', exact: true })).not.toBeVisible()
  await expect(page.getByText('SYNTHETIC-PRIVATE-KEY-ONLY', { exact: true })).toHaveCount(0)
  expect(await page.evaluate(() => JSON.stringify({ ...localStorage }))).not.toContain('SYNTHETIC-PRIVATE-KEY-ONLY')
})

async function setupMatch(page: Page) {
  await page.route('**/api/auth/permissions', r => r.fulfill({ json: ok(['devtools:view']) }))
  await page.goto('/system/devtools?tool=cert')
  await page.getByText('私钥匹配校验', { exact: true }).click()
  await page.getByPlaceholder('-----BEGIN CERTIFICATE-----', { exact: true }).fill('SYNTHETIC-CERTIFICATE')
  await page.getByPlaceholder('-----BEGIN PRIVATE KEY----- 或 -----BEGIN RSA/EC PRIVATE KEY-----', { exact: true }).fill('SYNTHETIC-PRIVATE-MATCH-ONLY')
}
const matchResult = { matched: true, publicKeyAlgorithm: 'RSA', reason: '验收匹配结果' }
test('证书匹配失败保留输入和上次结果并可原地重试', async ({ page }) => {
  let failed = false
  await page.route('**/api/devtools/cert/match', route => route.fulfill({ json: failed
    ? { code: 50000, message: '证书匹配暂时失败' } : ok(matchResult) }))
  await setupMatch(page)
  const action = page.getByRole('button', { name: '校验匹配', exact: true })
  await action.click()
  await expect(page.getByText('私钥与证书配对', { exact: true })).toBeVisible()
  failed = true
  await action.click()
  const error = page.locator('.cert-tool .crud-load-state')
  await expect(error).toBeVisible()
  await expect(page.getByText('私钥与证书配对', { exact: true })).toBeVisible()
  await expect(page.getByPlaceholder('-----BEGIN PRIVATE KEY----- 或 -----BEGIN RSA/EC PRIVATE KEY-----', { exact: true })).toHaveValue('SYNTHETIC-PRIVATE-MATCH-ONLY')
  failed = false
  await error.getByRole('button').click()
  await expect(error).toHaveCount(0)
  expect(await page.evaluate(() => JSON.stringify({ ...localStorage }))).not.toContain('SYNTHETIC-PRIVATE-MATCH-ONLY')
})
test('证书匹配清空后迟到结果不能重新显示匹配成功', async ({ page }) => {
  let held: Route | undefined
  await page.route('**/api/devtools/cert/match', route => { held = route })
  await setupMatch(page)
  await page.getByRole('button', { name: '校验匹配', exact: true }).click()
  await expect.poll(() => Boolean(held)).toBe(true)
  await page.getByRole('button', { name: '清空', exact: true }).click()
  await release(page, held!, matchResult)
  await expect(page.getByText('私钥与证书配对', { exact: true })).toHaveCount(0)
  await expect(page.getByPlaceholder('-----BEGIN PRIVATE KEY----- 或 -----BEGIN RSA/EC PRIVATE KEY-----', { exact: true })).toHaveValue('')
})
test('密钥库解析失败保留已选文件，原地重试成功后恢复条目', async ({ page }) => {
  let failed = true
  await page.route('**/api/devtools/cert/keystore', route => route.fulfill({ json: failed
    ? { code: 50000, message: '密钥库解析暂时失败' } : ok(storeResult) }))
  await setup(page)
  await page.locator('.cert-tool input[type=file]').setInputFiles(fakeStore)
  const error = page.locator('.cert-tool .crud-load-state')
  await expect(error).toBeVisible()
  await expect(page.locator('.file-hint')).toContainText(fakeStore.name)
  failed = false
  await error.getByRole('button').click()
  await expect(error).toHaveCount(0)
  await expect(page.getByRole('button', { name: /acceptance-key.*含私钥/ })).toBeVisible()
})
