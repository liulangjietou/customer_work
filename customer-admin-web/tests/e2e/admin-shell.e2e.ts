import { expect, test } from './fixtures/adminTestFixture'
import type { Page, Route } from '@playwright/test'
import { SQL_REPORT_MENU_TITLE, SQL_REPORT_PATH } from './fixtures/adminRoutes'

const TEST_CAPTCHA_IMAGE = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9WnWQAAAAASUVORK5CYII='

type RgbColor = readonly [number, number, number]

function parseCssColor(rawColor: string): RgbColor {
  const color = rawColor.trim().toLowerCase()
  const hex = color.match(/^#([0-9a-f]{3}|[0-9a-f]{6})$/i)?.[1]
  if (hex) {
    const expanded = hex.length === 3
      ? hex.split('').map((channel) => channel + channel).join('')
      : hex
    return [
      Number.parseInt(expanded.slice(0, 2), 16),
      Number.parseInt(expanded.slice(2, 4), 16),
      Number.parseInt(expanded.slice(4, 6), 16),
    ]
  }

  const rgb = color.match(/^rgba?\(\s*([\d.]+)[,\s]+([\d.]+)[,\s]+([\d.]+)/i)
  if (rgb) {
    return [Number(rgb[1]), Number(rgb[2]), Number(rgb[3])]
  }
  throw new Error(`Unsupported CSS color: ${rawColor}`)
}

function relativeLuminance(color: RgbColor): number {
  const [red, green, blue] = color.map((channel) => {
    const normalized = channel / 255
    return normalized <= 0.04045
      ? normalized / 12.92
      : ((normalized + 0.055) / 1.055) ** 2.4
  })
  return 0.2126 * red + 0.7152 * green + 0.0722 * blue
}

function contrastRatio(foreground: string, background: string): number {
  const foregroundLuminance = relativeLuminance(parseCssColor(foreground))
  const backgroundLuminance = relativeLuminance(parseCssColor(background))
  const lighter = Math.max(foregroundLuminance, backgroundLuminance)
  const darker = Math.min(foregroundLuminance, backgroundLuminance)
  return (lighter + 0.05) / (darker + 0.05)
}

function themePresetOption(page: Page, label: string) {
  return page.getByRole('option').filter({ hasText: label })
}

async function selectThemePreset(page: Page, label: string) {
  const trigger = page.getByLabel('选择界面主题', { exact: true })
  if (await trigger.getAttribute('aria-expanded') !== 'true') {
    await trigger.click()
  }
  const listbox = page.getByRole('listbox', { name: '界面主题' })
  await expect(listbox).toBeVisible()
  const option = themePresetOption(page, label)
  await expect(option).toBeVisible()
  await option.click()
  await expect(listbox).toBeHidden()
  await expect(trigger).toHaveAttribute('aria-expanded', 'false')
  await expect(trigger).toHaveAttribute('title', `当前主题：${label}`)
}

test.describe('后台壳层契约', () => {
  test('六段生命周期导航消费动态菜单，页面标题优先采用服务端菜单名', async ({ page }) => {
    await page.goto('/system/user', { waitUntil: 'domcontentloaded' })

    await expect(page.locator('.layout')).toBeVisible()
    const lifecycleNavigation = page.getByRole('navigation', { name: '智能体生命周期导航' })
    await expect(lifecycleNavigation).toBeVisible()
    await expect(lifecycleNavigation.getByRole('button')).toHaveCount(6)
    for (const section of ['总览', '智能体', '构建', '运营', '治理', '设置']) {
      await expect(lifecycleNavigation.getByRole('button', { name: section, exact: true })).toBeVisible()
    }

    await expect(lifecycleNavigation.getByRole('button', { name: '设置', exact: true }))
      .toHaveAttribute('aria-current', 'true')
    await expect(page.locator('#cw-page-title')).toHaveText('成员与身份（服务端菜单）')

    await lifecycleNavigation.getByRole('button', { name: '智能体', exact: true }).click()
    await expect(page.getByRole('menuitem', { name: 'Java 智能体', exact: true })).toBeVisible()
  })

  test('Home、Workspace 空态和动态 Workspace 都不重复渲染页面上下文头', async ({ page }) => {
    await page.goto('/home', { waitUntil: 'domcontentloaded' })
    await expect(page.locator('.layout-main--home')).toBeVisible()
    await expect(page.locator('.cw-page-context')).toHaveCount(0)

    await page.goto('/workspace', { waitUntil: 'domcontentloaded' })
    await expect(page.locator('.workspace-empty')).toBeVisible()
    await expect(page.locator('.cw-page-context')).toHaveCount(0)

    await page.goto('/workspace/java-assistant', { waitUntil: 'domcontentloaded' })
    await expect(page.locator('.workspace-view')).toBeVisible()
    await expect(page.locator('.cw-page-context')).toHaveCount(0)
  })

  for (const admissionCase of [
    {
      status: 'PENDING',
      statusText: '等待审核',
      title: '申请已经提交，工作台正在等待管理员确认。',
      remark: null,
    },
    {
      status: 'REJECTED',
      statusText: '审核未通过',
      title: '当前账号暂未获得工作台准入。',
      remark: '缺少可验证的准入材料，请补充后重新申请。',
    },
  ] as const) {
    test(`未审核准入 ${admissionCase.status} 阻断手工业务路由且不加载菜单与页面数据`, async ({ page }) => {
      await page.addInitScript(({ status, remark }) => {
        localStorage.setItem('admin-approval-status', status)
        if (remark) {
          localStorage.setItem('admin-approval-remark', remark)
        } else {
          localStorage.removeItem('admin-approval-remark')
        }
      }, { status: admissionCase.status, remark: admissionCase.remark })

      const requestedApiPaths: string[] = []
      page.on('request', (request) => {
        const url = new URL(request.url())
        if (url.pathname.startsWith('/api/')) {
          requestedApiPaths.push(url.pathname)
        }
      })

      await page.goto('/system/user', { waitUntil: 'domcontentloaded' })

      await expect(page).toHaveURL(/\/home$/)
      await expect(page.getByRole('heading', { name: admissionCase.title })).toBeVisible()
      await expect(page.getByText(admissionCase.statusText, { exact: true }).first()).toBeVisible()
      if (admissionCase.remark) {
        await expect(page.getByText(admissionCase.remark, { exact: true })).toBeVisible()
      }
      expect(requestedApiPaths.filter((path) => (
        path === '/api/menu/routes' || path === '/api/system/user'
      ))).toEqual([])
      for (const businessMenu of [
        '成员与身份（服务端菜单）',
        'Java 智能体',
        '模型管理',
        '调用统计',
      ]) {
        await expect(page.getByRole('menuitem', { name: businessMenu, exact: true })).toHaveCount(0)
      }
      await expect(page.locator('.quick-entry')).toHaveCount(0)
    })
  }

  test('SQL 报表首次进入与刷新后都保留 defineKey，并按完整菜单路径解析标题', async ({ page }) => {
    await page.goto(SQL_REPORT_PATH, { waitUntil: 'domcontentloaded' })

    await expect(page).toHaveURL(new RegExp(`${SQL_REPORT_PATH.replace('?', '\\?')}$`))
    await expect(page.locator('#cw-page-title')).toHaveText(SQL_REPORT_MENU_TITLE)
    await expect(page.getByRole('heading', { name: '资金对账演示报表' })).toBeVisible()

    await page.reload({ waitUntil: 'domcontentloaded' })
    await expect(page).toHaveURL(new RegExp(`${SQL_REPORT_PATH.replace('?', '\\?')}$`))
    await expect(page.locator('#cw-page-title')).toHaveText(SQL_REPORT_MENU_TITLE)
    await expect(page.getByRole('heading', { name: '资金对账演示报表' })).toBeVisible()
  })

  test('Element Plus 表格使用具体中文空态', async ({ page }) => {
    await page.goto('/system/user', { waitUntil: 'domcontentloaded' })

    const emptyText = page.locator('.el-table__empty-text').first()
    await expect(emptyText).toBeVisible()
    await expect(emptyText).toHaveText('暂无符合条件的用户')
    await expect(page.getByText('No Data', { exact: true })).toHaveCount(0)
  })

  test('Night 暗色主题在刷新后保持，并继续使用统一的工作面语义色', async ({ page }) => {
    await page.goto('/system/dict', { waitUntil: 'domcontentloaded' })

    await selectThemePreset(page, 'Night 夜航')
    await expect(page.locator('html')).toHaveClass(/dark/)

    const beforeReload = await page.evaluate(() => ({
      mode: localStorage.getItem('customer-admin-theme-mode'),
      canvas: getComputedStyle(document.documentElement).getPropertyValue('--cw-canvas').trim(),
      paper: getComputedStyle(document.documentElement).getPropertyValue('--cw-paper').trim(),
    }))
    expect(beforeReload.mode).toBe('dark')
    expect(beforeReload.canvas).toBe('#071820')
    expect(beforeReload.paper).toBe('#0d242c')

    const semanticColors = await page.evaluate(() => {
      const styles = getComputedStyle(document.documentElement)
      return {
        paper: styles.getPropertyValue('--cw-paper').trim(),
        successForeground: styles.getPropertyValue('--el-color-success').trim(),
        warningForeground: styles.getPropertyValue('--el-color-warning').trim(),
        dangerForeground: styles.getPropertyValue('--el-color-danger').trim(),
        successSolid: styles.getPropertyValue('--cw-success-solid').trim(),
        warningSolid: styles.getPropertyValue('--cw-amber-solid').trim(),
        dangerSolid: styles.getPropertyValue('--cw-danger-solid').trim(),
        onSuccess: styles.getPropertyValue('--cw-on-success').trim(),
        onWarning: styles.getPropertyValue('--cw-on-warning').trim(),
        onDanger: styles.getPropertyValue('--cw-on-danger').trim(),
      }
    })
    for (const type of ['Success', 'Warning', 'Danger'] as const) {
      const key = type.toLowerCase() as 'success' | 'warning' | 'danger'
      expect(
        contrastRatio(semanticColors[`${key}Foreground`], semanticColors.paper),
        `${key} foreground`,
      ).toBeGreaterThanOrEqual(4.5)
      expect(
        contrastRatio(semanticColors[`${key}Solid`], semanticColors[`on${type}`]),
        `${key} solid`,
      ).toBeGreaterThanOrEqual(4.5)
    }

    await page.goto('/workbench/site', { waitUntil: 'domcontentloaded' })
    const successButton = page.getByRole('button', { name: '生成登录脚本', exact: true })
    await expect(successButton).toBeVisible()
    const successButtonColors = await successButton.evaluate((button) => {
      const styles = getComputedStyle(button)
      return { color: styles.color, background: styles.backgroundColor }
    })
    expect(contrastRatio(successButtonColors.color, successButtonColors.background)).toBeGreaterThanOrEqual(4.5)

    await page.goto('/system/dict', { waitUntil: 'domcontentloaded' })

    await page.reload({ waitUntil: 'domcontentloaded' })
    await expect(page.locator('html')).toHaveClass(/dark/)
    await expect(page.locator('#cw-page-title')).toHaveText('字典管理')
    await expect(page.getByLabel('选择界面主题', { exact: true }))
      .toHaveAttribute('title', '当前主题：Night 夜航')
  })

  test('System 主题在暗色系统下选择、冷启动与刷新保持，并实时跟随系统切亮', async ({ page }) => {
    await page.emulateMedia({ colorScheme: 'dark' })
    await page.goto('/system/dict', { waitUntil: 'domcontentloaded' })

    await selectThemePreset(page, 'System 随行')
    await expect(page.locator('html')).toHaveClass(/dark/)
    expect(await page.evaluate(() => localStorage.getItem('customer-admin-theme-mode'))).toBe('auto')

    const darkColors = await page.evaluate(() => {
      const styles = getComputedStyle(document.documentElement)
      return {
        paper: styles.getPropertyValue('--cw-paper').trim(),
        cobalt: styles.getPropertyValue('--cw-cobalt').trim(),
        success: styles.getPropertyValue('--cw-success').trim(),
        danger: styles.getPropertyValue('--cw-danger').trim(),
      }
    })
    for (const token of ['cobalt', 'success', 'danger'] as const) {
      const ratio = contrastRatio(darkColors[token], darkColors.paper)
      expect(
        ratio,
        `dark --cw-${token} ${darkColors[token]} on --cw-paper ${darkColors.paper}`,
      ).toBeGreaterThanOrEqual(4.5)
    }

    await page.reload({ waitUntil: 'domcontentloaded' })
    await expect(page.locator('html')).toHaveClass(/dark/)
    expect(await page.evaluate(() => localStorage.getItem('customer-admin-theme-mode'))).toBe('auto')

    await page.emulateMedia({ colorScheme: 'light' })
    await expect(page.locator('html')).not.toHaveClass(/dark/)
    await expect(page.getByLabel('选择界面主题', { exact: true }))
      .toHaveAttribute('title', '当前主题：System 随行')
    expect(await page.evaluate(() => localStorage.getItem('customer-admin-theme-mode'))).toBe('auto')
  })

  test('命名主题选择器提供完整单选语义、键盘路径、关闭行为与 Violet 冷启动持久化', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto('/system/dict', { waitUntil: 'domcontentloaded' })

    const trigger = page.getByLabel('选择界面主题', { exact: true })
    const listbox = page.getByRole('listbox', { name: '界面主题' })
    await expect(trigger).toHaveAttribute('aria-haspopup', 'listbox')
    await expect(trigger).toHaveAttribute('aria-expanded', 'false')
    await expect(trigger).toHaveAttribute('aria-controls', /theme-preset-listbox-/)

    await trigger.press('Enter')
    await expect(listbox).toBeVisible()
    await expect(trigger).toHaveAttribute('aria-expanded', 'true')
    await expect(page.getByRole('option')).toHaveCount(9)
    await expect(themePresetOption(page, 'System 随行')).toContainText('AUTO')
    await expect(themePresetOption(page, 'Night 夜航')).toContainText('DARK')
    await expect(themePresetOption(page, 'Violet 智紫')).toContainText('LIGHT')

    await page.keyboard.press('End')
    const graphite = themePresetOption(page, 'Graphite 墨岩')
    await expect(graphite).toBeFocused()
    const lastOptionGeometry = await graphite.evaluate((option) => {
      const optionRect = option.getBoundingClientRect()
      const listRect = option.parentElement!.getBoundingClientRect()
      return {
        optionTop: optionRect.top,
        optionBottom: optionRect.bottom,
        listTop: listRect.top,
        listBottom: listRect.bottom,
      }
    })
    expect(lastOptionGeometry.optionTop).toBeGreaterThanOrEqual(lastOptionGeometry.listTop)
    expect(lastOptionGeometry.optionBottom).toBeLessThanOrEqual(lastOptionGeometry.listBottom + 1)
    await page.keyboard.press('ArrowDown')
    await expect(graphite).toBeFocused()

    await page.keyboard.press('Home')
    const system = themePresetOption(page, 'System 随行')
    await expect(system).toBeFocused()
    await page.keyboard.press('ArrowUp')
    await expect(system).toBeFocused()
    await page.keyboard.press('ArrowDown')
    await expect(themePresetOption(page, 'Atlas 翡翠')).toBeFocused()
    await page.keyboard.press('ArrowDown')
    await expect(themePresetOption(page, 'Ocean 深海')).toBeFocused()
    await page.keyboard.press('ArrowDown')
    const violet = themePresetOption(page, 'Violet 智紫')
    await expect(violet).toBeFocused()
    await page.keyboard.press(' ')

    await expect(listbox).toBeHidden()
    await expect(trigger).toBeFocused()
    await expect(trigger).toHaveAttribute('title', '当前主题：Violet 智紫')
    const violetState = await page.evaluate(() => ({
      color: localStorage.getItem('customer-admin-theme-color'),
      mode: localStorage.getItem('customer-admin-theme-mode'),
    }))
    expect(violetState.mode).toBe('light')
    expect(violetState.color).toBeTruthy()

    await page.reload({ waitUntil: 'domcontentloaded' })
    await expect(page.getByLabel('选择界面主题', { exact: true }))
      .toHaveAttribute('title', '当前主题：Violet 智紫')
    expect(await page.evaluate(() => localStorage.getItem('customer-admin-theme-color'))).toBe(violetState.color)

    const coldPage = await page.context().newPage()
    await coldPage.goto('/system/dict', { waitUntil: 'domcontentloaded' })
    await expect(coldPage.getByLabel('选择界面主题', { exact: true }))
      .toHaveAttribute('title', '当前主题：Violet 智紫')
    await coldPage.close()

    await trigger.press(' ')
    await expect(listbox).toBeVisible()
    await page.keyboard.press('Escape')
    await expect(listbox).toBeHidden()
    await expect(trigger).toBeFocused()

    await trigger.click()
    await expect(listbox).toBeVisible()
    await page.locator('.layout-main').click({ position: { x: 18, y: 18 } })
    await expect(listbox).toBeHidden()

    await trigger.click()
    await expect(listbox).toBeVisible()
    await themePresetOption(page, 'Night 夜航').hover()
    await expect(violet).toBeFocused()
    await page.keyboard.press('Tab')
    await expect(listbox).toBeHidden()
    await expect(trigger).not.toBeFocused()
  })

  test('主题旧颜色与模式两键迁移为 Custom，刷新后不误识别为 Night', async ({ page }) => {
    await page.addInitScript(() => {
      if (sessionStorage.getItem('theme-legacy-migration-seeded') === 'true') return
      localStorage.removeItem('customer-admin-theme-selection')
      localStorage.setItem('customer-admin-theme-color', '#14b8a6')
      localStorage.setItem('customer-admin-theme-mode', 'dark')
      sessionStorage.setItem('theme-legacy-migration-seeded', 'true')
    })

    await page.goto('/system/dict', { waitUntil: 'domcontentloaded' })

    const trigger = page.getByLabel('选择界面主题', { exact: true })
    await expect(trigger).toHaveAttribute('title', '当前主题：Custom 自定义')
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'custom')
    const migratedSelection = await page.evaluate(() => {
      const rawSelection = localStorage.getItem('customer-admin-theme-selection')
      return rawSelection ? JSON.parse(rawSelection) : null
    })
    expect(migratedSelection).toEqual({
      version: 1,
      kind: 'custom',
      primaryColor: '#14b8a6',
      mode: 'dark',
    })

    await page.reload({ waitUntil: 'domcontentloaded' })
    await expect(trigger).toHaveAttribute('title', '当前主题：Custom 自定义')
    await expect(trigger).not.toHaveAttribute('title', '当前主题：Night 夜航')
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'custom')
    const persistedSelection = await page.evaluate(() => {
      const rawSelection = localStorage.getItem('customer-admin-theme-selection')
      return rawSelection ? JSON.parse(rawSelection) : null
    })
    expect(persistedSelection).toEqual(migratedSelection)
  })

  test('1440/1024/760/390 顶栏与主题弹层都不产生横向溢出', async ({ page }) => {
    await page.goto('/system/user', { waitUntil: 'domcontentloaded' })

    for (const width of [1440, 1024, 760, 390]) {
      await page.setViewportSize({ width, height: 844 })
      const trigger = page.getByLabel('选择界面主题', { exact: true })
      await expect(trigger).toBeVisible()
      const triggerWidth = await trigger.evaluate((element) => element.getBoundingClientRect().width)
      if (width <= 760) {
        expect(triggerWidth, `${width}px compact trigger`).toBeLessThanOrEqual(36)
      } else {
        expect(triggerWidth, `${width}px named trigger`).toBeGreaterThan(120)
      }

      const overflow = await page.locator('.layout-header').evaluate((header) => ({
        headerRight: header.getBoundingClientRect().right,
        viewportWidth: window.innerWidth,
        documentScrollWidth: document.documentElement.scrollWidth,
      }))
      expect(overflow.headerRight, `${width}px header right`).toBeLessThanOrEqual(overflow.viewportWidth)
      expect(overflow.documentScrollWidth, `${width}px document width`).toBeLessThanOrEqual(overflow.viewportWidth)

      if (width === 390) {
        await trigger.press('End')
        const listbox = page.getByRole('listbox', { name: '界面主题' })
        const graphite = themePresetOption(page, 'Graphite 墨岩')
        await expect(graphite).toBeFocused()
        const mobileGeometry = await listbox.evaluate((element) => {
          const rect = element.getBoundingClientRect()
          return {
            position: getComputedStyle(element).position,
            left: rect.left,
            right: rect.right,
            bottom: rect.bottom,
            viewportWidth: window.innerWidth,
            viewportHeight: window.innerHeight,
          }
        })
        expect(mobileGeometry.position).toBe('fixed')
        expect(mobileGeometry.left).toBeGreaterThanOrEqual(0)
        expect(mobileGeometry.right).toBeLessThanOrEqual(mobileGeometry.viewportWidth)
        expect(mobileGeometry.bottom).toBeLessThanOrEqual(mobileGeometry.viewportHeight)
        await page.keyboard.press('Escape')
      }
    }
  })

  test('登录页复用紧凑主题入口，Workspace 仅保留全局入口与新建会话动作', async ({ page }) => {
    const fulfill = async (route: Route, data: unknown) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json; charset=utf-8',
        body: JSON.stringify({ code: 0, message: 'success', data }),
      })
    }
    await page.route('**/api/login-images/list', (route) => fulfill(route, []))
    await page.route('**/api/auth/register-options', (route) => fulfill(route, {
      selfServiceEnabled: false,
      captchaRequired: false,
      emailRequired: false,
      emailVerificationRequired: false,
    }))
    await page.route('**/api/auth/login-captcha/challenge', (route) => fulfill(route, {
      challengeId: 'theme-entry-challenge',
      ttlSeconds: 120,
      backgroundImage: TEST_CAPTCHA_IMAGE,
      puzzlePieceImage: TEST_CAPTCHA_IMAGE,
      canvasWidth: 320,
      canvasHeight: 160,
      pieceWidth: 56,
      pieceHeight: 56,
      pieceY: 52,
    }))

    await page.goto('/login', { waitUntil: 'domcontentloaded' })
    await expect(page.getByRole('heading', { name: '欢迎回来' })).toBeVisible()
    const loginThemeTrigger = page.getByLabel('选择界面主题', { exact: true })
    await expect(loginThemeTrigger).toBeVisible()
    expect(await loginThemeTrigger.evaluate((element) => element.getBoundingClientRect().width)).toBeLessThanOrEqual(38)
    await selectThemePreset(page, 'Violet 智紫')

    await page.goto('/workspace/java-assistant', { waitUntil: 'domcontentloaded' })
    await expect(page.locator('.workspace-view')).toBeVisible()
    await expect(page.getByLabel('选择界面主题', { exact: true })).toHaveCount(1)
    await expect(page.getByLabel('选择界面主题', { exact: true }))
      .toHaveAttribute('title', '当前主题：Violet 智紫')
    await expect(page.getByLabel('切换主题色', { exact: true })).toHaveCount(0)
    await expect(page.locator('.theme-toolbar, .theme-btn, .color-popover')).toHaveCount(0)
    const newSession = page.getByRole('button', { name: '新建会话', exact: true })
    await expect(newSession).toBeVisible()
    const composer = page.getByPlaceholder('输入消息，回车发送；⌘/Ctrl+V 可粘贴截图或文件作为附件')
    await composer.fill('等待新建会话清空的草稿')
    await newSession.click()
    await expect(composer).toHaveValue('')
  })

  test('明亮品牌色在亮暗模式下拆分为可访问前景与全状态实心色', async ({ page }) => {
    await page.addInitScript(() => {
      if (sessionStorage.getItem('theme-bright-custom-seeded') === 'true') return
      localStorage.removeItem('customer-admin-theme-selection')
      localStorage.setItem('customer-admin-theme-color', '#f59e0b')
      localStorage.setItem('customer-admin-theme-mode', 'light')
      sessionStorage.setItem('theme-bright-custom-seeded', 'true')
    })
    await page.goto('/workspace/java-assistant', { waitUntil: 'domcontentloaded' })

    const assertThemeContrast = async () => {
      const colors = await page.evaluate(() => {
        const styles = getComputedStyle(document.documentElement)
        return {
          foreground: styles.getPropertyValue('--theme-primary').trim(),
          surface: styles.getPropertyValue('--cw-paper').trim(),
          solid: styles.getPropertyValue('--theme-primary-solid').trim(),
          hover: styles.getPropertyValue('--theme-primary-solid-hover').trim(),
          active: styles.getPropertyValue('--theme-primary-solid-active').trim(),
          onSolid: styles.getPropertyValue('--cw-on-primary').trim(),
        }
      })
      expect(contrastRatio(colors.foreground, colors.surface), 'primary foreground').toBeGreaterThanOrEqual(4.5)
      for (const state of ['solid', 'hover', 'active'] as const) {
        expect(contrastRatio(colors[state], colors.onSolid), `primary ${state}`).toBeGreaterThanOrEqual(4.5)
      }

      const newSession = page.locator('.new-session-btn')
      await expect(newSession).toBeVisible()
      const normal = await newSession.evaluate((button) => {
        const styles = getComputedStyle(button)
        return { color: styles.color, background: styles.backgroundColor }
      })
      expect(contrastRatio(normal.color, normal.background), 'new session normal').toBeGreaterThanOrEqual(4.5)
      await newSession.hover()
      const hovered = await newSession.evaluate((button) => {
        const styles = getComputedStyle(button)
        return { color: styles.color, background: styles.backgroundColor }
      })
      expect(contrastRatio(hovered.color, hovered.background), 'new session hover').toBeGreaterThanOrEqual(4.5)
    }

    const assertCheckedControlContrast = async (expectedTheme?: 'night') => {
      await page.goto('/ops/dead-letter', { waitUntil: 'domcontentloaded' })
      if (expectedTheme === 'night') {
        await expect(page.locator('html')).toHaveClass(/dark/)
        await expect(page.locator('html')).toHaveAttribute('data-theme', 'night')
      }
      const checkedRadio = page.locator('.el-radio-button__original-radio:checked + .el-radio-button__inner').first()
      await expect(checkedRadio).toBeVisible()
      const colors = await checkedRadio.evaluate((control) => {
        const styles = getComputedStyle(control)
        return { color: styles.color, background: styles.backgroundColor }
      })
      expect(contrastRatio(colors.color, colors.background), 'checked radio button').toBeGreaterThanOrEqual(4.5)
    }

    const assertTagContrast = async (expectedTheme?: 'night') => {
      if (expectedTheme === 'night') {
        await expect(page.locator('html')).toHaveClass(/dark/)
        await expect(page.locator('html')).toHaveAttribute('data-theme', 'night')
      }
      await page.evaluate(() => {
        document.querySelector('#theme-tag-contrast-probe')?.remove()
        const probe = document.createElement('div')
        probe.id = 'theme-tag-contrast-probe'
        probe.setAttribute('aria-hidden', 'true')
        probe.style.cssText = 'position:fixed;left:-10000px;top:0;'
        for (const type of ['primary', 'success', 'warning', 'danger', 'info']) {
          for (const effect of ['light', 'plain', 'dark']) {
            const tag = document.createElement('span')
            tag.className = `el-tag el-tag--${type} el-tag--${effect}`
            tag.dataset.tagContrast = `${type}-${effect}`
            tag.textContent = `${type}-${effect}`
            probe.appendChild(tag)
          }
        }
        document.body.appendChild(probe)
      })

      for (const type of ['primary', 'success', 'warning', 'danger', 'info']) {
        for (const effect of ['light', 'plain', 'dark']) {
          const label = `${type}-${effect}`
          const colors = await page.locator(`[data-tag-contrast="${label}"]`).evaluate((tag) => {
            const styles = getComputedStyle(tag)
            return { color: styles.color, background: styles.backgroundColor }
          })
          expect(
            contrastRatio(colors.color, colors.background),
            `${label} tag`,
          ).toBeGreaterThanOrEqual(4.5)
        }
      }
    }

    await assertThemeContrast()
    await assertCheckedControlContrast()
    await assertTagContrast()
    await page.goto('/workspace/java-assistant', { waitUntil: 'domcontentloaded' })
    await selectThemePreset(page, 'Night 夜航')
    await expect(page.locator('html')).toHaveClass(/dark/)
    await assertThemeContrast()
    await assertCheckedControlContrast('night')
    await assertTagContrast('night')
  })

  for (const pageTemplate of [
    { path: '/system/user', template: 'list', readySelector: '.layout-main > .page' },
    { path: '/ops/csat', template: 'dashboard', readySelector: '.layout-main > .csat-board' },
    { path: '/workbench/sql-console', template: 'console', readySelector: '.layout-main > .page' },
  ] as const) {
    test(`${pageTemplate.template} 代表页把正确母版绑定到内容容器`, async ({ page }) => {
      await page.goto(pageTemplate.path, { waitUntil: 'domcontentloaded' })

      await expect(page.locator(pageTemplate.readySelector)).toBeVisible()
      await expect(page.locator('.layout-main')).toHaveAttribute('data-page-template', pageTemplate.template)
    })
  }

  test('404 直接访问展示路由缺口并可回到工作首页', async ({ page }) => {
    await page.goto('/missing-admin-route', { waitUntil: 'domcontentloaded' })

    expect(new URL(page.url()).pathname).toBe('/missing-admin-route')
    await expect(page.getByRole('heading', { name: '这条工作路径没有可用页面' })).toBeVisible()
    await expect(page.getByText('404 / ROUTE GAP', { exact: true })).toBeVisible()

    await page.getByRole('button', { name: '返回工作首页', exact: true }).click()
    await expect(page).toHaveURL(/\/home$/)
    await expect(page.locator('.layout-main--home')).toBeVisible()
  })

  test('Workspace 离开后返回保留未发送草稿，且不重复请求首屏会话', async ({ page }) => {
    let sessionListRequests = 0
    page.on('request', (request) => {
      const url = new URL(request.url())
      if (request.method() === 'GET' && url.pathname === '/api/workspace/java-assistant/chat/sessions') {
        sessionListRequests += 1
      }
    })

    await page.goto('/workspace/java-assistant', { waitUntil: 'domcontentloaded' })
    const composer = page.getByPlaceholder('输入消息，回车发送；⌘/Ctrl+V 可粘贴截图或文件作为附件')
    await expect(composer).toBeVisible()
    // Chat 与 VibeCoding 面板各自维护历史侧栏，首次挂载的两次请求是当前基线；
    // KeepAlive 契约关心的是离开/返回不能在该基线上继续增加。
    await expect.poll(() => sessionListRequests).toBe(2)
    const initialSessionListRequests = sessionListRequests
    await composer.fill('这是未发送的 KeepAlive 草稿')

    const lifecycleNavigation = page.getByRole('navigation', { name: '智能体生命周期导航' })
    await lifecycleNavigation.getByRole('button', { name: '设置', exact: true }).click()
    await page.getByRole('menuitem', { name: '系统管理', exact: true }).click()
    await page.getByRole('menuitem', { name: '成员与身份（服务端菜单）', exact: true }).click()
    await expect(page.locator('#cw-page-title')).toHaveText('成员与身份（服务端菜单）')

    await lifecycleNavigation.getByRole('button', { name: '智能体', exact: true }).click()
    await page.getByRole('menuitem', { name: 'Java 智能体', exact: true }).click()
    await expect(page.locator('.workspace-view')).toBeVisible()
    await expect(composer).toHaveValue('这是未发送的 KeepAlive 草稿')
    expect(sessionListRequests).toBe(initialSessionListRequests)
  })

  test('390px 顶栏不溢出，移动导航可完整打开并由 Escape 恢复焦点', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 })
    // 本用例显式切成控制面视角，确保移动顶栏连同租户入口一起参与宽度验收。
    await page.route('**/api/tenant/current-view', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json; charset=utf-8',
        body: JSON.stringify({
          code: 0,
          message: 'success',
          data: { userTenantId: 'default', effectiveTenantId: 'default', crossTenantAuthority: true },
          timestamp: Date.UTC(2026, 7, 30, 10, 0, 0),
        }),
      })
    })
    await page.goto('/system/user', { waitUntil: 'domcontentloaded' })

    const aside = page.locator('.layout-aside')
    await expect.poll(() => aside.evaluate((element) => element.getBoundingClientRect().width)).toBe(0)

    for (const label of [
      '打开导航菜单',
      '搜索菜单、智能体或配置',
      '选择界面主题',
      '打开站内消息',
      '打开用户菜单',
    ]) {
      await expect(page.getByLabel(label, { exact: true })).toBeVisible()
    }
    await expect(page.getByRole('combobox', { name: '租户视角' })).toBeVisible()
    const overflow = await page.locator('.layout-header').evaluate((header) => ({
      headerRight: header.getBoundingClientRect().right,
      viewportWidth: window.innerWidth,
      documentScrollWidth: document.documentElement.scrollWidth,
    }))
    expect(overflow.headerRight).toBeLessThanOrEqual(overflow.viewportWidth)
    expect(overflow.documentScrollWidth).toBeLessThanOrEqual(overflow.viewportWidth)

    const trigger = page.locator('.navigation-toggle')
    await expect(trigger).toHaveAccessibleName('打开导航菜单')
    await trigger.click()
    await expect(trigger).toHaveAttribute('aria-expanded', 'true')
    await expect(trigger).toHaveAccessibleName('关闭导航菜单')
    const navigationDialog = page.getByRole('dialog', { name: '页面导航' })
    await expect(navigationDialog).toBeVisible()
    await expect(navigationDialog.locator('.primary-rail')).toBeVisible()
    await expect(navigationDialog.locator('.context-pane')).toBeVisible()

    await page.keyboard.press('Escape')
    await expect(page.locator('#lifecycle-navigation-shell')).toBeHidden()
    await expect(trigger).toBeFocused()
    await expect(trigger).toHaveAccessibleName('打开导航菜单')
    await expect(trigger).toHaveAttribute('aria-expanded', 'false')
  })
})
