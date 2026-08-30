import { expect, test } from './fixtures/adminTestFixture'
import { STATIC_ROUTE_CASES } from './fixtures/adminRoutes'

test.describe('41 个静态业务路由挂载 smoke', () => {
  for (const routeCase of STATIC_ROUTE_CASES) {
    test(`${routeCase.path} 可挂载且不落入 404`, async ({ page }) => {
      await page.goto(routeCase.path, { waitUntil: 'domcontentloaded' })

      expect(new URL(page.url()).pathname).toBe(routeCase.path)
      await expect(page.locator(routeCase.readySelector)).toBeVisible()
      await expect(page.locator('#cw-page-title')).toHaveText(routeCase.menuTitle)
      await expect(page.locator('.not-found')).toHaveCount(0)

      // 同一次挂载切到真实移动断点，证明页面宽表只在自己的工作区横向滚动，
      // 不会把整个文档撑出视口。这样新增路由不能只靠桌面 smoke 混过门禁。
      await page.setViewportSize({ width: 390, height: 844 })
      await expect(page.locator(routeCase.readySelector)).toBeVisible()
      const mobileGeometry = await page.evaluate(() => ({
        viewportWidth: window.innerWidth,
        documentWidth: document.documentElement.scrollWidth,
        bodyWidth: document.body.scrollWidth,
      }))
      expect(mobileGeometry.documentWidth, 'document should not overflow at 390px').toBeLessThanOrEqual(mobileGeometry.viewportWidth)
      expect(mobileGeometry.bodyWidth, 'body should not overflow at 390px').toBeLessThanOrEqual(mobileGeometry.viewportWidth)
    })
  }
})
