import { expect, test, type Page } from '@playwright/test'
import type { LoginResponse, RegisterOptionsVO } from '../../src/types/api'
import { LOGIN_E2E_ORIGIN } from './loginTestEnvironment'

const LOGIN_PATH = '/login?redirect=/home'
const LOGIN_IMAGES_API = `${LOGIN_E2E_ORIGIN}/api/login-images/list`
const REGISTER_OPTIONS_API = `${LOGIN_E2E_ORIGIN}/api/auth/register-options`
const CAPTCHA_API = `${LOGIN_E2E_ORIGIN}/api/auth/captcha`
const EMAIL_CODE_API = `${LOGIN_E2E_ORIGIN}/api/auth/email-code`
const REGISTER_API = `${LOGIN_E2E_ORIGIN}/api/auth/register`
const LOCAL_LOGIN_API = `${LOGIN_E2E_ORIGIN}/api/auth/login`
const SSO_LOGIN_API = `${LOGIN_E2E_ORIGIN}/api/auth/sso-login`
const PERMISSIONS_API = `${LOGIN_E2E_ORIGIN}/api/auth/permissions`
const MENU_ROUTES_API = `${LOGIN_E2E_ORIGIN}/api/menu/routes`
const MENU_VERSION_API = `${LOGIN_E2E_ORIGIN}/api/menu/version`
const CURRENT_TENANT_VIEW_API = `${LOGIN_E2E_ORIGIN}/api/tenant/current-view`
const UNREAD_MESSAGE_COUNT_API = `${LOGIN_E2E_ORIGIN}/api/message/unread-count`
const TEST_BUSINESS_REJECTION_CODE = 40000
const RESOURCE_DUPLICATE_CODE = 30004
const PASSWORD_TOO_WEAK_CODE = 30011
const EMAIL_CODE_INVALID_CODE = 30012
const EMAIL_CODE_REISSUE_REQUIRED_CODE = 30013
const TEST_CAPTCHA_IMAGE = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9WnWQAAAAASUVORK5CYII='

const REGISTER_OPTIONS: RegisterOptionsVO = {
  selfServiceEnabled: true,
  captchaRequired: false,
  emailRequired: false,
  emailVerificationRequired: false,
  emailCodeCooldownSeconds: 60,
}

interface PendingLoginRequest {
  url: string
  body: Record<string, unknown>
  release: () => void
}

function successResult<T>(data: T) {
  return { code: 0, message: 'success', data }
}

async function mockLoginBootstrap(page: Page, options: RegisterOptionsVO = REGISTER_OPTIONS) {
  // 只拦截浏览器真正访问的后端 URL；不能使用 **/api/**，否则会误伤 Vite 的 /src/api/*.ts。
  await page.route(LOGIN_IMAGES_API, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(successResult([])),
    })
  })
  await page.route(REGISTER_OPTIONS_API, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(successResult(options)),
    })
  })
}

async function mockAuthenticatedBootstrap(page: Page, onRequest?: (url: string) => void) {
  const endpoints: ReadonlyArray<readonly [string, unknown]> = [
    [PERMISSIONS_API, []],
    [MENU_ROUTES_API, []],
    [MENU_VERSION_API, 1],
  ]
  for (const [url, data] of endpoints) {
    await page.route(url, async (route) => {
      onRequest?.(route.request().url())
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(successResult(data)),
      })
    })
  }
  // /home 布局挂载后会精确读取这两个顶栏状态；避免 E2E 意外访问本机 8082 后端。
  await page.route(CURRENT_TENANT_VIEW_API, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(successResult({
        userTenantId: 'default',
        effectiveTenantId: 'default',
        crossTenantAuthority: false,
      })),
    })
  })
  await page.route(UNREAD_MESSAGE_COUNT_API, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(successResult(0)),
    })
  })
}

function successfulLoginResult(forceChangePassword = false): LoginResponse {
  return {
    token: forceChangePassword ? 'force-change-token' : 'local-login-token',
    nickname: 'Richard',
    forceChangePassword,
    approvalStatus: 'APPROVED',
    approvalRemark: null,
  }
}

async function captureRejectedLogin(page: Page, url: string): Promise<{
  requestCaptured: Promise<PendingLoginRequest>
}> {
  let captureRequest!: (request: PendingLoginRequest) => void
  const requestCaptured = new Promise<PendingLoginRequest>((resolve) => {
    captureRequest = resolve
  })

  await page.route(url, async (route) => {
    let releaseResponse!: () => void
    const responseReleased = new Promise<void>((resolve) => {
      releaseResponse = resolve
    })
    captureRequest({
      url: route.request().url(),
      body: route.request().postDataJSON() as Record<string, unknown>,
      release: releaseResponse,
    })
    await responseReleased
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        code: TEST_BUSINESS_REJECTION_CODE,
        message: 'E2E 登录分流验证',
        data: null,
      }),
    })
  })

  // 包一层对象，避免 async 返回值把内部 Promise 自动展开并在请求发出前形成死锁。
  return { requestCaptured }
}

async function suppressExpectedLoginRejection(page: Page) {
  await page.addInitScript((expectedCode) => {
    window.addEventListener('unhandledrejection', (event) => {
      const reason = event.reason as { code?: unknown } | null
      if (reason?.code === expectedCode) {
        event.preventDefault()
        event.stopImmediatePropagation()
      }
    })
  }, TEST_BUSINESS_REJECTION_CODE)
}

async function openLogin(page: Page) {
  await mockLoginBootstrap(page)
  await page.goto(LOGIN_PATH, { waitUntil: 'domcontentloaded' })
  await expect(page.getByRole('heading', { name: '欢迎回来' })).toBeVisible()
  await expect(page.getByRole('button', { name: '创建账号' })).toBeVisible()
}

async function documentScrollTop(page: Page) {
  return page.evaluate(() => document.scrollingElement?.scrollTop ?? 0)
}

async function scrollDocumentToBottom(page: Page) {
  return page.evaluate(() => {
    const scroller = document.scrollingElement
    if (!scroller) {
      return 0
    }
    scroller.scrollTop = scroller.scrollHeight
    return scroller.scrollTop
  })
}

async function expectDocumentAtTop(page: Page) {
  await expect.poll(() => documentScrollTop(page)).toBe(0)
}

async function expectAuthSurfaceFits(page: Page, viewportWidth: number) {
  const authSurface = page.locator('[data-login-scroll]')
  const layout = await authSurface.evaluate((element) => ({
    clientHeight: element.clientHeight,
    scrollHeight: element.scrollHeight,
    overflowY: getComputedStyle(element).overflowY,
  }))

  expect(layout.overflowY).toBe('auto')
  expect(layout.scrollHeight).toBeLessThanOrEqual(layout.clientHeight + 1)
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(viewportWidth)
}

async function fillRegisterAccountStep(page: Page) {
  await page.locator('#register-username').fill('richard.ui')
  await page.locator('#register-nickname').fill('Richard')
  await page.locator('#register-email').fill('richard@example.com')
}

async function openRegisterSecurityStep(page: Page, options: RegisterOptionsVO) {
  await mockLoginBootstrap(page, options)
  await page.goto(LOGIN_PATH, { waitUntil: 'domcontentloaded' })
  await page.getByRole('button', { name: '创建账号' }).click()
  await fillRegisterAccountStep(page)
  await page.getByRole('button', { name: '下一步' }).click()
  await expect(page.locator('#register-password')).toBeVisible()
}

interface EmailRegistrationHarness {
  emailCodeRequestCount: number
  registrationPayloads: Record<string, unknown>[]
}

async function prepareEmailCodeOnlyRegistration(
  page: Page,
  responseForAttempt: (attempt: number) => unknown,
): Promise<EmailRegistrationHarness> {
  const harness: EmailRegistrationHarness = {
    emailCodeRequestCount: 0,
    registrationPayloads: [],
  }
  await page.route(EMAIL_CODE_API, async (route) => {
    harness.emailCodeRequestCount += 1
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(successResult(300)),
    })
  })
  await page.route(REGISTER_API, async (route) => {
    harness.registrationPayloads.push(route.request().postDataJSON() as Record<string, unknown>)
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(responseForAttempt(harness.registrationPayloads.length)),
    })
  })
  await openRegisterSecurityStep(page, {
    selfServiceEnabled: true,
    captchaRequired: false,
    emailRequired: true,
    emailVerificationRequired: true,
    emailCodeCooldownSeconds: 60,
  })
  await page.getByRole('button', { name: '发送验证码' }).click()
  await expect(page.locator('#register-email-code')).toBeEnabled()
  await page.locator('#register-password').fill('Password123')
  await page.locator('#register-confirm-password').fill('Password123')
  return harness
}

test.describe('桌面登录布局', () => {
  test.use({ viewport: { width: 1280, height: 720 } })

  test('主操作无需滚动，键盘可切换模式，登录请求严格按身份分流', async ({ page }) => {
    // 业务失败是本用例刻意构造的终态，只抑制对应测试错误，避免 Vite 把含虚拟密码的表单快照写进 CI 日志。
    await suppressExpectedLoginRejection(page)
    await openLogin(page)

    await expect(page.getByRole('button', { name: '进入运营台' })).toBeInViewport()
    await expect(page.getByRole('button', { name: '创建账号' })).toBeInViewport()
    await expect(page.getByText('© customer_work · owlzhangfq@gmail.com')).toBeInViewport()
    await expectAuthSurfaceFits(page, 1280)

    const localTab = page.getByRole('tab', { name: '本地账号' })
    const ssoTab = page.getByRole('tab', { name: 'OA 账号' })
    await localTab.focus()
    await page.keyboard.press('ArrowRight')
    await expect(ssoTab).toBeFocused()
    await expect(ssoTab).toHaveAttribute('aria-selected', 'true')
    await expect(page.getByRole('heading', { name: 'OA 账号登录' })).toBeVisible()
    await expect(page.getByRole('button', { name: '创建账号' })).toHaveCount(0)

    await page.keyboard.press('Home')
    await expect(localTab).toBeFocused()
    await expect(localTab).toHaveAttribute('aria-selected', 'true')

    const localCapture = await captureRejectedLogin(page, LOCAL_LOGIN_API)
    await page.locator('#login-username').fill('local.richard')
    await page.locator('#login-password').fill('Local1234')
    await page.getByRole('checkbox', { name: '保持登录' })
      .evaluate((element: HTMLInputElement) => element.click())
    await expect(page.getByRole('checkbox', { name: '保持登录' })).toBeChecked()
    await page.getByRole('button', { name: '进入运营台' }).click()

    const localRequest = await localCapture.requestCaptured
    try {
      expect(localRequest.url).toBe(LOCAL_LOGIN_API)
      expect(localRequest.body).toMatchObject({ username: 'local.richard', rememberMe: true })
      await expect(localTab).toBeDisabled()
      await expect(ssoTab).toBeDisabled()
    } finally {
      localRequest.release()
    }
    await expect(localTab).toBeEnabled()
    await expect(page).toHaveURL(`${LOGIN_E2E_ORIGIN}${LOGIN_PATH}`)

    await ssoTab.click()
    const ssoCapture = await captureRejectedLogin(page, SSO_LOGIN_API)
    await page.locator('#login-username').fill('oa.richard')
    await page.locator('#login-password').fill('Sso12345')
    await expect(page.getByRole('checkbox', { name: '保持登录' })).not.toBeChecked()
    await page.getByRole('button', { name: '使用 OA 账号登录' }).click()

    const ssoRequest = await ssoCapture.requestCaptured
    try {
      expect(ssoRequest.url).toBe(SSO_LOGIN_API)
      expect(ssoRequest.body).toMatchObject({ username: 'oa.richard', rememberMe: false })
      await expect(localTab).toBeDisabled()
      await expect(ssoTab).toBeDisabled()
    } finally {
      ssoRequest.release()
    }
    await expect(ssoTab).toBeEnabled()
    await expect(page).toHaveURL(`${LOGIN_E2E_ORIGIN}${LOGIN_PATH}`)
  })
})

test.describe('登录成功状态机', () => {
  test.use({ viewport: { width: 1280, height: 720 } })

  test('本地普通登录持久化完整状态、记住用户名并完成 redirect=/home', async ({ page }) => {
    const bootstrapRequests: string[] = []
    let loginPayload: Record<string, unknown> | undefined

    await mockAuthenticatedBootstrap(page, (url) => bootstrapRequests.push(url))
    await page.route(LOCAL_LOGIN_API, async (route) => {
      loginPayload = route.request().postDataJSON() as Record<string, unknown>
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(successResult(successfulLoginResult())),
      })
    })
    await openLogin(page)

    await page.locator('#login-username').fill('local.richard')
    await page.locator('#login-password').fill('Local1234')
    await page.getByRole('checkbox', { name: '保持登录' })
      .evaluate((element: HTMLInputElement) => element.click())
    await page.getByRole('button', { name: '进入运营台' }).click()

    await expect(page).toHaveURL(`${LOGIN_E2E_ORIGIN}/home`)
    expect(loginPayload).toEqual({
      username: 'local.richard',
      password: 'Local1234',
      rememberMe: true,
    })
    expect(bootstrapRequests).toEqual([
      PERMISSIONS_API,
      MENU_ROUTES_API,
      MENU_VERSION_API,
    ])
    expect(await page.evaluate(() => ({
      token: localStorage.getItem('admin-token'),
      nickname: localStorage.getItem('admin-nickname'),
      username: localStorage.getItem('admin-username'),
      forceChangePassword: localStorage.getItem('admin-force-change-password'),
      approvalStatus: localStorage.getItem('admin-approval-status'),
      approvalRemark: localStorage.getItem('admin-approval-remark'),
      rememberedUsername: localStorage.getItem('admin-remember-username-local'),
      rememberedSsoUsername: localStorage.getItem('admin-remember-username-sso'),
    }))).toEqual({
      token: 'local-login-token',
      nickname: 'Richard',
      username: 'local.richard',
      forceChangePassword: 'false',
      approvalStatus: 'APPROVED',
      approvalRemark: null,
      rememberedUsername: 'local.richard',
      rememberedSsoUsername: null,
    })
  })

  test('forceChangePassword 强制进入改密页且不启动菜单 bootstrap', async ({ page }) => {
    const bootstrapRequests: string[] = []

    await mockAuthenticatedBootstrap(page, (url) => bootstrapRequests.push(url))
    await page.route(LOCAL_LOGIN_API, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(successResult(successfulLoginResult(true))),
      })
    })
    await openLogin(page)

    await page.locator('#login-username').fill('first.login')
    await page.locator('#login-password').fill('Local1234')
    await page.getByRole('button', { name: '进入运营台' }).click()

    await expect(page).toHaveURL(`${LOGIN_E2E_ORIGIN}/change-password`)
    await expect(page.getByText('首次登录请修改密码', { exact: true })).toBeVisible()
    expect(bootstrapRequests).toEqual([])
    expect(await page.evaluate(() => ({
      token: localStorage.getItem('admin-token'),
      username: localStorage.getItem('admin-username'),
      forceChangePassword: localStorage.getItem('admin-force-change-password'),
      rememberedUsername: localStorage.getItem('admin-remember-username-local'),
    }))).toEqual({
      token: 'force-change-token',
      username: 'first.login',
      forceChangePassword: 'true',
      rememberedUsername: null,
    })
  })
})

test.describe('原始紧凑视口', () => {
  test.use({ viewport: { width: 916, height: 664 } })

  test('登录与两步注册的主操作无需内部滚动即可使用', async ({ page }) => {
    await openLogin(page)

    await expect(page.getByRole('button', { name: '进入运营台' })).toBeInViewport()
    await expect(page.getByRole('button', { name: '创建账号' })).toBeInViewport()
    await expect(page.getByText('© customer_work · owlzhangfq@gmail.com')).toBeInViewport()
    await expectAuthSurfaceFits(page, 916)

    await page.getByRole('button', { name: '创建账号' }).click()
    await expect(page.getByRole('heading', { name: '加入智能体运营台' })).toBeVisible()
    await expect(page.getByRole('button', { name: '返回登录' })).toBeInViewport()
    await expect(page.getByRole('button', { name: '下一步' })).toBeInViewport()
    await expectAuthSurfaceFits(page, 916)

    await fillRegisterAccountStep(page)
    await page.getByRole('button', { name: '下一步' }).click()
    await expect(page.locator('#register-password')).toBeVisible()
    await expect(page.getByRole('button', { name: '上一步' })).toBeInViewport()
    await expect(page.getByRole('button', { name: '提交注册' })).toBeInViewport()
    await expectAuthSurfaceFits(page, 916)
  })
})

test.describe('邮箱验证码异步状态', () => {
  test.use({ viewport: { width: 1280, height: 720 } })

  test('邮箱与图形码完整注册只提交 emailCode，成功后返回登录并预填账号', async ({ page }) => {
    let captchaRequestCount = 0
    let emailCodePayload: Record<string, unknown> | undefined
    let registrationPayload: Record<string, unknown> | undefined

    await page.route(CAPTCHA_API, async (route) => {
      captchaRequestCount += 1
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(successResult({
          captchaId: `captcha-${captchaRequestCount}`,
          image: TEST_CAPTCHA_IMAGE,
          ttlSeconds: 300,
        })),
      })
    })
    await page.route(EMAIL_CODE_API, async (route) => {
      emailCodePayload = route.request().postDataJSON() as Record<string, unknown>
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(successResult(300)),
      })
    })
    await page.route(REGISTER_API, async (route) => {
      registrationPayload = route.request().postDataJSON() as Record<string, unknown>
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(successResult(null)),
      })
    })
    await openRegisterSecurityStep(page, {
      selfServiceEnabled: true,
      captchaRequired: true,
      emailRequired: true,
      emailVerificationRequired: true,
      emailCodeCooldownSeconds: 37,
    })

    await expect(page.getByRole('button', { name: '刷新图形验证码' })).toBeVisible()
    await page.locator('#register-captcha').fill('AB12')
    await page.getByRole('button', { name: '发送验证码' }).click()

    await expect(page.getByRole('button', { name: /^(37|36) 秒后重发$/ })).toBeDisabled()
    await expect(page.locator('#register-email-code')).toBeEnabled()
    await expect(page.locator('#register-password')).toBeEnabled()
    await expect(page.getByRole('button', { name: '提交注册' })).toBeEnabled()
    await expect(page.getByRole('button', { name: '获取验证码' })).toBeVisible()
    await page.locator('#register-email-code').fill('123456')
    await page.locator('#register-password').fill('Password123')
    await page.locator('#register-confirm-password').fill('Password123')
    await page.getByRole('button', { name: '提交注册' }).click()

    await expect(page.getByRole('heading', { name: '注册成功，richard.ui' })).toBeVisible()
    expect(emailCodePayload).toEqual({
      email: 'richard@example.com',
      captchaId: 'captcha-1',
      captcha: 'AB12',
    })
    expect(registrationPayload).toEqual({
      username: 'richard.ui',
      nickname: 'Richard',
      email: 'richard@example.com',
      password: 'Password123',
      confirmPassword: 'Password123',
      emailCode: '123456',
    })
    expect(registrationPayload).not.toHaveProperty('captcha')
    expect(registrationPayload).not.toHaveProperty('captchaId')
    expect(captchaRequestCount).toBe(1)

    await page.getByRole('button', { name: '返回登录' }).click()
    await expect(page.getByRole('heading', { name: '欢迎回来' })).toBeVisible()
    await expect(page.locator('#login-username')).toHaveValue('richard.ui')
    await expect(page.locator('#login-password')).toHaveValue('')
    await expect(page.getByRole('checkbox', { name: '保持登录' })).not.toBeChecked()
  })

  test('发码失败清空已消费挑战，邮箱变化撤销旧邮箱的验证码状态', async ({ page }) => {
    let captchaRequestCount = 0
    const emailCodePayloads: Record<string, unknown>[] = []

    await page.route(CAPTCHA_API, async (route) => {
      captchaRequestCount += 1
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(successResult({
          captchaId: `captcha-${captchaRequestCount}`,
          image: TEST_CAPTCHA_IMAGE,
          ttlSeconds: 300,
        })),
      })
    })
    await page.route(EMAIL_CODE_API, async (route) => {
      emailCodePayloads.push(route.request().postDataJSON() as Record<string, unknown>)
      const response = emailCodePayloads.length === 1
        ? { code: TEST_BUSINESS_REJECTION_CODE, message: 'E2E 发码失败', data: null }
        : successResult(300)
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(response),
      })
    })
    await openRegisterSecurityStep(page, {
      selfServiceEnabled: true,
      captchaRequired: true,
      emailRequired: true,
      emailVerificationRequired: true,
      emailCodeCooldownSeconds: 37,
    })

    await expect(page.getByRole('button', { name: '刷新图形验证码' })).toBeVisible()
    await page.locator('#register-captcha').fill('AB12')
    await page.getByRole('button', { name: '发送验证码' }).click()

    await expect(page.getByText('E2E 发码失败', { exact: true })).toBeVisible()
    await expect(page.locator('#register-captcha')).toHaveValue('')
    await expect(page.locator('#register-email-code')).toHaveValue('')
    await expect(page.getByRole('button', { name: '获取验证码' })).toBeVisible()
    await expect(page.getByRole('button', { name: '发送验证码' })).toBeEnabled()

    await page.getByRole('button', { name: '获取验证码' }).click()
    await expect(page.getByRole('button', { name: '刷新图形验证码' })).toBeVisible()
    await page.locator('#register-captcha').fill('CD34')
    await page.getByRole('button', { name: '发送验证码' }).click()
    await expect(page.getByRole('button', { name: /^(37|36) 秒后重发$/ })).toBeDisabled()
    await page.locator('#register-email-code').fill('123456')

    await page.getByRole('button', { name: '上一步' }).click()
    await page.locator('#register-email').fill('changed@example.com')
    await page.getByRole('button', { name: '下一步' }).click()

    await expect(page.locator('#register-email-code')).toHaveValue('')
    await expect(page.getByRole('button', { name: '发送验证码' })).toBeEnabled()
    await expect(page.getByRole('button', { name: '获取验证码' })).toBeVisible()
    expect(emailCodePayloads).toEqual([
      {
        email: 'richard@example.com',
        captchaId: 'captcha-1',
        captcha: 'AB12',
      },
      {
        email: 'richard@example.com',
        captchaId: 'captcha-2',
        captcha: 'CD34',
      },
    ])
  })
})

test.describe('注册失败恢复', () => {
  test.use({ viewport: { width: 1280, height: 720 } })

  test('邮箱验证码注册业务失败后清空一次性凭据，并可重新发码成功重试', async ({ page }) => {
    let captchaRequestCount = 0
    let emailCodeRequestCount = 0
    const registrationPayloads: Record<string, unknown>[] = []

    await page.route(CAPTCHA_API, async (route) => {
      captchaRequestCount += 1
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(successResult({
          captchaId: `captcha-${captchaRequestCount}`,
          image: TEST_CAPTCHA_IMAGE,
          ttlSeconds: 300,
        })),
      })
    })
    await page.route(EMAIL_CODE_API, async (route) => {
      emailCodeRequestCount += 1
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(successResult(300)),
      })
    })
    await page.route(REGISTER_API, async (route) => {
      registrationPayloads.push(route.request().postDataJSON() as Record<string, unknown>)
      const response = registrationPayloads.length === 1
        ? { code: RESOURCE_DUPLICATE_CODE, message: 'E2E 用户名已存在', data: null }
        : successResult(null)
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(response),
      })
    })
    await openRegisterSecurityStep(page, {
      selfServiceEnabled: true,
      captchaRequired: true,
      emailRequired: true,
      emailVerificationRequired: true,
      emailCodeCooldownSeconds: 2,
    })

    await page.locator('#register-captcha').fill('OLD1')
    await page.getByRole('button', { name: '发送验证码' }).click()
    await expect(page.getByRole('button', { name: /^(2|1) 秒后重发$/ })).toBeDisabled()
    await page.locator('#register-email-code').fill('111111')
    await page.locator('#register-password').fill('Password123')
    await page.locator('#register-confirm-password').fill('Password123')
    await page.getByRole('button', { name: '提交注册' }).click()

    await expect(page.getByText('E2E 用户名已存在', { exact: true })).toBeVisible()
    await expect(page.locator('#register-email-code')).toHaveValue('')
    await expect(page.getByRole('button', { name: /^(2|1) 秒后重发$/ })).toBeDisabled()
    await expect(page.getByRole('button', { name: '获取验证码' })).toBeVisible()
    expect(registrationPayloads).toHaveLength(1)
    expect(registrationPayloads[0]).toMatchObject({ emailCode: '111111' })

    await page.getByRole('button', { name: '获取验证码' }).click()
    await expect(page.getByRole('button', { name: '刷新图形验证码' })).toBeVisible()
    await page.locator('#register-captcha').fill('NEW2')
    await expect(page.getByRole('button', { name: '发送验证码' })).toBeEnabled({ timeout: 3_500 })
    await page.getByRole('button', { name: '发送验证码' }).click()
    await expect(page.getByRole('button', { name: /^(2|1) 秒后重发$/ })).toBeDisabled()
    await page.locator('#register-email-code').fill('222222')
    await page.getByRole('button', { name: '提交注册' }).click()

    await expect(page.getByRole('heading', { name: '注册成功，richard.ui' })).toBeVisible()
    expect(registrationPayloads).toHaveLength(2)
    expect(registrationPayloads[1]).toMatchObject({ emailCode: '222222' })
    expect(registrationPayloads[1]).not.toHaveProperty('captcha')
    expect(registrationPayloads[1]).not.toHaveProperty('captchaId')
    expect(captchaRequestCount).toBe(2)
    expect(emailCodeRequestCount).toBe(2)
  })

  test('邮箱验证码普通输错后保留凭据状态，可直接改正并重试', async ({ page }) => {
    const harness = await prepareEmailCodeOnlyRegistration(page, (attempt) => (
      attempt === 1
        ? { code: EMAIL_CODE_INVALID_CODE, message: '邮箱验证码错误', data: null }
        : successResult(null)
    ))

    await page.locator('#register-email-code').fill('000000')
    await page.getByRole('button', { name: '提交注册' }).click()

    await expect(page.getByText('邮箱验证码错误', { exact: true })).toBeVisible()
    await expect(page.locator('#register-email-code')).toHaveValue('000000')
    await page.locator('#register-email-code').fill('123456')
    await page.getByRole('button', { name: '提交注册' }).click()

    await expect(page.getByRole('heading', { name: '注册成功，richard.ui' })).toBeVisible()
    expect(harness.emailCodeRequestCount).toBe(1)
    expect(harness.registrationPayloads.map((payload) => payload.emailCode)).toEqual(['000000', '123456'])
  })

  test('邮箱验证码失效错误码清空一次性凭据，并保留服务端冷却', async ({ page }) => {
    const harness = await prepareEmailCodeOnlyRegistration(page, () => ({
      code: EMAIL_CODE_REISSUE_REQUIRED_CODE,
      message: '邮箱验证码已失效，请重新获取',
      data: null,
    }))

    await page.locator('#register-email-code').fill('123456')
    await page.getByRole('button', { name: '提交注册' }).click()

    await expect(page.getByText('邮箱验证码已失效，请重新获取', { exact: true })).toBeVisible()
    await expect(page.locator('#register-email-code')).toHaveValue('')
    await expect(page.getByRole('button', { name: /^(60|59) 秒后重发$/ })).toBeDisabled()
    expect(harness.emailCodeRequestCount).toBe(1)
    expect(harness.registrationPayloads).toHaveLength(1)
  })

  test('服务端弱口令拒绝发生在邮箱码核验前，可修改密码并复用原验证码', async ({ page }) => {
    const harness = await prepareEmailCodeOnlyRegistration(page, (attempt) => (
      attempt === 1
        ? { code: PASSWORD_TOO_WEAK_CODE, message: 'E2E 弱口令黑名单', data: null }
        : successResult(null)
    ))

    await page.locator('#register-email-code').fill('123456')
    await page.getByRole('button', { name: '提交注册' }).click()

    await expect(page.getByText('E2E 弱口令黑名单', { exact: true })).toBeVisible()
    await expect(page.locator('#register-email-code')).toHaveValue('123456')
    await page.locator('#register-password').fill('SaferPassword456')
    await page.locator('#register-confirm-password').fill('SaferPassword456')
    await page.getByRole('button', { name: '提交注册' }).click()

    await expect(page.getByRole('heading', { name: '注册成功，richard.ui' })).toBeVisible()
    expect(harness.emailCodeRequestCount).toBe(1)
    expect(harness.registrationPayloads).toHaveLength(2)
    expect(harness.registrationPayloads[1]).toMatchObject({
      password: 'SaferPassword456',
      confirmPassword: 'SaferPassword456',
      emailCode: '123456',
    })
  })

  test('纯图形码注册业务失败后清空挑战，并仅允许新挑战参与重试', async ({ page }) => {
    let captchaRequestCount = 0
    const registrationPayloads: Record<string, unknown>[] = []

    await page.route(CAPTCHA_API, async (route) => {
      captchaRequestCount += 1
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(successResult({
          captchaId: `captcha-${captchaRequestCount}`,
          image: TEST_CAPTCHA_IMAGE,
          ttlSeconds: 300,
        })),
      })
    })
    await page.route(REGISTER_API, async (route) => {
      registrationPayloads.push(route.request().postDataJSON() as Record<string, unknown>)
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: TEST_BUSINESS_REJECTION_CODE,
          message: 'E2E 注册业务失败',
          data: null,
        }),
      })
    })
    await openRegisterSecurityStep(page, {
      selfServiceEnabled: true,
      captchaRequired: true,
      emailRequired: false,
      emailVerificationRequired: false,
      emailCodeCooldownSeconds: 60,
    })

    await expect(page.getByRole('button', { name: '刷新图形验证码' })).toBeVisible()
    await page.locator('#register-captcha').fill('OLD1')
    await page.locator('#register-password').fill('Password123')
    await page.locator('#register-confirm-password').fill('Password123')
    await page.getByRole('button', { name: '提交注册' }).click()

    await expect(page.getByText('E2E 注册业务失败', { exact: true })).toBeVisible()
    await expect(page.locator('#register-captcha')).toHaveValue('')
    await expect(page.getByRole('button', { name: '获取验证码' })).toBeVisible()
    expect(registrationPayloads).toHaveLength(1)
    expect(registrationPayloads[0]).toMatchObject({
      captchaId: 'captcha-1',
      captcha: 'OLD1',
    })

    await page.getByRole('button', { name: '获取验证码' }).click()
    await expect(page.getByRole('button', { name: '刷新图形验证码' })).toBeVisible()
    await page.locator('#register-captcha').fill('NEW2')
    await page.getByRole('button', { name: '提交注册' }).click()

    await expect.poll(() => registrationPayloads.length).toBe(2)
    expect(registrationPayloads[1]).toMatchObject({
      captchaId: 'captcha-2',
      captcha: 'NEW2',
    })
    expect(registrationPayloads[1]).not.toHaveProperty('emailCode')
    expect(captchaRequestCount).toBe(2)
  })
})

test.describe('注册入口能力', () => {
  test.use({ viewport: { width: 1280, height: 720 } })

  test('注册选项业务失败时 fail-closed，仍保留本地登录能力', async ({ page }) => {
    await page.route(LOGIN_IMAGES_API, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(successResult([])),
      })
    })
    await page.route(REGISTER_OPTIONS_API, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: TEST_BUSINESS_REJECTION_CODE,
          message: 'E2E 注册能力不可用',
          data: null,
        }),
      })
    })

    await page.goto(LOGIN_PATH, { waitUntil: 'domcontentloaded' })

    await expect(page.getByText('E2E 注册能力不可用', { exact: true })).toBeVisible()
    await expect(page.getByRole('button', { name: '创建账号' })).toHaveCount(0)
    await expect(page.getByRole('heading', { name: '欢迎回来' })).toBeVisible()
    await expect(page.getByRole('button', { name: '进入运营台' })).toBeEnabled()
  })
})

test.describe('移动端整页滚动', () => {
  test.use({ viewport: { width: 390, height: 720 } })

  test('模式与注册步骤切换回顶，且不产生嵌套或横向滚动', async ({ page }) => {
    await openLogin(page)

    const authSurface = page.locator('[data-login-scroll]')
    expect(await authSurface.evaluate((element) => getComputedStyle(element).overflowY)).toBe('visible')
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390)

    const initialBottom = await scrollDocumentToBottom(page)
    expect(initialBottom).toBeGreaterThan(0)
    await page.locator('#login-mode-sso').evaluate((element: HTMLButtonElement) => element.click())
    await expect(page.getByRole('heading', { name: 'OA 账号登录' })).toBeVisible()
    await expectDocumentAtTop(page)

    await page.locator('#login-mode-local').evaluate((element: HTMLButtonElement) => element.click())
    await expect(page.getByRole('heading', { name: '欢迎回来' })).toBeVisible()
    await page.getByRole('button', { name: '创建账号' }).click()
    await expect(page.getByRole('heading', { name: '加入智能体运营台' })).toBeVisible()
    await expectDocumentAtTop(page)

    await fillRegisterAccountStep(page)

    const accountStepBottom = await scrollDocumentToBottom(page)
    expect(accountStepBottom).toBeGreaterThan(0)
    await page.getByRole('button', { name: '下一步' }).evaluate((element: HTMLButtonElement) => element.click())
    await expect(page.locator('#register-password')).toBeVisible()
    await expectDocumentAtTop(page)

    const securityStepBottom = await scrollDocumentToBottom(page)
    expect(securityStepBottom).toBeGreaterThan(0)
    await page.getByRole('button', { name: '上一步' }).evaluate((element: HTMLButtonElement) => element.click())
    await expect(page.locator('#register-username')).toBeVisible()
    await expectDocumentAtTop(page)

    expect(await authSurface.evaluate((element) => element.scrollTop)).toBe(0)
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390)
  })
})
