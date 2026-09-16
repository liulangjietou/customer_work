import { expect, test, type Page, type WebSocketRoute } from '@playwright/test'
import type { ChatMessage, TicketDetail } from '../../src/types/api'
import type { RefundApprovalView } from '../../src/types/businessProgress'
import type { CustomerAnswerSource, CustomerAnswerSourcePreview } from '../../src/types/customerAnswerSources'

const sessionId = 'uU1:browser'
const ticketId = 'TK-browser'
const userId = 'U1'
interface ChatCommand { sessionId: string; content: string; clientMsgId: string }
interface SourceReply { body: unknown; status?: number; gate?: Promise<void> }

async function installConversation(page: Page, initialMessages: ChatMessage[] = []) {
  const commands: ChatCommand[] = []
  const sockets: WebSocketRoute[] = []
  const receipts = new Map<string, ChatMessage>()
  const messages: ChatMessage[] = [...initialMessages]
  const historyQueries: Array<number | null> = []
  const failures = { history: false }
  const unexpected: string[] = []
  const sourceReplies = new Map<string, SourceReply>()
  const sourceRequests: Array<{ path: string; cacheControl: string | undefined }> = []
  const businessReplies = new Map<string, SourceReply>()
  const businessRequests: Array<{ path: string; page: number; size: number; cache: string | undefined }> = []
  const orders = new Map<string, unknown>()
  const extraTickets = new Map<string, TicketDetail>()
  const extraMessages = new Map<string, ChatMessage[]>()
  let receiptQueries = 0
  const detail: TicketDetail = {
    ticket: {
      id: ticketId, sessionId, userId, title: '订单配送咨询', category: 'ORDER', priority: 'NORMAL',
      status: 'PROCESSING', assignee: '客服小陈', handoffReason: null, resolveNote: null,
      reopenCount: 0, createdAtMs: Date.now(), updatedAtMs: Date.now(),
    },
    events: [],
  }
  await page.context().addInitScript(({ userId }) => {
    localStorage.setItem('user-token', 'browser-fixture-token')
    localStorage.setItem('user-id', userId)
    localStorage.setItem('user-nickname', '陈先生')
  }, { userId })
  await page.context().route('**/*', async route => {
    const url = new URL(route.request().url())
    const path = decodeURIComponent(url.pathname)
    if (url.origin !== 'http://127.0.0.1:4176') {
      unexpected.push(`external ${url.origin}${path}`)
      return route.abort()
    }
    if (!path.startsWith('/api/')) return route.continue()
    if (route.request().method() !== 'GET') {
      unexpected.push(`${route.request().method()} ${path}`)
      return route.abort()
    }
    let body: unknown
    if (path.endsWith('/refund-approvals')) {
      const number = Number(url.searchParams.get('page') || 1)
      businessRequests.push({ path, page: number, size: Number(url.searchParams.get('size')), cache: route.request().headers()['cache-control'] })
      const reply = businessReplies.get(`${path}?page=${number}`) ?? { body: { total: 0, items: [] } }
      if (reply.gate) await reply.gate
      return route.fulfill({ status: reply.status ?? 200, json: reply.body, headers: { 'Cache-Control': 'no-store' } })
    }
    if (orders.has(path)) return route.fulfill({ json: orders.get(path) })
    if (sourceReplies.has(path)) {
      const reply = sourceReplies.get(path)!
      sourceRequests.push({ path, cacheControl: route.request().headers()['cache-control'] })
      if (reply.gate) await reply.gate
      return route.fulfill({ status: reply.status ?? 200, json: reply.body, headers: { 'Cache-Control': 'no-store' } })
    }
    if (path === '/api/customer/user/tickets') body = { total: 1, items: [detail.ticket] }
    else if (path.startsWith('/api/customer/user/tickets/') && extraTickets.has(path.split('/').at(-1)!)) body = extraTickets.get(path.split('/').at(-1)!)
    else if (path.endsWith('/messages') && extraMessages.has(path.split('/').at(-2)!)) body = extraMessages.get(path.split('/').at(-2)!)
    else if (path === `/api/customer/user/tickets/${ticketId}`) body = detail
    else if (path === `/api/customer/user/sessions/${sessionId}/messages`) {
      if (failures.history) return route.fulfill({ status: 503, json: { message: '会话记录暂时无法加载' } })
      const beforeId = url.searchParams.has('beforeId') ? Number(url.searchParams.get('beforeId')) : null
      historyQueries.push(beforeId)
      body = messages.filter(message => beforeId === null || message.id < beforeId)
        .slice(-Number(url.searchParams.get('limit') || 50))
    }
    else if (path.startsWith(`/api/customer/user/sessions/${sessionId}/receipts/`)) {
      receiptQueries++
      const clientMsgId = path.split('/').at(-1)!
      body = { clientMsgId, message: receipts.get(clientMsgId) ?? null }
    } else if (path === '/api/customer/feedback') body = []
    else if (path === '/api/customer/user/quota') body = {
      levelCode: null, windowSeconds: 3600, tokenUsed: 0, tokenLimit: 0, tokenRemaining: -1,
      requestUsed: 0, requestLimit: 0, requestRemaining: -1, limited: false,
    }
    else if (path === `/api/customer/csat/${sessionId}`) return route.fulfill({ status: 404, json: {} })
    else {
      unexpected.push(`GET ${path}`)
      return route.abort()
    }
    return route.fulfill({ json: body })
  })
  await page.context().routeWebSocket('**/*', socket => {
    const url = new URL(socket.url())
    // Vite 热更新只保留本机连接，不转接真实业务服务。
    if (url.host === '127.0.0.1:4176' && url.pathname === '/' && url.searchParams.has('token')) {
      socket.onMessage(() => {})
      socket.send(JSON.stringify({ type: 'connected' }))
      return
    }
    if (url.host !== '127.0.0.1:4176' || url.pathname !== '/ws/user') {
      unexpected.push(`WS ${url.host}${url.pathname}`)
      socket.close()
      return
    }
    sockets.push(socket)
    socket.onMessage(raw => {
      const frame = JSON.parse(String(raw))
      if (frame.type === 'chat') commands.push(frame.data)
      else if (frame.type === 'ping') socket.send(JSON.stringify({ type: 'pong' }))
    })
  })
  function persist(command: ChatCommand) {
    const existing = receipts.get(command.clientMsgId)
    if (existing) return existing
    const message: ChatMessage = {
      id: messages.length + 1, messageId: `MSG-${command.clientMsgId}`, sessionId, ticketId,
      senderType: 'USER', senderId: userId, content: command.content, createdAtMs: Date.now(),
    }
    messages.push(message)
    receipts.set(command.clientMsgId, message)
    return message
  }
  function send(type: string, data: unknown) {
    sockets.at(-1)!.send(JSON.stringify({ type, data }))
  }
  function acknowledge(command: ChatCommand) {
    const saved = persist(command)
    send('chat_accepted', { clientMsgId: command.clientMsgId, id: saved.id, messageId: saved.messageId,
      sessionId, ticketId, ts: saved.createdAtMs })
  }
  await page.goto(`/chat?ticketId=${ticketId}`)
  await expect.poll(() => sockets.length).toBe(1)
  await expect(page.getByLabel('消息内容')).toBeVisible()
  return { commands, sockets, messages, detail, persist, send, acknowledge, unexpected,
    historyQueries, failures, sourceReplies, sourceRequests, extraTickets, extraMessages,
    businessReplies, businessRequests, orders,
    receiptQueries: () => receiptQueries }
}

test('手机端保留发送中的草稿，收到回执后确认受理', async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 390, height: 844 })
  const fixture = await installConversation(page)
  const input = page.getByLabel('消息内容')
  await input.fill('请帮我确认订单的配送时间。')
  await page.getByRole('button', { name: '发送', exact: true }).click()
  await expect.poll(() => fixture.commands.length).toBe(1)
  await expect(input).toHaveValue('请帮我确认订单的配送时间。')
  await expect(page.locator('.delivery-state')).toContainText('发送中')
  await page.screenshot({ path: testInfo.outputPath('receipt-sending-mobile.png'), fullPage: true })
  fixture.acknowledge(fixture.commands[0]!)
  await expect(input).toHaveValue('')
  await expect(page.locator('.delivery-state')).toContainText('已受理')
  await page.screenshot({ path: testInfo.outputPath('receipt-accepted-mobile.png'), fullPage: true })
  expect(fixture.unexpected).toEqual([])
})

test('回执丢失时先查已保存消息，核对成功不会再次发命令', async ({ page }) => {
  const fixture = await installConversation(page)
  await page.getByLabel('消息内容').fill('这条消息只受理一次')
  await page.getByRole('button', { name: '发送', exact: true }).click()
  await expect.poll(() => fixture.commands.length).toBe(1)
  const command = fixture.commands[0]!
  fixture.persist(command)
  fixture.send('error', { code: 'CHAT_ERROR', sessionId, clientMsgId: command.clientMsgId,
    acceptance: 'UNKNOWN', message: '暂未确认受理结果，请核对。' })
  await page.getByRole('button', { name: '核对并重试' }).click()
  await expect(page.locator('.delivery-state')).toContainText('已受理')
  expect(fixture.receiptQueries()).toBe(1)
  expect(fixture.commands).toHaveLength(1)
  expect(fixture.unexpected).toEqual([])
})

test('未受理的原消息沿用标识重试，后来编辑的草稿不被回执清空', async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 360, height: 800 })
  const fixture = await installConversation(page)
  const input = page.getByLabel('消息内容')
  await input.fill('上一条配送咨询')
  await page.getByRole('button', { name: '发送', exact: true }).click()
  await expect.poll(() => fixture.commands.length).toBe(1)
  const original = fixture.commands[0]!
  fixture.send('error', { code: 'CHAT_QUOTA_EXCEEDED', sessionId, clientMsgId: original.clientMsgId,
    acceptance: 'REJECTED', message: '暂时未受理，原消息和附件已保留，连接恢复后可核对并重试。' })
  await input.fill('这是后来编辑的另一条消息')
  await page.screenshot({ path: testInfo.outputPath('receipt-rejected-mobile.png'), fullPage: true })
  const retry = page.getByRole('button', { name: '重试发送' })
  const bounds = await retry.boundingBox()
  expect(bounds!.height).toBeGreaterThanOrEqual(44)
  await retry.click()
  await expect.poll(() => fixture.commands.length).toBe(2)
  expect(fixture.commands[1]).toEqual(original)
  fixture.acknowledge(original)
  await expect(page.locator('.delivery-state')).toContainText('已受理')
  await expect(input).toHaveValue('这是后来编辑的另一条消息')
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  expect(fixture.unexpected).toEqual([])
})

test('断线期间的消息和关闭状态在重连后补齐', async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 390, height: 844 })
  const fixture = await installConversation(page)
  fixture.sockets[0]!.close({ code: 1012, reason: 'fixture reconnect' })
  fixture.messages.push({ id: 25, messageId: 'MSG-offline-agent', sessionId, ticketId,
    senderType: 'AGENT', senderId: 'agent-7', content: '您的订单已签收，本次服务已结束。', createdAtMs: Date.now() })
  fixture.detail.ticket.status = 'CLOSED'
  await expect.poll(() => fixture.sockets.length).toBe(2)
  await expect(page.getByText('您的订单已签收，本次服务已结束。', { exact: true })).toHaveCount(1)
  await expect(page.getByLabel('消息内容')).toHaveCount(0)
  await page.screenshot({ path: testInfo.outputPath('receipt-reconnected-closed-mobile.png'), fullPage: true })
  expect(fixture.unexpected).toEqual([])
})

test('重连补拉超过一页的消息，连续记录不会留下空档', async ({ page }) => {
  const message = (id: number): ChatMessage => ({ id, messageId: `MSG-history-${id}`, sessionId, ticketId,
    senderType: 'BOT', senderId: null, content: `第 ${id} 条服务记录`, createdAtMs: 1_780_000_000_000 + id })
  const fixture = await installConversation(page, [message(1)])
  await expect.poll(() => fixture.historyQueries.length).toBeGreaterThanOrEqual(2)
  fixture.sockets[0]!.close({ code: 1012, reason: 'fixture gap' })
  fixture.messages.push(...Array.from({ length: 100 }, (_, index) => message(index + 2)))
  await expect.poll(() => fixture.sockets.length).toBe(2)
  await expect(page.locator('.row-BOT')).toHaveCount(101)
  expect(fixture.historyQueries).toContain(52)
  expect(fixture.historyQueries).toContain(2)
  expect(fixture.unexpected).toEqual([])
})

test('补拉失败保留可展开的中断片段，恢复后可原地同步', async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 390, height: 844 })
  const fixture = await installConversation(page)
  await page.getByLabel('消息内容').fill('查询当前配送进度')
  await page.getByRole('button', { name: '发送', exact: true }).click()
  await expect.poll(() => fixture.commands.length).toBe(1)
  const command = fixture.commands[0]!
  fixture.acknowledge(command)
  fixture.send('chat_chunk', { sessionId, ticketId, clientMsgId: command.clientMsgId, content: '已查到订单，正在核对配送' })
  await expect(page.getByText('已查到订单，正在核对配送', { exact: false })).toBeVisible()
  fixture.failures.history = true
  fixture.sockets[0]!.close({ code: 1012, reason: 'fixture interrupted' })
  await expect(page.getByRole('button', { name: '重新同步' })).toBeVisible()
  await page.locator('.interrupted-reply summary').click()
  await expect(page.getByText('已查到订单，正在核对配送', { exact: true })).toBeVisible()
  await page.screenshot({ path: testInfo.outputPath('receipt-interrupted-mobile.png'), fullPage: true })
  fixture.failures.history = false
  await page.getByRole('button', { name: '重新同步' }).click()
  await expect(page.locator('.synchronization-error')).toHaveCount(0)
  expect(fixture.commands).toHaveLength(1)
  expect(fixture.unexpected).toEqual([])
})

const evidence = {
  finishReason: 'INTERRUPTED',
  citations: [{ knowledgeBase: '售后服务政策与退款办理参考说明'.repeat(5),
    documentId: 'refund-policy-document-'.repeat(12), chunkId: 'refund-7-days', score: 0.9 }],
  taskPlan: [{ content: '核对这笔订单的退款申请、实际退款进度以及到账渠道，保留待确认的事项。'.repeat(3),
    status: 'completed', priority: 'high' }],
}
const savedAnswer = (id = 1, extra = {}): ChatMessage => ({
  id, messageId: `MSG-answer-${id}`, sessionId, ticketId, senderType: 'BOT', senderId: null,
  content: `第 ${id} 条答复：已整理可供核对的信息。`, createdAtMs: 1_780_000_000_000 + id,
  ...extra,
})

test('答复历史恢复状态、助手计划与长参考线索，390px 保留本地线索展开', async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 390, height: 844 })
  const errors: string[] = []
  page.on('pageerror', error => errors.push(error.message))
  const fixture = await installConversation(page, [savedAnswer(1, evidence)])
  const row = page.locator('.row-BOT')
  await expect(row.locator('.answer-status')).toContainText('答复已中断')
  await expect(row.locator('.task-plan')).toContainText('助手计划')
  await expect(row.locator('.task-plan')).toContainText('实际办理结果以订单或工单为准')
  await expect(row.locator('.task-plan')).toContainText('助手标记完成')
  const reference = row.locator('.citations summary')
  await reference.scrollIntoViewIfNeeded()
  await reference.click()
  await expect(row.getByText(evidence.citations[0]!.documentId, { exact: true })).toBeVisible()
  const bounds = await reference.boundingBox()
  expect(bounds!.width).toBeGreaterThanOrEqual(44)
  expect(bounds!.height).toBeGreaterThanOrEqual(44)
  await expect(row.getByRole('button', { name: /原文|预览/ })).toHaveCount(0)
  await expect(row.locator('a')).toHaveCount(0)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  for (const selector of ['.task-plan', '.citations']) {
    expect(await row.locator(selector).evaluate(element => element.scrollWidth <= element.clientWidth)).toBe(true)
  }
  await page.screenshot({ path: testInfo.outputPath('h5-answer-evidence-390.png'), fullPage: true })
  const lastReference = row.getByText(evidence.citations[0]!.chunkId, { exact: true })
  await lastReference.scrollIntoViewIfNeeded()
  const lastBounds = await lastReference.boundingBox()
  const messageArea = await page.locator('.message-area').boundingBox()
  expect(lastBounds!.y).toBeGreaterThanOrEqual(messageArea!.y)
  expect(lastBounds!.y + lastBounds!.height).toBeLessThanOrEqual(messageArea!.y + messageArea!.height)
  await testInfo.attach('mobile-reference-geometry', {
    body: JSON.stringify({ summary: bounds, lastReference: lastBounds, messageArea }),
    contentType: 'application/json',
  })
  await page.screenshot({ path: testInfo.outputPath('h5-answer-reference-bottom-390.png'), fullPage: true })
  await reference.scrollIntoViewIfNeeded()
  await reference.press('Space')
  await expect(row.locator('.citations')).not.toHaveAttribute('open')
  await expect(reference).toBeFocused()
  await page.reload()
  await expect(page.locator('.answer-status')).toContainText('答复已中断')
  await expect(page.locator('.task-plan')).toContainText(evidence.taskPlan[0]!.content)
  expect(errors).toEqual([])
  expect(fixture.unexpected).toEqual([])
})

const savedSource: CustomerAnswerSource = {
  sourceIndex: 0, status: 'AVAILABLE', title: '退货政策的历史适用条件与办理说明'.repeat(6),
  knowledgeBase: '售后服务知识库', versionNo: 7, sourceVersion: 'policy-revision-2026-09-01',
}
const savedPreview: CustomerAnswerSourcePreview = {
  ...savedSource,
  content: '本段资料用于核对签收时间和适用条件，办理结果以业务记录为准。\n'.repeat(85)
    + '<img src="https://invalid.example/private.png">\n历史段落结束。',
}
const unavailableSource: CustomerAnswerSourcePreview = {
  sourceIndex: 0, status: 'UNAVAILABLE', title: null, knowledgeBase: null,
  versionNo: null, sourceVersion: null, content: null,
}
const sourcePath = (messageId: string, targetSession = sessionId) =>
  `/api/customer/user/sessions/${targetSession}/messages/${messageId}/sources`

for (const width of [390, 360]) {
  test(`参考资料 ${width}px 使用真实面板展示长历史段落，触控与关闭焦点可达`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width, height: 844 })
    const errors: string[] = []
    page.on('pageerror', error => errors.push(error.message))
    const fixture = await installConversation(page, [savedAnswer(1, evidence), savedAnswer(2)])
    const listPath = sourcePath('MSG-answer-1')
    fixture.sourceReplies.set(listPath, { body: [savedSource] })
    fixture.sourceReplies.set(`${listPath}/0`, { body: savedPreview })
    const entry = page.getByRole('button', { name: '查看参考资料', exact: true })
    await expect(entry).toHaveCount(1)
    const entryBounds = await entry.boundingBox()
    expect(entryBounds!.width).toBeGreaterThanOrEqual(44)
    expect(entryBounds!.height).toBeGreaterThanOrEqual(44)
    await entry.click()
    const panel = page.getByRole('dialog', { name: '参考资料', exact: true })
    await expect(panel).toBeVisible()
    await expect(panel).toContainText('知识库版本 7')
    await expect(panel).toContainText('来源版本 policy-revision-2026-09-01')
    for (const target of [panel.getByRole('button', { name: '关闭', exact: true }),
      panel.getByRole('button', { name: '刷新参考资料' }), panel.locator('[data-source-index="0"]')]) {
      const bounds = await target.boundingBox()
      expect(bounds!.width).toBeGreaterThanOrEqual(44)
      expect(bounds!.height).toBeGreaterThanOrEqual(44)
    }
    await panel.locator('[data-source-index="0"]').click()
    await expect(panel.locator('pre')).toHaveText(savedPreview.content!)
    await expect(panel.locator('img, a, script')).toHaveCount(0)
    for (const name of ['返回列表', '重新核验原文']) {
      const bounds = await panel.getByRole('button', { name, exact: true }).boundingBox()
      expect(bounds!.width).toBeGreaterThanOrEqual(44)
      expect(bounds!.height).toBeGreaterThanOrEqual(44)
    }
    await expect(panel.locator('.source-body')).toHaveJSProperty('scrollLeft', 0)
    expect(await panel.locator('.source-body').evaluate(element => element.scrollWidth <= element.clientWidth)).toBe(true)
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    await page.screenshot({ path: testInfo.outputPath(`customer-source-preview-${width}.png`), fullPage: true })
    await panel.locator('.source-body').evaluate(element => { element.scrollTop = element.scrollHeight })
    expect(await panel.locator('.source-body').evaluate(element => element.scrollHeight - element.scrollTop - element.clientHeight)).toBeLessThanOrEqual(1)
    await page.screenshot({ path: testInfo.outputPath(`customer-source-preview-end-${width}.png`), fullPage: true })
    await panel.getByRole('button', { name: '返回列表', exact: true }).click()
    await expect(panel.locator('[data-source-index="0"]')).toBeFocused()
    await panel.getByRole('heading', { name: '参考资料', exact: true }).click()
    await page.keyboard.press('Shift+Tab')
    await expect(panel.getByRole('button', { name: '刷新参考资料' })).toBeFocused()
    await page.keyboard.press('Escape')
    await expect(panel).toHaveCount(0)
    await expect(entry).toBeFocused()
    await entry.click()
    await expect(panel.locator('[data-source-index="0"]')).toBeVisible()
    expect(fixture.sourceRequests.filter(request => request.path === listPath)).toHaveLength(3)
    expect(fixture.sourceRequests.every(request => request.cacheControl === 'no-store')).toBe(true)
    expect(errors).toEqual([])
    expect(fixture.unexpected).toEqual([])
  })
}

test('参考资料空列表、失效来源和读取失败各自展示，缺失版本不补造', async ({ page }) => {
  const fixture = await installConversation(page, [savedAnswer(1, evidence)])
  const path = sourcePath('MSG-answer-1')
  fixture.sourceReplies.set(path, { body: [] })
  await page.getByRole('button', { name: '查看参考资料' }).click()
  const panel = page.getByRole('dialog', { name: '参考资料', exact: true })
  await expect(panel).toContainText('这条答复没有可打开的参考资料')
  await expect(panel.locator('[data-source-index]')).toHaveCount(0)
  fixture.sourceReplies.set(path, { status: 503, body: { message: 'internal-source-trace' } })
  await panel.getByRole('button', { name: '刷新参考资料' }).click()
  await expect(panel.getByRole('alert')).toContainText('参考资料暂时无法加载')
  await expect(panel.getByRole('button', { name: '重新核验原文' })).toHaveCount(0)
  await expect(panel).not.toContainText('没有可打开的参考资料')
  await expect(panel).not.toContainText('internal-source-trace')
  fixture.sourceReplies.set(path, { body: [unavailableSource] })
  await panel.getByRole('button', { name: '重新核对', exact: true }).click()
  await expect(panel).toContainText('资料已不可用')
  await expect(panel.locator('[data-source-index]')).toHaveCount(0)
  fixture.sourceReplies.set(path, { body: [{ ...savedSource, versionNo: null, sourceVersion: null }] })
  fixture.sourceReplies.set(`${path}/0`, { status: 404, body: { message: 'not found' } })
  await panel.getByRole('button', { name: '刷新参考资料' }).click()
  await expect(panel.locator('[data-source-index="0"]')).toBeVisible()
  await expect(panel).not.toContainText('知识库版本')
  await expect(panel).not.toContainText('来源版本')
  await panel.locator('[data-source-index="0"]').click()
  await expect(panel.getByRole('alert')).toContainText('参考资料当前不可访问')
  await expect(panel).not.toContainText(savedSource.title!)
  await expect(panel.locator('pre')).toHaveCount(0)
  expect(fixture.unexpected).toEqual([])
})

test('参考资料重新核验先清原文，503 可重试而撤权不保留旧标题正文', async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 390, height: 844 })
  const fixture = await installConversation(page, [savedAnswer(1, evidence)])
  const path = sourcePath('MSG-answer-1')
  const actual = { ...savedPreview, title: '历史政策', content: '已授权的历史原文。' }
  fixture.sourceReplies.set(path, { body: [savedSource] })
  fixture.sourceReplies.set(`${path}/0`, { body: actual })
  await page.getByRole('button', { name: '查看参考资料' }).click()
  const panel = page.getByRole('dialog', { name: '参考资料', exact: true })
  await panel.locator('[data-source-index="0"]').click()
  await expect(panel.locator('pre')).toHaveText(actual.content)
  let release!: () => void
  const gate = new Promise<void>(resolve => { release = resolve })
  fixture.sourceReplies.set(`${path}/0`, { status: 503, body: { message: 'private-service-error' }, gate })
  try {
    await panel.getByRole('button', { name: '重新核验原文' }).click()
    await expect.poll(() => fixture.sourceRequests.length).toBe(3)
    await expect(panel.locator('pre')).toHaveCount(0)
    await expect(panel).not.toContainText(actual.title)
  } finally {
    release()
  }
  await expect(panel.getByRole('alert')).toContainText('参考资料暂时无法加载')
  fixture.sourceReplies.set(`${path}/0`, { body: actual })
  await panel.getByRole('button', { name: '重新核对', exact: true }).click()
  await expect(panel.locator('pre')).toHaveText(actual.content)
  fixture.sourceReplies.set(`${path}/0`, { body: unavailableSource })
  await panel.getByRole('button', { name: '重新核验原文' }).click()
  await expect(panel.getByRole('alert')).toContainText('资料已不可用')
  await expect(panel.getByRole('button', { name: '重新核验原文' })).toHaveCount(0)
  await expect(panel.locator('pre')).toHaveCount(0)
  await expect(panel).not.toContainText(actual.title)
  await expect(panel).not.toContainText(actual.knowledgeBase!)
  await page.screenshot({ path: testInfo.outputPath('customer-source-revoked-390.png'), fullPage: true })
  expect(fixture.unexpected).toEqual([])
})

test('参考资料关闭后切到当前新答复，旧消息迟到原文不能覆盖新消息', async ({ page }) => {
  const fixture = await installConversation(page, [savedAnswer(1, evidence)])
  const current = savedAnswer(2, evidence)
  fixture.send('chat_done', { ...current, ts: current.createdAtMs, usage: {}, traceId: 'fixture' })
  const oldPath = sourcePath('MSG-answer-1')
  const newPath = sourcePath('MSG-answer-2')
  let release!: () => void
  const gate = new Promise<void>(resolve => { release = resolve })
  fixture.sourceReplies.set(oldPath, { body: [savedSource] })
  fixture.sourceReplies.set(`${oldPath}/0`, { body: { ...savedPreview, content: '不得复活的旧消息原文' }, gate })
  fixture.sourceReplies.set(newPath, { body: [{ ...savedSource, title: '当前消息的资料' }] })
  fixture.sourceReplies.set(`${newPath}/0`, { body: { ...savedPreview, title: '当前消息的资料', content: '当前消息的授权段落' } })
  const entries = page.getByRole('button', { name: '查看参考资料', exact: true })
  await expect(entries).toHaveCount(2)
  await entries.nth(0).click()
  const panel = page.getByRole('dialog', { name: '参考资料', exact: true })
  try {
    await panel.locator('[data-source-index="0"]').click()
    await expect.poll(() => fixture.sourceRequests.some(request => request.path === `${oldPath}/0`)).toBe(true)
    await panel.getByRole('button', { name: '关闭', exact: true }).click()
    await expect(panel).toHaveCount(0)
    await entries.nth(1).click()
    await panel.locator('[data-source-index="0"]').click()
    await expect(panel.locator('pre')).toHaveText('当前消息的授权段落')
    const lateResponse = page.waitForResponse(response =>
      decodeURIComponent(new URL(response.url()).pathname) === `${oldPath}/0`)
    release()
    await (await lateResponse).finished()
    await page.evaluate(() => new Promise<void>(resolve => requestAnimationFrame(() => resolve())))
  } finally {
    release()
  }
  await expect(panel.locator('pre')).toHaveText('当前消息的授权段落')
  await expect(page.locator('body')).not.toContainText('不得复活的旧消息原文')
  expect(fixture.unexpected).toEqual([])
})

test('参考资料切会话和离开页面清除正文，迟到读取不能复活旧面板', async ({ page }) => {
  const fixture = await installConversation(page, [savedAnswer(1, evidence)])
  const oldPath = sourcePath('MSG-answer-1')
  let release!: () => void
  const gate = new Promise<void>(resolve => { release = resolve })
  fixture.sourceReplies.set(oldPath, { body: [savedSource] })
  fixture.sourceReplies.set(`${oldPath}/0`, { body: { ...savedPreview, content: '旧会话迟到的段落' }, gate })
  const nextTicket = 'TK-source-next'
  const nextSession = 'uU1:source-next'
  fixture.extraTickets.set(nextTicket, { ...fixture.detail, ticket: { ...fixture.detail.ticket, id: nextTicket, sessionId: nextSession } })
  fixture.extraMessages.set(nextSession, [savedAnswer(2, { ...evidence, sessionId: nextSession, ticketId: nextTicket, content: '第二会话的答复' })])
  const newPath = sourcePath('MSG-answer-2', nextSession)
  fixture.sourceReplies.set(newPath, { body: [{ ...savedSource, title: '第二会话资料' }] })
  await page.getByRole('button', { name: '查看参考资料' }).click()
  const panel = page.getByRole('dialog', { name: '参考资料', exact: true })
  try {
    await panel.locator('[data-source-index="0"]').click()
    await expect.poll(() => fixture.sourceRequests.some(request => request.path === `${oldPath}/0`)).toBe(true)
    await page.goto(`/chat?ticketId=${nextTicket}`)
    await expect(page.getByText('第二会话的答复', { exact: true })).toBeVisible()
  } finally {
    release()
  }
  await expect(panel).toHaveCount(0)
  await expect(page.locator('body')).not.toContainText('旧会话迟到的段落')
  await page.getByRole('button', { name: '查看参考资料' }).click()
  await expect(panel).toContainText('第二会话资料')
  await page.goto('/messages')
  await expect(panel).toHaveCount(0)
  await expect(page.locator('body')).not.toContainText('第二会话资料')
  expect(fixture.unexpected).toEqual([])
})

test('参考资料核验时登录失效清除已读原文并回到登录页', async ({ page }) => {
  const fixture = await installConversation(page, [savedAnswer(1, evidence)])
  const path = sourcePath('MSG-answer-1')
  fixture.sourceReplies.set(path, { body: [savedSource] })
  fixture.sourceReplies.set(`${path}/0`, { body: { ...savedPreview, content: '登录主体限定的原文' } })
  await page.getByRole('button', { name: '查看参考资料' }).click()
  const panel = page.getByRole('dialog', { name: '参考资料', exact: true })
  await panel.locator('[data-source-index="0"]').click()
  await expect(panel.locator('pre')).toHaveText('登录主体限定的原文')
  fixture.sourceReplies.set(`${path}/0`, { status: 401, body: {} })
  await panel.getByRole('button', { name: '重新核验原文' }).click()
  await expect(page).toHaveURL(/\/login$/)
  await expect(panel).toHaveCount(0)
  await expect(page.locator('body')).not.toContainText('登录主体限定的原文')
  expect(fixture.unexpected).toEqual([])
})

test('答复实时元数据经过旧历史重连补拉仍保留，同消息不会重复', async ({ page }) => {
  const fixture = await installConversation(page)
  const saved = savedAnswer()
  fixture.messages.push(saved)
  fixture.send('chat_done', { ...saved, ...evidence, ts: saved.createdAtMs,
    traceId: 'fixture', usage: { inputTokens: 1, outputTokens: 1, cachedTokens: 0, totalTokens: 2, timeSeconds: 1 } })
  await expect(page.locator('.answer-status')).toContainText('答复已中断')
  fixture.sockets[0]!.close({ code: 1012, reason: 'fixture evidence reconnect' })
  await expect.poll(() => fixture.sockets.length).toBe(2)
  await expect.poll(() => fixture.historyQueries.length).toBeGreaterThanOrEqual(3)
  await expect(page.locator('.task-plan')).toContainText(evidence.taskPlan[0]!.content)
  await expect(page.locator('.citations')).toContainText('参考线索')
  await expect(page.locator('.answer-status')).toContainText('答复已中断')
  await expect(page.locator('.row-BOT')).toHaveCount(1)
  fixture.messages[0] = savedAnswer(1, { finishReason: 'ERROR', taskPlan: [], citations: [] })
  fixture.sockets[1]!.close({ code: 1012, reason: 'fixture authoritative snapshot' })
  await expect.poll(() => fixture.sockets.length).toBe(3)
  await expect(page.locator('.answer-status')).toContainText('答复生成失败')
  await expect(page.locator('.task-plan')).toHaveCount(0)
  await expect(page.locator('.citations')).toHaveCount(0)
  expect(fixture.unexpected).toEqual([])
})

test('答复历史元数据不会被缺失字段的旧 WS 擦除，明确空集合可替换', async ({ page }) => {
  const saved = savedAnswer()
  const fixture = await installConversation(page, [savedAnswer(1, evidence)])
  fixture.send('chat_done', { ...saved, ts: saved.createdAtMs })
  await expect(page.locator('.answer-status')).toContainText('答复已中断')
  await expect(page.locator('.task-plan')).toContainText(evidence.taskPlan[0]!.content)
  await expect(page.locator('.row-BOT')).toHaveCount(1)
  fixture.send('chat_done', { ...saved, finishReason: 'MODEL_STOP', citations: [], taskPlan: [], ts: saved.createdAtMs })
  await expect(page.locator('.answer-status')).toContainText('答复已生成')
  await expect(page.locator('.task-plan')).toHaveCount(0)
  await expect(page.locator('.citations')).toHaveCount(0)
  await expect(page.getByText('本次服务已解决', { exact: true })).toHaveCount(0)
  expect(fixture.detail.ticket.status).toBe('PROCESSING')
  expect(fixture.unexpected).toEqual([])
})

test('答复分页恢复更早的清单与线索，不把旧记录标为已生成', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  const fixture = await installConversation(page, [savedAnswer(1, evidence),
    ...Array.from({ length: 50 }, (_, index) => savedAnswer(index + 2))])
  const older = page.getByRole('button', { name: '加载更早消息' })
  await older.scrollIntoViewIfNeeded()
  await older.click()
  const first = page.locator('.row-BOT').first()
  await expect(first.locator('.answer-status')).toContainText('答复已中断')
  await expect(first.locator('.task-plan')).toContainText(evidence.taskPlan[0]!.content)
  await expect(first.locator('.citations')).toContainText('参考线索')
  await expect(page.locator('.row-BOT').nth(1).locator('.answer-status')).toContainText('未记录结束状态')
  expect(fixture.historyQueries).toContain(2)
  expect(fixture.unexpected).toEqual([])
})

test('答复中断、生成故障、额度不足与未完成均有清晰状态', async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 390, height: 844 })
  const phases = [
    ['INTERRUPTED', '答复已中断'], ['ERROR', '答复生成失败'],
    ['QUOTA_EXCEEDED', '本轮额度不足'], ['MAX_ITERATIONS', '答复尚未完成'],
  ] as const
  const fixture = await installConversation(page,
    phases.map(([finishReason], index) => savedAnswer(index + 1, { finishReason })))
  for (const [index, [, label]] of phases.entries()) {
    await expect(page.locator('.row-BOT').nth(index).locator('.answer-status')).toHaveText(label)
  }
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await page.screenshot({ path: testInfo.outputPath('h5-answer-states-390.png'), fullPage: true })
  expect(fixture.unexpected).toEqual([])
})


const approvalPath = (session = sessionId, page = 1) => `/api/customer/user/sessions/${session}/refund-approvals?page=${page}`
const approvalRecord: RefundApprovalView = {
  id: 'approval-browser', orderId: 'ORDER-20260912-008', amount: '299.00',
  approvalStatus: 'APPROVED', executionStatus: 'NOT_APPLICABLE',
  createdAtMs: 1789188600000, decidedAtMs: 1789188900000,
}

for (const width of [360, 390]) {
  test(`办理进度 ${width}px 可核对独立状态，纯文本长内容和关闭焦点可用`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width, height: 844 })
    const fixture = await installConversation(page)
    fixture.detail.ticket.status = 'WAITING_CONFIRM'
    fixture.detail.ticket.title = '退款与原订单状态核对'
    fixture.businessReplies.set(approvalPath(), { body: { total: 3, items: [
      approvalRecord,
      { ...approvalRecord, id: 'failure', orderId: '<img src=x onerror=alert(1)>长订单编号'.repeat(4), approvalStatus: 'APPROVED', executionStatus: 'EXECUTE_FAILED' },
      { ...approvalRecord, id: 'completed', approvalStatus: 'APPROVED', executionStatus: 'EXECUTED' },
    ] } })
    const trigger = page.getByRole('button', { name: '办理进度', exact: true })
    await trigger.click()
    const panel = page.getByRole('dialog', { name: '办理进度', exact: true })
    await expect(panel).toContainText('待您确认')
    await expect(panel).toContainText('已批准，执行待核对')
    await expect(panel).toContainText('处理未完成')
    await expect(panel).toContainText('到账情况请核对支付渠道记录')
    await expect(panel).not.toContainText('已到账')
    await expect(panel.locator('img')).toHaveCount(0)
    await expect.poll(async () => {
      const bounds = await panel.boundingBox()
      return bounds ? Math.abs(bounds.y + bounds.height - 844) : 999
    }).toBeLessThan(1)
    expect(await panel.evaluate(el => el.scrollWidth <= el.clientWidth)).toBe(true)
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    const sizes = await panel.locator('button').evaluateAll(nodes => nodes.map(node => node.getBoundingClientRect().height))
    expect(sizes.every(height => height >= 44)).toBe(true)
    await page.screenshot({ path: testInfo.outputPath(`business-progress-${width}.png`), fullPage: true, animations: 'disabled' })
    await panel.locator('.refund-card').last().scrollIntoViewIfNeeded()
    await page.screenshot({ path: testInfo.outputPath(`business-progress-end-${width}.png`), fullPage: true, animations: 'disabled' })
    const refresh = panel.getByRole('button', { name: '刷新办理进度' })
    await refresh.focus()
    await page.keyboard.press('Tab')
    await expect(panel.getByRole('button', { name: '关闭', exact: true })).toBeFocused()
    // 点击非交互标题后，Vant 将焦点交给 Popup 根节点，反向 Tab 也必须留在弹层内。
    await panel.getByRole('heading', { name: '办理进度', exact: true }).click()
    await page.keyboard.press('Shift+Tab')
    await expect(refresh).toBeFocused()
    await page.keyboard.press('Escape')
    await expect(panel).toHaveCount(0)
    await expect(trigger).toBeFocused()
    expect(fixture.businessRequests).toEqual([{ path: approvalPath().split('?')[0], page: 1, size: 10, cache: 'no-store' }])
    expect(fixture.commands).toHaveLength(0)
    expect(fixture.unexpected).toEqual([])
  })
}

test('办理进度失败不会冒充空记录，重试恢复后工单与订单都能回原会话', async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 390, height: 844 })
  const fixture = await installConversation(page)
  fixture.businessReplies.set(approvalPath(), { status: 503, body: { message: 'private-failure-trace' } })
  await page.getByRole('button', { name: '办理进度', exact: true }).click()
  const panel = page.getByRole('dialog', { name: '办理进度', exact: true })
  await expect(panel.locator('[data-section="refund"] [role="alert"]')).toContainText('退款进度暂时无法加载')
  await expect(panel).not.toContainText('暂无退款审批记录')
  await expect(panel).not.toContainText('private-failure-trace')
  await expect.poll(async () => {
    const bounds = await panel.boundingBox()
    return bounds ? Math.abs(bounds.y + bounds.height - 844) : 999
  }).toBeLessThan(1)
  await page.screenshot({ path: testInfo.outputPath('business-progress-retry-390.png'), fullPage: true, animations: 'disabled' })
  fixture.businessReplies.set(approvalPath(), { body: { total: 0, items: [] } })
  await panel.locator('[data-action="retry-refunds"]').click()
  await expect(panel).toContainText('暂无退款审批记录')
  await panel.getByRole('button', { name: '查看工单详情' }).click()
  await expect(page).toHaveURL(`/tickets/${ticketId}`)
  await page.getByRole('button', { name: '继续对话' }).click()
  await expect(page).toHaveURL(`/chat?ticketId=${ticketId}`)
  fixture.businessReplies.set(approvalPath(), { body: { total: 1, items: [approvalRecord] } })
  fixture.orders.set(`/api/customer/user/orders/${approvalRecord.orderId}`, {
    orderId: approvalRecord.orderId, productId: 'headphone', productName: '无线耳机', amount: '299.00',
    status: 'PAID', receiverAddr: '', logisticsTrace: '', createdAtMs: approvalRecord.createdAtMs,
  })
  await page.getByRole('button', { name: '办理进度', exact: true }).click()
  await panel.getByRole('button', { name: '查看订单', exact: false }).click()
  await expect(page).toHaveURL(`/orders/${approvalRecord.orderId}?ticketId=${ticketId}`)
  await page.locator('.service-button').click()
  await expect(page).toHaveURL(`/chat?orderId=${approvalRecord.orderId}&ticketId=${ticketId}`)
  expect(fixture.commands).toHaveLength(0)
  expect(fixture.unexpected).toEqual([])
})

test('办理进度分页、记录减少与刷新都读取当前会话，旧页不会残留', async ({ page }) => {
  const fixture = await installConversation(page)
  fixture.businessReplies.set(approvalPath(), { body: { total: 11, items: [approvalRecord] } })
  fixture.businessReplies.set(approvalPath(sessionId, 2), { body: { total: 11, items: [{ ...approvalRecord, id: 'second-page', amount: '29.00' }] } })
  await page.getByRole('button', { name: '办理进度', exact: true }).click()
  const panel = page.getByRole('dialog', { name: '办理进度', exact: true })
  await panel.getByRole('button', { name: '下一页' }).click()
  await expect(panel.locator('.refund-amount')).toContainText('29.00')
  await expect(panel.locator('.progress-pagination')).toContainText('2 / 2')
  fixture.businessReplies.set(approvalPath(sessionId, 2), { body: { total: 1, items: [] } })
  fixture.businessReplies.set(approvalPath(), { body: { total: 1, items: [{ ...approvalRecord, approvalStatus: 'PENDING' }] } })
  await panel.getByRole('button', { name: '刷新办理进度' }).click()
  await expect(panel.locator('.refund-amount')).toContainText('299.00')
  await expect(panel).toContainText('待人工审核')
  expect(fixture.businessRequests.map(request => request.page)).toEqual([1, 2, 2, 1])
  expect(fixture.unexpected).toEqual([])
})

test('办理进度切换会话丢弃迟到记录，认证失效清空后回到登录', async ({ page }) => {
  const fixture = await installConversation(page)
  let release!: () => void
  const gate = new Promise<void>(resolve => { release = resolve })
  fixture.businessReplies.set(approvalPath(), { gate, body: { total: 1, items: [approvalRecord] } })
  const nextTicket = 'TK-progress-other'
  const nextSession = 'uU1:progress-other'
  fixture.extraTickets.set(nextTicket, { ...fixture.detail, ticket: { ...fixture.detail.ticket, id: nextTicket, sessionId: nextSession } })
  fixture.extraMessages.set(nextSession, [])
  await page.getByRole('button', { name: '办理进度', exact: true }).click()
  await expect.poll(() => fixture.businessRequests.length).toBe(1)
  try { await page.goto(`/chat?ticketId=${nextTicket}`) } finally { release() }
  await expect(page.getByRole('dialog', { name: '办理进度', exact: true })).toHaveCount(0)
  fixture.businessReplies.set(approvalPath(nextSession), { status: 401, body: {} })
  await page.getByRole('button', { name: '办理进度', exact: true }).click()
  await expect(page).toHaveURL(/\/login$/)
  await expect(page.locator('.refund-card')).toHaveCount(0)
  expect(fixture.unexpected).toEqual([])
})
