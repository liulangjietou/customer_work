import { test, expect } from './fixtures/adminTestFixture'
import { deskFixture, ticket } from './fixtures/serviceDeskFixture'

test('改变状态筛选后，队列快捷视图与实际查询保持一致', async ({ page }) => {
  await deskFixture(page)
  await page.goto('/ticket/user-ticket')
  await expect(page.getByText('实时在线', { exact: true })).toBeVisible()
  const waiting = page.getByRole('button', { name: '待接入', exact: true })
  await waiting.click()
  await expect(waiting).toHaveAttribute('aria-pressed', 'true')
  const status = page.getByRole('combobox', { name: '工单状态筛选' })
  await status.focus()
  await status.press('Enter')
  const filtered = page.waitForRequest((request) => {
    const url = new URL(request.url())
    return url.pathname === '/api/ticket/page' && url.searchParams.get('status') === 'PROCESSING'
  })
  await page.getByRole('option', { name: '人工处理中', exact: true }).click()
  await filtered
  await expect(waiting).toHaveAttribute('aria-pressed', 'false')
  await expect(page.getByRole('button', { name: '全部', exact: true })).toHaveAttribute(
    'aria-pressed',
    'true',
  )
  await page.getByRole('button', { name: '我负责', exact: true }).click()
  await expect(page.getByRole('button', { name: '我负责', exact: true })).toHaveAttribute(
    'aria-pressed',
    'true',
  )
})

test('坐席回复必须等待保存回执，网络写出不能清空草稿', async ({ page }) => {
  await deskFixture(page)
  let release!: () => void
  const paused = new Promise<void>((resolve) => {
    release = resolve
  })
  let requests = 0
  await page.route('**/api/ticket/TK-1/reply', async (route) => {
    requests += 1
    const body = route.request().postDataJSON()
    await paused
    await route.fulfill({
      json: {
        code: 0,
        data: {
          id: 21,
          messageId: 'saved-21',
          sessionId: 'session-TK-1',
          ticketId: 'TK-1',
          senderType: 'AGENT',
          senderId: 'admin-shell-e2e',
          content: body.content,
          createdAtMs: Date.now(),
        },
      },
    })
  })
  await page.goto('/ticket/user-ticket')
  await page.getByRole('button', { name: '查看', exact: true }).first().click()
  const input = page.locator('textarea').first()
  await input.fill('正在核对，请稍候。')
  await input.dispatchEvent('keydown', { key: 'Enter', isComposing: true })
  await input.dispatchEvent('keydown', { key: 'Enter', shiftKey: true })
  expect(requests).toBe(0)
  await page.getByRole('button', { name: '发送', exact: true }).click()
  try {
    await expect(input).toHaveValue('正在核对，请稍候。')
    await expect.poll(() => requests).toBe(1)
  } finally {
    release()
  }
  await expect(input).toHaveValue('')
  await expect(page.getByText('正在核对，请稍候。', { exact: true })).toHaveCount(1)
})

test('切换工单保留各自草稿，各工单内容相互独立', async ({ page }, testInfo) => {
  await deskFixture(page)
  await page.setViewportSize({ width: 1680, height: 1050 })
  await page.goto('/ticket/user-ticket')
  await page.getByRole('button', { name: '查看', exact: true }).first().click()
  const input = page.getByRole('textbox', { name: '回复客户', exact: true })
  await expect(input).toBeEditable()
  await input.fill('这是第一位客户的草稿')
  await page.getByRole('button', { name: '查看', exact: true }).nth(1).click()
  await expect(input).toHaveValue('')
  await expect(input).toBeEditable()
  await input.fill('这是第二位客户的草稿')
  await page.getByRole('button', { name: '查看', exact: true }).first().click()
  await expect(input).toHaveValue('这是第一位客户的草稿')
  await expect(page.getByRole('complementary', { name: '工单信息与操作' })).toBeVisible()
  await page.screenshot({
    path: testInfo.outputPath('desk-desktop.png'),
    fullPage: true,
    animations: 'disabled',
  })
})

test('失去回复响应后查询回执恢复，等待期间编辑的新草稿保留', async ({ page }, testInfo) => {
  await deskFixture(page)
  let posts = 0
  let clientMsgId = ''
  await page.route('**/api/ticket/TK-1/reply', async (route) => {
    posts += 1
    clientMsgId = route.request().postDataJSON().clientMsgId
    await route.fulfill({ json: { code: 40011, message: '暂时无法确认服务响应' } })
  })
  await page.route('**/api/ticket/TK-1/receipts/*', (route) =>
    route.fulfill({
      json: {
        code: 0,
        data: {
          clientMsgId,
          message: {
            id: 21,
            messageId: 'saved-21',
            sessionId: 'session-TK-1',
            ticketId: 'TK-1',
            senderType: 'AGENT',
            senderId: 'admin-shell-e2e',
            content: '保存结果需要查询',
            createdAtMs: Date.now(),
          },
        },
      },
    }),
  )
  await page.goto('/ticket/user-ticket')
  await page.getByRole('button', { name: '查看', exact: true }).first().click()
  const input = page.getByRole('textbox', { name: '回复客户', exact: true })
  await expect(input).toBeEditable()
  await input.fill('保存结果需要查询')
  await page.getByRole('button', { name: '发送', exact: true }).click()
  await expect(page.getByText('保存结果待确认', { exact: true })).toBeVisible()
  await expect(input).toHaveValue('保存结果需要查询')
  await expect
    .poll(async () => {
      const action = await page.getByRole('button', { name: '查询结果', exact: true }).boundingBox()
      const container = await page.locator('.messages').boundingBox()
      return !!action && !!container && action.y + action.height <= container.y + container.height
    })
    .toBe(true)
  await page.screenshot({
    path: testInfo.outputPath('desk-receipt-unknown.png'),
    fullPage: true,
    animations: 'disabled',
  })
  await input.fill('新的补充内容')
  await page.getByRole('button', { name: '查询结果', exact: true }).click()
  await expect(page.getByText('保存结果待确认', { exact: true })).not.toBeVisible()
  await expect(input).toHaveValue('新的补充内容')
  await expect(page.getByText('保存结果需要查询', { exact: true })).toHaveCount(1)
  expect(posts).toBe(1)
})

test('重连补齐多页消息和关闭快照，同一消息重复推送只显示一次', async ({ page }, testInfo) => {
  const sockets = await deskFixture(page)
  let gap = false
  let pages = 0
  const row = (id: number) => ({
    id,
    messageId: `saved-${id}`,
    ticketId: 'TK-1',
    sessionId: 'session-TK-1',
    senderType: 'USER',
    senderId: '客户-TK-1',
    content: `客户记录 ${id}`,
    createdAtMs: 1789070400000 + id * 1000,
  })
  await page.route('**/api/ticket/TK-1/messages?**', (route) => {
    pages += 1
    const before = Number(new URL(route.request().url()).searchParams.get('beforeId')) || 62
    const rows = gap
      ? Array.from({ length: 61 }, (_, index) => row(index + 1))
          .filter((message) => message.id < before)
          .slice(-30)
      : [row(1)]
    return route.fulfill({ json: { code: 0, data: rows } })
  })
  await page.route('**/api/ticket/TK-1', (route) =>
    route.fulfill({
      json: {
        code: 0,
        data: { ticket: { ...ticket('TK-1'), status: gap ? 'CLOSED' : 'PROCESSING' }, events: [] },
      },
    }),
  )
  await page.goto('/ticket/user-ticket')
  await page.getByRole('button', { name: '查看', exact: true }).first().click()
  const input = page.getByRole('textbox', { name: '回复客户', exact: true })
  await expect(input).toBeEditable()
  await input.fill('断线前保留的草稿')
  await expect(page.getByText('客户记录 1', { exact: true })).toBeVisible()
  gap = true
  await sockets[0]!.close({ code: 1011, reason: 'fixture interruption' })
  await expect.poll(() => sockets.length).toBe(2)
  await expect(page.locator('.message-row')).toHaveCount(61)
  expect(pages).toBeGreaterThanOrEqual(4)
  sockets[1]!.send(JSON.stringify({ type: 'chat', data: { ...row(61), ts: row(61).createdAtMs } }))
  await expect(page.getByText('客户记录 61', { exact: true })).toHaveCount(1)
  await expect(input).toHaveValue('断线前保留的草稿')
  await expect(input).not.toBeEditable()
  await expect(page.getByRole('button', { name: '发送', exact: true })).toBeDisabled()
  await page.setViewportSize({ width: 390, height: 844 })
  await expect
    .poll(async () => (await page.locator('.layout-aside').boundingBox())?.width ?? 0)
    .toBe(0)
  await page.screenshot({
    path: testInfo.outputPath('desk-closed-mobile.png'),
    fullPage: true,
    animations: 'disabled',
  })
})

test('仅查看权限不能发送，360px 可往返队列和工单信息', async ({ page }, testInfo) => {
  await deskFixture(page)
  await page.route('**/api/auth/permissions', (route) =>
    route.fulfill({ json: { code: 0, data: ['user-ticket:view'] } }),
  )
  await page.setViewportSize({ width: 360, height: 800 })
  await page.goto('/ticket/user-ticket')
  await page.getByRole('button', { name: '查看', exact: true }).first().click()
  const input = page.getByRole('textbox', { name: '回复客户', exact: true })
  await expect(input).not.toBeEditable()
  await expect(page.getByText('当前账号可查看此工单，没有回复权限。')).toBeVisible()
  await page.getByRole('button', { name: '工单信息', exact: true }).click()
  await expect(page.getByRole('dialog')).toBeVisible()
  await expect(page.getByRole('dialog').getByText('客户-TK-1', { exact: true })).toBeVisible()
  await page.screenshot({
    path: testInfo.outputPath('desk-context-mobile.png'),
    fullPage: true,
    animations: 'disabled',
  })
  await page.keyboard.press('Escape')
  await expect(page.getByRole('dialog')).not.toBeVisible()
  await page.getByRole('button', { name: '返回工单队列' }).click()
  await expect(page.getByRole('button', { name: '查看', exact: true }).nth(1)).toBeVisible()
  const overflow = await page.evaluate(
    () => document.documentElement.scrollWidth > window.innerWidth,
  )
  expect(overflow).toBe(false)
  await page.screenshot({
    path: testInfo.outputPath('desk-queue-mobile.png'),
    fullPage: true,
    animations: 'disabled',
  })
})

test('切单后迟到的工单详情不能改变正在回复的客户', async ({ page }) => {
  await deskFixture(page)
  let release!: () => void
  const paused = new Promise<void>((resolve) => {
    release = resolve
  })
  await page.route('**/api/ticket/TK-1', async (route) => {
    await paused
    await route.fulfill({ json: { code: 0, data: { ticket: ticket('TK-1'), events: [] } } })
  })
  await page.goto('/ticket/user-ticket')
  const request = page.waitForRequest('**/api/ticket/TK-1')
  await page.getByRole('button', { name: '查看', exact: true }).first().click()
  await request
  await page.getByRole('button', { name: '查看', exact: true }).nth(1).click()
  await expect(page.locator('.conversation-heading h2')).toHaveText('售后进度 TK-2')
  const input = page.getByRole('textbox', { name: '回复客户', exact: true })
  await input.fill('第二位客户的回复')
  const response = page.waitForResponse('**/api/ticket/TK-1')
  release()
  await (await response).finished()
  await page.evaluate(
    () =>
      new Promise<void>((resolve) =>
        requestAnimationFrame(() => requestAnimationFrame(() => resolve())),
      ),
  )
  await expect(page.locator('.conversation-heading h2')).toHaveText('售后进度 TK-2')
  await expect(input).toHaveValue('第二位客户的回复')
})

test('挂起工单只提供当前状态允许的转回队列操作，失败留在原工单核对', async ({ page }) => {
  await deskFixture(page)
  await page.setViewportSize({ width: 1680, height: 1050 })
  await page.route('**/api/ticket/TK-1', (route) =>
    route.fulfill({
      json: {
        code: 0,
        data: { ticket: { ...ticket('TK-1'), status: 'ON_HOLD' }, events: [] },
      },
    }),
  )
  let transfers = 0
  await page.route('**/api/ticket/TK-1/transfer', async (route) => {
    transfers += 1
    expect(route.request().postDataJSON()).toEqual({ toAgent: '' })
    await route.fulfill({ json: { code: 40010, message: '工单状态已变化，请刷新核对。' } })
  })
  await page.goto('/ticket/user-ticket')
  await page.getByRole('button', { name: '查看', exact: true }).first().click()
  const context = page.getByRole('complementary', { name: '工单信息与操作' })
  await expect(context.getByRole('button', { name: '恢复处理', exact: true })).toBeVisible()
  await expect(context.getByRole('button', { name: '转派', exact: true })).toHaveCount(0)
  await context.getByRole('button', { name: '转回接单池', exact: true }).click()
  const dialog = page.getByRole('dialog')
  await dialog.getByRole('button', { name: '取消', exact: true }).click()
  expect(transfers).toBe(0)
  await context.getByRole('button', { name: '转回接单池', exact: true }).click()
  await dialog.getByRole('button', { name: '确认', exact: true }).click()
  await expect(context.getByRole('alert')).toHaveText('工单状态已变化，请刷新核对。')
  expect(transfers).toBe(1)
  await expect(page.locator('.el-message--error')).toHaveCount(0)
  await expect(page.locator('.conversation-heading h2')).toHaveText('售后进度 TK-1')
})

test('阅读较早消息时新消息不抢滚动位置，切回工单恢复阅读位置', async ({ page }) => {
  const sockets = await deskFixture(page)
  await page.setViewportSize({ width: 1680, height: 1050 })
  const message = (id: number) => ({
    id,
    messageId: `reading-${id}`,
    ticketId: 'TK-1',
    sessionId: 'session-TK-1',
    senderType: 'USER',
    senderId: '客户-TK-1',
    content: `需要核对的客户消息 ${id}`,
    createdAtMs: 1789070400000 + id * 1000,
  })
  let count = 25
  await page.route('**/api/ticket/TK-1/messages?**', (route) =>
    route.fulfill({
      json: { code: 0, data: Array.from({ length: count }, (_, index) => message(index + 1)) },
    }),
  )
  await page.goto('/ticket/user-ticket')
  await page.getByRole('button', { name: '查看', exact: true }).first().click()
  await expect(page.locator('.message-row')).toHaveCount(25)
  const history = page.locator('.messages')
  await expect(history).toHaveAttribute('aria-busy', 'false')
  await history.evaluate((element) => {
    // 模拟消息先于本帧 scroll 回调到达；判断阅读位置必须以 DOM 实际位置为准。
    element.addEventListener('scroll', (event) => event.stopImmediatePropagation(), {
      capture: true,
      once: true,
    })
    element.scrollTop = 300
  })
  await expect.poll(() => history.evaluate((element) => element.scrollTop)).toBe(300)
  count = 26
  sockets[0]!.send(
    JSON.stringify({ type: 'chat', data: { ...message(26), ts: message(26).createdAtMs } }),
  )
  await expect(page.locator('.message-row')).toHaveCount(26)
  await expect.poll(() => history.evaluate((element) => element.scrollTop)).toBe(300)
  await page.getByRole('button', { name: '查看', exact: true }).nth(1).click()
  await expect(page.locator('.conversation-heading h2')).toHaveText('售后进度 TK-2')
  await page.getByRole('button', { name: '查看', exact: true }).first().click()
  await expect(page.locator('.message-row')).toHaveCount(26)
  await expect(history).toHaveAttribute('aria-busy', 'false')
  await expect.poll(() => history.evaluate((element) => element.scrollTop)).toBe(300)
})
