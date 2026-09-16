import { expect, test } from './fixtures/adminTestFixture'
import { EXAMPLE_AGENT } from './fixtures/adminEntities'

test('模型部署、路由和实验按权限分区，打开对应页签时才读取数据', async ({ page }, testInfo) => {
  const reads: string[] = []
  await page.route('**/api/auth/permissions', (route) =>
    route.fulfill({ json: { code: 0, data: ['model:view', 'model-experiment:view'] } }),
  )
  page.on('request', (request) => {
    const path = new URL(request.url()).pathname
    if (['/api/aiconfig/model-routing-policies', '/api/aiconfig/model-experiments'].includes(path))
      reads.push(path)
  })
  await page.goto('/aiconfig/model')
  await expect(page.getByRole('tab', { name: '部署列表', exact: true })).toHaveAttribute(
    'aria-selected',
    'true',
  )
  expect(reads).toEqual([])
  await page.getByRole('tab', { name: '路由策略', exact: true }).click()
  await expect(page.getByRole('heading', { name: '显式路由策略', exact: true })).toBeVisible()
  await expect.poll(() => reads).toEqual(['/api/aiconfig/model-routing-policies'])
  await page.getByRole('tab', { name: '认证与实验', exact: true }).click()
  await expect(page.getByRole('heading', { name: '模型在线实验', exact: true })).toBeVisible()
  await expect
    .poll(() => reads)
    .toEqual(['/api/aiconfig/model-routing-policies', '/api/aiconfig/model-experiments'])
  await page.getByRole('tab', { name: '部署列表', exact: true }).click()
  await page.screenshot({
    path: testInfo.outputPath('models-1440.png'),
    fullPage: true,
    animations: 'disabled',
  })
})

test('智能体卡片和列表展示同一份真实响应，切换视图不重发查询', async ({ page }, testInfo) => {
  let reads = 0
  const agents = [
    EXAMPLE_AGENT,
    {
      ...EXAMPLE_AGENT,
      id: 8,
      agentName: '客服知识检索助手',
      agentCode: 'knowledge-helper',
      status: 0,
    },
    {
      ...EXAMPLE_AGENT,
      id: 9,
      agentName: '客户服务质量检查助手',
      agentCode: 'quality-helper',
      backupModelIds: [],
      backupModelNames: [],
    },
  ]
  await page.route('**/api/auth/permissions', (route) =>
    route.fulfill({ json: { code: 0, data: ['agent:view', 'agent:edit', 'agent:add'] } }),
  )
  await page.route('**/api/aiconfig/agent?*', (route) => {
    reads += 1
    return route.fulfill({ json: { code: 0, data: { list: agents, total: agents.length } } })
  })
  await page.setViewportSize({ width: 1440, height: 960 })
  await page.goto('/aiconfig/agent')
  const cards = page.locator('.agent-overview-card')
  await expect(cards).toHaveCount(3)
  const agent = page.getByRole('article', { name: 'Java 智能体', exact: true })
  await expect(agent).toContainText('企业推理模型')
  await expect(agent).toContainText('3 项')
  const readCount = reads
  await page.screenshot({
    path: testInfo.outputPath('agents-1440.png'),
    fullPage: true,
    animations: 'disabled',
  })
  await page
    .getByRole('radiogroup', { name: '智能体展示方式' })
    .getByText('列表', { exact: true })
    .click()
  await expect(page.getByRole('radio', { name: '列表', exact: true })).toBeChecked()
  await expect(page.locator('.data-table')).toContainText('Java 智能体')
  await page
    .getByRole('radiogroup', { name: '智能体展示方式' })
    .getByText('卡片', { exact: true })
    .click()
  await expect(cards).toHaveCount(3)
  expect(reads).toBe(readCount)

  await page.getByLabel('选择界面主题', { exact: true }).click()
  await page.getByRole('option').filter({ hasText: 'Night 夜航' }).click()
  await expect(page.locator('html')).toHaveClass(/dark/)
  await page.screenshot({
    path: testInfo.outputPath('agents-dark-1440.png'),
    fullPage: true,
    animations: 'disabled',
  })
  await page.setViewportSize({ width: 390, height: 844 })
  await expect
    .poll(() =>
      page.locator('.layout-aside').evaluate((element) => element.getBoundingClientRect().width),
    )
    .toBe(0)
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth)).toBe(390)
  await expect(agent.getByRole('button', { name: '配置', exact: true })).toBeVisible()
  await page.screenshot({
    path: testInfo.outputPath('agents-390.png'),
    fullPage: true,
    animations: 'disabled',
  })
})

test('智能体只读角色在两种视图都没有配置、删除和空的更多菜单', async ({ page }) => {
  await page.route('**/api/auth/permissions', (route) =>
    route.fulfill({ json: { code: 0, data: ['agent:view'] } }),
  )
  await page.route('**/api/aiconfig/agent?*', (route) =>
    route.fulfill({ json: { code: 0, data: { list: [EXAMPLE_AGENT], total: 1 } } }),
  )
  await page.goto('/aiconfig/agent')
  for (const name of ['卡片', '列表']) {
    await page
      .getByRole('radiogroup', { name: '智能体展示方式' })
      .getByText(name, { exact: true })
      .click()
    await expect(page.getByRole('button', { name: '打开', exact: true })).toBeVisible()
    await expect(page.getByRole('button', { name: '配置', exact: true })).toHaveCount(0)
    await expect(page.getByRole('button', { name: 'Java 智能体的更多操作' })).toHaveCount(0)
    await expect(page.getByRole('button', { name: '新建智能体', exact: true })).toHaveCount(0)
  }
})

test('MCP 长表单在手机上保留标题和提交区，关闭后焦点回到新建入口', async ({ page }, testInfo) => {
  await page.route('**/api/auth/permissions', (route) =>
    route.fulfill({ json: { code: 0, data: ['mcp:view', 'mcp:add'] } }),
  )
  await page.setViewportSize({ width: 390, height: 700 })
  await page.goto('/aiconfig/mcp')
  const create = page.getByRole('button', { name: '新建 MCP', exact: true })
  await create.click()
  const dialog = page.getByRole('dialog', { name: '新建 MCP', exact: true })
  await expect(dialog).toBeVisible()
  const geometry = await dialog.evaluate((element) => {
    const body = element.querySelector('.el-dialog__body')!
    const header = element.querySelector('.el-dialog__header')!.getBoundingClientRect()
    const footer = element.querySelector('.el-dialog__footer')!.getBoundingClientRect()
    return {
      top: header.top,
      bottom: footer.bottom,
      scrolls: body.scrollHeight > body.clientHeight,
      viewport: innerHeight,
    }
  })
  expect(geometry.top).toBeGreaterThanOrEqual(0)
  expect(geometry.bottom).toBeLessThanOrEqual(geometry.viewport)
  expect(geometry.scrolls).toBe(true)
  await page.screenshot({
    path: testInfo.outputPath('mcp-form-390.png'),
    fullPage: true,
    animations: 'disabled',
  })
  await page.keyboard.press('Escape')
  await expect(dialog).not.toBeVisible()
  await expect(create).toBeFocused()
})
