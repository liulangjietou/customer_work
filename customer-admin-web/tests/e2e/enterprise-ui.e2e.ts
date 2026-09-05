import { expect, test } from './fixtures/adminTestFixture'
import { buildAdminMenuTree } from './fixtures/adminRoutes'

const agent = {
  id: 7,
  agentName: 'Java 智能体',
  agentCode: 'java-assistant',
  modelId: 11,
  modelName: '企业推理模型',
  backupModelIds: [12],
  backupModelNames: ['备用模型'],
  modelRoutePolicyId: null,
  mcpIds: [21],
  skillIds: [31],
  skillVersionIds: [301],
  systemToolIds: [41],
  knowledgeBaseIds: [51],
  knowledgeBaseNames: ['研发规范'],
  knowledgeBaseVersionIds: [501],
  systemPrompt: '根据企业规范处理任务。',
  capabilities: ['chat', 'plan'],
  icon: 'Cpu',
  status: 1,
  subAgentIds: [],
  maxIters: 12,
  toolTimeoutSeconds: 90,
  toolMaxAttempts: 2,
  compressTriggerMsgs: 30,
  compressKeepMsgs: 10,
  createTime: '2026-09-01 10:00:00',
}

test('完整配置页保留模型、知识版本关联、提示词与高级参数的提交契约', async ({ page }) => {
  let saved: Record<string, unknown> | undefined
  await page.route('**/api/auth/permissions', (route) =>
    route.fulfill({ json: { code: 0, data: ['agent:view', 'agent:edit', 'agent:add'] } }),
  )
  await page.route('**/api/aiconfig/agent?*', (route) =>
    route.fulfill({ json: { code: 0, data: { list: [agent], total: 1 } } }),
  )
  await page.route('**/api/aiconfig/agent/7', async (route) => {
    expect(route.request().method()).toBe('PUT')
    saved = route.request().postDataJSON()
    await route.fulfill({ json: { code: 0, data: null } })
  })
  await page.goto('/aiconfig/agent')
  await page.getByRole('button', { name: '配置', exact: true }).click()
  await expect(page.getByRole('region', { name: '智能体配置' })).toBeVisible()
  await expect(page.locator('.el-dialog:visible')).toHaveCount(0)
  await page.getByLabel('名称', { exact: true }).fill('Java 研发助手')
  await page.getByRole('button', { name: '保存智能体', exact: true }).first().click()
  await expect.poll(() => saved?.agentName).toBe('Java 研发助手')
  expect(saved).toMatchObject({
    agentCode: agent.agentCode,
    modelId: 11,
    backupModelIds: [12],
    mcpIds: [21],
    skillIds: [31],
    systemToolIds: [41],
    knowledgeBaseIds: [51],
    systemPrompt: agent.systemPrompt,
    capabilities: ['chat', 'plan'],
    maxIters: 12,
    toolTimeoutSeconds: 90,
    toolMaxAttempts: 2,
    compressTriggerMsgs: 30,
    compressKeepMsgs: 10,
  })
  await expect(page.getByRole('region', { name: '智能体配置' })).toHaveCount(0)
})

test('首页按菜单权限加载统计，缺少权限时不发统计和告警请求', async ({ page }) => {
  const removeStats = (
    nodes: ReturnType<typeof buildAdminMenuTree>,
  ): ReturnType<typeof buildAdminMenuTree> =>
    nodes
      .filter((node) => !['/system/agent-call-stats', '/system/slo'].includes(node.path ?? ''))
      .map((node) => ({ ...node, children: removeStats(node.children ?? []) }))
  await page.route('**/api/menu/routes', (route) =>
    route.fulfill({ json: { code: 0, data: removeStats(buildAdminMenuTree()) } }),
  )
  const requests: string[] = []
  page.on('request', (request) => {
    if (/\/api\/(agent-call-stats|slo)\//.test(request.url())) requests.push(request.url())
  })
  await page.goto('/home')
  await expect(page.getByText('尚未开通调用统计权限')).toBeVisible()
  await expect(page.getByText('尚未开通服务质量权限')).toBeVisible()
  expect(requests).toEqual([])
})

test('统计加载失败显示重试，恢复后展示接口中的实际数值', async ({ page }) => {
  let failed = true
  await page.route('**/api/agent-call-stats/summary?*', (route) =>
    route.fulfill({
      json: failed
        ? { code: 500, message: '统计暂时不可用' }
        : { code: 0, data: { totalCalls: 1234, totalTokens: 56000, avgDurationMs: 1800 } },
    }),
  )
  await page.goto('/home')
  await expect(page.getByText('运行统计暂时无法加载')).toBeVisible()
  await expect(page.locator('.metric-card').nth(1).locator('strong')).toHaveText('—')
  failed = false
  await page.getByRole('button', { name: '重新加载', exact: true }).click()
  await expect(page.locator('.metric-card').nth(1).locator('strong')).toHaveText('1,234')
  await expect(page.locator('.metric-card').nth(2).locator('strong')).toHaveText('1.8s')
})

for (const width of [390, 1280]) {
  test(`${width}px 工作区不横向溢出，详情可关闭，导航与发送操作可到达`, async ({ page }) => {
    await page.setViewportSize({ width, height: 844 })
    await page.goto('/workspace/java-assistant')
    const composer = page.getByRole('textbox', { name: '消息内容' })
    await composer.fill('第一行\n第二行\n第三行')
    const send = page.getByRole('button', { name: '发送', exact: true })
    await expect(send).toBeVisible()
    await expect(page.getByRole('tab', { name: '对话', exact: true })).toBeVisible()
    await page.getByRole('button', { name: '执行详情', exact: true }).click()
    await expect(page.getByText('发送任务后查看执行过程')).toBeVisible()
    await page.getByRole('button', { name: '关闭执行详情' }).click()
    await expect(composer).toHaveValue('第一行\n第二行\n第三行')
    const dimensions = await page.evaluate(() => ({
      width: innerWidth,
      scroll: document.documentElement.scrollWidth,
    }))
    expect(dimensions.scroll).toBeLessThanOrEqual(dimensions.width)
    const composerBox = await composer.boundingBox()
    const sendBox = await send.boundingBox()
    expect(composerBox!.width).toBeGreaterThan(150)
    expect(sendBox!.x + sendBox!.width).toBeLessThanOrEqual(width)
    expect(sendBox!.y + sendBox!.height).toBeLessThanOrEqual(844)
  })
}

test('移动端从打开的执行详情返回上一页时，抽屉不会残留遮挡页面', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/home')
  await page.getByRole('button', { name: '进入Java 智能体', exact: true }).click()
  await expect(page).toHaveURL(/\/workspace\/java-assistant$/)
  await page.getByRole('button', { name: '执行详情', exact: true }).click()
  await expect(page.getByRole('heading', { name: '执行详情', exact: true })).toBeVisible()
  await page.goBack()
  await expect(page).toHaveURL(/\/home$/)
  await expect(page.getByRole('heading', { name: '执行详情', exact: true })).toHaveCount(0)
  await page.getByRole('button', { name: '进入Java 智能体', exact: true }).click()
  await expect(page).toHaveURL(/\/workspace\/java-assistant$/)
  await expect(page.getByRole('heading', { name: '执行详情', exact: true })).toHaveCount(0)
})
