import type { Page, Route } from '@playwright/test'
import { readFile } from 'node:fs/promises'
import { expect, test } from './fixtures/adminTestFixture'

const ok = (data: unknown) => ({ code: 0, data })
const permissions = ['eval:view', 'eval:run', 'eval:dataset-edit', 'eval:dataset-review']
const datasetCase = { caseId: 'acceptance-case', evalType: 'INTENT', input: '验收订单查询',
  expected: 'ORDER', category: '订单', source: 'MANUAL', enabled: true, originRef: null, createdAtMs: 1789444800000 }
const releaseVersion = { releaseId: 'acceptance-release', evalType: 'INTENT', versionName: '验收数据集版本',
  snapshotVersionId: 'acceptance-snapshot-001', contentHash: 'acceptance-hash', status: 'DRAFT',
  caseCount: 1, createdAtMs: 1789444800000, createdBy: 7, reviewComment: null }
const run = { runId: 'acceptance-run', evalType: 'INTENT', total: 1, passed: 1, primaryMetric: 1,
  secondaryMetric: 1, failedCaseIds: [], failures: [], metrics: {}, trigger: 'MANUAL', datasetSize: 1,
  remark: '验收运行', createdAtMs: 1789444800000 }
const comparison = { current: run, baseline: null, regressions: [], fixes: [], verdict: 'FIRST_RUN',
  primaryDelta: 0, secondaryDelta: 0, datasetChanged: false }

async function setup(page: Page, granted = permissions) {
  const writes: string[] = []
  await page.route('**/api/auth/permissions', route => route.fulfill({ json: ok(granted) }))
  await page.route('**/api/eval/**', async route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (request.method() !== 'GET') {
      writes.push(path)
      await route.fulfill({ json: ok(path === '/api/eval/run' ? comparison : datasetCase) })
      return
    }
    if (path === '/api/eval/runs') await route.fulfill({ json: ok([]) })
    else if (path.endsWith('/comparison')) await route.fulfill({ json: ok(comparison) })
    else if (path.endsWith('/cases') || path.endsWith('/export')) await route.fulfill({ json: ok([datasetCase]) })
    else if (path.endsWith('/versions')) await route.fulfill({ json: ok([releaseVersion]) })
    else await route.fallback()
  })
  await page.goto('/ops/eval')
  await expect(page.getByRole('button', { name: '导出 JSON', exact: true })).toBeVisible()
  return writes
}
async function identity(page: Page) {
  await page.evaluate(async permissions => {
    const source = '/src/store/auth.ts'
    const { useAuthStore } = await import(source)
    const auth = useAuthStore()
    auth.applyLoginResult({ token: auth.token, nickname: '新身份', forceChangePassword: false,
      approvalStatus: 'APPROVED', approvalRemark: null }, 'eval-later-user')
    auth.permissions = permissions
  }, permissions)
  await settle(page)
}
async function settle(page: Page) {
  await page.evaluate(async () => {
    const paint = () => new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve())))
    await paint()
    await Promise.all(document.getAnimations().filter(a => Number.isFinite(Number(a.effect?.getComputedTiming().endTime)))
      .map(a => a.finished.catch(() => {})))
    await paint()
  })
}
async function release(page: Page, route: Route, data: unknown) {
  const request = route.request()
  const waiting = page.waitForResponse(response => response.request() === request)
  await route.fulfill({ json: ok(data) })
  await (await waiting).finished()
  await settle(page)
}
const caseForm = (page: Page) => page.getByRole('dialog', { name: '新增评测用例', exact: true })
async function newCase(page: Page, id: string) {
  await page.getByRole('button', { name: '新增用例', exact: true }).click()
  await caseForm(page).getByLabel('用例编号', { exact: true }).fill(id)
  await caseForm(page).getByLabel('用户输入', { exact: true }).fill('验收新增输入')
}

test('评测触发确认期间重新登录不得以新身份运行旧请求', async ({ page }) => {
  const writes = await setup(page)
  await page.getByRole('button', { name: '立即评测', exact: true }).click()
  const confirmation = page.getByRole('dialog', { name: '立即跑一次意图评测', exact: true })
  await expect(confirmation).toBeVisible()
  await identity(page)
  if (await confirmation.isVisible()) await confirmation.getByRole('button', { name: '开始评测', exact: true }).click()
  await settle(page)
  expect(writes).toEqual([])
})

test('评测用例保存失败保留输入，重试请求期间锁定表单', async ({ page }) => {
  await setup(page)
  let held: Route | undefined
  let writes = 0
  await page.route('**/api/eval/datasets/INTENT/cases', route => {
    if (route.request().method() === 'GET') return route.fulfill({ json: ok([datasetCase]) })
    writes += 1; held = route
  })
  await newCase(page, 'new-case')
  await caseForm(page).getByRole('button', { name: '保存用例', exact: true }).click()
  await expect.poll(() => Boolean(held)).toBe(true)
  await held!.fulfill({ json: { code: 50000, message: '验收用例保存失败' } })
  await expect(caseForm(page).getByLabel('用户输入', { exact: true })).toHaveValue('验收新增输入')
  held = undefined
  await caseForm(page).getByRole('button', { name: '保存用例', exact: true }).click()
  await expect.poll(() => Boolean(held)).toBe(true)
  await expect(caseForm(page).getByLabel('用户输入', { exact: true })).toBeDisabled()
  await release(page, held!, datasetCase)
  await expect(caseForm(page)).not.toBeVisible()
  expect(writes).toBe(2)
})

test('评测旧用例保存完成不能关闭新打开的草稿', async ({ page }) => {
  await setup(page)
  let held: Route | undefined
  await page.route('**/api/eval/datasets/INTENT/cases', route => {
    if (route.request().method() === 'GET') return route.fulfill({ json: ok([datasetCase]) })
    held = route
  })
  await newCase(page, 'old-case')
  await caseForm(page).getByRole('button', { name: '保存用例', exact: true }).click()
  await expect.poll(() => Boolean(held)).toBe(true)
  await caseForm(page).getByRole('button', { name: '取消', exact: true }).click()
  await newCase(page, 'new-unsent-case')
  await release(page, held!, datasetCase)
  await expect(caseForm(page)).toBeVisible()
  await expect(caseForm(page).getByLabel('用例编号', { exact: true })).toHaveValue('new-unsent-case')
})

test('评测用例删除确认期间重新登录不得删除旧身份覆盖', async ({ page }) => {
  const writes = await setup(page)
  await page.getByRole('row').filter({ hasText: 'acceptance-case' }).getByRole('button', { name: '删除覆盖', exact: true }).click()
  const confirmation = page.getByRole('dialog', { name: '删除数据库覆盖', exact: true })
  await expect(confirmation).toBeVisible()
  await identity(page)
  if (await confirmation.isVisible()) await confirmation.getByRole('button', { name: '确定', exact: true }).click()
  await settle(page)
  expect(writes).toEqual([])
})

test('评测 JSON 导入失败保留整批输入并支持原地重试', async ({ page }) => {
  await setup(page)
  let held: Route | undefined
  let calls = 0
  await page.route('**/api/eval/datasets/INTENT/import', route => { calls += 1; held = route })
  await page.getByRole('button', { name: '导入 JSON', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '导入评测用例', exact: true })
  const payload = JSON.stringify([{ caseId: 'import-case', input: '完整保留的导入输入' }])
  await dialog.getByRole('textbox').fill(payload)
  await dialog.getByRole('button', { name: /确定|导入/ }).click()
  await expect.poll(() => Boolean(held)).toBe(true)
  await held!.fulfill({ json: { code: 50000, message: '验收批量导入失败' } })
  await settle(page)
  await expect(dialog).toBeVisible()
  await expect(dialog.getByRole('textbox')).toHaveValue(payload)
  held = undefined
  await dialog.getByRole('button', { name: /确定|导入/ }).click()
  await expect.poll(() => Boolean(held)).toBe(true)
  await release(page, held!, [datasetCase])
  await expect(dialog).not.toBeVisible()
  expect(calls).toBe(2)
})

test('评测命名版本确认期间重新登录不得创建旧请求版本', async ({ page }) => {
  const writes = await setup(page)
  await page.getByRole('button', { name: '创建命名版本', exact: true }).click()
  const confirmation = page.getByRole('dialog', { name: '创建数据集版本', exact: true })
  await confirmation.getByRole('textbox').fill('未授权的新身份版本')
  await identity(page)
  if (await confirmation.isVisible()) await confirmation.getByRole('button', { name: '确定', exact: true }).click()
  await settle(page)
  expect(writes).toEqual([])
})

for (const action of ['通过', '驳回'] as const) {
  test(`评测版本${action}确认期间重新登录不能提交旧审核`, async ({ page }) => {
    const writes = await setup(page)
    await page.getByRole('tab', { name: '命名版本与审核', exact: true }).click()
    await page.getByRole('row').filter({ hasText: '验收数据集版本' }).getByRole('button', { name: action, exact: true }).click()
    const confirmation = page.getByRole('dialog', { name: `${action}版本`, exact: true })
    await expect(confirmation).toBeVisible()
    await identity(page)
    if (await confirmation.isVisible()) await confirmation.getByRole('button', { name: '确定', exact: true }).click()
    await settle(page)
    expect(writes).toEqual([])
  })
}

test('评测数据集导出的旧身份响应不得触发下载', async ({ page }) => {
  await setup(page)
  let held: Route | undefined
  let downloads = 0
  page.on('download', () => { downloads += 1 })
  await page.route('**/api/eval/datasets/INTENT/export', route => { held = route })
  await page.getByRole('button', { name: '导出 JSON', exact: true }).click()
  await expect.poll(() => Boolean(held)).toBe(true)
  await identity(page)
  await release(page, held!, [datasetCase])
  expect(downloads).toBe(0)
})

test('评测工作集旧身份读取不得覆盖重新登录后的空工作集', async ({ page }) => {
  await setup(page)
  let held: Route | undefined
  await page.route('**/api/eval/datasets/QUALITY/cases', route => { held = route })
  await page.getByText('回复质量', { exact: true }).click()
  await expect.poll(() => Boolean(held)).toBe(true)
  const old = held!
  await page.route('**/api/eval/datasets/*/cases', route => route.fulfill({ json: ok([]) }))
  await identity(page)
  await release(page, old, [{ ...datasetCase, caseId: 'old-identity-case', input: '旧身份内容不应出现' }])
  await expect(page.getByRole('row').filter({ hasText: 'old-identity-case' })).toHaveCount(0)
  await expect(page.getByText('旧身份内容不应出现', { exact: true })).toHaveCount(0)
})

test('评测命名版本创建失败保留名称，重试锁定输入并提交同一类型', async ({ page }) => {
  await setup(page)
  const payloads: unknown[] = []
  let held: Route | undefined
  await page.route('**/api/eval/datasets/INTENT/versions', route => {
    if (route.request().method() === 'GET') return route.fulfill({ json: ok([releaseVersion]) })
    payloads.push(route.request().postDataJSON()); held = route
  })
  await page.getByRole('button', { name: '创建命名版本', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '创建数据集版本', exact: true })
  await dialog.getByRole('textbox').fill('保留的验收版本')
  await dialog.getByRole('button', { name: '确定', exact: true }).click()
  await expect.poll(() => Boolean(held)).toBe(true)
  await expect(dialog.getByRole('textbox')).toBeDisabled()
  await held!.fulfill({ json: { code: 50000, message: '验收创建失败' } })
  await expect(dialog.getByRole('textbox')).toBeEnabled()
  await expect(dialog.getByRole('textbox')).toHaveValue('保留的验收版本')
  held = undefined
  await dialog.getByRole('button', { name: '确定', exact: true }).click()
  await expect.poll(() => Boolean(held)).toBe(true)
  await release(page, held!, releaseVersion)
  await expect(dialog).not.toBeVisible()
  expect(payloads).toEqual([{ versionName: '保留的验收版本' }, { versionName: '保留的验收版本' }])
})

test('评测版本审核失败保留意见，恢复后显示已通过状态', async ({ page }) => {
  await setup(page)
  let held: Route | undefined
  const payloads: unknown[] = []
  await page.route('**/api/eval/datasets/versions/acceptance-release/review', route => {
    payloads.push(route.request().postDataJSON()); held = route
  })
  await page.getByRole('tab', { name: '命名版本与审核', exact: true }).click()
  const row = page.getByRole('row').filter({ hasText: '验收数据集版本' })
  await row.getByRole('button', { name: '通过', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '通过版本', exact: true })
  await dialog.getByRole('textbox').fill('已核对快照内容')
  await dialog.getByRole('button', { name: '确定', exact: true }).click()
  await expect.poll(() => Boolean(held)).toBe(true)
  await expect(dialog.getByRole('textbox')).toBeDisabled()
  await held!.fulfill({ json: { code: 50000, message: '验收审核失败' } })
  await expect(dialog.getByRole('textbox')).toBeEnabled()
  await expect(dialog.getByRole('textbox')).toHaveValue('已核对快照内容')
  held = undefined
  await dialog.getByRole('button', { name: '确定', exact: true }).click()
  await expect.poll(() => Boolean(held)).toBe(true)
  await page.route('**/api/eval/datasets/INTENT/versions', route => route.fulfill({ json: ok([{ ...releaseVersion, status: 'APPROVED' }]) }))
  await release(page, held!, { ...releaseVersion, status: 'APPROVED' })
  await expect(dialog).not.toBeVisible()
  await expect(row).toContainText('APPROVED')
  await expect(row.getByRole('button', { name: '通过', exact: true })).toHaveCount(0)
  expect(payloads).toEqual(Array(2).fill({ decision: 'APPROVED', comment: '已核对快照内容' }))
})

test('评测正常触发后刷新运行记录并打开对应报告', async ({ page }) => {
  const writes = await setup(page)
  await page.route('**/api/eval/runs?*', route => route.fulfill({ json: ok([run]) }))
  await page.getByRole('button', { name: '立即评测', exact: true }).click()
  const confirmation = page.getByRole('dialog', { name: '立即跑一次意图评测', exact: true })
  await confirmation.getByRole('textbox').fill('验收运行')
  await confirmation.getByRole('button', { name: '开始评测', exact: true }).click()
  const detail = page.getByRole('dialog', { name: '运行详情与版本对比', exact: true })
  await expect(detail).toContainText('首次运行')
  await expect(page.locator('.list-card').getByRole('row').filter({ hasText: '验收运行' })).toHaveCount(1)
  expect(writes).toEqual(['/api/eval/run'])
})

test('评测报告读取失败可原地重试，仍查询用户选择的运行', async ({ page }) => {
  await setup(page)
  await page.route('**/api/eval/runs?*', route => route.fulfill({ json: ok([run]) }))
  await page.getByRole('button', { name: '刷新', exact: true }).click()
  let reads = 0
  await page.route('**/api/eval/runs/acceptance-run/comparison', route => {
    reads += 1
    return route.fulfill({ json: reads === 1 ? { code: 50000, message: '验收报告失败' } : ok(comparison) })
  })
  await page.getByRole('button', { name: '详情与对比', exact: true }).click()
  const detail = page.getByRole('dialog', { name: '运行详情与版本对比', exact: true })
  await detail.getByRole('button', { name: '重新加载', exact: true }).click()
  await expect(detail).toContainText('首次运行')
  expect(reads).toBe(2)
})

test('评测版本差异读取失败重试仍比较同一对不可变版本', async ({ page }) => {
  await setup(page)
  const earlier = { ...releaseVersion, releaseId: 'earlier-release', versionName: '早期验收版本' }
  await page.route('**/api/eval/datasets/INTENT/versions', route => route.fulfill({ json: ok([releaseVersion, earlier]) }))
  await page.reload()
  await page.getByRole('tab', { name: '命名版本与审核', exact: true }).click()
  const pairs: string[][] = []
  await page.route('**/api/eval/datasets/versions/diff?*', route => {
    const query = new URL(route.request().url()).searchParams
    pairs.push([query.get('fromReleaseId')!, query.get('toReleaseId')!])
    return route.fulfill({ json: pairs.length === 1 ? { code: 50000, message: '验收差异失败' }
      : ok({ fromReleaseId: earlier.releaseId, toReleaseId: releaseVersion.releaseId,
        addedCaseIds: ['diff-case'], removedCaseIds: [], changedCases: [] }) })
  })
  await page.getByRole('row').filter({ hasText: '验收数据集版本' }).getByRole('button', { name: '与前版 diff', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '数据集版本差异', exact: true })
  await dialog.getByRole('button', { name: '重新加载', exact: true }).click()
  await expect(dialog).toContainText('diff-case')
  expect(pairs).toEqual(Array(2).fill(['earlier-release', 'acceptance-release']))
})


test('评测只读用户可以导出有效工作集，但没有写入或审核入口', async ({ page }, testInfo) => {
  const writes = await setup(page, ['eval:view'])
  for (const name of ['新增用例', '导入 JSON', '创建命名版本', '立即评测']) {
    await expect(page.getByRole('button', { name, exact: true })).toHaveCount(0)
  }
  await page.getByRole('tab', { name: '命名版本与审核', exact: true }).click()
  await expect(page.getByRole('button', { name: '通过', exact: true })).toHaveCount(0)
  await expect(page.getByRole('button', { name: '驳回', exact: true })).toHaveCount(0)
  const downloading = page.waitForEvent('download')
  await page.getByRole('button', { name: '导出 JSON', exact: true }).click()
  const download = await downloading
  expect(download.suggestedFilename()).toMatch(/^eval-intent-.*\.json$/)
  const output = testInfo.outputPath('readonly-eval-export.json')
  await download.saveAs(output)
  expect(JSON.parse(await readFile(output, 'utf8'))).toEqual([datasetCase])
  expect(writes).toEqual([])
})


for (const theme of ['ember', 'night'] as const) {
  test(`评测导入与审核在 ${theme} 主题的窄屏保留输入和提交区`, async ({ page }, testInfo) => {
    await page.addInitScript(theme => localStorage.setItem('customer-admin-theme-selection',
      JSON.stringify({ version: 1, kind: 'preset', id: theme })), theme)
    await page.setViewportSize({ width: 390, height: 760 })
    await setup(page)
    if (theme === 'ember') {
      await page.getByRole('button', { name: '导入 JSON', exact: true }).click()
      await page.getByRole('dialog', { name: '导入评测用例', exact: true }).getByRole('textbox').fill(JSON.stringify([
        { caseId: 'order-status', input: '我的订单现在到哪里了？', expected: 'ORDER', enabled: true },
      ], null, 2))
    } else {
      await page.getByRole('tab', { name: '命名版本与审核', exact: true }).click()
      await page.getByRole('row').filter({ hasText: '验收数据集版本' }).getByRole('button', { name: '通过', exact: true }).click()
      await page.getByRole('dialog', { name: '通过版本', exact: true }).getByRole('textbox').fill('已核对标准用例与版本快照')
    }
    const dialog = page.getByRole('dialog', { name: theme === 'ember' ? '导入评测用例' : '通过版本', exact: true })
    await settle(page)
    await expect(page.locator('html')).toHaveAttribute('data-theme', theme)
    const geometry = await dialog.evaluate(element => {
      const panel = element.querySelector('.el-dialog')!
      const footer = element.querySelector('.el-dialog__footer')!.getBoundingClientRect()
      return { width: panel.clientWidth, contentWidth: panel.scrollWidth, bottom: footer.bottom, viewport: innerHeight }
    })
    expect(geometry.contentWidth).toBeLessThanOrEqual(geometry.width + 1)
    expect(geometry.bottom).toBeLessThanOrEqual(geometry.viewport)
    await page.screenshot({ path: testInfo.outputPath(`eval-${theme}-390.png`), animations: 'disabled' })
  })
}
