import { expect, test } from './fixtures/adminTestFixture'

for (const mode of ['对话', '代码工作区'] as const) {
  for (const phase of ['UNKNOWN', 'FINAL', 'STOPPED', 'FAILED'] as const) {
    test(`${mode} 正确呈现 ${phase}，停止请求不冒充停止结果`, async ({ page }, testInfo) => {
      await page.emulateMedia({ reducedMotion: 'reduce' })
      await page.setViewportSize(mode === '代码工作区' ? { width: 390, height: 844 } : { width: 1440, height: 900 })
      let release: () => void = () => {}
      const ready = new Promise<void>((resolve) => { release = resolve })
      const stream = mode === '对话' ? 'chat' : 'vibecoding'
      await page.route('**/api/workspace/java-assistant/vibecoding/files?*', (route) =>
        route.fulfill({ json: { code: 0, data: [] } }),
      )
      await page.route(`**/api/workspace/java-assistant/${stream}/stream`, async (route) => {
        await ready
        const body = phase === 'UNKNOWN'
          ? 'event: done\ndata: [DONE]\n\n'
          : 'event: message\ndata: 已收到的部分内容。\n\n' +
            `event: terminal\ndata: ${JSON.stringify({ phase, turnId: 'turn-1', finishReason: phase === 'STOPPED' ? 'INTERRUPTED' : 'MODEL_STOP' })}\n\n`
        await route.fulfill({ contentType: 'text/event-stream', body })
      })
      await page.route(`**/api/workspace/java-assistant/${stream}/sessions/*/interrupt`, async (route) => {
        await route.fulfill({ json: { code: 0, data: true } })
        release()
      })
      await page.goto('/workspace/java-assistant')
      if (mode === '代码工作区') await page.getByRole('tab', { name: mode, exact: true }).click()
      const composer = page.getByRole('textbox', { name: '消息内容' })
      await composer.fill('执行状态测试')
      await composer.press('Enter')
      await expect(page.getByRole('button', { name: '终止', exact: true })).toBeVisible()
      await composer.fill('  下一条草稿\n保持格式  ')
      if (phase === 'FINAL' || phase === 'UNKNOWN') {
        await page.getByRole('button', { name: '终止', exact: true }).click()
      } else {
        release()
      }
      const title = { UNKNOWN: '完成状态未知', FINAL: '最终结果', STOPPED: '本轮已停止', FAILED: '本轮未完成' }[phase]
      await expect(page.getByText(title, { exact: true })).toBeVisible()
      await expect(composer).toHaveValue('  下一条草稿\n保持格式  ')
      if (phase === 'STOPPED') await expect(page.getByRole('button', { name: '继续', exact: true })).toBeVisible()
      else await expect(page.getByRole('button', { name: '继续', exact: true })).toHaveCount(0)
      if (phase === 'UNKNOWN') await expect(page.getByRole('alert').filter({ hasText: '完成状态尚未确认' })).toBeVisible()
      else await expect(page.getByText('已收到的部分内容。', { exact: true })).toBeVisible()
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
      await page.screenshot({
        path: testInfo.outputPath(`terminal-${stream}-${phase.toLowerCase()}.png`),
        fullPage: true,
        animations: 'disabled',
      })
    })
  }
}
