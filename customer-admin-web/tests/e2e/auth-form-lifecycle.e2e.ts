import type { Page, Route } from '@playwright/test'
import { expect, test } from './fixtures/adminTestFixture'

const ok = (data: unknown) => ({ code: 0, data })
const NEW_TOKEN = 'auth-form-new-login'
const image = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9WnWQAAAAASUVORK5CYII='

async function loginFixtures(page: Page) {
  await page.route('**/api/login-images/list', route => route.fulfill({ json: ok([]) }))
  await page.route('**/api/auth/register-options', route => route.fulfill({ json: ok({ selfServiceEnabled: false }) }))
  await page.route('**/api/auth/login-captcha/challenge', route => route.fulfill({ json: ok({
    challengeId: 'auth-form-challenge', ttlSeconds: 120, backgroundImage: image, puzzlePieceImage: image,
    canvasWidth: 320, canvasHeight: 160, pieceWidth: 56, pieceHeight: 56, pieceY: 52,
  }) }))
  await page.route('**/api/auth/login-captcha/verify', route => route.fulfill({ json: ok({ proof: 'auth-form-proof', ttlSeconds: 120 }) }))
}

async function newLogin(page: Page, token = NEW_TOKEN, stayOnForm = false) {
  await page.evaluate(async ({ token, stayOnForm }) => {
    const authPath = '/src/store/auth.ts'
    const menuPath = '/src/store/menu.ts'
    const routerPath = '/src/router/index.ts'
    const { useAuthStore } = await import(authPath)
    const { useMenuStore } = await import(menuPath)
    const { default: router } = await import(routerPath)
    useMenuStore().reset()
    useAuthStore().applyLoginResult({ token, nickname: '新账号', forceChangePassword: false,
      approvalStatus: 'APPROVED', approvalRemark: null }, 'new-account')
    if (!stayOnForm) await router.replace('/ticket/user-order?from=current-login')
  }, { token, stayOnForm })
  if (!stayOnForm) await expect(page).toHaveURL(/user-order\?from=current-login$/)
}

for (const stayOnForm of [false, true]) {
  test(`旧改密成功响应不能清空后来登录的账号，保留表单=${stayOnForm}`, async ({ page }) => {
    await loginFixtures(page)
    let held: Route | undefined
    await page.route('**/api/auth/change-password', route => { held = route })
    await page.goto('/change-password')
    await page.getByLabel('原密码', { exact: true }).fill('original-password')
    await page.getByLabel('新密码', { exact: true }).fill('new-password-123')
    await page.getByLabel('确认密码', { exact: true }).fill('new-password-123')
    await page.getByRole('button', { name: '确认修改', exact: true }).click()
    await expect.poll(() => Boolean(held)).toBe(true)
    // 保留表单时故意复用同一 token，必须依赖登录代次而非仅比较 token 或组件是否卸载。
    const expectedToken = stayOnForm ? 'admin-shell-e2e-token' : NEW_TOKEN
    await newLogin(page, expectedToken, stayOnForm)
    const response = page.waitForResponse('**/api/auth/change-password')
    await held!.fulfill({ json: ok(null) })
    await (await response).finished()
    await page.waitForLoadState('networkidle')
    expect(await page.evaluate(() => localStorage.getItem('admin-token'))).toBe(expectedToken)
    await expect(page).toHaveURL(stayOnForm ? /\/change-password$/ : /user-order\?from=current-login$/)
    await expect(page.getByText('密码修改成功，请重新登录', { exact: true })).toHaveCount(0)
  })
}

for (const code of [0, 40000]) {
  test(`当前改密 code=${code} 保持正确结果与可恢复表单`, async ({ page }) => {
    await loginFixtures(page)
    await page.route('**/api/auth/change-password', route => route.fulfill({
      json: { code, message: '原密码不正确', data: null },
    }))
    await page.goto('/change-password')
    await page.getByLabel('原密码', { exact: true }).fill('original-password')
    await page.getByLabel('新密码', { exact: true }).fill('new-password-123')
    await page.getByLabel('确认密码', { exact: true }).fill('new-password-123')
    await page.getByRole('button', { name: '确认修改', exact: true }).click()
    if (code === 0) {
      await expect(page).toHaveURL(/\/login$/)
      expect(await page.evaluate(() => localStorage.getItem('admin-token'))).toBeNull()
      await expect(page.getByText('密码修改成功，请重新登录', { exact: true })).toBeVisible()
    } else {
      await expect(page.getByText('原密码不正确', { exact: true })).toBeVisible()
      await expect(page).toHaveURL(/\/change-password$/)
      await expect(page.getByLabel('新密码', { exact: true })).toHaveValue('new-password-123')
      await expect(page.getByRole('button', { name: '确认修改', exact: true })).toBeEnabled()
    }
  })
}

for (const mode of ['local', 'sso'] as const) {
  for (const stayOnForm of [false, true]) {
    test(`旧 ${mode} 登录成功响应不能替换新账号，保留表单=${stayOnForm}`, async ({ page }) => {
      await loginFixtures(page)
      let held: Route | undefined
      const path = mode === 'local' ? 'login' : 'sso-login'
      await page.route(`**/api/auth/${path}`, route => { held = route })
      await page.goto('/ticket/user-order')
      await page.evaluate(async () => {
        const authPath = '/src/store/auth.ts'
        const routerPath = '/src/router/index.ts'
        const { useAuthStore } = await import(authPath)
        const { default: router } = await import(routerPath)
        useAuthStore().clear()
        await router.replace('/login?redirect=/home')
      })
      if (mode === 'sso') await page.getByRole('tab', { name: 'OA 账号', exact: true }).click()
      await page.locator('#login-username').fill('old-account')
      await page.locator('#login-password').fill('old-password')
      await page.getByText('保持登录', { exact: true }).click()
      await expect(page.getByRole('checkbox', { name: '保持登录' })).toBeChecked()
      await page.locator('[data-login-captcha-entry]').click()
      const slider = page.getByRole('slider', { name: '拖动验证码' })
      await expect(slider).toHaveAttribute('aria-disabled', 'false')
      await slider.focus()
      await page.keyboard.press('End')
      await page.keyboard.press('Enter')
      await expect.poll(() => Boolean(held)).toBe(true)
      await newLogin(page, NEW_TOKEN, stayOnForm)
      const response = page.waitForResponse(`**/api/auth/${path}`)
      await held!.fulfill({ json: ok({ token: 'old-login-result', nickname: '旧账号', forceChangePassword: false,
        approvalStatus: 'APPROVED', approvalRemark: null }) })
      await (await response).finished()
      await page.waitForLoadState('networkidle')
      expect(await page.evaluate(() => localStorage.getItem('admin-token'))).toBe(NEW_TOKEN)
      await expect(page).toHaveURL(stayOnForm ? /\/login\?redirect=\/home$/ : /user-order\?from=current-login$/)
      expect(await page.evaluate(key => localStorage.getItem(key), `admin-remember-username-${mode}`)).toBeNull()
    })
  }
}
