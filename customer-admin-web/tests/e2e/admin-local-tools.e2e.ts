import type { Page } from '@playwright/test'
import { expect, test } from './fixtures/adminTestFixture'
const ok = (data: unknown) => ({ code: 0, data })
async function setup(page: Page, tool: string) {
  await page.route('**/api/auth/permissions', route => route.fulfill({ json: ok(['devtools:view']) }))
  await page.goto(`/system/devtools?tool=${tool}`)
}

test('本地编解码与哈希保留中文语义，切换工具后恢复本主体输入', async ({ page }) => {
  await setup(page, 'codec')
  const base64 = page.getByRole('tabpanel', { name: 'Base64', exact: true })
  await base64.getByPlaceholder('输入原文，UTF-8 安全，支持中文…', { exact: true }).fill('你好')
  await expect(base64.locator('textarea[readonly]')).toHaveValue('5L2g5aW9')
  await page.getByRole('tab', { name: '哈希 / HMAC', exact: true }).click()
  await page.getByPlaceholder('输入需要计算哈希的文本…', { exact: true }).fill('abc')
  await expect(page.locator('.hash-row').filter({ has: page.locator('.hash-label', { hasText: /^MD5$/ }) }).locator('.hash-value'))
    .toHaveText('900150983cd24fb0d6963f7d28e17f72')
  await page.getByRole('button', { name: /时间戳转换/ }).click()
  await page.getByRole('button', { name: /编解码 \/ 哈希/ }).click()
  await page.getByRole('tab', { name: 'Base64', exact: true }).click()
  await expect(base64.getByPlaceholder('输入原文，UTF-8 安全，支持中文…', { exact: true })).toHaveValue('你好')
})

test('本地 AES 输入错误可修正，实际加解密往返且密钥不持久化', async ({ page }) => {
  await setup(page, 'aes')
  const plaintext = '仅浏览器验收的中文消息'
  await page.getByPlaceholder('输入待加密的明文…', { exact: true }).fill(plaintext)
  await page.getByRole('button', { name: '加密', exact: true }).click()
  await expect(page.locator('.aes-tool .field-error')).toContainText('请输入密钥')
  await page.getByPlaceholder('输入密钥', { exact: true }).fill('0123456789abcdef')
  await page.getByPlaceholder('输入 IV（16 字节）', { exact: true }).fill('abcdef0123456789')
  await page.getByRole('button', { name: '加密', exact: true }).click()
  const encrypt = page.getByRole('tabpanel', { name: '加密', exact: true })
  await expect(encrypt.locator('textarea[readonly]')).not.toHaveValue('')
  const ciphertext = await encrypt.locator('textarea[readonly]').inputValue()
  await page.getByRole('tab', { name: '解密', exact: true }).click()
  await page.getByPlaceholder('输入待解密的密文…', { exact: true }).fill(ciphertext)
  await page.getByRole('button', { name: '解密', exact: true }).click()
  await expect(page.getByRole('tabpanel', { name: '解密', exact: true }).locator('textarea[readonly]')).toHaveValue(plaintext)
  const persisted = await page.evaluate(() => JSON.stringify({ ...localStorage }))
  expect(persisted).not.toContain('0123456789abcdef')
  expect(persisted).not.toContain('abcdef0123456789')
})

test('正则非法输入可修正并呈现实际匹配', async ({ page }) => {
  await setup(page, 'regex')
  const expression = page.getByPlaceholder(/不含分隔符/)
  await page.getByPlaceholder('输入待测试文本…', { exact: true }).fill('a12b34')
  await expression.fill('[')
  await expect(page.locator('.regex-tool .field-error').first()).toBeVisible()
  await expression.fill('\\d+')
  await expect(page.locator('.regex-tool .field-error')).toHaveCount(0)
  await expect(page.getByText('匹配列表（2 项）', { exact: true })).toBeVisible()
  await expect(page.locator('.regex-tool .el-table__body').getByText('12', { exact: true })).toBeVisible()
  await expect(page.locator('.regex-tool .el-table__body').getByText('34', { exact: true })).toBeVisible()
})

test('时间戳在 UTC 视角转换并保留真实日期', async ({ page }) => {
  await setup(page, 'timestamp')
  await page.locator('.timestamp-tool .el-select').click()
  await page.getByRole('option', { name: 'UTC', exact: true }).click()
  await page.getByPlaceholder('输入 10 位秒或 13 位毫秒时间戳', { exact: true }).fill('1700000000')
  await expect(page.getByPlaceholder('yyyy-MM-dd HH:mm:ss / yyyy-MM-dd / ISO8601', { exact: true })).toHaveValue('2023-11-14 22:13:20')
  await expect(page.locator('.comparison')).toContainText('2023-11-15 06:13:20')
})
