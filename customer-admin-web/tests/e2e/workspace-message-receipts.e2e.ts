import type { Page, Route } from '@playwright/test'
import { expect, test, type AdminHarness } from './fixtures/adminTestFixture'

const AGENT = 'java-assistant'
const ok = (data: unknown) => ({ code: 0, data })

async function openMode(page: Page, mode: 'chat' | 'vibecoding') {
  await page.route(`**/api/workspace/${AGENT}/vibecoding/files?*`, route => route.fulfill({ json: ok([]) }))
  await page.goto(`/workspace/${AGENT}`)
  if (mode === 'vibecoding') await page.getByRole('tab', { name: '代码工作区', exact: true }).click()
}

function completed(clientMessageId: string) {
  return `event: accepted\ndata: ${JSON.stringify({ clientMessageId, acceptedAtMs: 1 })}\n\n`
    + 'event: message\ndata: 原任务已完成\n\n'
    + `event: terminal\ndata: ${JSON.stringify({ phase: 'FINAL', turnId: 'turn', messageId: 'reply', historySaved: true })}\n\n`
    + 'event: done\ndata: [DONE]\n\n'
}

for (const mode of ['chat', 'vibecoding'] as const) {
  test(`${mode} 停止终态缺少受理回执时先核对，继续按钮不可触发新任务`, async ({ page }) => {
    let calls = 0
    await page.route(`**/api/workspace/${AGENT}/${mode}/stream`, route => {
      calls++
      return route.fulfill({ contentType: 'text/event-stream', body:
        'event: terminal\ndata: {"phase":"STOPPED","finishReason":"INTERRUPTED"}\n\n' })
    })
    await openMode(page, mode)
    const composer = page.getByRole('textbox', { name: '消息内容' })
    await composer.fill('等待核对的原任务')
    await page.getByRole('button', { name: '发送', exact: true }).click()
    await expect(page.getByRole('button', { name: '核对并重试原消息' })).toBeVisible()
    await expect(page.getByRole('button', { name: '继续', exact: true })).toBeDisabled()
    await expect(composer).toHaveValue('等待核对的原任务')
    expect(calls).toBe(1)
  })

  test(`${mode} 收到持久回执前保留输入，丢失回执后查已保存正文且不重复执行`, async ({ page, adminHarness }) => {
    let held: Route | undefined
    const requests: Record<string, unknown>[] = []
    await page.route(`**/api/workspace/${AGENT}/${mode}/stream`, route => {
      requests.push(route.request().postDataJSON())
      held = route
    })
    await openMode(page, mode)
    const composer = page.getByRole('textbox', { name: '消息内容' })
    await composer.fill('只执行一次的任务')
    await page.getByRole('button', { name: '发送', exact: true }).click()
    await expect.poll(() => Boolean(held)).toBe(true)
    await expect(composer).toHaveValue('只执行一次的任务')
    await expect(page.getByText('受理尚未确认，原文和附件已保留。请先核对，避免重复执行。')).toBeVisible()
    expect(requests[0].clientMessageId).toMatch(/^[0-9a-f-]{36}$/)
    await held!.abort('failed')
    await page.route(`**/api/workspace/${AGENT}/${mode}/sessions/*/receipts/*`, route => route.fulfill({ json: ok({
      clientMessageId: requests[0].clientMessageId, acceptedAtMs: 1,
      terminal: { phase: 'FINAL', turnId: 'turn', messageId: 'reply', historySaved: true },
    }) }))
    await page.route(`**/api/workspace/${AGENT}/chat/sessions/*/messages`, route => route.fulfill({ json: ok([
      { id: 'turn', role: 'user', text: '只执行一次的任务', phase: 'USER_INPUT', attachments: [] },
      { id: 'reply', role: 'assistant', text: '从已保存历史恢复的最终答复', phase: 'FINAL', turnId: 'turn', attachments: [] },
    ]) }))
    await page.getByRole('button', { name: '核对并重试原消息' }).click()
    await expect(page.getByText('从已保存历史恢复的最终答复', { exact: true })).toBeVisible()
    await expect(composer).toHaveValue('')
    expect(requests).toHaveLength(1)
    await consumeExpectedNetworkFailure(adminHarness)
  })

  test(`${mode} 明确未受理时沿用原标识重试，保留下一条草稿和唯一用户气泡`, async ({ page, adminHarness }) => {
    const requests: Record<string, unknown>[] = []
    await page.route(`**/api/workspace/${AGENT}/${mode}/stream`, route => {
      const body = route.request().postDataJSON()
      requests.push(body)
      if (requests.length === 1) return route.abort('failed')
      return route.fulfill({ contentType: 'text/event-stream', body: completed(body.clientMessageId) })
    })
    await page.route(`**/api/workspace/${AGENT}/${mode}/sessions/*/receipts/*`, route => route.fulfill({
      json: { code: 30003, message: '消息受理记录不存在', data: null },
    }))
    await openMode(page, mode)
    const composer = page.getByRole('textbox', { name: '消息内容' })
    await composer.fill('原任务')
    await page.getByRole('button', { name: '发送', exact: true }).click()
    const reconcile = page.getByRole('button', { name: '核对并重试原消息' })
    await expect(reconcile).toBeVisible()
    await composer.fill('下一条任务草稿')
    await reconcile.click()
    await expect(page.getByText('原任务已完成', { exact: true })).toBeVisible()
    await expect(composer).toHaveValue('下一条任务草稿')
    expect(requests).toHaveLength(2)
    expect(requests[1]).toEqual(requests[0])
    await expect(page.getByRole('tabpanel', { name: mode === 'chat' ? '对话' : '代码工作区', exact: true }).locator('.message-row.user')).toHaveCount(1)
    await consumeExpectedNetworkFailure(adminHarness)
  })

  test(`${mode} 核对服务故障时保留原附件与草稿，不产生新的执行请求`, async ({ page, adminHarness }) => {
    let calls = 0
    await page.route(`**/api/workspace/${AGENT}/chat/attachment**`, route => route.fulfill({ json: ok({
      id: 'receipt-file', fileName: '报告.txt', mimeType: 'text/plain', fileSize: 6,
      content: '附件内容', parseStatus: 'SUCCESS', errorMessage: null,
    }) }))
    await page.route(`**/api/workspace/${AGENT}/${mode}/stream`, route => { calls++; return route.abort('failed') })
    await page.route(`**/api/workspace/${AGENT}/${mode}/sessions/*/receipts/*`, route => route.fulfill({
      json: { code: 50000, message: '核对服务暂不可用', data: null },
    }))
    await openMode(page, mode)
    await page.getByRole('tabpanel', { name: mode === 'chat' ? '对话' : '代码工作区', exact: true }).locator('input[type=file]').setInputFiles({ name: '报告.txt', mimeType: 'text/plain', buffer: Buffer.from('附件内容') })
    const composer = page.getByRole('textbox', { name: '消息内容' })
    await composer.fill('请处理这份附件')
    await expect(page.getByRole('button', { name: '发送', exact: true })).toBeEnabled()
    await page.getByRole('button', { name: '发送', exact: true }).click()
    await page.getByRole('button', { name: '核对并重试原消息' }).click()
    await expect(page.getByText('暂时无法核对受理状态。原文和附件仍保留，请稍后再试。')).toBeVisible()
    await expect(composer).toHaveValue('请处理这份附件')
    await expect(page.getByRole('button', { name: '移除附件 报告.txt', exact: true })).toBeVisible()
    expect(calls).toBe(1)
    await consumeExpectedNetworkFailure(adminHarness)
  })
}

/** 每例主动断开一次已断言的流请求，只核销对应的一条浏览器网络日志。 */
async function consumeExpectedNetworkFailure(harness: AdminHarness) {
  await expect.poll(() => harness.consoleErrors).toEqual(['Failed to load resource: net::ERR_FAILED'])
  harness.consoleErrors.splice(0, 1)
}
