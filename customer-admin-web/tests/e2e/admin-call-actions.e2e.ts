import type { Page, Route } from '@playwright/test'
import { expect, test } from './fixtures/adminTestFixture'
const ok = (data: unknown) => ({ code: 0, data })
const values = ['agent-call-stats:view', 'agent-call-stats:replay', 'agent-call-stats:delete']
const rows = [801, 802].map((id, i) => ({ id, requestId: `request-${id}`, sessionId: `session-${id}`, sessionType: 'EVALUATION',
  agentCode: 'acceptance-agent', agentName: '验收智能体', username: 'acceptance-user', question: `验收调用${i + 1}`, answerPreview: '验收响应',
  startTime: '2026-09-15 10:00:00', durationMs: 12, modelMs: 10, toolMs: 2, mcpMs: 0, skillMs: 0,
  totalTokens: 20, inputTokens: 10, outputTokens: 10, modelCostStatus: 'UNAVAILABLE', modelSegmentCount: 1,
  settledCostSegmentCount: 0, unsettledCostSegmentCount: 1 }))
const versions = { datasetVersion: '', datasetFingerprint: '', modelVersion: 'model-a', promptVersion: 'prompt-a', agentVersion: 'agent-a', knowledgeBaseVersion: 'kb-a', toolVersion: 'tool-a', judgeVersion: '', rubricVersion: '' }
const manifest = (index: number) => ({ schemaVersion: 3, mode: 'MOCK_DEFAULT', executable: true, executionBlockedReason: '', source: 'ADMIN',
  callLogId: rows[index].id, traceId: null, requestId: rows[index].requestId, agentCode: 'acceptance-agent', sessionType: 'EVALUATION',
  question: rows[index].question, recordedAnswer: '旧答案', startTime: rows[index].startTime, runtimeRevision: null,
  runtimeContentHash: null, experimentId: null, experimentRevision: null, experimentArm: null, experimentDeploymentId: null,
  experimentBucket: null, versionBinding: { ...versions, modelVersion: `manifest-model-${index}` }, segments: [], replaySnapshot: {}, supportedModes: ['MOCK'], captureWarnings: [] })
async function setup(page: Page) {
  await page.route('**/api/auth/permissions', r => r.fulfill({ json: ok(values) }))
  await page.route('**/api/agent-call-stats/page?*', r => r.fulfill({ json: ok({ rows, total: 2 }) }))
  await page.goto('/system/agent-call-stats')
}
async function deliver(page: Page, held: Route, data: unknown) {
  const response = page.waitForResponse(r => r.url() === held.request().url())
  await held.fulfill({ json: ok(data) })
  await (await response).finished()
  await page.evaluate(() => new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))))
}
for (const kind of ['detail', 'manifest'] as const) test(`调用${kind} 先前抽屉的迟到结果不能覆盖后来打开的记录`, async ({ page }) => {
  let held: Route | undefined
  const response = (index: number) => kind === 'detail'
    ? { ...rows[index], answer: `DETAIL-ANSWER-${index}`, segments: [], versionBinding: versions }
    : manifest(index)
  await page.route(url => kind === 'detail'
    ? /^\/api\/agent-call-stats\/80[12]$/.test(url.pathname)
    : /^\/api\/agent-call-stats\/80[12]\/replay-manifest$/.test(url.pathname), r => {
      if (r.request().url().includes('/801')) { held = r; return }
      return r.fulfill({ json: ok(response(1)) })
    })
  await setup(page)
  const open = async (i: number) => page.getByRole('row').filter({ hasText: `验收调用${i}` })
    .getByRole('button', { name: kind === 'detail' ? '耗时详情' : '重放清单', exact: true }).click()
  await open(1)
  await expect.poll(() => Boolean(held)).toBe(true)
  await page.locator('.el-drawer:visible .el-drawer__close-btn').click()
  await open(2)
  const drawer = page.locator('.el-drawer:visible')
  const marker = kind === 'detail' ? 'DETAIL-ANSWER-1' : 'manifest-model-1'
  await expect(drawer.getByText(marker, { exact: true }).first()).toBeVisible()
  await deliver(page, held!, response(0))
  await expect(drawer.getByText(marker, { exact: true }).first()).toBeVisible()
})
test('调用详情读取失败留在当前目标并可重试', async ({ page }) => {
  let failed = true
  await page.route('**/api/agent-call-stats/801?*', r => r.fulfill({ json: failed
    ? { code: 50000, message: '调用详情暂不可读' }
    : ok({ ...rows[0], answer: 'DETAIL-RECOVERED', segments: [], versionBinding: versions }) }))
  await setup(page)
  await page.getByRole('row').filter({ hasText: '验收调用1' }).getByRole('button', { name: '耗时详情', exact: true }).click()
  const drawer = page.locator('.el-drawer:visible')
  await expect(drawer.locator('.crud-load-state')).toBeVisible()
  failed = false
  await drawer.locator('.crud-load-state').getByRole('button').click()
  await expect(drawer.getByText('DETAIL-RECOVERED', { exact: true })).toBeVisible()
})
test('MOCK 迟到完成不能写入后来打开的调用重放清单', async ({ page }) => {
  let held: Route | undefined
  await page.route('**/api/agent-call-stats/*/replay-manifest?*', r => r.fulfill({ json: ok(manifest(r.request().url().includes('/801/') ? 0 : 1)) }))
  await page.route('**/api/agent-call-stats/801/replay?*', r => { held = r })
  await setup(page)
  const open = async (i: number) => page.getByRole('row').filter({ hasText: `验收调用${i}` }).getByRole('button', { name: '重放清单', exact: true }).click()
  await open(1)
  await page.locator('.el-drawer:visible').getByRole('button', { name: '执行 MOCK', exact: true }).click()
  await expect.poll(() => Boolean(held)).toBe(true)
  expect(held!.request().postDataJSON()).toEqual({ mode: 'MOCK' })
  await page.locator('.el-drawer:visible .el-drawer__close-btn').click()
  await open(2)
  const drawer = page.locator('.el-drawer:visible')
  await expect(drawer.getByText('manifest-model-1', { exact: true }).first()).toBeVisible()
  await deliver(page, held!, { replayId: 'old-replay', callLogId: 801, mode: 'MOCK', isolated: true, externalCallCount: 0,
    mockedModelCalls: 1, mockedRagRetrievals: 0, mockedToolCalls: 0, replayedAnswer: 'OLD-REPLAY-ANSWER',
    diff: { answerChanged: false, recordedAnswerSha256: 'x', replayedAnswerSha256: 'x', commonPrefixChars: 1, artifactVersions: [], warnings: ['OLD-REPLAY-DIFF-MARKER'] }, executedAtMs: 1 })
  await expect(drawer.locator('.replay-result')).toHaveCount(0)
  await expect(drawer.getByText('OLD-REPLAY-DIFF-MARKER', { exact: false })).toHaveCount(0)
})
test('调用删除确认等待期间重登，不允许发出先前身份的写入', async ({ page }) => {
  let writes = 0
  await page.route('**/api/agent-call-stats/801?*', r => { writes += 1; return r.fulfill({ json: ok(true) }) })
  await setup(page)
  await page.getByRole('row').filter({ hasText: '验收调用1' }).getByRole('button', { name: '删除', exact: true }).click()
  const confirm = page.getByRole('dialog', { name: '提示', exact: true })
  await page.evaluate(async permissions => {
    const modulePath = '/src/store/auth.ts'
    const { useAuthStore } = await import(modulePath)
    const auth = useAuthStore()
    auth.applyLoginResult({ token: auth.token, nickname: '新身份', forceChangePassword: false, approvalStatus: 'APPROVED', approvalRemark: null }, 'new-user')
    auth.permissions = permissions
  }, values)
  await confirm.getByRole('button', { name: '确定', exact: true }).click()
  await expect(confirm).not.toBeVisible()
  await page.evaluate(() => new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))))
  expect(writes).toBe(0)
})

test('调用详情沿用列表已接受的来源，不采用尚未查询的新来源', async ({ page }) => {
  const sources: string[] = []
  await page.route('**/api/agent-call-stats/801?*', r => {
    sources.push(new URL(r.request().url()).searchParams.get('source') ?? '')
    return r.fulfill({ json: ok({ ...rows[0], answer: 'DETAIL-ADMIN-RESULT', segments: [], versionBinding: versions }) })
  })
  await setup(page)
  await page.getByRole('row').filter({ hasText: '验收调用1' }).waitFor()
  await page.locator('.filter-card .el-select').first().click()
  await page.getByRole('option', { name: '客服端（App）', exact: true }).click()
  await page.getByRole('row').filter({ hasText: '验收调用1' }).getByRole('button', { name: '耗时详情', exact: true }).click()
  await expect(page.locator('.el-drawer:visible').getByText('DETAIL-ADMIN-RESULT', { exact: true })).toBeVisible()
  expect(sources).toEqual(['ADMIN'])
})
test('重放清单读取失败保留当前目标，重试后恢复版本事实', async ({ page }) => {
  let failed = true
  await page.route('**/api/agent-call-stats/801/replay-manifest?*', r => r.fulfill({ json: failed
    ? { code: 50000, message: '清单暂不可读' } : ok(manifest(0)) }))
  await setup(page)
  await page.getByRole('row').filter({ hasText: '验收调用1' }).getByRole('button', { name: '重放清单', exact: true }).click()
  const drawer = page.locator('.el-drawer:visible')
  await expect(drawer.locator('.crud-load-state')).toBeVisible()
  failed = false
  await drawer.locator('.crud-load-state').getByRole('button').click()
  await expect(drawer.getByText('manifest-model-0', { exact: true }).first()).toBeVisible()
})
test('MOCK 重放失败可重试，成功按返回事实显示结果且只提交 MOCK 模式', async ({ page }) => {
  let failed = true
  const submissions: unknown[] = []
  await page.route('**/api/agent-call-stats/801/replay-manifest?*', r => r.fulfill({ json: ok(manifest(0)) }))
  await page.route('**/api/agent-call-stats/801/replay?*', r => {
    submissions.push(r.request().postDataJSON())
    return r.fulfill({ json: failed ? { code: 50000, message: '重放暂时失败' } : ok({
      replayId: 'current-replay', callLogId: 801, mode: 'MOCK', isolated: true, externalCallCount: 0,
      mockedModelCalls: 1, mockedRagRetrievals: 0, mockedToolCalls: 0, replayedAnswer: 'fixture-only',
      diff: { answerChanged: false, recordedAnswerSha256: 'a', replayedAnswerSha256: 'a', commonPrefixChars: 1,
        artifactVersions: [], warnings: ['CURRENT-REPLAY-DIFF'] }, executedAtMs: 1,
    }) })
  })
  await setup(page)
  await page.getByRole('row').filter({ hasText: '验收调用1' }).getByRole('button', { name: '重放清单', exact: true }).click()
  const drawer = page.locator('.el-drawer:visible')
  await drawer.getByRole('button', { name: '执行 MOCK', exact: true }).click()
  await expect(drawer.locator('.crud-load-state')).toBeVisible()
  await expect(drawer.getByText('manifest-model-0', { exact: true }).first()).toBeVisible()
  failed = false
  await drawer.locator('.crud-load-state').getByRole('button').click()
  await expect(drawer.locator('.replay-result')).toContainText('外部调用 0')
  await expect(drawer.locator('.replay-json').filter({ hasText: 'CURRENT-REPLAY-DIFF' })).toBeVisible()
  expect(submissions).toEqual([{ mode: 'MOCK' }, { mode: 'MOCK' }])
})
test('调用删除写入中锁定目标，失败后保持行，重试对已删除记录给出真实提示', async ({ page }) => {
  let held: Route | undefined
  let writes = 0
  await page.route('**/api/agent-call-stats/801?*', r => { writes += 1; held = r })
  await setup(page)
  const row = page.getByRole('row').filter({ hasText: '验收调用1' })
  const action = row.getByRole('button', { name: '删除', exact: true })
  await action.click()
  await page.getByRole('dialog', { name: '提示', exact: true }).getByRole('button', { name: '确定', exact: true }).click()
  await expect.poll(() => Boolean(held)).toBe(true)
  await expect(action).toBeDisabled()
  await held!.fulfill({ json: { code: 50000, message: '删除暂时失败' } })
  await expect(action).toBeEnabled()
  await expect(row).toBeVisible()
  held = undefined
  await action.click()
  await page.getByRole('dialog', { name: '提示', exact: true }).getByRole('button', { name: '确定', exact: true }).click()
  await expect.poll(() => Boolean(held)).toBe(true)
  await held!.fulfill({ json: ok(false) })
  await expect(page.getByText('该记录已不存在，可能已被其他管理员删除', { exact: true })).toBeVisible()
  expect(writes).toBe(2)
})
