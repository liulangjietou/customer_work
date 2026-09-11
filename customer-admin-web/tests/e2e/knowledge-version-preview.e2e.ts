import { expect, test } from './fixtures/adminTestFixture'
import type { Page } from '@playwright/test'

const metadata = (revisionId = 100, versionId = 70) => ({
  knowledgeBaseId: 7,
  versionId,
  versionNo: versionId === 70 ? 3 : 4,
  revisionId,
  status: 'AVAILABLE',
  title: versionId === 70 ? '退款期限说明' : '退款期限新版说明',
  externalId: 'refund-policy',
  sourceName: '售后政策源',
  sourceVersion: versionId === 70 ? 'policy-2026-08' : 'policy-2026-09',
  sourceUri: 'https://policy.example.invalid/refunds',
  contentHash: 'c7'.repeat(32),
  currentRevision: versionId !== 70,
  sourceUpdatedAt: '2026-08-15 10:00:00',
  revisionCreatedAt: '2026-08-15 10:10:00',
})
const version = (id: number) => ({
  id,
  versionNo: id === 70 ? 3 : 4,
  checkpoint: `policy-${id}`,
  snapshotHash: 'snapshot-hash',
  documentCount: id === 70 ? 22 : 1,
  qualityScore: 1,
  qualityStatus: 'PASSED',
  changeNote: `来源版本 ${id}`,
  createTime: '',
})
const original = '签收后七天内可申请退货。\n\n退款进度以订单的实际处理结果为准。'

async function fixture(page: Page, previewAllowed = true) {
  await page.route('**/api/auth/permissions', (route) =>
    route.fulfill({
      json: {
        code: 0,
        data: previewAllowed
          ? ['knowledge-base:view', 'knowledge-base:source-preview']
          : ['knowledge-base:view'],
      },
    }),
  )
  await page.route('**/api/aiconfig/knowledge-base/page?*', (route) =>
    route.fulfill({
      json: {
        code: 0,
        data: {
          total: 1,
          list: [
            {
              id: 7,
              kbName: '售后服务知识库',
              baseUrl: 'https://knowledge.example.invalid',
              appId: 'fixture',
              apiKeyMasked: '****',
              contentType: 'application/json',
              extraHeaders: null,
              topN: 5,
              scoreThreshold: 0.15,
              status: 1,
              testStatus: 1,
              testTime: '',
              remark: '',
              currentVersionId: 71,
              latestVersionNo: 4,
              createTime: '',
              updateTime: '',
            },
          ],
        },
      },
    }),
  )
  await page.route('**/api/aiconfig/knowledge-base/7/sources', (route) =>
    route.fulfill({ json: { code: 0, data: [] } }),
  )
  await page.route('**/api/aiconfig/knowledge-base/7/versions', (route) =>
    route.fulfill({ json: { code: 0, data: [version(70), version(71)] } }),
  )
  await page.route('**/api/aiconfig/knowledge-base/7/versions/*/documents?*', (route) => {
    const url = new URL(route.request().url())
    const versionId = Number(url.pathname.split('/')[6])
    const pageNum = Number(url.searchParams.get('pageNum') ?? 1)
    return route.fulfill({
      json: {
        code: 0,
        data: {
          pageNum,
          pageSize: 20,
          total: versionId === 70 ? 22 : 1,
          list: [
            {
              ...metadata(pageNum === 1 ? 100 : 120, versionId),
              title: pageNum === 2 ? '退款凭证说明' : metadata(100, versionId).title,
            },
          ],
        },
      },
    })
  })
  await page.route('**/api/aiconfig/knowledge-base/7/versions/*/documents/*/preview', (route) => {
    const parts = new URL(route.request().url()).pathname.split('/')
    const versionId = Number(parts[6])
    const revisionId = Number(parts[8])
    return route.fulfill({
      json: {
        code: 0,
        data: {
          document: metadata(revisionId, versionId),
          content: versionId === 70 ? original : '新版独立正文',
        },
      },
    })
  })
}
async function openVersions(page: Page) {
  await page.goto('/aiconfig/knowledge-base')
  await page.getByRole('button', { name: '管理知识源' }).click()
  await page.getByRole('tab', { name: '版本记录', exact: true }).click()
}
async function openReader(page: Page, index = 0) {
  await page.getByRole('button', { name: '查看版本原文', exact: true }).nth(index).click()
  const reader = page.locator('.knowledge-version-preview:visible')
  await expect(reader).toBeVisible()
  return reader
}

test('历史版本展示冻结正文和来源详情，目录可以翻页', async ({ page }, testInfo) => {
  await fixture(page)
  await openVersions(page)
  const reader = await openReader(page)
  await reader.getByRole('button', { name: /退款期限说明/ }).click()
  await expect(reader.getByLabel('已授权的版本原文')).toHaveText(original)
  await expect(reader).toContainText('历史文档修订')
  await expect(reader).toContainText('修订 #100')
  await reader.getByRole('button', { name: '查看来源详情' }).click()
  await expect(reader).toContainText('policy-2026-08')
  await expect(
    reader.getByText('https://policy.example.invalid/refunds', { exact: true }),
  ).toBeVisible()
  await page.screenshot({
    path: testInfo.outputPath('knowledge-version-desktop.png'),
    animations: 'disabled',
  })
  const desktopSize = page.viewportSize()!
  await page.setViewportSize({ width: 390, height: 844 })
  await page.screenshot({
    path: testInfo.outputPath('knowledge-version-mobile-reading.png'),
    animations: 'disabled',
  })
  await page.setViewportSize(desktopSize)
  await reader.getByRole('button', { name: '下一页', exact: true }).click()
  await expect(reader.getByRole('button', { name: /退款凭证说明/ })).toBeVisible()
  await expect(reader.getByLabel('已授权的版本原文')).toHaveCount(0)
})

test('目录与原文首次读取失败均能原地重试', async ({ page }) => {
  await fixture(page)
  let listAttempts = 0
  let bodyAttempts = 0
  await page.route('**/api/aiconfig/knowledge-base/7/versions/70/documents?*', (route) =>
    route.fulfill({
      json:
        ++listAttempts === 1
          ? { code: 50000, message: '数据库暂不可用' }
          : { code: 0, data: { pageNum: 1, pageSize: 20, total: 1, list: [metadata()] } },
    }),
  )
  await page.route('**/api/aiconfig/knowledge-base/7/versions/70/documents/100/preview', (route) =>
    route.fulfill({
      json:
        ++bodyAttempts === 1
          ? { code: 50000, message: '暂不可用' }
          : { code: 0, data: { document: metadata(), content: original } },
    }),
  )
  await openVersions(page)
  const reader = await openReader(page)
  await expect(reader).toContainText('文档目录加载失败')
  await reader.getByRole('button', { name: '重新加载', exact: true }).click()
  await reader.getByRole('button', { name: /退款期限说明/ }).click()
  await expect(reader).toContainText('原文暂不可读')
  await reader.getByRole('button', { name: '重试读取' }).click()
  await expect(reader.getByLabel('已授权的版本原文')).toHaveText(original)
  expect(listAttempts).toBe(2)
  expect(bodyAttempts).toBe(2)
})

for (const [code, message] of [
  [20001, '当前账号无权预览'],
  [30003, '此版本来源已不可用'],
] as const) {
  test(`重新核验返回 ${code} 时立即清除已经读过的原文`, async ({ page }) => {
    await fixture(page)
    let attempts = 0
    await page.route(
      '**/api/aiconfig/knowledge-base/7/versions/70/documents/100/preview',
      (route) =>
        route.fulfill({
          json:
            ++attempts === 1
              ? { code: 0, data: { document: metadata(), content: original } }
              : { code, message: '来源访问状态已变化' },
        }),
    )
    await openVersions(page)
    const reader = await openReader(page)
    await reader.getByRole('button', { name: /退款期限说明/ }).click()
    await expect(reader.getByLabel('已授权的版本原文')).toHaveText(original)
    await reader.getByRole('button', { name: '重新核验' }).click()
    await expect(reader.getByRole('alert')).toContainText(message)
    await expect(reader.getByLabel('已授权的版本原文')).toHaveCount(0)
    await expect(reader).not.toContainText('签收后七天')
  })
}

test('切换版本后旧原文的迟到响应不能覆盖新版本', async ({ page }) => {
  await fixture(page)
  let release = () => {}
  const held = new Promise<void>((resolve) => {
    release = resolve
  })
  let started = false
  await page.route(
    '**/api/aiconfig/knowledge-base/7/versions/70/documents/100/preview',
    async (route) => {
      started = true
      await held
      await route.fulfill({ json: { code: 0, data: { document: metadata(), content: original } } })
    },
  )
  await openVersions(page)
  let reader = await openReader(page)
  await reader.getByRole('button', { name: /退款期限说明/ }).click()
  await expect.poll(() => started).toBe(true)
  await reader.locator('.el-drawer__close-btn').click()
  await expect(reader).toHaveCount(0)
  reader = await openReader(page, 1)
  await reader.getByRole('button', { name: /退款期限新版说明/ }).click()
  await expect(reader.getByLabel('已授权的版本原文')).toHaveText('新版独立正文')
  const response = page.waitForResponse((url) => url.url().endsWith('/70/documents/100/preview'))
  release()
  await (await response).finished()
  await page.evaluate(() => new Promise<void>((resolve) => requestAnimationFrame(() => resolve())))
  await expect(reader.getByLabel('已授权的版本原文')).toHaveText('新版独立正文')
  await expect(reader).not.toContainText('签收后七天')
})

test('仅有知识库查看权限的账号没有原文入口', async ({ page }) => {
  await fixture(page, false)
  await openVersions(page)
  await expect(page.getByText('来源版本 70', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '查看版本原文', exact: true })).toHaveCount(0)
})

test('目录的无权和失效成员不发起原文请求，错误版本的正文也不会展示', async ({ page }) => {
  await fixture(page)
  await page.route('**/api/aiconfig/knowledge-base/7/versions/70/documents?*', (route) =>
    route.fulfill({
      json: {
        code: 0,
        data: {
          pageNum: 1,
          pageSize: 20,
          total: 3,
          list: [
            metadata(),
            { ...metadata(101), title: null, externalId: null, status: 'FORBIDDEN' },
            { ...metadata(102), title: null, externalId: null, status: 'UNAVAILABLE' },
          ],
        },
      },
    }),
  )
  await page.route('**/api/aiconfig/knowledge-base/7/versions/70/documents/100/preview', (route) =>
    route.fulfill({
      json: { code: 0, data: { document: metadata(100, 71), content: '错误版本的正文' } },
    }),
  )
  await openVersions(page)
  const reader = await openReader(page)
  await expect(reader.getByRole('button', { name: /当前账号无权预览/ })).toBeDisabled()
  await expect(reader.getByRole('button', { name: /来源已失效/ })).toBeDisabled()
  await reader.getByRole('button', { name: /退款期限说明/ }).click()
  await expect(reader).toContainText('原文暂不可读')
  await expect(reader).not.toContainText('错误版本的正文')
})

test('390px 长原文可阅读并返回目录，来源中的 HTML 与地址只显示为文字', async ({
  page,
}, testInfo) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await fixture(page)
  const unsafeText = '<img src="https://outside.example.invalid/pixel" onerror="alert(1)">'
  await page.route('**/api/aiconfig/knowledge-base/7/versions/70/documents/100/preview', (route) =>
    route.fulfill({
      json: {
        code: 0,
        data: {
          document: { ...metadata(), sourceUri: 'javascript:alert(1)' },
          content:
            original +
            '\n\n' +
            '阅读时保留文档版本，申请结果以业务记录为准。'.repeat(120) +
            '\n' +
            unsafeText,
        },
      },
    }),
  )
  await openVersions(page)
  const reader = await openReader(page)
  await reader.getByRole('button', { name: /退款期限说明/ }).click()
  await expect(reader.getByLabel('已授权的版本原文')).toContainText(unsafeText)
  await expect(reader.locator('img')).toHaveCount(0)
  await reader.getByRole('button', { name: '查看来源详情' }).click()
  await expect(reader.getByText('javascript:alert(1)', { exact: true })).toBeVisible()
  await expect(reader.locator('a[href^="javascript:"]')).toHaveCount(0)
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(390)
  const paper = await reader.getByLabel('已授权的版本原文').boundingBox()
  expect(paper!.width).toBeLessThanOrEqual(390)
  const back = reader.getByRole('button', { name: '返回文档列表' })
  expect((await back.boundingBox())!.height).toBeGreaterThanOrEqual(44)
  await page.screenshot({
    path: testInfo.outputPath('knowledge-version-mobile.png'),
    animations: 'disabled',
  })
  await reader.locator('.version-source').evaluate((element) => {
    element.scrollTop = element.scrollHeight
  })
  await expect(back).toBeInViewport()
  await back.click()
  await expect(reader.getByRole('button', { name: /退款期限说明/ })).toBeFocused()
})

// 权限刷新仍调用真实 auth store 与请求封装；仅后端权限响应由本地夹具提供。
test('预览期间权限收回会清空原文，迟到响应不能恢复访问', async ({ page }) => {
  await fixture(page)
  let allowed = true
  await page.route('**/api/auth/permissions', (route) =>
    route.fulfill({
      json: {
        code: 0,
        data: allowed
          ? ['knowledge-base:view', 'knowledge-base:source-preview']
          : ['knowledge-base:view'],
      },
    }),
  )
  let release = () => {}
  const pending = new Promise<void>((resolve) => {
    release = resolve
  })
  let requested = false
  await page.route(
    '**/api/aiconfig/knowledge-base/7/versions/70/documents/100/preview',
    async (route) => {
      requested = true
      await pending
      await route.fulfill({ json: { code: 0, data: { document: metadata(), content: original } } })
    },
  )
  await openVersions(page)
  const reader = await openReader(page)
  await reader.getByRole('button', { name: /退款期限说明/ }).click()
  await expect.poll(() => requested).toBe(true)
  allowed = false
  await page.evaluate(async () => {
    const modulePath = '/src/store/auth.ts'
    const { useAuthStore } = await import(modulePath)
    await useAuthStore().loadPermissions()
  })
  await expect(reader).toContainText('当前账号没有原文预览权限')
  const response = page.waitForResponse((result) =>
    result.url().endsWith('/70/documents/100/preview'),
  )
  release()
  await (await response).finished()
  await page.evaluate(() => new Promise<void>((resolve) => requestAnimationFrame(() => resolve())))
  await expect(reader.getByLabel('已授权的版本原文')).toHaveCount(0)
  await expect(reader).not.toContainText('签收后七天')
})
