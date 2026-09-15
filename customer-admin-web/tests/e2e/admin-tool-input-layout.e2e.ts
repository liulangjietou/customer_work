import { expect, test } from './fixtures/adminTestFixture'

const cases = [
  { tool: 'jwt', placeholders: ['可选，仅 HS256/HS384/HS512 可校验签名'] },
  { tool: 'aes', placeholders: ['输入密钥', '输入 IV（16 字节）'] },
]
for (const theme of ['ocean', 'night'] as const) {
  for (const item of cases) {
    test(`${item.tool} 在 ${theme} 主题的390px页面保留可输入的密钥宽度`, async ({ page }, testInfo) => {
      await page.route('**/api/auth/permissions', r => r.fulfill({ json: { code: 0, data: ['devtools:view'] } }))
      await page.setViewportSize({ width: 390, height: 844 })
      await page.goto(`/system/devtools?tool=${item.tool}`)
      await page.evaluate(async preset => {
        const modulePath = '/src/store/theme.ts'
        const { useThemeStore } = await import(modulePath)
        useThemeStore().selectPreset(preset)
      }, theme)
      for (const placeholder of item.placeholders) {
        const input = page.getByPlaceholder(placeholder, { exact: true })
        await input.scrollIntoViewIfNeeded()
        await expect(input).toBeVisible()
        // 不溢出还不够：密钥输入至少容纳一段可核对文本，不能被编码选项压缩成几十像素。
        await expect.poll(async () => (await input.boundingBox())?.width ?? 0).toBeGreaterThanOrEqual(240)
      }
      const key = page.getByPlaceholder(item.placeholders[0], { exact: true })
      await key.fill('SYNTHETIC-KEY-ONLY')
      await key.focus()
      await expect(key).toBeFocused()
      await expect(key).toHaveValue('SYNTHETIC-KEY-ONLY')
      await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth)).toBe(390)
      await key.scrollIntoViewIfNeeded()
      await page.screenshot({ path: testInfo.outputPath(`${item.tool}-key-${theme}-390.png`), animations: 'disabled' })
    })
  }
}
