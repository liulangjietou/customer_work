import { expect, test } from './fixtures/adminTestFixture'

for (const mode of ['对话', '代码工作区'] as const) {
  test(`${mode} 历史按轮显示结果，过程和未知状态不冒充最终结论`, async ({ page }) => {
    await page.emulateMedia({ reducedMotion: 'reduce' })
    if (mode === '代码工作区') await page.setViewportSize({ width: 390, height: 844 })
    const common = { timestamp: '2026-09-11T09:00:00', attachments: [], turnId: 'input-1' }
    await page.route('**/api/workspace/java-assistant/chat/sessions?*', (route) =>
      route.fulfill({
        json: {
          code: 0,
          data: {
            pageNum: 1,
            pageSize: 20,
            total: 1,
            list: [
              {
                sessionId: 'history-review',
                preview: '核对订单进度',
                messageCount: 6,
                lastMessageTime: common.timestamp,
              },
            ],
          },
        },
      }),
    )
    await page.route(
      '**/api/workspace/java-assistant/chat/sessions/history-review/messages',
      (route) =>
        route.fulfill({
          json: {
            code: 0,
            data: [
              { ...common, id: 'input-1', role: 'user', text: '核对订单进度', phase: 'USER_INPUT' },
              {
                ...common,
                id: 'process-1',
                role: 'assistant',
                text: '先读取订单记录，再核对物流进度。',
                phase: 'PROCESS',
              },
              {
                ...common,
                id: 'final-1',
                role: 'assistant',
                text: '订单已发货，可以查看物流详情。',
                phase: 'FINAL',
                finishReason: 'MODEL_STOP',
              },
              {
                ...common,
                id: 'legacy-1',
                role: 'assistant',
                text: '这是缺少结束记录的历史内容。',
                phase: 'UNKNOWN',
              },
              {
                ...common,
                id: 'input-2',
                turnId: 'input-2',
                role: 'user',
                text: '继续整理售后材料',
                phase: 'USER_INPUT',
              },
              {
                ...common,
                id: 'stopped-1',
                turnId: 'input-2',
                role: 'assistant',
                text: '已整理部分材料。',
                phase: 'STOPPED',
                finishReason: 'INTERRUPTED',
              },
            ],
          },
        }),
    )
    await page.route('**/api/workspace/java-assistant/vibecoding/files?*', (route) =>
      route.fulfill({ json: { code: 0, data: [] } }),
    )
    await page.goto('/workspace/java-assistant')
    if (mode === '代码工作区') await page.getByRole('tab', { name: mode, exact: true }).click()
    const panel = page.locator(
      mode === '对话' ? '.chat-panel:visible' : '.vibecoding-panel:visible',
    )
    await expect(panel.locator('.message-row.assistant')).toHaveCount(3)
    await expect(panel.getByText('最终结果', { exact: true })).toHaveCount(1)
    await expect(panel.getByText('历史回复', { exact: true })).toBeVisible()
    await expect(panel.getByText('本轮已停止', { exact: true })).toBeVisible()
    if (mode === '对话') {
      await panel.getByRole('button', { name: /查看执行记录/ }).click()
    }
    await expect(page.getByText('先读取订单记录，再核对物流进度。').first()).toBeVisible()
    await expect(page.getByText('阶段输出', { exact: true }).first()).toBeVisible()
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  })
}
