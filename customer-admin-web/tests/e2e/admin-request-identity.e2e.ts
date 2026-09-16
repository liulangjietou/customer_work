import type { Page, Route } from '@playwright/test'
import { expect, test } from './fixtures/adminTestFixture'

const OLD_TOKEN = 'admin-shell-e2e-token'
const NEW_TOKEN = 'new-admin-request-token'
const CAPTCHA_IMAGE = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9WnWQAAAAASUVORK5CYII='
const ORDER = {
  orderId: 'identity-order', userId: 'identity-customer', username: '测试客户', productId: 'identity-product',
  productName: '身份隔离测试商品', amount: '99.00', status: '待发货', receiverAddr: '测试地址', createdAtMs: 1789086000000,
}
const ok = (data: unknown) => ({ code: 0, data })

async function holdAuthenticatedOrderRequest(page: Page) {
  await page.route('**/api/auth/permissions', route => route.fulfill({ json: ok(['user-order:view']) }))
  await page.route('**/api/ticket/orders/page?*', route => route.fulfill({ json: ok({ items: [ORDER], total: 1 }) }))
  // 当前凭据确实失效时应进入真实登录页；显式声明该页面的匿名读取契约。
  await page.route('**/api/login-images/list', route => route.fulfill({ json: ok([]) }))
  await page.route('**/api/auth/register-options', route => route.fulfill({
    json: ok({ selfServiceEnabled: false, captchaRequired: true, passwordResetEnabled: false }),
  }))
  await page.route('**/api/auth/login-captcha/challenge', route => route.fulfill({ json: ok({
    challengeId: 'request-identity-captcha', ttlSeconds: 60,
    backgroundImage: CAPTCHA_IMAGE, puzzlePieceImage: CAPTCHA_IMAGE,
    canvasWidth: 320, canvasHeight: 160, pieceWidth: 56, pieceHeight: 56, pieceY: 52,
  }) }))
  let held: Route | undefined
  await page.route('**/api/ticket/orders/identity-order', route => { held = route })
  await page.goto('/ticket/user-order')
  await page.locator('.el-table__body tr').filter({ hasText: ORDER.orderId })
    .getByRole('button', { name: '详情', exact: true }).click()
  await expect.poll(() => Boolean(held)).toBe(true)
  expect(held!.request().headers().authorization).toBe(OLD_TOKEN)
  return held!
}

async function applyNewLogin(page: Page) {
  await page.evaluate(async token => {
    const path = '/src/store/auth.ts'
    const { useAuthStore } = await import(path)
    useAuthStore().applyLoginResult({
      token, nickname: '新登录管理员', forceChangePassword: false, approvalStatus: 'APPROVED', approvalRemark: null,
    }, 'new-admin')
  }, NEW_TOKEN)
  await expect(page.getByRole('dialog', { name: '订单详情', exact: true })).not.toBeVisible()
}

async function releaseFailure(page: Page, route: Route, code: number, message: string) {
  const response = page.waitForResponse(item => item.url() === route.request().url())
  await route.fulfill({ json: { code, message, data: null } })
  await (await response).finished()
  // 路由副作用可能异步加载登录/改密组件；等待它们完成，不能在跳转前就判定“没有跳转”。
  await page.waitForLoadState('networkidle')
}

for (const { code, label } of [
  { code: 10001, label: '登录失效' },
  { code: 20002, label: '强制改密' },
  { code: 50000, label: '业务错误提示' },
]) {
  test(`旧 token 订单请求迟到的${label}不能干扰新登录`, async ({ page }) => {
    const held = await holdAuthenticatedOrderRequest(page)
    await applyNewLogin(page)
    const message = `旧登录作用域的${label}`
    await releaseFailure(page, held, code, message)

    expect(await page.evaluate(() => localStorage.getItem('admin-token'))).toBe(NEW_TOKEN)
    await expect(page).toHaveURL(/\/ticket\/user-order$/)
    expect(await page.locator('.el-message').filter({ hasText: message }).count()).toBe(0)
    await expect(page.getByRole('dialog', { name: '订单详情', exact: true })).not.toBeVisible()
  })
}

test('当前 token 的真实登录失效仍清理凭据并跳转登录', async ({ page }) => {
  const held = await holdAuthenticatedOrderRequest(page)
  await releaseFailure(page, held, 10001, '当前登录已失效')

  expect(await page.evaluate(() => localStorage.getItem('admin-token'))).toBeNull()
  await expect(page).toHaveURL(/\/login\?redirect=/)
  await expect(page.locator('.el-message')).toContainText('当前登录已失效')
})

test('当前 token 的强制改密仍进入改密流程', async ({ page }) => {
  const held = await holdAuthenticatedOrderRequest(page)
  await releaseFailure(page, held, 20002, '当前登录需要修改密码')

  expect(await page.evaluate(() => localStorage.getItem('admin-token'))).toBe(OLD_TOKEN)
  await expect(page).toHaveURL(/\/change-password$/)
})
