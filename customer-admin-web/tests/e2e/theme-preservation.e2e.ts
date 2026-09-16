import type { Page } from '@playwright/test'
import { expect, test } from './fixtures/adminTestFixture'

// 来自体验升级前的已发布主题目录，固定预期避免实现改色后测试跟着同步变绿。
const themes = [
  { id: 'system', label: 'System 随行', primary: '#2563eb', canvas: '#f6f8fb', paper: '#ffffff', mode: 'auto' },
  { id: 'atlas', label: 'Atlas 翡翠', primary: '#0f827a', canvas: '#f1f6f5', paper: '#ffffff', mode: 'light' },
  { id: 'ocean', label: 'Ocean 深海', primary: '#3e63dd', canvas: '#f6f8fb', paper: '#ffffff', mode: 'light' },
  { id: 'violet', label: 'Violet 智紫', primary: '#7347bd', canvas: '#f7f3fa', paper: '#fffefe', mode: 'light' },
  { id: 'ember', label: 'Ember 暖焰', primary: '#b45309', canvas: '#fbf5ee', paper: '#fffdf9', mode: 'light' },
  { id: 'dawn', label: 'Dawn 晨曦', primary: '#b42362', canvas: '#fbf3f6', paper: '#fffdfd', mode: 'light' },
  { id: 'night', label: 'Night 夜航', primary: '#14b8a6', canvas: '#071820', paper: '#0d242c', mode: 'dark' },
  { id: 'aurora', label: 'Aurora 极光', primary: '#8b5cf6', canvas: '#100d23', paper: '#18142f', mode: 'dark' },
  { id: 'graphite', label: 'Graphite 墨岩', primary: '#94a3b8', canvas: '#111318', paper: '#191c22', mode: 'dark' },
] as const

async function orders(page: Page) {
  await page.route('**/api/auth/permissions', route => route.fulfill({ json: { code: 0, data: ['user-order:view'] } }))
  await page.route('**/api/ticket/orders/page?*', route => route.fulfill({ json: { code: 0, data: {
    total: 1, items: [{ orderId: 'theme-order', userId: 'customer-theme', username: '林女士', productId: 'product-theme',
      productName: '便携会议终端', amount: '299.00', status: '待发货', receiverAddr: '测试地址', createdAtMs: 1789086000000 }],
  } } }))
  await page.goto('/ticket/user-order')
  await expect(page.getByText('theme-order', { exact: true })).toBeVisible()
}

async function assertColors(page: Page, expected: { primary: string; canvas: string; paper: string }) {
  // 主题浮层关闭后鼠标可能落到下方查询按钮；常态配色必须在非悬停状态下验证。
  await page.mouse.move(0, 0)
  await expect.poll(() => page.evaluate(() => {
    const styles = getComputedStyle(document.documentElement)
    return {
      primary: styles.getPropertyValue('--theme-primary-source').trim(),
      canvas: styles.getPropertyValue('--cw-canvas').trim(),
      paper: styles.getPropertyValue('--cw-paper').trim(),
    }
  })).toEqual(expected)
  const rgb = (hex: string) => `rgb(${[1, 3, 5].map(offset => parseInt(hex.slice(offset, offset + 2), 16)).join(', ')})`
  const search = page.getByRole('button', { name: '查询', exact: true })
  expect(await search.evaluate(button => button.matches(':hover'))).toBe(false)
  await expect(search).toHaveCSS('background-color', rgb(expected.primary))
  await search.hover()
  const hover = await page.evaluate(() => getComputedStyle(document.documentElement).getPropertyValue('--theme-primary-solid-hover').trim())
  await expect(search).toHaveCSS('background-color', rgb(hover))
  await page.mouse.move(0, 0)
  await expect(search).toHaveCSS('background-color', rgb(expected.primary))
  await expect(page.locator('.order-management .el-card')).toHaveCSS('background-color', rgb(expected.paper))
}

for (const theme of themes) {
  test(`${theme.label} 在订单页面保持原配色，刷新后保留选择`, async ({ page }, testInfo) => {
    await page.emulateMedia({ colorScheme: 'light' })
    await orders(page)
    await page.getByLabel('选择界面主题', { exact: true }).click()
    await page.getByRole('option').filter({ hasText: theme.label }).click()
    await expect(page.locator('html')).toHaveAttribute('data-theme', theme.id)
    await assertColors(page, { primary: theme.primary, canvas: theme.canvas, paper: theme.paper })
    await page.reload()
    await expect(page.getByText('theme-order', { exact: true })).toBeVisible()
    await expect(page.locator('html')).toHaveAttribute('data-theme', theme.id)
    await assertColors(page, { primary: theme.primary, canvas: theme.canvas, paper: theme.paper })
    expect(await page.evaluate(() => localStorage.getItem('customer-admin-theme-mode'))).toBe(theme.mode)
    if (['ember', 'ocean', 'night'].includes(theme.id)) {
      if (theme.id === 'ember') await page.setViewportSize({ width: 390, height: 844 })
      await page.screenshot({ path: testInfo.outputPath(`orders-${theme.id}.png`), fullPage: true, animations: 'disabled' })
    }
  })
}

test('旧自定义颜色和深色选择在新订单页刷新后保持', async ({ page }) => {
  await orders(page)
  await page.evaluate(() => {
    localStorage.setItem('customer-admin-theme-selection', JSON.stringify({ version: 1, kind: 'custom', primaryColor: '#d97706', mode: 'dark' }))
    localStorage.setItem('customer-admin-theme-color', '#d97706')
    localStorage.setItem('customer-admin-theme-mode', 'dark')
  })
  await page.reload()
  await assertColors(page, { primary: '#d97706', canvas: '#09111f', paper: '#101a2b' })
  await page.reload()
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'custom')
  await expect(page.locator('html')).toHaveClass(/dark/)
  await assertColors(page, { primary: '#d97706', canvas: '#09111f', paper: '#101a2b' })
})
