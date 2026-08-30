import { expect, test, type Page } from '@playwright/test'
import { LOGIN_E2E_ORIGIN } from './loginTestEnvironment'

const WORKSPACE_PATH = '/workspace/java-assistant?mode=vibecoding'
const API_PATTERN = `${LOGIN_E2E_ORIGIN}/api/**`
const ARTIFACTS_WIDTH_STORAGE_KEY = 'customer-admin-vibecoding-artifacts-width'
const LONG_FILE_NAME = 'CustomerOrderReconciliationApplicationServiceWithLongDescriptiveName.java'
const LONG_FILE_PATH = `src/main/java/com/example/customer/${LONG_FILE_NAME}`

interface WorkspaceFixtures {
  unknownRequests: string[]
}

interface PanelMetrics {
  panelWidth: number
  chatWidth: number
  artifactsWidth: number
  historyWidth: number
  historyLeft: number
  chatTop: number
  historyTop: number
  longFileLabelWidth: number
  longFileLabelScrollWidth: number
}

function successResult<T>(data: T) {
  return { code: 0, message: 'success', data, timestamp: Date.now() }
}

async function installWorkspaceFixtures(page: Page): Promise<WorkspaceFixtures> {
  const fixtures: WorkspaceFixtures = { unknownRequests: [] }

  await page.addInitScript(() => {
    localStorage.setItem('admin-token', 'workspace-resize-e2e-token')
    localStorage.setItem('admin-nickname', 'Resize E2E')
    localStorage.setItem('admin-username', 'workspace-resize-e2e')
    localStorage.setItem('admin-force-change-password', 'false')
    localStorage.setItem('admin-approval-status', 'APPROVED')
  })

  await page.route(API_PATTERN, async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname.replace(/^\/api/, '')
    const fulfill = (data: unknown) => route.fulfill({
      status: 200,
      contentType: 'application/json; charset=utf-8',
      body: JSON.stringify(successResult(data)),
    })

    if (path === '/auth/permissions') return fulfill([])
    if (path === '/menu/version') return fulfill(1)
    if (path === '/menu/routes') {
      return fulfill([
        {
          id: 1,
          name: '首页',
          path: '/home',
          icon: 'House',
          iconType: 'library',
          permCode: null,
          sort: 1,
          agentCode: null,
          capabilities: null,
          dynamic: false,
          children: [],
        },
        {
          id: 10,
          name: '智能体工作区',
          path: '/workspace',
          icon: 'Cpu',
          iconType: 'library',
          permCode: null,
          sort: 2,
          agentCode: null,
          capabilities: null,
          dynamic: false,
          children: [
            {
              id: 11,
              name: '编程语言智能体',
              path: '/workspace/java-assistant',
              icon: 'Monitor',
              iconType: 'library',
              permCode: null,
              sort: 1,
              agentCode: 'java-assistant',
              capabilities: ['vibecoding'],
              dynamic: true,
              children: [],
            },
          ],
        },
      ])
    }
    if (path === '/tenant/current-view') {
      return fulfill({
        userTenantId: 'default',
        effectiveTenantId: 'default',
        crossTenantAuthority: false,
      })
    }
    if (path === '/message/unread-count') return fulfill(0)
    if (path === '/workspace/java-assistant/chat/sessions') {
      return fulfill({
        pageNum: 1,
        pageSize: 20,
        total: 1,
        list: [
          {
            sessionId: 'resize-history-session',
            preview: 'VibeCoding 分栏回归',
            lastMessageTime: '2026-08-30 09:30:00.000',
            messageCount: 2,
          },
        ],
      })
    }
    if (path === '/workspace/java-assistant/chat/sessions/resize-history-session/messages') {
      return fulfill([
        {
          id: 'resize-message-1',
          role: 'user',
          text: '[VibeCoding指引-local] 请检查分栏拖动。',
          timestamp: '2026-08-30 09:29:59',
          attachments: [],
        },
        {
          id: 'resize-message-2',
          role: 'assistant',
          text: '分栏回归 fixture 已加载。',
          timestamp: '2026-08-30 09:30:00',
          attachments: [],
        },
      ])
    }
    if (path === '/workspace/java-assistant/vibecoding/sandbox-mode') {
      return fulfill({ mode: 'local' })
    }
    if (path === '/workspace/java-assistant/vibecoding/files') {
      return fulfill([
        {
          name: LONG_FILE_NAME,
          relativePath: LONG_FILE_PATH,
          directory: false,
          children: [],
        },
      ])
    }

    fixtures.unknownRequests.push(`${request.method()} ${path}`)
    return fulfill([])
  })

  return fixtures
}

async function openVibeCodingWorkspace(page: Page) {
  await page.goto(WORKSPACE_PATH, { waitUntil: 'domcontentloaded' })
  await expect(page.locator('.workspace-view')).toBeVisible()
  await expect(page.getByRole('tab', { name: 'VibeCoding' })).toHaveAttribute('aria-selected', 'true')
  await expect(page.locator('.vibecoding-panel')).toBeVisible()
  await expect(page.locator(`.tree-node > span[title="${LONG_FILE_PATH}"]`)).toBeVisible()
}

async function panelMetrics(page: Page): Promise<PanelMetrics> {
  return page.locator('.vibecoding-panel').evaluate((panel, longFilePath) => {
    const rect = (selector: string) => {
      const element = panel.querySelector<HTMLElement>(selector)
      if (!element) throw new Error(`Missing E2E element: ${selector}`)
      return element.getBoundingClientRect()
    }
    const panelRect = panel.getBoundingClientRect()
    const chatRect = rect('.chat-column')
    const artifactsRect = rect('.artifacts-column')
    const historyRect = rect('.history-column')
    const longFileLabel = panel.querySelector<HTMLElement>(`.tree-node > span[title="${longFilePath}"]`)
    if (!longFileLabel) throw new Error('Missing long workspace filename')
    return {
      panelWidth: panelRect.width,
      chatWidth: chatRect.width,
      artifactsWidth: artifactsRect.width,
      historyWidth: historyRect.width,
      historyLeft: historyRect.left,
      chatTop: chatRect.top,
      historyTop: historyRect.top,
      longFileLabelWidth: longFileLabel.clientWidth,
      longFileLabelScrollWidth: longFileLabel.scrollWidth,
    }
  }, LONG_FILE_PATH)
}

async function dragSeparator(page: Page, deltaX: number) {
  await startSeparatorDrag(page, deltaX)
  await page.mouse.up()
}

async function startSeparatorDrag(page: Page, deltaX: number): Promise<number> {
  const separator = page.getByRole('separator', { name: '调整对话区与产物文件区宽度' })
  await separator.evaluate((element) => {
    element.addEventListener('pointerdown', (event) => {
      element.setAttribute('data-e2e-pointer-id', String(event.pointerId))
    }, { once: true })
  })
  const box = await separator.boundingBox()
  expect(box).not.toBeNull()
  if (!box) throw new Error('Missing resize separator bounding box')

  const startX = box.x + box.width / 2
  const startY = box.y + box.height / 2
  await page.mouse.move(startX, startY)
  await page.mouse.down()
  await page.mouse.move(startX + deltaX, startY, { steps: 8 })

  const rawPointerId = await separator.getAttribute('data-e2e-pointer-id')
  expect(rawPointerId).not.toBeNull()
  if (!rawPointerId) throw new Error('Missing pointer id after separator pointerdown')
  const pointerId = Number(rawPointerId)
  expect(Number.isInteger(pointerId)).toBe(true)
  return pointerId
}

function expectHistoryUnchanged(before: PanelMetrics, after: PanelMetrics) {
  expect(Math.abs(after.historyWidth - before.historyWidth)).toBeLessThanOrEqual(1)
  expect(Math.abs(after.historyLeft - before.historyLeft)).toBeLessThanOrEqual(1)
}

test.describe('VibeCoding 产物栏宽度', () => {
  test.use({ viewport: { width: 1600, height: 1000 } })

  test('鼠标、键盘、刷新与响应式切换都保留同一宽度偏好', async ({ page }, testInfo) => {
    const fixtures = await installWorkspaceFixtures(page)
    await openVibeCodingWorkspace(page)

    const separator = page.getByRole('separator', { name: '调整对话区与产物文件区宽度' })
    await expect(separator).toBeVisible()
    await expect(separator).toHaveAttribute('aria-orientation', 'vertical')
    await expect(separator).toHaveAttribute('aria-valuemin', '220')
    await expect(separator).toHaveAttribute('aria-valuenow', '260')
    await expect(separator).toHaveAttribute('aria-valuetext', '产物文件区宽度 260 像素')
    expect(Number(await separator.getAttribute('aria-valuemax'))).toBeGreaterThan(260)

    const controlledIds = (await separator.getAttribute('aria-controls'))?.split(/\s+/) ?? []
    expect(controlledIds).toHaveLength(2)
    for (const id of controlledIds) {
      await expect(page.locator(`#${id}`)).toHaveCount(1)
    }

    const initial = await panelMetrics(page)
    await dragSeparator(page, -120)
    const draggedLeft = await panelMetrics(page)
    expect(draggedLeft.artifactsWidth).toBeGreaterThan(initial.artifactsWidth + 90)
    expect(draggedLeft.chatWidth).toBeLessThan(initial.chatWidth - 90)
    expectHistoryUnchanged(initial, draggedLeft)
    expect(draggedLeft.longFileLabelWidth).toBeGreaterThan(initial.longFileLabelWidth + 80)
    expect(draggedLeft.longFileLabelScrollWidth).toBeGreaterThan(draggedLeft.longFileLabelWidth)

    await dragSeparator(page, 80)
    const draggedRight = await panelMetrics(page)
    expect(draggedRight.artifactsWidth).toBeLessThan(draggedLeft.artifactsWidth - 55)
    expect(draggedRight.chatWidth).toBeGreaterThan(draggedLeft.chatWidth + 55)
    expectHistoryUnchanged(draggedLeft, draggedRight)

    await separator.focus()
    const preferredWidth = Number(await separator.getAttribute('aria-valuenow'))
    await page.keyboard.press('ArrowLeft')
    await expect(separator).toHaveAttribute('aria-valuenow', String(preferredWidth + 16))
    await page.keyboard.press('ArrowRight')
    await expect(separator).toHaveAttribute('aria-valuenow', String(preferredWidth))
    expect(await page.evaluate((key) => localStorage.getItem(key), ARTIFACTS_WIDTH_STORAGE_KEY))
      .toBe(String(preferredWidth))

    const beforeReload = await panelMetrics(page)
    await page.reload({ waitUntil: 'domcontentloaded' })
    await expect(page.getByRole('tab', { name: 'VibeCoding' })).toHaveAttribute('aria-selected', 'true')
    await expect(page.locator(`.tree-node > span[title="${LONG_FILE_PATH}"]`)).toBeVisible()
    await expect(separator).toHaveAttribute('aria-valuenow', String(preferredWidth))
    await expect.poll(async () => (
      Math.abs((await panelMetrics(page)).artifactsWidth - beforeReload.artifactsWidth)
    )).toBeLessThanOrEqual(1)

    await dragSeparator(page, -100)
    const compactPreferredWidth = preferredWidth + 100
    await expect(separator).toHaveAttribute('aria-valuenow', String(compactPreferredWidth))
    await page.setViewportSize({ width: 1100, height: 1000 })
    await expect.poll(async () => {
      const width = (await panelMetrics(page)).panelWidth
      return width > 700 && width <= 1040
    }).toBe(true)
    await expect(separator).toBeVisible()
    await expect.poll(async () => Number(await separator.getAttribute('aria-valuenow'))
      - Number(await separator.getAttribute('aria-valuemax'))).toBe(0)
    const compactMetrics = await panelMetrics(page)
    expect(compactMetrics.historyTop - compactMetrics.chatTop).toBeGreaterThan(400)
    expect(await page.evaluate((key) => localStorage.getItem(key), ARTIFACTS_WIDTH_STORAGE_KEY))
      .toBe(String(compactPreferredWidth))

    await page.setViewportSize({ width: 1600, height: 1000 })
    await expect(separator).toHaveAttribute('aria-valuenow', String(compactPreferredWidth))
    await expect.poll(async () => Math.abs(
      (await panelMetrics(page)).artifactsWidth - compactPreferredWidth,
    )).toBeLessThanOrEqual(1)
    await dragSeparator(page, 100)
    await expect(separator).toHaveAttribute('aria-valuenow', String(preferredWidth))

    await page.setViewportSize({ width: 760, height: 1000 })
    await expect.poll(async () => (await panelMetrics(page)).panelWidth).toBeLessThanOrEqual(700)
    await expect(separator).toHaveCount(0)
    expect(await page.evaluate((key) => localStorage.getItem(key), ARTIFACTS_WIDTH_STORAGE_KEY))
      .toBe(String(preferredWidth))

    await page.setViewportSize({ width: 1600, height: 1000 })
    await expect(separator).toBeVisible()
    await expect(separator).toHaveAttribute('aria-valuenow', String(preferredWidth))

    await page.setViewportSize({ width: 1440, height: 1000 })
    await expect(separator).toBeVisible()
    await separator.focus()
    await page.keyboard.press('Home')
    await expect.poll(async () => Number(await separator.getAttribute('aria-valuenow'))
      - Number(await separator.getAttribute('aria-valuemin'))).toBe(0)
    await page.keyboard.press('End')
    await expect.poll(async () => Number(await separator.getAttribute('aria-valuenow'))
      - Number(await separator.getAttribute('aria-valuemax'))).toBe(0)
    await page.keyboard.press('Enter')
    await expect(separator).toHaveAttribute('aria-valuenow', '260')
    expect(fixtures.unknownRequests).toEqual([])
    await page.screenshot({ path: testInfo.outputPath('workspace-resize.png'), fullPage: true })
  })

  test('pointercancel 恢复拖动前偏好并释放全局拖动状态', async ({ page }) => {
    const fixtures = await installWorkspaceFixtures(page)
    await openVibeCodingWorkspace(page)
    const separator = page.getByRole('separator', { name: '调整对话区与产物文件区宽度' })

    await separator.focus()
    await page.keyboard.press('ArrowLeft')
    await page.keyboard.press('ArrowRight')
    await expect.poll(async () => Math.abs((await panelMetrics(page)).artifactsWidth - 260))
      .toBeLessThanOrEqual(1)

    const pointerId = await startSeparatorDrag(page, -64)
    await expect(separator).toHaveAttribute('aria-valuenow', '324')
    await expect(page.locator('body')).toHaveClass(/is-vibecoding-panel-resizing/)
    await separator.evaluate((element) => {
      element.addEventListener('lostpointercapture', () => {
        element.setAttribute('data-e2e-cancel-capture-released', 'true')
      }, { once: true })
    })
    await separator.dispatchEvent('pointercancel', {
      pointerId,
      pointerType: 'mouse',
      isPrimary: true,
      bubbles: true,
    })
    await page.mouse.up()

    await expect(separator).toHaveAttribute('data-e2e-cancel-capture-released', 'true')
    await expect(separator).toHaveAttribute('aria-valuenow', '260')
    await expect(page.locator('body')).not.toHaveClass(/is-vibecoding-panel-resizing/)
    expect(await page.evaluate((key) => localStorage.getItem(key), ARTIFACTS_WIDTH_STORAGE_KEY)).toBe('260')
    expect(fixtures.unknownRequests).toEqual([])
  })

  test('lostpointercapture 保留并持久化最后有效宽度', async ({ page }) => {
    const fixtures = await installWorkspaceFixtures(page)
    await openVibeCodingWorkspace(page)
    const separator = page.getByRole('separator', { name: '调整对话区与产物文件区宽度' })

    const pointerId = await startSeparatorDrag(page, -48)
    await expect(separator).toHaveAttribute('aria-valuenow', '308')
    expect(await separator.evaluate((element, activePointerId) => (
      element.hasPointerCapture(activePointerId)
    ), pointerId)).toBe(true)
    await separator.evaluate((element, activePointerId) => new Promise<void>((resolve, reject) => {
      const timeoutId = window.setTimeout(() => reject(new Error('lostpointercapture was not dispatched')), 1000)
      element.addEventListener('lostpointercapture', (event) => {
        window.clearTimeout(timeoutId)
        if (event.pointerId !== activePointerId) {
          reject(new Error(`Unexpected pointer id: ${event.pointerId}`))
          return
        }
        resolve()
      }, { once: true })
      element.releasePointerCapture(activePointerId)
    }), pointerId)
    await expect(page.locator('body')).not.toHaveClass(/is-vibecoding-panel-resizing/)
    await page.mouse.up()

    await expect.poll(() => separator.evaluate((element, activePointerId) => (
      element.hasPointerCapture(activePointerId)
    ), pointerId)).toBe(false)
    expect(await page.evaluate((key) => localStorage.getItem(key), ARTIFACTS_WIDTH_STORAGE_KEY)).toBe('308')
    expect(fixtures.unknownRequests).toEqual([])
  })

  test('拖动中切入 stacked 会取消手势并可在回到宽屏后继续拖动', async ({ page }) => {
    const fixtures = await installWorkspaceFixtures(page)
    await openVibeCodingWorkspace(page)
    const separator = page.getByRole('separator', { name: '调整对话区与产物文件区宽度' })

    await separator.focus()
    await page.keyboard.press('ArrowLeft')
    await page.keyboard.press('ArrowRight')
    await expect.poll(async () => Math.abs((await panelMetrics(page)).artifactsWidth - 260))
      .toBeLessThanOrEqual(1)
    await startSeparatorDrag(page, -48)
    await expect(page.locator('body')).toHaveClass(/is-vibecoding-panel-resizing/)

    await page.setViewportSize({ width: 760, height: 1000 })
    await expect.poll(async () => (await panelMetrics(page)).panelWidth).toBeLessThanOrEqual(700)
    await expect(separator).toHaveCount(0)
    await expect(page.locator('body')).not.toHaveClass(/is-vibecoding-panel-resizing/)
    await page.mouse.up()
    expect(await page.evaluate((key) => localStorage.getItem(key), ARTIFACTS_WIDTH_STORAGE_KEY)).toBe('260')

    await page.setViewportSize({ width: 1600, height: 1000 })
    await expect(separator).toBeVisible()
    await expect(separator).toHaveAttribute('aria-valuenow', '260')
    await expect.poll(async () => Math.abs((await panelMetrics(page)).artifactsWidth - 260))
      .toBeLessThanOrEqual(1)
    await dragSeparator(page, -32)
    await expect(separator).toHaveAttribute('aria-valuenow', '292')
    expect(fixtures.unknownRequests).toEqual([])
  })
})
