import { expect, test } from './fixtures/adminTestFixture'

test('发送按钮的禁用外观与可发送状态有明确区别', async ({ page }) => {
  await page.emulateMedia({ reducedMotion: 'reduce' })
  await page.goto('/workspace/java-assistant')
  const send = page.getByRole('button', { name: '发送', exact: true })
  await expect(send).toBeDisabled()
  const disabledBackground = await send.evaluate(
    (button) => getComputedStyle(button).backgroundColor,
  )
  await page.getByRole('textbox', { name: '消息内容' }).fill('准备发送的任务')
  await expect(send).toBeEnabled()
  await expect
    .poll(() => send.evaluate((button) => getComputedStyle(button).backgroundColor))
    .not.toBe(disabledBackground)
})

for (const scenario of [
  { name: 'Shift+Enter 换行', init: '第一行', key: 'Shift+Enter' },
  { name: '中文输入法确认', init: '正在输入中文', key: 'composition' },
]) {
  test(`${scenario.name}不触发消息发送`, async ({ page }) => {
    let sent = 0
    await page.route('**/api/workspace/java-assistant/chat/stream', async (route) => {
      sent += 1
      await route.fulfill({
        contentType: 'text/event-stream',
        body: 'event: message\ndata: 测试回答\n\n',
      })
    })
    await page.goto('/workspace/java-assistant')
    const composer = page.getByPlaceholder('输入消息，回车发送；⌘/Ctrl+V 可粘贴截图或文件作为附件')
    await composer.fill(scenario.init)
    if (scenario.key === 'composition') {
      await composer.dispatchEvent('compositionstart')
      await composer.dispatchEvent('keydown', { key: 'Enter', code: 'Enter', isComposing: true })
      await composer.dispatchEvent('keyup', { key: 'Enter', code: 'Enter', isComposing: true })
      await composer.dispatchEvent('compositionend')
    } else {
      await composer.press(scenario.key)
    }
    await expect(composer).toHaveValue(scenario.init + (scenario.key === 'composition' ? '' : '\n'))
    expect(sent).toBe(0)
  })
}

test('历史移入导航后，切换模式与离开返回仍保留草稿和唯一可见列表', async ({ page }) => {
  await page.goto('/workspace/java-assistant')
  const history = page.locator('#workspace-history-slot .history-sidebar')
  await expect(history).toHaveCount(1)
  await expect(history).toBeVisible()
  const composer = page.getByRole('textbox', { name: '消息内容' })
  await composer.fill('对话中的下一步草稿')
  await page.getByRole('tab', { name: '代码工作区', exact: true }).click()
  await expect(history).toHaveCount(1)
  await expect(history).toBeVisible()
  await composer.fill('代码工作区草稿')
  await page.getByRole('tab', { name: '对话', exact: true }).click()
  await expect(composer).toHaveValue('对话中的下一步草稿')
  const navigation = page.getByRole('navigation', { name: '智能体生命周期导航' })
  await navigation.getByRole('button', { name: '设置', exact: true }).click()
  await page.getByRole('menuitem', { name: '系统管理', exact: true }).click()
  await page.getByRole('menuitem', { name: '成员与身份（服务端菜单）', exact: true }).click()
  await expect(page.locator('#cw-page-title')).toHaveText('成员与身份（服务端菜单）')
  await expect(page.locator('#workspace-history-slot')).toBeHidden()
  await navigation.getByRole('button', { name: '智能体', exact: true }).click()
  await page.getByRole('menuitem', { name: 'Java 智能体', exact: true }).click()
  await expect(history).toHaveCount(1)
  await expect(history).toBeVisible()
  await expect(composer).toHaveValue('对话中的下一步草稿')
})

test('生成期间可以编写草稿，终止与继续保留草稿并展示执行记录', async ({ page }) => {
  let finishFirst: () => void = () => {}
  const firstResponse = new Promise<void>((resolve) => {
    finishFirst = resolve
  })
  const requests: { message: string }[] = []
  await page.route('**/api/workspace/java-assistant/chat/stream', async (route) => {
    requests.push(route.request().postDataJSON())
    if (requests.length === 1) await firstResponse
    await route.fulfill({
      contentType: 'text/event-stream',
      body: 'event: node:thinking\ndata: 读取任务上下文\n\nevent: node:tool_builtin\ndata: 查询任务状态\n\nevent: node:tool_result\ndata: 已完成状态查询\n\nevent: message\ndata: 已获得任务结果。\n\n',
    })
  })
  await page.route('**/api/workspace/java-assistant/chat/sessions/*/interrupt', async (route) => {
    await route.fulfill({ json: { code: 0, data: true } })
    finishFirst()
  })
  await page.goto('/workspace/java-assistant')
  const composer = page.getByRole('textbox', { name: '消息内容' })
  await composer.fill('执行第一个任务')
  await composer.press('Enter')
  await expect(page.getByRole('button', { name: '终止', exact: true })).toBeVisible()
  await composer.fill('  下一条任务的草稿\n保留格式  ')
  await page.getByRole('button', { name: '终止', exact: true }).click()
  await expect(page.getByRole('button', { name: '继续', exact: true })).toBeVisible()
  await expect(composer).toHaveValue('  下一条任务的草稿\n保留格式  ')
  await page.getByRole('button', { name: '继续', exact: true }).click()
  await expect.poll(() => requests.length).toBe(2)
  expect(requests[1].message).toBe('请继续刚才的任务。')
  await expect(composer).toHaveValue('  下一条任务的草稿\n保留格式  ')
  await page.getByRole('button', { name: '执行详情', exact: true }).click()
  await expect(page.getByText('读取任务上下文').first()).toBeVisible()
  await expect(page.getByText('查询任务状态').first()).toBeVisible()
  await expect(page.getByText('已获得任务结果。').first()).toBeVisible()
})

test('失败附件不会被静默忽略，移除后可正常发送已有草稿', async ({ page }) => {
  let sent = 0
  await page.route('**/api/workspace/java-assistant/chat/attachment?*', (route) =>
    route.fulfill({
      json: {
        code: 0,
        data: {
          id: 'failed-attachment',
          parseStatus: 'FAILED',
          errorMessage: '文件无法解析',
          content: '',
          mimeType: 'text/plain',
        },
      },
    }),
  )
  await page.route('**/api/workspace/java-assistant/chat/stream', async (route) => {
    sent += 1
    expect(route.request().postDataJSON().message).toBe('请参考附件')
    await route.fulfill({
      contentType: 'text/event-stream',
      body: 'event: message\ndata: 已收到\n\n',
    })
  })
  await page.goto('/workspace/java-assistant')
  await page.getByRole('textbox', { name: '消息内容' }).fill('请参考附件')
  await page.locator('.chat-panel input[type="file"]').setInputFiles({
    name: 'notes.txt',
    mimeType: 'text/plain',
    buffer: Buffer.from('test attachment'),
  })
  await expect(page.getByText('请等待附件上传完成，或移除失败附件')).toBeVisible()
  await expect(page.getByRole('button', { name: '发送', exact: true })).toBeDisabled()
  await page.getByRole('button', { name: '移除附件 notes.txt', exact: true }).click()
  await expect(page.getByRole('textbox', { name: '消息内容' })).toHaveValue('请参考附件')
  await page.getByRole('button', { name: '发送', exact: true }).click()
  await expect.poll(() => sent).toBe(1)
})
