import type { Page } from '@playwright/test'
import type { KnowledgeGap, KnowledgeGapReview, KnowledgeGapReviewRequest } from '../../src/api/ops'
import { expect, test } from './fixtures/adminTestFixture'

const invoiceHash = 'a'.repeat(64)
const clockHash = 'b'.repeat(64)
const now = Date.now()
function signal(questionHash = invoiceHash): KnowledgeGap {
  return {
    questionHash,
    scopeId: 'tenant-a',
    question: questionHash === invoiceHash ? '退款申请一直报错' : '今天星期几？',
    missCount: questionHash === invoiceHash ? 1 : 100,
    firstSeenAtMs: now - 86400000,
    lastSeenAtMs: now - 1000,
    evidence: {
      path: 'TOOL',
      agentCode: 'after-sale',
      channelCode: 'user-ws',
      sessionType: 'CHAT',
      retrievalResult: 'EMPTY',
    },
    classification: {
      category: questionHash === invoiceHash ? 'PENDING' : 'REALTIME',
      priority: 'NORMAL',
      origin: questionHash === invoiceHash ? 'UNKNOWN' : 'RULE',
      reason: '请核对原始问题与处理方向',
      revision: 0,
      reviewedBy: null,
      reviewedAtMs: null,
    },
  }
}
async function setup(page: Page, canReview = true) {
  const state = {
    gaps: [signal(), signal(clockHash)],
    history: [] as KnowledgeGapReview[],
    posts: [] as KnowledgeGapReviewRequest[],
    views: [] as string[],
    loseAck: false,
  }
  await page.route('**/api/auth/permissions', (route) =>
    route.fulfill({
      json: {
        code: 0,
        data: canReview ? ['knowledge-gap:view', 'improvement:manage'] : ['knowledge-gap:view'],
      },
    }),
  )
  await page.route(
    (url) => url.pathname === '/api/ops/knowledge-gap/top',
    (route) => {
      const view = new URL(route.request().url()).searchParams.get('view') ?? 'ALL'
      state.views.push(view)
      const gaps = state.gaps.filter(
        (gap) =>
          view === 'ALL' ||
          (view === 'WORK'
            ? !['REALTIME', 'NON_BUSINESS'].includes(gap.classification!.category) ||
              gap.classification!.priority === 'HIGH'
            : gap.classification!.category === view),
      )
      return route.fulfill({ json: { code: 0, data: gaps } })
    },
  )
  await page.route(
    (url) => url.pathname.startsWith('/api/ops/knowledge-gap/reviews/'),
    async (route) => {
      const gap = state.gaps.find(
        (item) => item.questionHash === new URL(route.request().url()).pathname.split('/').at(-1),
      )!
      if (route.request().method() === 'GET')
        return route.fulfill({ json: { code: 0, data: { gap, history: state.history } } })
      const data = route.request().postDataJSON() as KnowledgeGapReviewRequest
      state.posts.push(data)
      if (data.expectedRevision !== gap.classification!.revision)
        return route.fulfill({
          json: { code: 30017, message: '该问题已被其他人复核，请保留输入并重新核对' },
        })
      const previous = gap.classification!
      gap.classification = {
        ...previous,
        ...data,
        origin: 'MANUAL',
        revision: previous.revision + 1,
        reviewedBy: '42',
        reviewedAtMs: Date.now(),
      }
      state.history.unshift({
        revision: gap.classification.revision,
        previousCategory: previous.category,
        previousPriority: previous.priority,
        category: data.category,
        priority: data.priority,
        reason: data.reason,
        reviewedBy: '42',
        reviewedAtMs: Date.now(),
        signalCount: gap.missCount,
      })
      return route.fulfill({
        json: state.loseAck
          ? { code: 500, message: '响应中断，结果待核对' }
          : { code: 0, data: gap },
      })
    },
  )
  return state
}
async function open(page: Page) {
  await page.goto('/ops/knowledge-gap')
  await page
    .getByRole('row')
    .filter({ hasText: '退款申请一直报错' })
    .getByRole('button', { name: '问题详情', exact: true })
    .click()
  return page.locator('.gap-review-drawer:visible')
}
async function choose(page: Page, label: string, option: string) {
  const input = page.getByRole('combobox', { name: label, exact: true })
  await input.focus()
  await input.press('ArrowDown')
  await page.getByRole('option', { name: option, exact: true }).click()
}

test('工作清单在服务端筛选，全部原始信号仍可查看', async ({ page }) => {
  const state = await setup(page)
  await page.goto('/ops/knowledge-gap')
  await expect(page.getByRole('row').filter({ hasText: '退款申请一直报错' })).toBeVisible()
  await expect(page.getByRole('row').filter({ hasText: '今天星期几？' })).toHaveCount(0)
  expect(state.views).toEqual(['WORK'])
  await choose(page, '问题处理视图', '全部原始信号')
  await expect(page.getByRole('row').filter({ hasText: '今天星期几？' })).toContainText('规则建议')
  expect(state.views.at(-1)).toBe('ALL')
  await choose(page, '问题处理视图', '待分类')
  await expect(page.getByRole('row').filter({ hasText: '今天星期几？' })).toHaveCount(0)
  expect(state.views.at(-1)).toBe('PENDING')
})

test('只读权限可以核对最近来源，不能提交分类', async ({ page }) => {
  const state = await setup(page, false)
  const drawer = await open(page)
  await expect(drawer.getByText('当前权限可查看来源与复核记录')).toBeVisible()
  await expect(drawer.getByText('客户实时会话', { exact: true })).toBeVisible()
  await expect(drawer.getByText('after-sale', { exact: true })).toBeVisible()
  await expect(drawer.getByText('正常返回，未召回资料', { exact: true })).toBeVisible()
  await expect(drawer.getByRole('button', { name: '确认复核并保存' })).toHaveCount(0)
  expect(state.posts).toHaveLength(0)
})

test('严重单例填写理由后保存分类与优先级，并显示服务端复核记录', async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 1440, height: 1100 })
  const state = await setup(page)
  const drawer = await open(page)
  const save = drawer.getByRole('button', { name: '确认复核并保存', exact: true })
  await expect(save).toBeDisabled()
  await choose(page, '处理分类', '工具 / 依赖异常')
  await choose(page, '优先级', '高优先级 · 严重单例')
  await drawer
    .getByRole('textbox', { name: '复核理由', exact: true })
    .fill('支付查询接口返回明确异常，交由依赖负责人处理')
  await save.click()
  await expect(drawer.getByRole('status')).toContainText('分类与复核记录已保存')
  await expect(drawer.getByText('待分类 → 工具 / 依赖异常', { exact: true })).toBeVisible()
  expect(state.posts).toEqual([
    {
      expectedRevision: 0,
      category: 'DEPENDENCY',
      priority: 'HIGH',
      reason: '支付查询接口返回明确异常，交由依赖负责人处理',
    },
  ])
  await expect(save).toBeDisabled()
  await page.screenshot({
    path: testInfo.outputPath('knowledge-gap-classification-desktop.png'),
    fullPage: true,
    animations: 'disabled',
  })
  await page.setViewportSize({ width: 390, height: 844 })
  await expect
    .poll(() => drawer.evaluate((element) => element.getBoundingClientRect().width))
    .toBeLessThanOrEqual(390)
  await save.scrollIntoViewIfNeeded()
  const dimensions = await drawer.evaluate((element) => ({
    width: element.clientWidth,
    scroll: element.scrollWidth,
    document: document.documentElement.scrollWidth,
  }))
  expect(dimensions.scroll).toBeLessThanOrEqual(dimensions.width + 1)
  expect(dimensions.document).toBe(390)
  expect((await save.boundingBox())!.height).toBeGreaterThanOrEqual(44)
  await page.screenshot({
    path: testInfo.outputPath('knowledge-gap-classification-mobile.png'),
    fullPage: true,
    animations: 'disabled',
  })
})

test('复核响应丢失后保留理由并先核对，已保存的操作不会重复提交', async ({ page }) => {
  const state = await setup(page)
  state.loseAck = true
  const drawer = await open(page)
  const reason = drawer.getByRole('textbox', { name: '复核理由', exact: true })
  const save = drawer.getByRole('button', { name: '确认复核并保存', exact: true })
  await reason.fill('仍需核对依赖服务的原始日志')
  await save.click()
  await expect(drawer.getByText('响应中断，结果待核对', { exact: true })).toBeVisible()
  await expect(reason).toHaveValue('仍需核对依赖服务的原始日志')
  await expect(save).toBeDisabled()
  await drawer.getByRole('button', { name: '核对服务器记录', exact: true }).click()
  await expect(drawer.getByText('当前为第 1 次复核', { exact: true })).toBeVisible()
  await expect(save).toBeDisabled()
  expect(state.posts).toHaveLength(1)
})

test('多人复核冲突后保留输入，重新核对后必须明确再次提交', async ({ page }) => {
  const state = await setup(page)
  const drawer = await open(page)
  const reason = drawer.getByRole('textbox', { name: '复核理由', exact: true })
  await reason.fill('我已核实业务流程需要调整')
  state.gaps[0]!.classification = {
    ...state.gaps[0]!.classification!,
    category: 'DEPENDENCY',
    origin: 'MANUAL',
    revision: 1,
    reason: '另一位运营先登记了依赖故障',
    reviewedBy: '99',
    reviewedAtMs: Date.now(),
  }
  await drawer.getByRole('button', { name: '确认复核并保存', exact: true }).click()
  await expect(
    drawer.getByText('该问题已被其他人复核，请保留输入并重新核对', { exact: true }),
  ).toBeVisible()
  await drawer.getByRole('button', { name: '核对服务器记录', exact: true }).click()
  await expect(drawer.getByText('另一位运营先登记了依赖故障', { exact: true })).toBeVisible()
  await expect(reason).toHaveValue('我已核实业务流程需要调整')
  expect(state.posts).toHaveLength(1)
  await choose(page, '处理分类', '流程问题')
  await drawer.getByRole('button', { name: '确认复核并保存', exact: true }).click()
  await expect(drawer.getByText('当前为第 2 次复核', { exact: true })).toBeVisible()
  expect(state.posts[1]!.expectedRevision).toBe(1)
})

test('详情初次读取失败保留重试入口，不显示成没有复核记录', async ({ page }) => {
  await setup(page)
  let fail = true
  await page.route(
    (url) => url.pathname.endsWith(`/reviews/${invoiceHash}`),
    (route) =>
      route.fulfill({
        json: fail
          ? { code: 500, message: '读取不可用' }
          : { code: 0, data: { gap: signal(), history: [] } },
      }),
  )
  const drawer = await open(page)
  await expect(drawer.getByText('数据加载失败', { exact: true })).toBeVisible()
  await expect(drawer.getByRole('button', { name: '确认复核并保存' })).toHaveCount(0)
  fail = false
  await drawer.getByRole('button', { name: '重新加载', exact: true }).click()
  await expect(
    drawer.getByText('尚无人工复核。规则建议不会冒充人工结论。', { exact: true }),
  ).toBeVisible()
})
