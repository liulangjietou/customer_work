import { expect, test } from './fixtures/adminTestFixture'

for (const [id, label] of [['ember', 'Ember 暖焰'], ['night', 'Night 夜航'], ['violet', 'Violet 智紫']]) {
  test(`${label} 工作区知识入口沿用当前主题强调色`, async ({ page }) => {
    await page.goto('/workspace/java-assistant')
    await page.getByLabel('选择界面主题', { exact: true }).click()
    await page.getByRole('option').filter({ hasText: label }).click()
    await expect(page.locator('html')).toHaveAttribute('data-theme', id!)
    const color = await page.evaluate(() => {
      const sample = document.createElement('span')
      sample.style.color = 'var(--theme-primary)'
      document.body.append(sample)
      const resolved = getComputedStyle(sample).color
      sample.remove()
      return resolved
    })
    await expect(page.getByRole('button', { name: '打开代码知识库', exact: true }).locator('.el-icon'))
      .toHaveCSS('color', color)
  })
}
