import type { Page } from '@playwright/test'
import type { ImprovementCase } from '../../src/api/improvement'
import { expect, test } from './fixtures/adminTestFixture'

const gapA = {
  questionHash: 'a'.repeat(64),
  question: '如何申请电子发票',
  scopeId: 'tenant-a',
  missCount: 12,
  firstSeenAtMs: 1789000000000,
  lastSeenAtMs: 1789090000000,
}
const gapB = { ...gapA, questionHash: 'b'.repeat(64), question: '我的物流到哪里了', missCount: 3 }

function improvement(sourceKey: string, ownerId: string): ImprovementCase {
  return {
    id: sourceKey === gapA.questionHash ? 101 : 102,
    sourceType: 'KNOWLEDGE_GAP',
    sourceKey,
    ownerId,
    sourceSignalCount: 3,
    slaDueAtMs: Date.now() + 24 * 60 * 60 * 1000,
    slaStatus: 'ON_TRACK',
    overdueMs: 0,
    status: 'OWNED',
    agentId: null,
    agentCode: null,
    artifactType: null,
    artifactVersion: null,
    candidateVersions: null,
    evalType: null,
    evalCaseId: null,
    evalRunId: null,
    reevaluationStatus: 'NOT_RUN',
    reevaluationVerdict: null,
    reevaluationError: null,
    publishTaskId: null,
    publishRevision: null,
    publishStatus: null,
    publishedAtMs: null,
    observationStartedAtMs: null,
    observationEndsAtMs: null,
    minExposureCalls: null,
    maxRecurrenceSignals: null,
    observedCalls: 0,
    observedSignals: 0,
    effectStatus: 'NOT_STARTED',
    lastObservedAtMs: null,
    lastError: null,
    createdAtMs: 1789000000000,
    updatedAtMs: 1789090000000,
  }
}

async function openClosure(page: Page, question: string) {
  await page
    .getByRole('row')
    .filter({ hasText: question })
    .getByRole('button', { name: '治理闭环', exact: true })
    .click()
}

test.beforeEach(async ({ page }) => {
  await page.route('**/api/auth/permissions', (route) =>
    route.fulfill({
      json: { code: 0, data: ['knowledge-gap:view', 'knowledge-gap:fill', 'improvement:manage'] },
    }),
  )
  await page.route(
    (url) => url.pathname === '/api/ops/knowledge-gap/top',
    (route) => route.fulfill({ json: { code: 0, data: [gapA, gapB] } }),
  )
  await page.addInitScript(() => {
    const completions: Record<string, number> = {}
    Object.assign(window, { __gapCompletions: completions })
    XMLHttpRequest.prototype.open = new Proxy(XMLHttpRequest.prototype.open, {
      apply(target, receiver, args) {
        const path = new URL(String(args[1]), window.location.href).pathname
        receiver.addEventListener(
          'loadend',
          () => {
            requestAnimationFrame(() =>
              requestAnimationFrame(() => {
                completions[path] = (completions[path] ?? 0) + 1
              }),
            )
          },
          { once: true },
        )
        return Reflect.apply(target, receiver, args)
      },
    })
  })
})

test('知识缺口初次读取失败不能显示空榜和零指标，重试读取当前租户', async ({ page }) => {
  let attempts = 0
  const requestedScopes: (string | null)[] = []
  await page.route(
    (url) => url.pathname === '/api/ops/knowledge-gap/top',
    (route) => {
      attempts += 1
      requestedScopes.push(new URL(route.request().url()).searchParams.get('scopeId'))
      return route.fulfill({
        json:
          attempts === 1
            ? { code: 50000, message: '缺口数据暂不可用' }
            : { code: 0, data: [gapA, gapB] },
      })
    },
  )
  await page.goto('/ops/knowledge-gap')
  const board = page.locator('.knowledge-gap-board')
  await expect(board.locator('.crud-load-state')).toContainText('数据加载失败')
  await expect(board.locator('.el-empty')).not.toBeVisible()
  await expect(board.locator('.stat strong').first()).toHaveText('—')
  await board.getByRole('button', { name: '重新加载', exact: true }).click()
  await expect(page.getByRole('row').filter({ hasText: gapA.question })).toBeVisible()
  expect(attempts).toBe(2)
  expect(requestedScopes).toEqual([null, null])
})

test('治理内容读取失败不能当作尚未认领，重试后恢复原责任人', async ({ page }) => {
  let attempts = 0
  await page.route(
    (url) => url.pathname.includes('/api/improvement-cases/source/'),
    (route) => {
      attempts += 1
      return route.fulfill({
        json:
          attempts === 1
            ? { code: 50000, message: '治理记录读取失败' }
            : { code: 0, data: improvement(gapA.questionHash, '发票负责人') },
      })
    },
  )
  await page.goto('/ops/knowledge-gap')
  await openClosure(page, gapA.question)
  const drawer = page.locator('.el-drawer:visible')
  await expect(drawer.locator('.crud-load-state')).toContainText('数据加载失败')
  await expect(drawer.getByRole('button', { name: '认领', exact: true })).not.toBeVisible()
  await drawer.getByRole('button', { name: '重新加载', exact: true }).click()
  await expect(drawer.getByText('发票负责人', { exact: true })).toBeVisible()
})

test('切换知识缺口后，旧治理响应不能覆盖当前来源与操作对象', async ({ page }) => {
  let releaseA: () => void = () => {}
  const heldA = new Promise<void>((resolve) => {
    releaseA = resolve
  })
  let requestedA = false
  await page.route(
    (url) => url.pathname.includes('/api/improvement-cases/source/'),
    async (route) => {
      const sourceA = route.request().url().endsWith(gapA.questionHash)
      if (sourceA) {
        requestedA = true
        await heldA
      }
      await route.fulfill({
        json: {
          code: 0,
          data: sourceA
            ? improvement(gapA.questionHash, '过期发票负责人')
            : improvement(gapB.questionHash, '当前物流负责人'),
        },
      })
    },
  )
  await page.goto('/ops/knowledge-gap')
  await openClosure(page, gapA.question)
  await expect.poll(() => requestedA).toBe(true)
  await page.locator('.el-drawer:visible .el-drawer__close-btn').click()
  await expect(page.locator('.el-drawer:visible')).toHaveCount(0)
  await openClosure(page, gapB.question)
  const drawer = page.locator('.el-drawer:visible')
  await expect(drawer.getByText('当前物流负责人', { exact: true })).toBeVisible()
  releaseA()
  await page.waitForFunction(
    (path) =>
      (window as Window & { __gapCompletions: Record<string, number> }).__gapCompletions[path] ===
      1,
    `/api/improvement-cases/source/KNOWLEDGE_GAP/${gapA.questionHash}`,
  )
  await expect(drawer.getByText('当前物流负责人', { exact: true })).toBeVisible()
  await expect(drawer.getByText('过期发票负责人', { exact: true })).not.toBeVisible()
})

test('刷新失败保留榜单并暂停旧数据操作，重新加载后恢复', async ({ page }) => {
  let attempts = 0
  await page.route(
    (url) => url.pathname === '/api/ops/knowledge-gap/top',
    (route) => {
      attempts += 1
      return route.fulfill({
        json:
          attempts === 2
            ? { code: 50000, message: '暂时无法刷新' }
            : { code: 0, data: [gapA, gapB] },
      })
    },
  )
  await page.goto('/ops/knowledge-gap')
  const board = page.locator('.knowledge-gap-board')
  await expect(board.locator('.stat strong').first()).toHaveText('2')
  await board.getByRole('button', { name: '刷新', exact: true }).click()
  await expect(board.locator('.crud-load-state')).toContainText('数据更新失败，已保留上次结果')
  await expect(page.getByRole('row').filter({ hasText: gapA.question })).toBeVisible()
  await expect(board.getByRole('button', { name: '治理闭环' }).first()).toBeDisabled()
  await expect(board.getByRole('button', { name: '补充知识' }).first()).toBeDisabled()
  await board.getByRole('button', { name: '重新加载', exact: true }).click()
  await expect(board.getByRole('button', { name: '治理闭环' }).first()).toBeEnabled()
  await page.getByRole('textbox', { name: '搜索当前问题' }).fill('未记录的提问')
  await expect(board.getByText('没有匹配的问题', { exact: true })).toBeVisible()
  await expect(board.locator('.stat strong').first()).toHaveText('2')
})

test('认领响应异常保留输入，读取确认已保存后不能重复认领', async ({ page }) => {
  let claims = 0
  let confirmedOwner = ''
  await page.route(
    (url) => url.pathname.includes('/api/improvement-cases/source/'),
    async (route) => {
      if (route.request().method() === 'POST') {
        claims += 1
        confirmedOwner = route.request().postDataJSON().ownerId
        await route.fulfill({ json: { code: 50000, message: '认领结果未确认' } })
      } else {
        await route.fulfill({
          json: {
            code: 0,
            data: confirmedOwner ? improvement(gapA.questionHash, confirmedOwner) : null,
          },
        })
      }
    },
  )
  await page.goto('/ops/knowledge-gap')
  await openClosure(page, gapA.question)
  const drawer = page.locator('.el-drawer:visible')
  const owner = drawer.locator('.el-form-item').filter({ hasText: '责任人' }).getByRole('textbox')
  await owner.fill('发票跟进人')
  await drawer.getByRole('button', { name: '认领', exact: true }).click()
  await expect(drawer.locator('.el-alert').filter({ hasText: '认领结果未确认' })).toBeVisible()
  await expect(owner).toHaveValue('发票跟进人')
  await expect(drawer.getByRole('button', { name: '认领', exact: true })).toBeDisabled()
  await drawer.getByRole('button', { name: '核对服务器记录' }).click()
  await expect(drawer.getByText('发票跟进人', { exact: true })).toBeVisible()
  await expect(drawer.getByRole('button', { name: '认领', exact: true })).not.toBeVisible()
  expect(claims).toBe(1)
})

test('关闭正在认领的来源后，其迟到结果不能写进另一个问题或弹出成功提示', async ({ page }) => {
  let releaseClaim: () => void = () => {}
  const held = new Promise<void>((resolve) => {
    releaseClaim = resolve
  })
  let submitted = false
  await page.route(
    (url) => url.pathname.includes('/api/improvement-cases/source/'),
    async (route) => {
      if (route.request().method() === 'POST') {
        submitted = true
        await held
        await route.fulfill({
          json: { code: 0, data: improvement(gapA.questionHash, '迟到的负责人') },
        })
      } else await route.fulfill({ json: { code: 0, data: null } })
    },
  )
  await page.goto('/ops/knowledge-gap')
  await openClosure(page, gapA.question)
  await page
    .locator('.el-drawer:visible')
    .getByRole('button', { name: '认领', exact: true })
    .click()
  await expect.poll(() => submitted).toBe(true)
  await page.locator('.el-drawer:visible .el-drawer__close-btn').click()
  await expect(page.locator('.el-drawer:visible')).toHaveCount(0)
  await openClosure(page, gapB.question)
  const drawer = page.locator('.el-drawer:visible')
  const owner = drawer.locator('.el-form-item').filter({ hasText: '责任人' }).getByRole('textbox')
  await expect(owner).toHaveValue('')
  releaseClaim()
  await page.waitForFunction(
    (path) =>
      (
        window as Window & {
          __gapCompletions: Record<string, number>
        }
      ).__gapCompletions[path] === 1,
    `/api/improvement-cases/source/KNOWLEDGE_GAP/${gapA.questionHash}/triage`,
  )
  await expect(drawer.getByRole('button', { name: '认领', exact: true })).toBeEnabled()
  await expect(owner).toHaveValue('')
  await expect(page.locator('.el-message--success')).not.toBeVisible()
})

test('只读缺口和 Badcase 页面不读取无权的治理记录', async ({ page }) => {
  const deniedRequests: string[] = []
  await page.route('**/api/auth/permissions', (route) =>
    route.fulfill({
      json: { code: 0, data: ['knowledge-gap:view', 'badcase:view'] },
    }),
  )
  await page.route(
    (url) => url.pathname.startsWith('/api/improvement-cases/'),
    async (route) => {
      deniedRequests.push(route.request().url())
      await route.fulfill({ json: { code: 10003, message: '没有治理权限' } })
    },
  )
  await page.route(
    (url) => url.pathname === '/api/badcase/page',
    (route) =>
      route.fulfill({
        json: {
          code: 0,
          data: {
            total: 1,
            pageNum: 1,
            pageSize: 20,
            list: [
              {
                id: 'feedback-1',
                source: 'NEGATIVE_FEEDBACK',
                sessionId: 'fixture-session',
                messageId: 'message-1',
                userInput: '发票还没有收到',
                agentReply: '请核对订单开票记录',
                signalHash: gapA.questionHash,
                detail: '用户点踩后要求核实',
                status: 'PENDING',
                adoptedKnowledgeId: null,
                adoptedEvalCaseId: null,
                handledBy: null,
                handledAtMs: 0,
                ignoreReason: null,
                createdAtMs: gapA.lastSeenAtMs,
                pending: true,
              },
            ],
          },
        },
      }),
  )
  await page.goto('/ops/knowledge-gap')
  await expect(page.getByRole('row').filter({ hasText: gapA.question })).toBeVisible()
  await expect(page.getByRole('button', { name: '治理闭环' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: '补充知识' })).toHaveCount(0)
  await page.goto('/ops/badcase')
  await page.getByRole('button', { name: '筛选', exact: true }).click()
  await expect(page.locator('.el-drawer:visible')).toContainText('发票还没有收到')
  await expect(page.locator('.improvement-closure')).toHaveCount(0)
  await page.setViewportSize({ width: 390, height: 844 })
  await expect
    .poll(() =>
      page
        .locator('.el-drawer:visible')
        .evaluate((element) => element.getBoundingClientRect().width),
    )
    .toBeLessThanOrEqual(390)
  expect(deniedRequests).toEqual([])
})

test('同一候选复评未过不能发布，通过后只在明确点击时创建任务，并适配窄屏', async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 1440, height: 960 })
  await page.route('**/api/auth/permissions', (route) =>
    route.fulfill({
      json: {
        code: 0,
        data: ['knowledge-gap:view', 'improvement:manage', 'eval:run', 'agent:edit'],
      },
    }),
  )
  let candidate: ImprovementCase = {
    ...improvement(gapA.questionHash, '发票运营'),
    status: 'READY_FOR_REEVALUATION',
    agentId: 31,
    agentCode: 'invoice-assistant',
    artifactVersion: 'fixture-candidate-31',
    evalCaseId: 'invoice-001',
    evalType: 'QUALITY',
  }
  let runs = 0
  let publishes = 0
  await page.route(
    (url) => url.pathname.startsWith('/api/improvement-cases/'),
    async (route) => {
      const path = new URL(route.request().url()).pathname
      if (path.endsWith('/reevaluate')) {
        runs += 1
        candidate = {
          ...candidate,
          status: runs === 1 ? 'REEVALUATION_FAILED' : 'READY_TO_PUBLISH',
          reevaluationStatus: runs === 1 ? 'FAILED' : 'PASSED',
          reevaluationError: runs === 1 ? '用例 invoice-001 缺少订单核验' : null,
          evalRunId: `fixture-run-${runs}`,
        }
      } else if (path.endsWith('/publish')) {
        publishes += 1
        candidate = {
          ...candidate,
          status: 'PUBLISHING',
          publishTaskId: 'fixture-task-31',
          publishStatus: 'PENDING',
        }
      }
      await route.fulfill({ json: { code: 0, data: candidate } })
    },
  )
  await page.goto('/ops/knowledge-gap')
  await openClosure(page, gapA.question)
  const drawer = page.locator('.el-drawer:visible')
  const publish = drawer.getByRole('button', { name: '创建可靠发布任务', exact: true })
  await expect(publish).toBeDisabled()
  await drawer.getByRole('button', { name: '运行复评', exact: true }).click()
  await expect(drawer.getByText('用例 invoice-001 缺少订单核验', { exact: true })).toBeVisible()
  await expect(publish).toBeDisabled()
  await drawer.getByRole('button', { name: '运行复评', exact: true }).click()
  await expect(publish).toBeEnabled()
  expect(publishes).toBe(0)
  await expect(page.locator('.el-message')).toHaveCount(0)
  await page.screenshot({
    path: testInfo.outputPath('knowledge-gap-governance-desktop.png'),
    fullPage: true,
  })
  await publish.click()
  await drawer.getByText('候选、评测与发布记录', { exact: true }).click()
  await expect(drawer.getByText('fixture-task-31', { exact: true })).toBeVisible()
  await expect(drawer.getByText('待调度', { exact: true })).toBeVisible()
  await expect(publish).toBeDisabled()
  expect(publishes).toBe(1)
  await page.setViewportSize({ width: 390, height: 844 })
  // 抽屉尺寸有过渡动画；等待真实布局收敛后再核验，不能读取中间帧。
  await expect
    .poll(() => drawer.evaluate((element) => element.getBoundingClientRect().width))
    .toBeLessThanOrEqual(390)
  const geometry = await drawer.evaluate((element) => ({
    width: element.getBoundingClientRect().width,
    left: element.getBoundingClientRect().left,
    client: element.clientWidth,
    scroll: element.scrollWidth,
    document: document.documentElement.scrollWidth,
  }))
  expect(geometry.width).toBeLessThanOrEqual(390)
  expect(geometry.left).toBeGreaterThanOrEqual(0)
  expect(geometry.scroll).toBeLessThanOrEqual(geometry.client + 1)
  expect(geometry.document).toBeLessThanOrEqual(390)
  await drawer.getByRole('button', { name: '立即同步状态', exact: true }).scrollIntoViewIfNeeded()
  await expect(page.locator('.el-message')).toHaveCount(0)
  await page.screenshot({
    path: testInfo.outputPath('knowledge-gap-governance-mobile.png'),
    fullPage: true,
  })
})

test('修改候选绑定但未冻结时，不能发布此前通过评测的旧候选', async ({ page }) => {
  await page.route('**/api/auth/permissions', (route) =>
    route.fulfill({
      json: { code: 0, data: ['knowledge-gap:view', 'improvement:manage', 'agent:edit'] },
    }),
  )
  let current: ImprovementCase = {
    ...improvement(gapA.questionHash, '运营'),
    status: 'READY_TO_PUBLISH',
    agentId: 31,
    evalType: 'QUALITY',
    evalCaseId: 'invoice-001',
    reevaluationStatus: 'PASSED',
  }
  let binds = 0
  await page.route(
    (url) => url.pathname.startsWith('/api/improvement-cases/'),
    async (route) => {
      if (route.request().method() === 'POST') {
        expect(new URL(route.request().url()).pathname).toBe('/api/improvement-cases/101/artifact')
        expect(route.request().postDataJSON()).toEqual({
          agentId: 32,
          evalType: 'QUALITY',
          evalCaseId: 'invoice-001',
        })
        binds += 1
        current = {
          ...current,
          agentId: 32,
          status: 'READY_FOR_REEVALUATION',
          reevaluationStatus: 'NOT_RUN',
        }
      }
      await route.fulfill({ json: { code: 0, data: current } })
    },
  )
  await page.goto('/ops/knowledge-gap')
  await openClosure(page, gapA.question)
  const drawer = page.locator('.el-drawer:visible')
  const publish = drawer.getByRole('button', { name: '创建可靠发布任务', exact: true })
  await expect(publish).toBeEnabled()
  await drawer.getByRole('spinbutton').fill('32')
  await drawer.getByRole('spinbutton').blur()
  await expect(publish).toBeDisabled()
  await expect(drawer.getByText('候选绑定已修改，请先冻结候选，再复评或发布')).toBeVisible()
  await drawer.getByRole('button', { name: '冻结候选', exact: true }).click()
  await expect(drawer.getByText('待复评', { exact: true })).toBeVisible()
  await expect(publish).toBeDisabled()
  expect(binds).toBe(1)
})
