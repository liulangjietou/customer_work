import type { Page, Route } from '@playwright/test'
import { expect, test } from './fixtures/adminTestFixture'

const OLD_TOKEN = 'admin-shell-e2e-token'
const NEW_TOKEN = 'bootstrap-new-token'
const ok = (data: unknown) => ({ code: 0, data })
const CAPTCHA_IMAGE = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9WnWQAAAAASUVORK5CYII='

async function loginFixtures(page: Page) {
  await page.route('**/api/login-images/list', route => route.fulfill({ json: ok([]) }))
  await page.route('**/api/auth/register-options', route => route.fulfill({ json: ok({
    selfServiceEnabled: false, captchaRequired: true, passwordResetEnabled: false,
  }) }))
  await page.route('**/api/auth/login-captcha/challenge', route => route.fulfill({ json: ok({
    challengeId: 'bootstrap-captcha', ttlSeconds: 60, backgroundImage: CAPTCHA_IMAGE,
    puzzlePieceImage: CAPTCHA_IMAGE, canvasWidth: 320, canvasHeight: 160,
    pieceWidth: 56, pieceHeight: 56, pieceY: 52,
  }) }))
}

async function startOldBootstrap(page: Page, endpoint: 'permissions' | 'menu') {
  await loginFixtures(page)
  let held: Route | undefined
  await page.route('**/api/auth/permissions', route => {
    if (endpoint === 'permissions' && route.request().headers().authorization === OLD_TOKEN) held = route
    else return route.fulfill({ json: ok(['user-order:view']) })
  })
  if (endpoint === 'menu') {
    await page.route('**/api/menu/routes', route => {
      if (route.request().headers().authorization === OLD_TOKEN) held = route
      else return route.fallback()
    })
  }
  await page.goto('/ticket/user-order', { waitUntil: 'domcontentloaded' })
  await expect.poll(() => Boolean(held)).toBe(true)
  return held!
}

async function enterNewLogin(page: Page) {
  await page.evaluate(async token => {
    const authPath = '/src/store/auth.ts'
    const menuPath = '/src/store/menu.ts'
    const tabsPath = '/src/store/tabs.ts'
    const routerPath = '/src/router/index.ts'
    const { useAuthStore } = await import(authPath)
    const { useMenuStore } = await import(menuPath)
    const { useTabsStore } = await import(tabsPath)
    const { default: router } = await import(routerPath)
    await router.replace({ name: 'Login' })
    useMenuStore().reset()
    useTabsStore().reset()
    useAuthStore().applyLoginResult({ token, nickname: '新登录管理员', approvalStatus: 'APPROVED',
      approvalRemark: null, forceChangePassword: false }, 'new-admin')
    await router.replace('/ticket/user-order?from=new-login#orders')
  }, NEW_TOKEN)
  await expect(page.getByText('只读访问', { exact: true })).toBeVisible()
}

for (const endpoint of ['permissions', 'menu'] as const) {
  for (const code of [10001, 20002, 50000]) {
    test(`旧 ${endpoint} 初始化 code=${code} 不清理新登录或重定向旧目标`, async ({ page }) => {
      const held = await startOldBootstrap(page, endpoint)
      await enterNewLogin(page)
      const response = page.waitForResponse(item => item.url() === held.request().url())
      await held.fulfill({ json: { code, message: '旧初始化响应', data: null } })
      await (await response).finished()
      await page.waitForLoadState('networkidle')
      expect(await page.evaluate(() => localStorage.getItem('admin-token'))).toBe(NEW_TOKEN)
      await expect(page).toHaveURL(/user-order\?from=new-login#orders$/)
      await expect(page.getByText('只读访问', { exact: true })).toBeVisible()
      expect(await page.locator('.el-message').filter({ hasText: '旧初始化响应' }).count()).toBe(0)
    })
  }
}

test('旧权限初始化成功不覆盖新账号权限或当前路由', async ({ page }) => {
  const held = await startOldBootstrap(page, 'permissions')
  await enterNewLogin(page)
  await held.fulfill({ json: ok(['user:edit']) })
  await page.waitForLoadState('networkidle')
  await expect(page.getByText('只读访问', { exact: true })).toBeVisible()
  await expect(page).toHaveURL(/user-order\?from=new-login#orders$/)
  expect(await page.evaluate(async () => {
    const path = '/src/store/auth.ts'
    return (await import(path)).useAuthStore().permissions
  })).toEqual(['user-order:view'])
})

for (const code of [10001, 50000]) {
  test(`当前登录初始化 code=${code} 仍清理凭据并回到登录`, async ({ page, adminHarness }) => {
    const held = await startOldBootstrap(page, 'permissions')
    await held.fulfill({ json: { code, message: '当前初始化失败', data: null } })
    await expect(page).toHaveURL(/\/login\?redirect=/)
    expect(await page.evaluate(() => localStorage.getItem('admin-token'))).toBeNull()
    if (code === 50000) {
      // 本条故意触发当前初始化故障；仅核销已断言的这一条产品错误日志，其他运行异常仍由夹具拒绝。
      const expected = adminHarness.consoleErrors.filter(message => message.startsWith('menu bootstrap failed, redirect to login'))
      expect(expected).toHaveLength(1)
      expect(expected[0]).toContain('当前初始化失败')
      adminHarness.consoleErrors = adminHarness.consoleErrors.filter(message => message !== expected[0])
    }
  })
}

test('旧菜单初始化成功不能恢复旧路由或把新账号带回原目标', async ({ page }) => {
  const held = await startOldBootstrap(page, 'menu')
  await enterNewLogin(page)
  await held.fulfill({ json: ok([]) })
  await page.waitForLoadState('networkidle')
  await expect(page.getByText('只读访问', { exact: true })).toBeVisible()
  await expect(page).toHaveURL(/user-order\?from=new-login#orders$/)
})

for (const code of [0, 50000]) {
  test(`旧退出请求 code=${code} 完成不清理新账号菜单或跳回登录`, async ({ page }) => {
    await loginFixtures(page)
    await page.route('**/api/auth/permissions', route => route.fulfill({ json: ok(['user-order:view']) }))
    let held: Route | undefined
    await page.route('**/api/auth/logout', route => { held = route })
    await page.goto('/ticket/user-order')
    await page.getByLabel('打开用户菜单', { exact: true }).click()
    await page.getByRole('menuitem', { name: '退出登录' }).click()
    await expect.poll(() => Boolean(held)).toBe(true)
    await enterNewLogin(page)
    await held!.fulfill({ json: { code, message: '旧退出响应', data: null } })
    await page.waitForLoadState('networkidle')
    await expect(page).toHaveURL(/user-order\?from=new-login#orders$/)
    await expect(page.getByText('只读访问', { exact: true })).toBeVisible()
    expect(await page.evaluate(() => localStorage.getItem('admin-token'))).toBe(NEW_TOKEN)
    expect(await page.evaluate(async () => {
      const path = '/src/store/menu.ts'
      return (await import(path)).useMenuStore().routesRegistered
    })).toBe(true)
  })
}

for (const code of [0, 50000]) {
  test(`当前退出请求 code=${code} 都完成本地退出并回到登录`, async ({ page }) => {
    await loginFixtures(page)
    await page.route('**/api/auth/logout', route => route.fulfill({ json: { code, message: '退出响应', data: null } }))
    await page.goto('/ticket/user-order')
    await page.getByLabel('打开用户菜单', { exact: true }).click()
    await page.getByRole('menuitem', { name: '退出登录' }).click()
    await expect(page).toHaveURL(/\/login$/)
    expect(await page.evaluate(() => localStorage.getItem('admin-token'))).toBeNull()
    expect(await page.evaluate(async () => {
      const path = '/src/store/menu.ts'
      return (await import(path)).useMenuStore().routesRegistered
    })).toBe(false)
  })
}

test('当前初始化要求改密时保留凭据，进入最小改密态且刷新后不丢失', async ({ page }) => {
  const held = await startOldBootstrap(page, 'permissions')
  await held.fulfill({ json: { code: 20002, message: '需要修改密码', data: null } })
  await expect(page).toHaveURL(/\/change-password$/)
  expect(await page.evaluate(() => localStorage.getItem('admin-token'))).toBe(OLD_TOKEN)
  expect(await page.evaluate(() => localStorage.getItem('admin-force-change-password'))).toBe('true')
  await page.reload()
  await expect(page).toHaveURL(/\/change-password$/)
  expect(await page.evaluate(() => localStorage.getItem('admin-token'))).toBe(OLD_TOKEN)
})
