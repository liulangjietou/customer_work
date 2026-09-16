import { test, expect } from './fixtures/adminTestFixture'
import { deskFixture } from './fixtures/serviceDeskFixture'
import type { Page } from '@playwright/test'
import type { TicketAssistView } from '../../src/types/ticket'

const reply = '我会先核对退款记录，再向您说明当前进度。'
const timestamp = 1789070400000
const message = (ticketId = 'TK-1', id = 31) => ({
  id,
  messageId: `message-${id}`,
  ticketId,
  sessionId: `session-${ticketId}`,
  senderType: 'USER',
  senderId: `客户-${ticketId}`,
  content: '退款已经申请三天了，想了解处理进度。',
  createdAtMs: timestamp + id,
})
const assist = (ticketId = 'TK-1'): TicketAssistView => ({
  ticketId,
  summary: {
    oneLineSummary: `${ticketId} 客户想了解退款进度`,
    userIntent: '咨询退款进度',
    emotion: null,
    triedSolutions: [],
    pendingIssues: ['退款是否受理尚待核对'],
    suggestedNextStep: '核对订单与退款记录，确认后再答复客户。',
    suggestedReply: reply,
    fromModel: false,
    evidence: {
      version: `summary-v1:${ticketId}`,
      generatedAtMs: timestamp,
      historyLimit: 30,
      truncated: false,
      sources: [
        {
          id: 31,
          messageId: 'message-31',
          senderType: 'USER',
          excerpt: message(ticketId).content,
          createdAtMs: timestamp + 31,
          truncated: false,
        },
      ],
    },
  },
})

async function fixture(page: Page) {
  const sockets = await deskFixture(page)
  const writes: string[] = []
  page.on('request', (request) => {
    if (request.url().includes('/api/ticket/') && request.method() !== 'GET')
      writes.push(request.url())
  })
  await page.route('**/api/ticket/*/messages?**', (route) => {
    const id = new URL(route.request().url()).pathname.split('/').at(-2)!
    return route.fulfill({ json: { code: 0, data: [message(id)] } })
  })
  await page.route('**/api/ticket/TK-*/assist', (route) => {
    const id = new URL(route.request().url()).pathname.split('/').at(-2)!
    return route.fulfill({ json: { code: 0, data: assist(id) } })
  })
  return { sockets, writes }
}
async function open(page: Page) {
  await page.setViewportSize({ width: 1680, height: 1050 })
  await page.goto('/ticket/user-ticket')
  await page.getByRole('button', { name: '查看', exact: true }).first().click()
}
const editor = (page: Page) => page.getByRole('textbox', { name: '回复客户', exact: true })
const adopt = (page: Page) => page.getByRole('button', { name: '采用为草稿', exact: true })

test('原始依据可核对，采用只填入本工单编辑框且不自动发送', async ({ page }, testInfo) => {
  const { writes } = await fixture(page)
  await open(page)
  await expect(page.getByText('规则整理', { exact: true })).toBeVisible()
  await expect(adopt(page)).toBeEnabled()
  await page.getByRole('button', { name: '查看原始会话依据' }).click()
  const sources = page.getByRole('dialog', { name: '原始会话依据' })
  await expect(sources.getByText(message().content, { exact: true })).toBeVisible()
  await expect(sources).toContainText('不代表业务结果已核实')
  await sources.getByRole('button', { name: '关闭此对话框' }).click()
  await adopt(page).click()
  await expect(editor(page)).toHaveValue(reply)
  await expect(editor(page)).toBeFocused()
  expect(writes).toEqual([])
  await page.getByRole('heading', { name: '交接摘要与建议' }).scrollIntoViewIfNeeded()
  await page.screenshot({
    path: testInfo.outputPath('desk-assist-desktop.png'),
    fullPage: true,
    animations: 'disabled',
  })
})

test('已有草稿需确认后追加，取消和重复采用都保留原内容', async ({ page }) => {
  const { writes } = await fixture(page)
  await open(page)
  await expect(adopt(page)).toBeEnabled()
  await editor(page).fill('已核对客户联系方式。')
  await adopt(page).click()
  await page.getByRole('button', { name: '取消', exact: true }).click()
  await expect(editor(page)).toHaveValue('已核对客户联系方式。')
  await adopt(page).click()
  await page.getByRole('button', { name: '保留并追加', exact: true }).click()
  const combined = `已核对客户联系方式。\n\n${reply}`
  await expect(editor(page)).toHaveValue(combined)
  await adopt(page).click()
  await expect(page.getByText('这条建议已在当前草稿中。')).toBeVisible()
  await expect(editor(page)).toHaveValue(combined)
  expect(writes).toEqual([])
})

test('多字节草稿超限时保持原文，长依据正文滚动时仍可关闭', async ({ page }) => {
  const { writes } = await fixture(page)
  await open(page)
  await expect(adopt(page)).toBeEnabled()
  const original = '原'.repeat(21_830)
  await editor(page).fill(original)
  await adopt(page).click()
  await expect(page.getByText('采用后内容过长，请先缩短草稿。现有内容已保留。')).toBeVisible()
  await expect(editor(page)).toHaveValue(original)
  await expect(page.getByRole('button', { name: '保留并追加', exact: true })).toHaveCount(0)
  const long = assist()
  long.summary.evidence.sources = Array.from({ length: 15 }, (_, index) => ({
    id: index + 1,
    messageId: `long-${index}`,
    senderType: 'USER',
    excerpt: `${index + 1}：${'较长的原始会话记录。'.repeat(60)}`,
    createdAtMs: timestamp + index,
    truncated: false,
  }))
  await page.route('**/api/ticket/TK-1/assist', (route) =>
    route.fulfill({ json: { code: 0, data: long } }),
  )
  await page.getByRole('button', { name: '刷新辅助', exact: true }).click()
  await expect(adopt(page)).toBeEnabled()
  await page.getByRole('button', { name: '查看原始会话依据' }).click()
  const dialog = page.getByRole('dialog', { name: '原始会话依据' })
  await dialog.locator('.source-list li').last().scrollIntoViewIfNeeded()
  await expect(dialog.getByRole('button', { name: '关闭此对话框' })).toBeInViewport()
  expect(await dialog.evaluate((element) => element.scrollWidth <= element.clientWidth)).toBe(true)
  await dialog.getByRole('button', { name: '关闭此对话框' }).click()
  await expect(editor(page)).toHaveValue(original)
  expect(writes).toEqual([])
})

test('确认追加期间收到新消息会终止采用，旧草稿保持不动', async ({ page }) => {
  const { sockets, writes } = await fixture(page)
  await open(page)
  await expect(adopt(page)).toBeEnabled()
  await editor(page).fill('原草稿必须保留')
  await adopt(page).click()
  await expect(page.getByRole('button', { name: '保留并追加', exact: true })).toBeVisible()
  sockets[0]!.send(
    JSON.stringify({
      type: 'chat',
      data: { ...message('TK-1', 32), content: '另外发票也没有收到。', ts: timestamp + 32 },
    }),
  )
  await expect(page.getByText('会话已有更新，请刷新辅助后再采用建议。')).toBeVisible()
  await page.getByRole('button', { name: '保留并追加', exact: true }).click()
  await expect(editor(page)).toHaveValue('原草稿必须保留')
  await expect(adopt(page)).toBeDisabled()
  await page.getByRole('button', { name: '刷新辅助', exact: true }).click()
  await expect(adopt(page)).toBeEnabled()
  expect(writes).toEqual([])
})

test('切换工单后，迟到的前一工单摘要不能覆盖当前内容', async ({ page }) => {
  await fixture(page)
  let release!: () => void
  const wait = new Promise<void>((resolve) => {
    release = resolve
  })
  await page.route('**/api/ticket/TK-1/assist', async (route) => {
    await wait
    await route.fulfill({ json: { code: 0, data: assist('TK-1') } })
  })
  const requested = page.waitForRequest('**/api/ticket/TK-1/assist')
  await open(page)
  await requested
  await page.getByRole('button', { name: '查看', exact: true }).nth(1).click()
  await expect(page.getByText('TK-2 客户想了解退款进度', { exact: true })).toBeVisible()
  const response = page.waitForResponse('**/api/ticket/TK-1/assist')
  release()
  await response
  await expect(page.getByText('TK-1 客户想了解退款进度', { exact: true })).toHaveCount(0)
  await adopt(page).click()
  await expect(editor(page)).toHaveValue(reply)
  await page.getByRole('button', { name: '查看', exact: true }).first().click()
  await expect(editor(page)).toHaveValue('')
})

test('只读坐席可查看依据，缺少回复权限时不能采用', async ({ page }) => {
  const { writes } = await fixture(page)
  await page.route('**/api/auth/permissions', (route) =>
    route.fulfill({ json: { code: 0, data: ['user-ticket:view'] } }),
  )
  await open(page)
  await expect(page.getByText('TK-1 客户想了解退款进度', { exact: true })).toBeVisible()
  await expect(adopt(page)).toBeDisabled()
  await expect(editor(page)).not.toBeEditable()
  await page.getByRole('button', { name: '查看原始会话依据' }).click()
  await expect(page.getByRole('dialog', { name: '原始会话依据' })).toContainText(message().content)
  expect(writes).toEqual([])
})

test('读取失败保留上次内容且停止采用，重试确认后恢复', async ({ page }) => {
  await fixture(page)
  await open(page)
  await expect(adopt(page)).toBeEnabled()
  await page.route('**/api/ticket/TK-1/assist', (route) =>
    route.fulfill({ json: { code: 40011, message: '会话记录暂时不可用' } }),
  )
  await page.getByRole('button', { name: '刷新辅助', exact: true }).click()
  await expect(page.getByRole('region', { name: '坐席辅助' })).toContainText(
    '数据更新失败，已保留上次结果',
  )
  await expect(page.getByText('TK-1 客户想了解退款进度', { exact: true })).toBeVisible()
  await expect(adopt(page)).toBeDisabled()
  await page.route('**/api/ticket/TK-1/assist', (route) =>
    route.fulfill({ json: { code: 0, data: assist() } }),
  )
  await page.getByRole('button', { name: '刷新辅助', exact: true }).click()
  await expect(adopt(page)).toBeEnabled()
})

test('错误工单的响应不能提供可采用建议，真实空记录独立显示', async ({ page }) => {
  await fixture(page)
  await page.route('**/api/ticket/TK-1/assist', (route) =>
    route.fulfill({ json: { code: 0, data: assist('TK-2') } }),
  )
  await open(page)
  await expect(page.getByRole('region', { name: '坐席辅助' })).toContainText('数据加载失败')
  await expect(adopt(page)).toHaveCount(0)
  const empty = assist()
  empty.summary.evidence.sources = []
  await page.route('**/api/ticket/TK-1/assist', (route) =>
    route.fulfill({ json: { code: 0, data: empty } }),
  )
  await page.getByRole('button', { name: '刷新辅助', exact: true }).click()
  await expect(page.getByText('当前工单尚无可整理的会话记录。')).toBeVisible()
  await expect(adopt(page)).toHaveCount(0)
})

test('390px 的辅助和原文可完整滚动，采用后回到输入框', async ({ page }, testInfo) => {
  const { writes } = await fixture(page)
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/ticket/user-ticket')
  await page.getByRole('button', { name: '查看', exact: true }).first().click()
  await page.getByRole('button', { name: '工单信息', exact: true }).click()
  await expect(adopt(page)).toBeEnabled()
  const button = await adopt(page).boundingBox()
  expect(button!.height).toBeGreaterThanOrEqual(44)
  await page.screenshot({
    path: testInfo.outputPath('desk-assist-mobile.png'),
    fullPage: true,
    animations: 'disabled',
  })
  await page.getByRole('button', { name: '查看原始会话依据' }).click()
  const sources = page.getByRole('dialog', { name: '原始会话依据' })
  await expect(sources).toContainText(message().content)
  const bounds = await sources.boundingBox()
  expect(bounds!.x).toBeGreaterThanOrEqual(0)
  expect(bounds!.x + bounds!.width).toBeLessThanOrEqual(390)
  await page.screenshot({
    path: testInfo.outputPath('desk-assist-sources-mobile.png'),
    fullPage: true,
    animations: 'disabled',
  })
  await sources.getByRole('button', { name: '关闭此对话框' }).click()
  await adopt(page).click()
  await expect(editor(page)).toHaveValue(reply)
  await expect(editor(page)).toBeFocused()
  await expect(page.getByRole('dialog', { name: '工单信息', exact: true })).not.toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390)
  expect(writes).toEqual([])
})
