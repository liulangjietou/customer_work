import { expect, test } from './fixtures/adminTestFixture'

const knowledgeBase = (id: number) => ({
  id,
  kbName: `知识库 ${id}`,
  baseUrl: 'https://knowledge.example.invalid',
  appId: 'fixture-app',
  apiKeyMasked: '****',
  contentType: 'application/json',
  extraHeaders: null,
  topN: 5,
  scoreThreshold: 0.15,
  status: 1,
  testStatus: 1,
  testTime: '2026-09-11 09:00:00',
  remark: '售后与服务规范',
  currentVersionId: id * 100,
  latestVersionNo: 1,
  createTime: '',
  updateTime: '',
})

test('知识源抽屉首次失败可原地重试', async ({ page }) => {
  let attempts = 0
  await page.route('**/api/aiconfig/knowledge-base/page?*', (route) =>
    route.fulfill({
      json: { code: 0, data: { list: [knowledgeBase(1)], total: 1 } },
    }),
  )
  await page.route('**/api/aiconfig/knowledge-base/1/sources', (route) => {
    attempts += 1
    return route.fulfill({
      json: attempts === 1 ? { code: 50000, message: '文档源暂不可用' } : { code: 0, data: [] },
    })
  })
  await page.route('**/api/aiconfig/knowledge-base/1/versions', (route) =>
    route.fulfill({ json: { code: 0, data: [] } }),
  )
  await page.goto('/aiconfig/knowledge-base')
  await page.getByRole('button', { name: '管理知识源' }).click()
  const drawer = page.locator('.el-drawer:visible')
  await expect(drawer.locator('.crud-load-state')).toContainText('数据加载失败')
  await drawer.getByRole('button', { name: '重新加载', exact: true }).click()
  await expect(drawer.locator('.crud-load-state')).not.toBeVisible()
  expect(attempts).toBe(2)
})

test('切换知识库后，前一个抽屉的迟到响应不会覆盖当前版本', async ({ page }) => {
  let releaseFirst: () => void = () => {}
  const firstRequest = new Promise<void>((resolve) => {
    releaseFirst = resolve
  })
  let firstStarted = false
  await page.route('**/api/aiconfig/knowledge-base/page?*', (route) =>
    route.fulfill({
      json: { code: 0, data: { list: [knowledgeBase(1), knowledgeBase(2)], total: 2 } },
    }),
  )
  await page.route('**/api/aiconfig/knowledge-base/*/sources', (route) =>
    route.fulfill({ json: { code: 0, data: [] } }),
  )
  await page.route('**/api/aiconfig/knowledge-base/*/versions', async (route) => {
    const first = route.request().url().includes('/1/versions')
    if (first) {
      firstStarted = true
      await firstRequest
    }
    await route.fulfill({
      json: {
        code: 0,
        data: [
          {
            id: first ? 100 : 200,
            versionNo: 1,
            checkpoint: null,
            snapshotHash: 'fixture-hash',
            documentCount: 4,
            qualityScore: 1,
            qualityStatus: 'PASSED',
            changeNote: first ? '来自第一个知识库的版本' : '来自第二个知识库的版本',
            createTime: '',
          },
        ],
      },
    })
  })
  await page.goto('/aiconfig/knowledge-base')
  await page.getByRole('button', { name: '管理知识源' }).nth(0).click()
  await expect.poll(() => firstStarted).toBe(true)
  await page.locator('.el-drawer:visible .el-drawer__close-btn').click()
  await expect(page.locator('.el-drawer:visible')).toHaveCount(0)
  await page.getByRole('button', { name: '管理知识源' }).nth(1).click()
  await page.getByRole('tab', { name: '版本记录', exact: true }).click()
  await expect(page.getByText('来自第二个知识库的版本', { exact: true })).toBeVisible()
  const firstResponse = page.waitForResponse((response) =>
    response.url().endsWith('/knowledge-base/1/versions'),
  )
  releaseFirst()
  await (await firstResponse).finished()
  // 等待已结束响应对应的 Vue 更新帧，防止断言在迟到结果送达前提前通过。
  await page.evaluate(() => new Promise<void>((resolve) => requestAnimationFrame(() => resolve())))
  await expect(page.getByText('来自第二个知识库的版本', { exact: true })).toBeVisible()
  await expect(page.getByText('来自第一个知识库的版本', { exact: true })).not.toBeVisible()
})
