import { expect, test, type Page, type WebSocketRoute } from '@playwright/test'
import type { ChatMessage, TicketDetail } from '../../src/types/api'

const sessionId = 'uU1:browser'
const ticketId = 'TK-browser'
const userId = 'U1'
interface ChatCommand { sessionId: string; content: string; clientMsgId: string }

async function installConversation(page: Page, initialMessages: ChatMessage[] = []) {
  const commands: ChatCommand[] = []
  const sockets: WebSocketRoute[] = []
  const receipts = new Map<string, ChatMessage>()
  const messages: ChatMessage[] = [...initialMessages]
  const historyQueries: Array<number | null> = []
  const failures = { history: false }
  const unexpected: string[] = []
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
    if (path === `/api/customer/user/tickets/${ticketId}`) body = detail
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
    historyQueries, failures, receiptQueries: () => receiptQueries }
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

test('答复历史恢复状态、助手计划与长参考线索，390px 可展开核对且没有原文入口', async ({ page }, testInfo) => {
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
