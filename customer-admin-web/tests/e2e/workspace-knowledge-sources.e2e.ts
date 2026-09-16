import { expect, test } from './fixtures/adminTestFixture'
import type { Page } from '@playwright/test'

const root = '/api/workspace/java-assistant/chat/sessions'
const original =
  '签收后七天内可申请退货。\n<script>不得执行的原文</script>\n退款进度以订单实际处理结果为准。'
const metadata = (revisionId = 100) => ({
  knowledgeBaseId: 7,
  versionId: 70,
  versionNo: 3,
  revisionId,
  status: 'AVAILABLE',
  title: revisionId === 100 ? '退款期限说明' : '物流进度说明',
  externalId: 'policy-refund',
  sourceName: '售后政策源',
  sourceVersion: 'policy-2026-08',
  sourceUri: 'https://policy.example.invalid/refund',
  contentHash: 'a7'.repeat(32),
  currentRevision: false,
  sourceUpdatedAt: '2026-08-15 10:00:00',
  revisionCreatedAt: '2026-08-15 10:10:00',
})
const source = (sourceId = 0, revisionId = 100) => ({
  sourceId,
  number: sourceId + 1,
  status: 'AVAILABLE',
  knowledgeBaseName: '售后知识库',
  documentId: 'refund-policy',
  chunkId: 'chunk-1',
  score: 0.86,
  document: metadata(revisionId),
})
const recorded = (revisionId = 100) => ({
  status: 'RECORDED',
  retrievals: [{ agentCode: 'java-assistant', status: 'HIT', sources: [source(0, revisionId)] }],
})
const historyMessage = (id: string, phase = 'FINAL', text = '对应的已保存答复') => ({
  id,
  role: 'assistant',
  text,
  attachments: [],
  timestamp: '2026-09-11T10:00:00',
  turnId: id,
  phase,
  finishReason: phase === 'STOPPED' ? 'INTERRUPTED' : 'MODEL_STOP',
})

async function fixture(page: Page, history = [historyMessage('reply-1')]) {
  await page.emulateMedia({ reducedMotion: 'reduce' })
  await page.route('**/api/auth/permissions', (route) =>
    route.fulfill({ json: { code: 0, data: ['workspace'] } }),
  )
  await page.route(`**${root}?*`, (route) =>
    route.fulfill({
      json: {
        code: 0,
        data: {
          pageNum: 1,
          pageSize: 20,
          total: history.length ? 1 : 0,
          list: history.length
            ? [
                {
                  sessionId: 'history-review',
                  preview: '核对历史来源',
                  messageCount: history.length,
                  lastMessageTime: '2026-09-11T10:00:00',
                },
              ]
            : [],
        },
      },
    }),
  )
  await page.route(`**${root}/history-review/messages`, (route) =>
    route.fulfill({ json: { code: 0, data: history } }),
  )
  await page.route(`**${root}/*/messages/*/sources`, (route) =>
    route.fulfill({ json: { code: 0, data: recorded() } }),
  )
  await page.route(`**${root}/*/messages/*/sources/0/preview`, (route) =>
    route.fulfill({ json: { code: 0, data: { document: metadata(), content: original } } }),
  )
  await page.route('**/api/workspace/java-assistant/vibecoding/files?*', (route) =>
    route.fulfill({ json: { code: 0, data: [] } }),
  )
}
async function openReader(page: Page, index = 0) {
  await page
    .locator('.chat-panel:visible')
    .getByRole('button', { name: /本轮检索参考/ })
    .nth(index)
    .click()
  const reader = page.locator('.workspace-knowledge-sources:visible')
  await expect(reader).toBeVisible()
  return reader
}
async function navigate(page: Page, path: string) {
  await page.evaluate(async (target) => {
    const modulePath = '/src/router/index.ts'
    const { default: router } = await import(modulePath)
    await router.push(target)
  }, path)
}

test('历史不同轮分别核对 FINAL、STOPPED 和 UNKNOWN 来源，并保留最终消息标识', async ({ page }) => {
  const process = { ...historyMessage('process-1', 'PROCESS', '正在核对'), turnId: 'reply-1' }
  await fixture(page, [
    process,
    historyMessage('reply-1'),
    historyMessage('reply-2', 'STOPPED'),
    historyMessage('reply-3', 'UNKNOWN'),
  ])
  const paths: string[] = []
  await page.route(`**${root}/history-review/messages/*/sources`, (route) => {
    const path = new URL(route.request().url()).pathname
    paths.push(path)
    return route.fulfill({
      json: { code: 0, data: recorded(path.includes('reply-2') ? 200 : 100) },
    })
  })
  await page.goto('/workspace/java-assistant')
  for (const [index, title] of ['退款期限说明', '物流进度说明', '退款期限说明'].entries()) {
    const reader = await openReader(page, index)
    await expect(reader.getByRole('button', { name: new RegExp(title) })).toBeVisible()
    await reader.getByRole('button', { name: '关闭本轮检索参考' }).click()
    await expect(reader).toHaveCount(0)
  }
  expect(paths).toEqual(
    ['reply-1', 'reply-2', 'reply-3'].map((id) => `${root}/history-review/messages/${id}/sources`),
  )
})

test('当前终态按消息标识取来源，保存未确认与传输结束均不冒充无命中', async ({ page }) => {
  await fixture(page, [])
  let sentSession = ''
  let round = 0
  await page.route('**/api/workspace/java-assistant/chat/stream', (route) => {
    sentSession = route.request().postDataJSON().sessionId
    round += 1
    return route.fulfill({
      contentType: 'text/event-stream',
      body:
        'event: message\ndata: 已生成的答复\n\n' +
        (round === 1
          ? 'event: terminal\ndata: {"phase":"STOPPED","messageId":"current-1","historySaved":false,"knowledgeSourcesSaved":false}\n\n'
          : 'event: done\ndata: [DONE]\n\n'),
    })
  })
  let queriedPath = ''
  let sourcesRecorded = false
  await page.route(`**${root}/*/messages/current-1/sources`, (route) => {
    queriedPath = new URL(route.request().url()).pathname
    return route.fulfill({
      json: {
        code: 0,
        data: sourcesRecorded ? recorded() : { status: 'NOT_RECORDED', retrievals: [] },
      },
    })
  })
  await page.goto('/workspace/java-assistant')
  const composer = page.getByRole('textbox', { name: '消息内容' })
  await composer.fill('核对本次来源')
  await composer.press('Enter')
  await expect(page.getByText('保存未确认', { exact: true })).toBeVisible()
  const reader = await openReader(page)
  await expect(reader).toContainText('本轮未留存检索参考')
  expect(queriedPath).toBe(`${root}/${sentSession}/messages/current-1/sources`)
  sourcesRecorded = true
  await reader.getByRole('button', { name: '刷新来源' }).click()
  await expect(reader.getByRole('button', { name: /退款期限说明/ })).toBeVisible()
  await reader.getByRole('button', { name: '关闭本轮检索参考' }).click()
  await expect(page.getByText('保存未确认', { exact: true })).toHaveCount(0)
  await expect(page.getByText('本轮已停止', { exact: true })).toBeVisible()
  await composer.fill('仅有传输结束')
  await composer.press('Enter')
  const last = page.locator('.chat-panel .message-row.assistant').last()
  await expect(last.getByRole('button', { name: /本轮检索参考/ })).toBeDisabled()
  await expect(last).toContainText('消息标识未确认')
  await expect(last).not.toContainText('未命中')
})

for (const [status, label] of [
  ['NOT_RECORDED', '本轮未留存检索参考'],
  ['MISS', '本轮检索未命中'],
  ['EMPTY', '本轮未留存自动检索记录'],
  ['SKIPPED', '本轮跳过知识检索'],
  ['DEGRADED', '本轮检索未完整完成'],
] as const) {
  test(`${status} 留存状态与真实检索结果有独立提示`, async ({ page }) => {
    await fixture(page)
    await page.route(`**${root}/*/messages/*/sources`, (route) =>
      route.fulfill({
        json: {
          code: 0,
          data:
            status === 'NOT_RECORDED'
              ? { status, retrievals: [] }
              : {
                  status: 'RECORDED',
                  retrievals:
                    status === 'EMPTY'
                      ? []
                      : [{ agentCode: 'java-assistant', status, sources: [] }],
                },
        },
      }),
    )
    await page.goto('/workspace/java-assistant')
    const reader = await openReader(page)
    await expect(reader).toContainText(label)
    await expect(reader.getByRole('button', { name: /退款期限说明/ })).toHaveCount(0)
  })
}

test('列表与原文故障可原地重试，原文只按文本展示且支持 390px 与焦点返回', async ({
  page,
}, testInfo) => {
  await fixture(page)
  let lists = 0
  let previews = 0
  await page.route(`**${root}/*/messages/*/sources`, (route) =>
    route.fulfill({
      json: ++lists === 1 ? { code: 50000, message: '暂不可用' } : { code: 0, data: recorded() },
    }),
  )
  await page.route(`**${root}/*/messages/*/sources/0/preview`, (route) =>
    route.fulfill({
      json:
        ++previews === 1
          ? { code: 50000, message: '暂不可用' }
          : { code: 0, data: { document: metadata(), content: original } },
    }),
  )
  await page.goto('/workspace/java-assistant')
  const trigger = page.getByRole('button', { name: '本轮检索参考', exact: true })
  const reader = await openReader(page)
  await expect(reader).toContainText('检索参考加载失败')
  await expect(reader).not.toContainText('本轮检索未命中')
  await reader.getByRole('button', { name: '重新加载', exact: true }).click()
  await reader.getByRole('button', { name: /退款期限说明/ }).click()
  await expect(reader).toContainText('原文暂不可读')
  await reader.getByRole('button', { name: '重试读取' }).click()
  await expect(reader.getByLabel('已授权的检索来源原文')).toHaveText(original)
  await expect(reader.locator('script')).toHaveCount(0)
  await reader.getByRole('button', { name: '查看来源详情' }).click()
  await expect(reader).toContainText('policy-2026-08')
  await page.screenshot({
    path: testInfo.outputPath('workspace-sources-desktop.png'),
    animations: 'disabled',
  })
  await page.setViewportSize({ width: 390, height: 844 })
  const back = reader.getByRole('button', { name: '返回来源列表' })
  const close = reader.getByRole('button', { name: '关闭本轮检索参考' })
  await expect(back).toBeInViewport()
  const readingTargets = {
    关闭: await close.boundingBox(),
    返回列表: await back.boundingBox(),
    重新核验: await reader.getByRole('button', { name: '重新核验' }).boundingBox(),
  }
  await page.screenshot({
    path: testInfo.outputPath('workspace-sources-mobile.png'),
    animations: 'disabled',
  })
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(390)
  await back.click()
  await expect(reader.getByRole('button', { name: /退款期限说明/ })).toBeFocused()
  const refreshBounds = await reader.getByRole('button', { name: '刷新来源' }).boundingBox()
  await close.click()
  await expect(trigger).toBeFocused()
  const mobileTargets = {
    入口: await trigger.boundingBox(),
    刷新来源: refreshBounds,
    ...readingTargets,
  }
  await testInfo.attach('mobile-action-targets', {
    body: JSON.stringify(mobileTargets, null, 2),
    contentType: 'application/json',
  })
  for (const [name, bounds] of Object.entries(mobileTargets)) {
    expect.soft(bounds?.width ?? 0, `${name}移动点击区域宽度`).toBeGreaterThanOrEqual(44)
    expect.soft(bounds?.height ?? 0, `${name}移动点击区域高度`).toBeGreaterThanOrEqual(44)
  }
})

for (const [code, label] of [
  [20001, '当前账号无权预览'],
  [30003, '此检索来源已不可用'],
] as const) {
  test(`原文重新核验 ${code} 立即清除已读正文`, async ({ page }) => {
    await fixture(page)
    let attempts = 0
    await page.route(`**${root}/*/messages/*/sources/0/preview`, (route) =>
      route.fulfill({
        json:
          ++attempts === 1
            ? { code: 0, data: { document: metadata(), content: original } }
            : { code, message: '访问状态已变化' },
      }),
    )
    await page.goto('/workspace/java-assistant')
    const reader = await openReader(page)
    await reader.getByRole('button', { name: /退款期限说明/ }).click()
    await expect(reader.getByLabel('已授权的检索来源原文')).toHaveText(original)
    await reader.getByRole('button', { name: '重新核验' }).click()
    await expect(reader.getByRole('alert')).toContainText(label)
    await expect(reader.getByLabel('已授权的检索来源原文')).toHaveCount(0)
    await expect(reader).not.toContainText('签收后七天')
  })
}

test('无权、失效与外部线索不可预览，不发出正文或外部请求', async ({ page }) => {
  await fixture(page)
  await page.route(`**${root}/*/messages/*/sources`, (route) =>
    route.fulfill({
      json: {
        code: 0,
        data: {
          status: 'RECORDED',
          retrievals: [
            {
              agentCode: 'java-assistant',
              status: 'HIT',
              sources: ['FORBIDDEN', 'UNAVAILABLE', 'EXTERNAL'].map((status, sourceId) => ({
                sourceId,
                number: sourceId + 1,
                status,
                knowledgeBaseName: status === 'EXTERNAL' ? '外部检索服务' : null,
                documentId: null,
                chunkId: null,
                score: null,
                document: null,
              })),
            },
          ],
        },
      },
    }),
  )
  await page.goto('/workspace/java-assistant')
  const reader = await openReader(page)
  await expect(reader).toContainText('当前账号无权预览')
  await expect(reader).toContainText('来源已失效')
  await expect(reader).toContainText('外部来源仅提供线索')
  await expect(reader.locator('.knowledge-source:disabled')).toHaveCount(3)
})

for (const change of ['session', 'tab', 'page', 'permissions', 'principal'] as const) {
  test(`切换 ${change} 同步移除原文并丢弃迟到响应`, async ({ page }) => {
    await fixture(page)
    let release = () => {}
    const pending = new Promise<void>((resolve) => {
      release = resolve
    })
    let attempts = 0
    let held = false
    await page.route(`**${root}/*/messages/*/sources/0/preview`, async (route) => {
      if (++attempts > 1) {
        held = true
        await pending
      }
      await route.fulfill({ json: { code: 0, data: { document: metadata(), content: original } } })
    })
    await page.goto('/workspace/java-assistant')
    const reader = await openReader(page)
    await reader.getByRole('button', { name: /退款期限说明/ }).click()
    await expect(reader.getByLabel('已授权的检索来源原文')).toHaveText(original)
    await reader.getByRole('button', { name: '重新核验' }).click()
    await expect.poll(() => held).toBe(true)
    await expect(reader.getByLabel('已授权的检索来源原文')).toHaveCount(0)
    if (change === 'session') {
      await page.evaluate(async () => {
        const modulePath = '/src/store/chatConversations.ts'
        const { useChatConversationsStore } = await import(modulePath)
        useChatConversationsStore().newSession('java-assistant')
      })
    } else if (change === 'tab') await navigate(page, '/workspace/java-assistant?mode=vibecoding')
    else if (change === 'page') await navigate(page, '/home')
    else
      await page.evaluate(async (mode) => {
        const modulePath = '/src/store/auth.ts'
        const { useAuthStore } = await import(modulePath)
        if (mode === 'permissions') await useAuthStore().loadPermissions()
        else useAuthStore().token = 'another-account-token'
      }, change)
    await expect(page.locator('.workspace-knowledge-sources:visible')).toHaveCount(0)
    const response = page.waitForResponse((result) => result.url().endsWith('/sources/0/preview'))
    release()
    await (await response).finished()
    await page.evaluate(
      () => new Promise<void>((resolve) => requestAnimationFrame(() => resolve())),
    )
    await expect(page.getByLabel('已授权的检索来源原文')).toHaveCount(0)
    await expect(page.locator('body')).not.toContainText('签收后七天')
  })
}

for (const delayed of ['list', 'preview'] as const) {
  test(`打开另一条答复后迟到的 ${delayed} 不覆盖该轮来源和原文`, async ({ page }) => {
    await fixture(page, [historyMessage('reply-1'), historyMessage('reply-2')])
    let release = () => {}
    const pending = new Promise<void>((resolve) => {
      release = resolve
    })
    let held = false
    const heldPath = `${root}/history-review/messages/reply-1/sources${delayed === 'preview' ? '/0/preview' : ''}`
    await page.route(`**${heldPath}`, async (route) => {
      held = true
      await pending
      await route.fulfill({
        json: {
          code: 0,
          data: delayed === 'list' ? recorded() : { document: metadata(), content: original },
        },
      })
    })
    await page.route(`**${root}/history-review/messages/reply-2/sources`, (route) =>
      route.fulfill({ json: { code: 0, data: recorded(200) } }),
    )
    await page.route(`**${root}/history-review/messages/reply-2/sources/0/preview`, (route) =>
      route.fulfill({
        json: { code: 0, data: { document: metadata(200), content: '第二轮独立原文。' } },
      }),
    )
    await page.goto('/workspace/java-assistant')
    let reader = await openReader(page)
    if (delayed === 'preview') await reader.getByRole('button', { name: /退款期限说明/ }).click()
    await expect.poll(() => held).toBe(true)
    await reader.getByRole('button', { name: '关闭本轮检索参考' }).click()
    await expect(reader).toHaveCount(0)
    reader = await openReader(page, 1)
    await reader.getByRole('button', { name: /物流进度说明/ }).click()
    await expect(reader.getByLabel('已授权的检索来源原文')).toHaveText('第二轮独立原文。')
    const response = page.waitForResponse((result) => new URL(result.url()).pathname === heldPath)
    release()
    await (await response).finished()
    await page.evaluate(
      () => new Promise<void>((resolve) => requestAnimationFrame(() => resolve())),
    )
    await expect(reader.getByLabel('已授权的检索来源原文')).toHaveText('第二轮独立原文。')
    await expect(reader).not.toContainText('退款期限说明')
    await expect(reader).not.toContainText('签收后七天')
  })
}
