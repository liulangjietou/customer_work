import type { Page } from '@playwright/test'
import type { KnowledgeCandidate, KnowledgeCandidateSaveRequest } from '../../src/api/knowledgeCandidate'
import type { KnowledgeGap } from '../../src/api/ops'
import { expect, test } from './fixtures/adminTestFixture'

const hash = 'a'.repeat(64)
const candidateId = 'd7c3fc2a-1dfb-4112-86c7-52dc60b65c45'
function candidate(revision = 1): KnowledgeCandidate {
  return { id: candidateId, questionHash: hash, revision, status: 'DRAFT', sourceReviewRevision: 2,
    title: '发票申请规则', content: '完成交易后，可以从订单详情申请电子发票。', keyword: '电子发票,申请',
    contentHash: 'b'.repeat(64), editedBy: 42, editedAtMs: Date.now() }
}

async function setup(page: Page, options: { readOnly?: boolean; existing?: boolean; reviewFailure?: boolean; question?: string } = {}) {
  const state = {
    saved: options.existing ? candidate() : null as KnowledgeCandidate | null,
    gap: { questionHash: hash, question: options.question ?? '如何申请电子发票', scopeId: 'tenant-a', missCount: 12,
      firstSeenAtMs: Date.now() - 86400000, lastSeenAtMs: Date.now(),
      classification: { category: 'KNOWLEDGE', priority: 'NORMAL', origin: 'MANUAL', reason: '已核对订单开票规则，需补全操作步骤',
        revision: 2, reviewedBy: '42', reviewedAtMs: Date.now() } } as KnowledgeGap,
    writes: [] as { id: string; data: KnowledgeCandidateSaveRequest }[],
    loseAck: false, readFailure: false, reviewFailure: options.reviewFailure ?? false, holdSave: null as Promise<void> | null,
  }
  await page.route('**/api/auth/permissions', route => route.fulfill({ json: { code: 0,
    data: options.readOnly ? ['knowledge-gap:view'] : ['knowledge-gap:view', 'knowledge-gap:fill', 'improvement:manage'] } }))
  await page.route('**/api/ops/knowledge-gap/top?*', route => route.fulfill({ json: { code: 0, data: [state.gap] } }))
  await page.route(`**/api/ops/knowledge-gap/reviews/${hash}`, route => route.fulfill({ json: state.reviewFailure
    ? { code: 50000, message: '原始问题暂时无法读取' } : { code: 0, data: { gap: state.gap, history: [] } } }))
  await page.route('**/api/ops/knowledge-gap/candidates/**', async route => {
    if (route.request().method() === 'GET') return route.fulfill({ json: state.readFailure
      ? { code: 50000, message: '候选版本暂时无法读取' } : { code: 0, data: state.saved } })
    const id = new URL(route.request().url()).pathname.split('/').at(-1)!
    const data = route.request().postDataJSON() as KnowledgeCandidateSaveRequest
    state.writes.push({ id, data })
    if (data.expectedRevision !== (state.saved?.revision ?? 0)) return route.fulfill({ json: { code: 30016, message: '候选已被其他人修改' } })
    state.saved = { ...candidate(), ...data, id, revision: data.expectedRevision + 1 }
    await state.holdSave
    return route.fulfill({ json: state.loseAck ? { code: 50000, message: '保存结果未确认' } : { code: 0, data: state.saved } })
  })
  await page.goto('/ops/knowledge-gap')
  await page.getByRole('row').filter({ hasText: state.gap.question }).getByRole('button', { name: '知识候选', exact: true }).click()
  await expect(page.getByRole('textbox', { name: '候选标题' })).toBeVisible()
  return state
}

async function edit(page: Page, title = '电子发票申请说明') {
  await page.getByRole('textbox', { name: '候选标题' }).fill(title)
  await page.getByRole('textbox', { name: '候选正文' }).fill('从已完成订单的详情进入开票申请，核对抬头后提交。')
  await page.getByRole('textbox', { name: '检索关键词' }).fill('电子发票,开票申请')
}

test('保存候选保留独立修订，成功后可进入治理且不能重复提交相同正文', async ({ page }) => {
  const state = await setup(page)
  await edit(page)
  await page.getByRole('button', { name: '保存候选', exact: true }).click()
  await expect(page.getByText('候选 v1 已保存，可继续进入治理闭环。')).toBeVisible()
  await expect(page.getByRole('button', { name: '保存候选', exact: true })).toBeDisabled()
  await expect(page.getByRole('button', { name: '进入治理闭环', exact: true })).toBeEnabled()
  expect(state.writes).toHaveLength(1)
  expect(state.writes[0].data).toEqual({ questionHash: hash, expectedRevision: 0, sourceReviewRevision: 2,
    title: '电子发票申请说明', content: '从已完成订单的详情进入开票申请，核对抬头后提交。', keyword: '电子发票,开票申请' })
})

test('长来源保留完整问题并提示精简关键词，容量边界正文可以完整保存', async ({ page }) => {
  const question = '如何核对这笔订单的电子发票申请进度'.repeat(20)
  const state = await setup(page, { question })
  await page.getByRole('textbox', { name: '候选标题' }).fill('长正文开票规则')
  await page.getByRole('textbox', { name: '候选正文' }).fill('内'.repeat(20000))
  await expect(page.getByRole('textbox', { name: '候选正文' })).toHaveAttribute('maxlength', '20000')
  await expect(page.getByRole('textbox', { name: '检索关键词' })).toHaveAttribute('maxlength', '255')
  await page.getByRole('button', { name: '保存候选', exact: true }).click()
  await expect(page.getByText('检索关键词最多 255 字，请精简后保存')).toBeVisible()
  expect(state.writes).toHaveLength(0)
  await expect(page.locator('.knowledge-candidate-drawer')).toContainText(question)
  await page.getByRole('textbox', { name: '检索关键词' }).fill('词'.repeat(255))
  await page.getByRole('button', { name: '保存候选', exact: true }).click()
  await expect(page.getByText('候选 v1 已保存，可继续进入治理闭环。')).toBeVisible()
  expect(state.writes).toHaveLength(1)
  expect(state.writes[0].data.content.length).toBe(20000)
  expect(state.writes[0].data.keyword.length).toBe(255)
})

test('保存回执丢失后先核对，读取故障与已确认结果都保留编辑且不重复写入', async ({ page }) => {
  const state = await setup(page)
  state.loseAck = true
  await edit(page)
  await page.getByRole('button', { name: '保存候选', exact: true }).click()
  await expect(page.getByText('保存结果未确认', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '保存候选', exact: true })).toBeDisabled()
  state.readFailure = true
  await page.getByRole('button', { name: '核对已保存版本' }).click()
  await expect(page.locator('.knowledge-candidate-drawer .crud-load-state')).toContainText('数据更新失败，已保留上次结果')
  await expect(page.getByRole('textbox', { name: '候选标题' })).toHaveValue('电子发票申请说明')
  state.readFailure = false
  await page.getByRole('button', { name: '核对已保存版本' }).click()
  await expect(page.getByText('已核对到保存结果，当前输入继续保留。')).toBeVisible()
  await expect(page.getByRole('button', { name: '保存候选', exact: true })).toBeDisabled()
  expect(state.writes).toHaveLength(1)
})

test('并发修订必须明确对照，保留本地编辑后保存基于服务器新版本', async ({ page }) => {
  const state = await setup(page, { existing: true })
  await edit(page, '我的修订')
  state.saved = { ...candidate(2), title: '同事的修订', content: '同事补充的发票规则' }
  await page.getByRole('button', { name: '保存候选', exact: true }).click()
  await expect(page.getByText('候选已被其他人修改', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '核对已保存版本' }).click()
  await expect(page.getByRole('region', { name: '服务器候选版本' })).toContainText('同事补充的发票规则')
  await expect(page.getByRole('textbox', { name: '候选标题' })).toHaveValue('我的修订')
  await expect(page.getByRole('button', { name: '保存候选', exact: true })).toBeDisabled()
  await page.getByRole('button', { name: '基于此版本保留我的编辑' }).click()
  await page.getByRole('button', { name: '保存候选', exact: true }).click()
  await expect(page.getByText('候选 v3 已保存，可继续进入治理闭环。')).toBeVisible()
  expect(state.writes.map(write => write.data.expectedRevision)).toEqual([1, 2])
  expect(state.saved?.title).toBe('我的修订')
})

test('只读角色可以核对来源和候选正文，不能编辑或保存', async ({ page }) => {
  const state = await setup(page, { readOnly: true, existing: true })
  await expect(page.getByRole('textbox', { name: '候选标题' })).toHaveValue('发票申请规则')
  await expect(page.getByRole('textbox', { name: '候选标题' })).toBeDisabled()
  await expect(page.getByRole('button', { name: '保存候选', exact: true })).toBeDisabled()
  await expect(page.getByRole('button', { name: '进入治理闭环', exact: true })).toHaveCount(0)
  expect(state.writes).toHaveLength(0)
})

test('来源暂时故障仍能读取已保存候选，来源恢复后才允许编辑保存', async ({ page }) => {
  const state = await setup(page, { existing: true, reviewFailure: true })
  await expect(page.getByRole('textbox', { name: '候选标题' })).toHaveValue('发票申请规则')
  await expect(page.getByRole('textbox', { name: '候选正文' })).toHaveValue(candidate().content)
  await expect(page.getByRole('textbox', { name: '候选正文' })).toBeDisabled()
  await expect(page.getByRole('button', { name: '保存候选', exact: true })).toBeDisabled()
  await expect(page.getByRole('button', { name: '进入治理闭环', exact: true })).toBeDisabled()
  state.reviewFailure = false
  await page.getByRole('button', { name: '核对已保存版本' }).click()
  await expect(page.getByRole('textbox', { name: '候选正文' })).toBeEnabled()
  expect(state.writes).toHaveLength(0)
})

test('来源复核变更为依赖问题后保留候选并停止保存', async ({ page }) => {
  const state = await setup(page, { existing: true })
  await edit(page)
  state.gap.classification = { ...state.gap.classification!, category: 'DEPENDENCY', revision: 3 }
  await page.getByRole('button', { name: '核对已保存版本' }).click()
  await expect(page.getByText('先复核问题分类', { exact: true })).toBeVisible()
  await expect(page.getByRole('textbox', { name: '候选标题' })).toHaveValue('电子发票申请说明')
  await expect(page.getByRole('button', { name: '保存候选', exact: true })).toBeDisabled()
  expect(state.writes).toHaveLength(0)
})

test('重新登录同步关闭旧候选，迟到保存响应不能恢复旧编辑器', async ({ page }) => {
  const state = await setup(page)
  let finish: () => void = () => {}
  state.holdSave = new Promise<void>(resolve => { finish = resolve })
  await edit(page, '旧登录正在保存的候选')
  await page.getByRole('button', { name: '保存候选', exact: true }).click()
  await expect.poll(() => state.writes.length).toBe(1)
  await page.evaluate(async () => {
    const path = '/src/store/auth.ts'
    const { useAuthStore } = await import(path)
    const auth = useAuthStore()
    auth.applyLoginResult({ token: auth.token, nickname: '重新登录', approvalStatus: 'APPROVED',
      forceChangePassword: false, approvalRemark: null }, 'same-token-new-login')
  })
  await expect(page.locator('.knowledge-candidate-drawer')).toHaveCount(0)
  finish()
  await expect(page.getByRole('row').filter({ hasText: state.gap.question })).toBeVisible()
  await expect(page.getByText('候选 v1 已保存，可继续进入治理闭环。')).toHaveCount(0)
})

for (const theme of ['ember', 'night']) {
  test(`${theme} 窄屏保留主题与未保存编辑，关闭确认可取消`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width: 390, height: 844 })
    await page.addInitScript(id => localStorage.setItem('customer-admin-theme-selection', JSON.stringify({ version: 1, kind: 'preset', id })), theme)
    const state = await setup(page)
    await edit(page)
    await expect(page.locator('html')).toHaveAttribute('data-theme', theme)
    await expect(page.getByRole('button', { name: '保存候选', exact: true })).toBeInViewport()
    const reviewTag = page.getByText('人工复核 v2', { exact: true })
    await expect(reviewTag).toBeVisible()
    await expect.poll(() => reviewTag.evaluate(tag => tag.getBoundingClientRect().width)).toBeGreaterThan(60)
    const dimensions = await page.locator('.knowledge-candidate-drawer').evaluate(drawer => ({ scroll: drawer.scrollWidth, width: drawer.clientWidth }))
    expect(dimensions.scroll).toBeLessThanOrEqual(dimensions.width + 1)
    await page.locator('.knowledge-candidate-drawer .el-drawer__body').evaluate(body => { body.scrollTop = 0 })
    await page.screenshot({ path: testInfo.outputPath(`candidate-${theme}-390.png`), fullPage: true })
    await page.locator('.knowledge-candidate-drawer .el-drawer__close-btn').click()
    await page.getByRole('button', { name: '继续编辑', exact: true }).click()
    await expect(page.getByRole('textbox', { name: '候选标题' })).toHaveValue('电子发票申请说明')
    await page.locator('.knowledge-candidate-drawer .el-drawer__close-btn').click()
    await page.getByRole('button', { name: '丢弃并关闭', exact: true }).click()
    await expect(page.locator('.knowledge-candidate-drawer')).toHaveCount(0)
    expect(state.writes).toHaveLength(0)
  })
}
