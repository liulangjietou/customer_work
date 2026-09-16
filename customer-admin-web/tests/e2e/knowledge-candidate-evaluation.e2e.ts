import type { Page } from '@playwright/test'
import type { ImprovementCase } from '../../src/api/improvement'
import type { KnowledgeCandidate, KnowledgeCandidateBindingRequest, KnowledgeCandidateReview } from '../../src/api/knowledgeCandidate'
import { expect, test } from './fixtures/adminTestFixture'

const hash = 'a'.repeat(64)
const id = 'd7c3fc2a-1dfb-4112-86c7-52dc60b65c45'
const draft: KnowledgeCandidate = { id, questionHash: hash, revision: 2, status: 'DRAFT', sourceReviewRevision: 3,
  title: '电子发票申请规则', content: '完成交易后，从订单详情申请电子发票。', keyword: '电子发票,申请', contentHash: 'b'.repeat(64), editedBy: 42, editedAtMs: 100 }

function owned(): ImprovementCase {
  return { id: 101, sourceType: 'KNOWLEDGE_GAP', sourceKey: hash, sourceSignalCount: 12, ownerId: '发票负责人',
    slaDueAtMs: Date.now() + 86400000, slaStatus: 'ON_TRACK', overdueMs: 0, status: 'OWNED', agentId: null, agentCode: null,
    artifactType: null, artifactVersion: null, candidateVersions: null, evalType: 'QUALITY', evalCaseId: 'target', evalRunId: null,
    reevaluationStatus: 'NOT_RUN', reevaluationVerdict: null, reevaluationError: null, publishTaskId: null, publishRevision: null,
    publishStatus: null, publishedAtMs: null, observationStartedAtMs: null, observationEndsAtMs: null, minExposureCalls: null,
    maxRecurrenceSignals: null, observedCalls: 0, observedSignals: 0, effectStatus: 'NOT_STARTED', lastObservedAtMs: null,
    lastError: null, createdAtMs: 100, updatedAtMs: 100 }
}

function frozen(data: KnowledgeCandidateBindingRequest): KnowledgeCandidateReview {
  return { ...data, sourceReviewRevision: 3, agentCode: 'invoice-agent', modelName: 'reply-model', judgeModelName: 'judge-model',
    datasetVersionId: 'snapshot-1', artifactFingerprint: 'c'.repeat(64), comparison: null,
    cases: [{ caseId: 'target', input: '如何申请电子发票', expected: '从已完成订单申请，核对抬头', baselineReply: null,
      candidateReply: null, candidateRecalled: false, baselineFailed: false, candidateFailed: false }] }
}

async function setup(page: Page, options: { readonly?: boolean; failEvaluation?: boolean; longReply?: boolean } = {}) {
  const state = { current: owned(), evidence: null as KnowledgeCandidateReview | null,
    candidate: { ...draft }, publications: 0, losePublishAck: false, loseEvaluationAck: false,
    binds: [] as KnowledgeCandidateBindingRequest[], runs: [] as { remark?: string }[], loseBindAck: false,
    readFailure: false, reviewReads: 0, holdEvaluation: null as Promise<void> | null, holdReview: null as Promise<void> | null }
  await page.route('**/api/auth/permissions', route => route.fulfill({ json: { code: 0,
    data: ['knowledge-gap:view', 'improvement:manage', 'agent:view', 'model:view', 'eval:view',
      ...options.readonly ? [] : ['knowledge-gap:fill', 'eval:run']] } }))
  const gap = { questionHash: hash, question: '如何申请电子发票', scopeId: 'tenant-a', missCount: 12, firstSeenAtMs: 100, lastSeenAtMs: 200,
    classification: { category: 'KNOWLEDGE', priority: 'NORMAL', origin: 'MANUAL', reason: '已核对开票规则', revision: 3, reviewedBy: '42', reviewedAtMs: 200 } }
  await page.route('**/api/ops/knowledge-gap/top?*', route => route.fulfill({ json: { code: 0, data: [gap] } }))
  await page.route(`**/api/ops/knowledge-gap/reviews/${hash}`, route => route.fulfill({ json: { code: 0, data: { gap, history: [] } } }))
  await page.route('**/api/ops/knowledge-gap/candidates/**', route => {
    expect(route.request().method()).toBe('GET')
    return route.fulfill({ json: { code: 0, data: state.candidate } })
  })
  await page.route('**/api/aiconfig/agent?*', route => route.fulfill({ json: { code: 0, data: { pageNum: 1, pageSize: 100, total: 1,
    list: [{ id: 7, agentName: '发票客服', agentCode: 'invoice-agent', status: 1 }] } } }))
  await page.route('**/api/aiconfig/model?*', route => route.fulfill({ json: { code: 0, data: { pageNum: 1, pageSize: 100, total: 2,
    list: [{ id: 11, modelName: '答复部署', model: 'reply-model', status: 1 }, { id: 12, modelName: '评分部署', model: 'judge-model', status: 1 }] } } }))
  await page.route('**/api/eval/datasets/QUALITY/versions', route => route.fulfill({ json: { code: 0, data: [
    { releaseId: 'release-1', versionName: '发票回归 v1', snapshotVersionId: 'snapshot-1', caseCount: 1, status: 'APPROVED', evalType: 'QUALITY' },
    { releaseId: 'draft-release', versionName: '未经审核的草稿', caseCount: 1, status: 'DRAFT', evalType: 'QUALITY' },
  ] } }))
  await page.route('**/api/improvement-cases/**', async route => {
    const path = new URL(route.request().url()).pathname
    if (route.request().method() === 'GET') {
      if (path.endsWith('/knowledge-candidate')) {
        state.reviewReads += 1
        expect(state.reviewReads, '父记录对象回读不得触发循环请求').toBeLessThan(10)
        await state.holdReview
        return route.fulfill({ json: state.readFailure
          ? { code: 50000, message: '评测证据暂不可读' } : { code: 0, data: state.evidence } })
      }
      expect(path).toBe(`/api/improvement-cases/source/KNOWLEDGE_GAP/${hash}`)
      return route.fulfill({ json: { code: 0, data: state.current } })
    }
    if (path.endsWith('/knowledge-candidate')) {
      const data = route.request().postDataJSON() as KnowledgeCandidateBindingRequest
      state.binds.push(data)
      state.evidence = frozen(data)
      state.current = { ...state.current, artifactType: 'KNOWLEDGE_CANDIDATE', artifactVersion: state.evidence.artifactFingerprint,
        agentId: data.agentId, agentCode: 'invoice-agent', evalCaseId: data.targetCaseId, status: 'READY_FOR_REEVALUATION', reevaluationStatus: 'NOT_RUN' }
      return route.fulfill({ json: state.loseBindAck ? { code: 50000, message: '绑定结果未确认' } : { code: 0, data: state.current } })
    }
    if (path.endsWith('/knowledge-candidate/publish')) {
      state.publications += 1
      expect(route.request().postDataJSON()).toEqual({ expectedArtifactFingerprint: 'c'.repeat(64), expectedEvaluationRunId: 'candidate-run' })
      state.candidate.status = 'PUBLISHING'
      state.current = { ...state.current, status: 'PUBLISHING', publishTaskId: 'publication-task', publishStatus: 'PENDING' }
      return route.fulfill({ json: state.losePublishAck ? { code: 50000, message: '发布请求结果未确认' } : { code: 0, data: state.current } })
    }
    expect(path).toBe('/api/improvement-cases/101/knowledge-candidate/reevaluate')
    state.runs.push(route.request().postDataJSON())
    state.current = { ...state.current, status: 'REEVALUATING', reevaluationStatus: 'RUNNING', reevaluationDeadlineAtMs: Date.now() + 60000 }
    if (state.loseEvaluationAck) return route.fulfill({ json: { code: 50000, message: '复评请求结果未确认' } })
    await state.holdEvaluation
    const run = { evalType: 'QUALITY' as const, total: 1, passed: 1, primaryMetric: 1, secondaryMetric: 1,
      failedCaseIds: [], failures: [], metrics: {}, trigger: 'MANUAL' as const, datasetSize: 1, remark: null, createdAtMs: 300 }
    state.evidence = { ...state.evidence!, comparison: { current: { ...run, runId: 'candidate-run' },
      baseline: { ...run, runId: 'baseline-run', primaryMetric: 0.4, passed: 0, failedCaseIds: ['target'] },
      regressions: [], fixes: ['target'], verdict: 'IMPROVED', primaryDelta: 0.6, secondaryDelta: 1, datasetChanged: false },
      cases: state.evidence!.cases.map(item => ({ ...item, baselineReply: '原答复缺少办理路径。',
        candidateReply: options.longReply ? '从已完成订单申请电子发票，核对抬头后提交。'.repeat(40) : '从已完成订单申请电子发票，核对抬头后提交。',
        baselineFailed: true, candidateFailed: false, candidateRecalled: !options.failEvaluation })) }
    state.current = { ...state.current, status: options.failEvaluation ? 'REEVALUATION_FAILED' : 'READY_TO_PUBLISH', evalRunId: 'candidate-run',
      reevaluationStatus: options.failEvaluation ? 'FAILED' : 'PASSED', reevaluationVerdict: 'IMPROVED',
      reevaluationError: options.failEvaluation ? '目标问题未实际检索到本次知识候选' : null }
    return route.fulfill({ json: { code: 0, data: state.current } })
  })
  await page.goto('/ops/knowledge-gap')
  await page.getByRole('button', { name: '知识候选', exact: true }).click()
  await page.getByRole('button', { name: '进入治理闭环', exact: true }).click()
  await expect(page.getByRole('region', { name: '知识候选对照复评', exact: true })).toBeVisible()
  await expect(page.getByText('当前候选 v2', { exact: true })).toBeVisible()
  return state
}

async function choose(page: Page) {
  for (const [name, option] of [['智能体提示词', '发票客服'], ['答复模型', '答复部署 · reply-model'],
    ['评分模型', '评分部署 · judge-model'], ['已审核的质量用例集', '发票回归 v1 · 1 题']]) {
    const select = page.getByRole('combobox', { name, exact: true })
    await select.click()
    const controls = await select.getAttribute('aria-controls')
    expect(controls).toBeTruthy()
    const options = page.locator(`[id="${controls}"]`)
    await expect(options.getByRole('option', { name: '未经审核的草稿', exact: false })).toHaveCount(0)
    await options.getByRole('option', { name: option, exact: true }).click()
  }
}

test('知识候选进入实际绑定和复评入口，展示目标召回与逐题答复', async ({ page }) => {
  const state = await setup(page)
  await choose(page)
  await page.getByRole('button', { name: '冻结知识候选', exact: true }).click()
  await expect(page.getByText('已绑定候选 v2 · 1 题', { exact: true })).toBeVisible()
  expect(state.binds).toEqual([{ candidateId: id, candidateRevision: 2, agentId: 7, modelDeploymentId: 11,
    judgeDeploymentId: 12, datasetReleaseId: 'release-1', targetCaseId: 'target' }])
  await page.getByRole('textbox', { name: '本次复评说明' }).fill('验证发票规则')
  await page.getByRole('button', { name: '运行知识对照复评', exact: true }).click()
  await expect(page.getByRole('region', { name: '逐题答复对照' })).toContainText('已实际检索到候选')
  await expect(page.getByRole('region', { name: '逐题答复对照' })).toContainText('原答复缺少办理路径。')
  expect(state.runs).toEqual([{ remark: '验证发票规则' }])
  expect(state.reviewReads).toBe(3)
  await expect(page.getByRole('button', { name: '运行知识对照复评', exact: true })).toBeDisabled()
  await expect(page.getByRole('button', { name: '创建可靠发布任务', exact: true })).toHaveCount(0)
  await expect(page.getByRole('button', { name: '审阅并发布知识', exact: true })).toBeEnabled()
})

test('绑定回执丢失先核对，读取失败保留选择并阻止重复写入', async ({ page }) => {
  const state = await setup(page)
  await choose(page)
  state.loseBindAck = true
  await page.getByRole('button', { name: '冻结知识候选', exact: true }).click()
  await expect(page.getByText('绑定结果未确认', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '冻结知识候选', exact: true })).toBeDisabled()
  state.readFailure = true
  await page.getByRole('button', { name: '核对评测记录', exact: true }).click()
  await expect(page.locator('.knowledge-evaluation .crud-load-state')).toContainText('已保留上次结果')
  await expect(page.locator('.el-select').filter({ has: page.getByRole('combobox', { name: '答复模型', exact: true }) }))
    .toContainText('答复部署 · reply-model')
  state.readFailure = false
  await page.getByRole('button', { name: '核对评测记录', exact: true }).click()
  await expect(page.getByRole('button', { name: '运行知识对照复评', exact: true })).toBeEnabled()
  expect(state.binds).toHaveLength(1)
})

test('评分提高但没有召回候选时，保留失败原因与实际对照事实', async ({ page }) => {
  const state = await setup(page, { failEvaluation: true })
  await choose(page)
  await page.getByRole('button', { name: '冻结知识候选', exact: true }).click()
  await page.getByRole('button', { name: '运行知识对照复评', exact: true }).click()
  await expect(page.getByText('目标问题未实际检索到本次知识候选', { exact: true })).toBeVisible()
  await expect(page.getByRole('region', { name: '逐题答复对照' })).toContainText('未检索到候选')
  expect(state.current.status).toBe('REEVALUATION_FAILED')
  await expect(page.getByRole('button', { name: '审阅并发布知识', exact: true })).toBeDisabled()
  await page.getByRole('textbox', { name: '目标回归用例编号' }).fill('changed-target')
  await expect(page.getByRole('button', { name: '运行知识对照复评', exact: true })).toBeDisabled()
})

test('中断复评显示时限并自动核对，超时恢复后只由用户重新发起', async ({ page }) => {
  const state = await setup(page)
  await choose(page)
  await page.getByRole('button', { name: '冻结知识候选', exact: true }).click()
  await page.getByRole('textbox', { name: '本次复评说明' }).fill('中断恢复后继续核对')
  state.loseEvaluationAck = true
  await page.getByRole('button', { name: '运行知识对照复评', exact: true }).click()
  await expect(page.getByText('复评请求结果未确认', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '核对评测记录', exact: true }).click()
  await expect(page.locator('.knowledge-evaluation')).toContainText('本次时限至')
  await expect(page.getByRole('button', { name: '运行知识对照复评', exact: true })).toBeDisabled()
  state.current = { ...state.current, status: 'REEVALUATION_FAILED', reevaluationStatus: 'FAILED',
    reevaluationError: '复评已超过执行时限，请核对候选后重新发起' }
  await expect(page.getByText('复评已超过执行时限，请核对候选后重新发起', { exact: true })).toBeVisible({ timeout: 10000 })
  await expect(page.getByRole('button', { name: '运行知识对照复评', exact: true })).toBeEnabled()
  expect(state.runs).toHaveLength(1)
  state.loseEvaluationAck = false
  await page.getByRole('button', { name: '运行知识对照复评', exact: true }).click()
  await expect(page.getByRole('region', { name: '逐题答复对照' })).toBeVisible()
  expect(state.runs).toEqual([{ remark: '中断恢复后继续核对' }, { remark: '中断恢复后继续核对' }])
})

test('只读角色能查看候选但无法冻结或发起模型调用', async ({ page }) => {
  const state = await setup(page, { readonly: true })
  await expect(page.getByRole('button', { name: '冻结知识候选', exact: true })).toBeDisabled()
  await expect(page.getByRole('button', { name: '运行知识对照复评', exact: true })).toHaveCount(0)
  await expect(page.getByRole('button', { name: '审阅并发布知识', exact: true })).toHaveCount(0)
  expect(state.binds).toHaveLength(0); expect(state.runs).toHaveLength(0)
})

test('发布前审阅确切正文，等待实际回执后才展示知识已发布', async ({ page }, testInfo) => {
  const state = await setup(page)
  await choose(page)
  await page.getByRole('button', { name: '冻结知识候选', exact: true }).click()
  await page.getByRole('button', { name: '运行知识对照复评', exact: true }).click()
  await page.getByRole('button', { name: '审阅并发布知识', exact: true }).click()
  await expect(page.locator('.publication-preview')).toContainText(draft.content)
  await page.locator('.publication-preview').screenshot({ path: testInfo.outputPath('knowledge-publication-confirmation.png') })
  expect(state.publications).toBe(0)
  await page.getByRole('button', { name: '确认发布知识', exact: true }).click()
  await expect(page.getByText('发布结果核对中', { exact: true })).toBeVisible()
  expect(state.publications).toBe(1)
  await expect(page.getByRole('button', { name: '确认发布知识', exact: true })).toHaveCount(0)
  state.candidate.status = 'PUBLISHED'
  state.current = { ...state.current, status: 'PUBLISHED', publishRevision: 'faq/812', publishStatus: 'APPLIED', publishedAtMs: Date.now() }
  await page.getByRole('button', { name: '核对发布状态', exact: true }).click()
  await expect(page.getByRole('region', { name: '知识候选发布', exact: true })).toContainText('正式知识编号：faq/812')
  await expect(page.getByRole('region', { name: '知识候选发布', exact: true })).toContainText('线上效果尚未验证')
  await page.getByRole('region', { name: '知识候选发布', exact: true }).screenshot({ path: testInfo.outputPath('knowledge-publication-receipt.png') })
  await expect(page.getByRole('button', { name: '冻结知识候选', exact: true })).toHaveCount(0)
  expect(state.publications).toBe(1)
})

test('发布核对期间重新登录后，迟到记录和轮询不能恢复旧面板', async ({ page }) => {
  const state = await setup(page)
  await choose(page)
  await page.getByRole('button', { name: '冻结知识候选', exact: true }).click()
  await page.getByRole('button', { name: '运行知识对照复评', exact: true }).click()
  await expect(page.getByRole('button', { name: '审阅并发布知识', exact: true })).toBeEnabled()
  let release: () => void = () => {}
  state.holdReview = new Promise<void>(resolve => { release = resolve })
  await page.getByRole('button', { name: '审阅并发布知识', exact: true }).click()
  await page.getByRole('button', { name: '确认发布知识', exact: true }).click()
  await expect.poll(() => state.reviewReads).toBe(4)
  await page.evaluate(async () => {
    const path = '/src/store/auth.ts'
    const { useAuthStore } = await import(path)
    const auth = useAuthStore()
    auth.applyLoginResult({ token: auth.token, nickname: '重新登录', approvalStatus: 'APPROVED', forceChangePassword: false,
      approvalRemark: null }, 'new-publication-login')
  })
  release()
  await expect(page.locator('.knowledge-evaluation')).toHaveCount(0)
  const reads = state.reviewReads
  // 覆盖一个真实轮询周期，确保卸载后等待中的回读结束也不会重新启动定时器。
  await page.waitForTimeout(5500)
  expect(state.reviewReads).toBe(reads)
  expect(state.publications).toBe(1)
  await expect(page.locator('.knowledge-evaluation')).toHaveCount(0)
})

test('发布响应丢失时保留同一任务，核对失败不会重新提交', async ({ page }) => {
  const state = await setup(page)
  await choose(page)
  await page.getByRole('button', { name: '冻结知识候选', exact: true }).click()
  await page.getByRole('button', { name: '运行知识对照复评', exact: true }).click()
  state.losePublishAck = true
  await page.getByRole('button', { name: '审阅并发布知识', exact: true }).click()
  await page.getByRole('button', { name: '确认发布知识', exact: true }).click()
  await expect(page.getByText('发布请求结果未确认', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '审阅并发布知识', exact: true })).toBeDisabled()
  state.readFailure = true
  await page.getByRole('button', { name: '核对发布状态', exact: true }).click()
  await expect(page.locator('.knowledge-evaluation .crud-load-state')).toContainText('已保留上次结果')
  state.readFailure = false
  await page.getByRole('button', { name: '核对发布状态', exact: true }).click()
  await expect(page.getByText('发布结果核对中', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '审阅并发布知识', exact: true })).toHaveCount(0)
  expect(state.publications).toBe(1)
})

test('相同 token 重新登录后，迟到的复评响应不能恢复旧记录', async ({ page }) => {
  const state = await setup(page)
  await choose(page)
  await page.getByRole('button', { name: '冻结知识候选', exact: true }).click()
  let finish: () => void = () => {}
  state.holdEvaluation = new Promise<void>(resolve => { finish = resolve })
  await page.getByRole('button', { name: '运行知识对照复评', exact: true }).click()
  await expect.poll(() => state.runs.length).toBe(1)
  await page.evaluate(async () => {
    const path = '/src/store/auth.ts'
    const { useAuthStore } = await import(path)
    const auth = useAuthStore()
    auth.applyLoginResult({ token: auth.token, nickname: '重新登录', approvalStatus: 'APPROVED', forceChangePassword: false,
      approvalRemark: null }, 'same-token-new-login')
  })
  await expect(page.locator('.knowledge-evaluation')).toHaveCount(0)
  finish()
  await expect(page.getByRole('region', { name: '逐题答复对照' })).toHaveCount(0)
})

for (const theme of ['ember', 'night']) {
  test(`${theme} 下窄屏长答复与选择器没有横向溢出`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width: 390, height: 844 })
    await page.addInitScript(id => localStorage.setItem('customer-admin-theme-selection', JSON.stringify({ version: 1, kind: 'preset', id })), theme)
    await setup(page, { longReply: true })
    await choose(page)
    await page.getByRole('button', { name: '冻结知识候选', exact: true }).click()
    await page.getByRole('button', { name: '运行知识对照复评', exact: true }).click()
    await expect(page.getByRole('region', { name: '逐题答复对照' })).toBeVisible()
    await expect(page.locator('html')).toHaveAttribute('data-theme', theme)
    // 标签的进入动画结束后再截图，避免把缩放中的瞬间误认成窄屏文字挤压。
    for (const tag of await page.locator('.reply-pair .el-tag').all()) {
      await expect.poll(() => tag.evaluate(element => element.getBoundingClientRect().width)).toBeGreaterThan(32)
    }
    const widths = await page.locator('.el-drawer:visible').evaluate(element => ({ width: element.clientWidth, scroll: element.scrollWidth }))
    expect(widths.scroll).toBeLessThanOrEqual(widths.width + 1)
    await page.getByRole('region', { name: '逐题答复对照' }).scrollIntoViewIfNeeded()
    await page.screenshot({ path: testInfo.outputPath(`knowledge-evaluation-${theme}-390.png`), fullPage: true })
  })
}
