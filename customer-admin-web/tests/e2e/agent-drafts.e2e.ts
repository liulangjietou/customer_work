import type { Page } from '@playwright/test'
import { expect, test } from './fixtures/adminTestFixture'
import { EXAMPLE_AGENT } from './fixtures/adminEntities'
import type { AgentDraft, AgentDraftConfiguration } from '../../src/api/agentDraft'

async function draftServer(
  page: Page,
  options: { dropFirstReply?: boolean; failWrites?: boolean } = {},
) {
  const drafts = new Map<string, AgentDraft>()
  const writes: string[] = []
  let dropped = false
  await page.route('**/api/auth/permissions', (route) =>
    route.fulfill({
      json: {
        code: 0,
        data: ['agent:view', 'agent:add', 'agent:edit', 'model:view', 'model:edit'],
      },
    }),
  )
  await page.route('**/api/aiconfig/agent-drafts**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const id = url.pathname.split('/').slice(4).join('/')
    if (request.method() === 'GET') {
      if (!id)
        return route.fulfill({
          json: {
            code: 0,
            data: [...drafts.values()].map((value) => ({
              ...value,
              configuration: null,
            })),
          },
        })
      const found = drafts.get(id)
      return route.fulfill({
        json: found ? { code: 0, data: found } : { code: 30003, message: '草稿不存在' },
      })
    }
    writes.push(request.method())
    if (options.failWrites)
      return route.fulfill({
        json: { code: 50000, message: '草稿服务暂不可用，当前内容已保留' },
      })
    const existing = drafts.get(id)
    if (request.method() === 'DELETE') {
      expect(existing?.version).toBe(Number(url.searchParams.get('expectedVersion')))
      drafts.delete(id)
      return route.fulfill({ json: { code: 0, data: null } })
    }
    const body = request.postDataJSON()
    if (body.expectedVersion !== (existing?.version ?? 0))
      return route.fulfill({
        json: {
          code: 30016,
          message: '配置已在其他位置更新，请保留当前内容并重新核对',
        },
      })
    const draft: AgentDraft = {
      id,
      agentId: body.agentId,
      baseRevision: body.baseRevision,
      title: body.configuration.agentName || '未命名智能体',
      version: body.expectedVersion + 1,
      updatedAtMs: Date.now(),
      configuration: {
        ...body.configuration,
        modelId: body.configuration.modelId ?? null,
      },
    }
    drafts.set(id, draft)
    if (options.dropFirstReply && !dropped) {
      dropped = true
      return route.abort('failed')
    }
    return route.fulfill({ json: { code: 0, data: draft } })
  })
  return { drafts, writes }
}

async function create(page: Page) {
  await page.goto('/aiconfig/agent')
  await page.getByRole('button', { name: '新建智能体', exact: true }).click()
}

test('模板只填写内容，未选模型可保存个人草稿，刷新恢复不调用模型或发布', async ({
  page,
}, testInfo) => {
  const server = await draftServer(page)
  const runtimeWrites: string[] = []
  page.on('request', (request) => {
    if (
      request.method() !== 'GET' &&
      /\/api\/(workspace|aiconfig\/(agent$|channel|model\/))/.test(request.url())
    )
      runtimeWrites.push(request.url())
  })
  await page.setViewportSize({ width: 1440, height: 960 })
  await create(page)
  await page.getByRole('button', { name: '使用售后服务模板' }).click()
  await expect(page.getByLabel('名称', { exact: true })).toHaveValue('售后服务助手')
  await expect(page.getByRole('button', { name: '保存智能体', exact: true }).first()).toBeDisabled()
  await page.getByRole('button', { name: '保存草稿', exact: true }).click()
  await expect(page.getByRole('status')).toContainText('个人草稿已保存')
  const saved = [...server.drafts.values()][0]!
  expect(saved.configuration).toMatchObject({
    modelId: null,
    mcpIds: [],
    systemToolIds: [],
    skillIds: [],
    knowledgeBaseIds: [],
    capabilities: ['chat'],
  })
  await page.screenshot({
    path: testInfo.outputPath('agent-template-desktop.png'),
    fullPage: true,
    animations: 'disabled',
  })
  await page.reload()
  await page.getByRole('button', { name: '我的草稿', exact: true }).click()
  await page.getByRole('button', { name: '继续编辑', exact: true }).click()
  await expect(page.getByLabel('名称', { exact: true })).toHaveValue('售后服务助手')
  await expect(page.locator('#agent-prompt textarea')).toHaveValue(
    saved.configuration!.systemPrompt!,
  )
  await expect(page.getByRole('button', { name: '保存智能体', exact: true }).first()).toBeDisabled()
  expect(runtimeWrites).toEqual([])
  await page.setViewportSize({ width: 390, height: 844 })
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth)).toBe(390)
  await page.screenshot({
    path: testInfo.outputPath('agent-draft-mobile.png'),
    fullPage: true,
    animations: 'disabled',
  })
})

test('离开未保存表单需明确放弃，保存失败继续保留内容', async ({ page }) => {
  await draftServer(page, { failWrites: true })
  await create(page)
  await page.getByLabel('名称', { exact: true }).fill('尚未保存的配置')
  await page.getByRole('button', { name: '取消', exact: true }).click()
  await page
    .getByRole('dialog', { name: '离开配置' })
    .getByRole('button', { name: '继续编辑' })
    .click()
  await expect(page.getByLabel('名称', { exact: true })).toHaveValue('尚未保存的配置')
  await page.getByRole('button', { name: '保存草稿', exact: true }).click()
  await expect(page.locator('.draft-error')).toContainText('草稿服务暂不可用')
  await expect(page.getByLabel('名称', { exact: true })).toHaveValue('尚未保存的配置')
  await page.getByRole('button', { name: '取消', exact: true }).click()
  await page
    .getByRole('dialog', { name: '离开配置' })
    .getByRole('button', { name: '放弃未保存修改' })
    .click()
  await expect(page.getByRole('region', { name: '智能体配置' })).toHaveCount(0)
})

test('草稿写入成功但响应丢失时按固定标识核对，不能重复新建', async ({ page, adminHarness }) => {
  const server = await draftServer(page, { dropFirstReply: true })
  await create(page)
  await page.getByLabel('名称', { exact: true }).fill('回执待确认的草稿')
  await page.getByRole('button', { name: '保存草稿', exact: true }).click()
  await expect(page.getByRole('status')).toContainText('个人草稿已保存')
  expect(server.drafts.size).toBe(1)
  expect(server.writes).toEqual(['PUT'])
  // 这里只接收本例主动切断该 PUT 产生的一条浏览器网络错误，其余错误仍由公共门禁拒绝。
  await expect
    .poll(() => adminHarness.consoleErrors)
    .toEqual(['Failed to load resource: net::ERR_FAILED'])
  adminHarness.consoleErrors.splice(0, 1)
})

test('其他标签页更新草稿后拒绝覆盖，本页输入保持可读', async ({ page }) => {
  const server = await draftServer(page)
  await create(page)
  await page.getByLabel('名称', { exact: true }).fill('第一版草稿')
  await page.getByRole('button', { name: '保存草稿', exact: true }).click()
  await expect(page.getByRole('status')).toContainText('个人草稿已保存')
  const saved = [...server.drafts.values()][0]!
  server.drafts.set(saved.id, {
    ...saved,
    version: 2,
    configuration: { ...saved.configuration!, agentName: '另一个标签页的修改' },
  })
  await page.getByLabel('名称', { exact: true }).fill('本页尚未保存的内容')
  await page.getByRole('button', { name: '保存草稿', exact: true }).click()
  await expect(page.locator('.draft-error')).toContainText('配置已在其他位置更新')
  await expect(page.getByLabel('名称', { exact: true })).toHaveValue('本页尚未保存的内容')
  expect(server.drafts.get(saved.id)?.configuration?.agentName).toBe('另一个标签页的修改')
})

test('旧草稿恢复时识别正式配置变更，保存入口保持关闭', async ({ page }) => {
  const server = await draftServer(page)
  const configuration: AgentDraftConfiguration = {
    agentName: '较早的配置草稿',
    agentCode: EXAMPLE_AGENT.agentCode,
    modelId: 11,
    capabilities: ['chat'],
  }
  const id = 'da9c14f0-64e3-43e4-928f-ef3d74db4aa1'
  server.drafts.set(id, {
    id,
    agentId: 7,
    baseRevision: 2,
    title: configuration.agentName,
    version: 1,
    updatedAtMs: Date.now(),
    configuration,
  })
  await page.route('**/api/aiconfig/agent/7', (route) => {
    expect(route.request().method()).toBe('GET')
    return route.fulfill({
      json: { code: 0, data: { ...EXAMPLE_AGENT, runtimeRevision: 3 } },
    })
  })
  await page.goto('/aiconfig/agent')
  await page.getByRole('button', { name: '我的草稿', exact: true }).click()
  await page.getByRole('button', { name: '继续编辑', exact: true }).click()
  await expect(page.locator('.draft-error')).toContainText('正式配置已更新')
  await expect(page.getByLabel('名称', { exact: true })).toHaveValue('较早的配置草稿')
  await expect(page.getByRole('button', { name: '保存智能体', exact: true }).first()).toBeDisabled()
})

test('只有查看权限时不展示草稿入口也不读取个人草稿', async ({ page }) => {
  const reads: string[] = []
  page.on('request', (request) => {
    if (request.url().includes('/agent-drafts')) reads.push(request.url())
  })
  await page.route('**/api/auth/permissions', (route) =>
    route.fulfill({ json: { code: 0, data: ['agent:view'] } }),
  )
  await page.goto('/aiconfig/agent')
  await expect(page.getByRole('button', { name: '我的草稿', exact: true })).toHaveCount(0)
  await expect(page.getByRole('button', { name: '新建智能体', exact: true })).toHaveCount(0)
  expect(reads).toEqual([])
})

test('恢复草稿后正式保存带原配置版本，失败保留草稿，成功后才清理', async ({ page }) => {
  const server = await draftServer(page)
  const id = 'ca9c14f0-64e3-43e4-928f-ef3d74db4aa1'
  const configuration = {
    agentName: '已核对的配置',
    agentCode: EXAMPLE_AGENT.agentCode,
    modelId: 11,
    capabilities: ['chat'],
  }
  server.drafts.set(id, {
    id,
    agentId: 7,
    baseRevision: 3,
    title: configuration.agentName,
    version: 2,
    updatedAtMs: Date.now(),
    configuration,
  })
  let writes = 0
  await page.route('**/api/aiconfig/agent/7', (route) => {
    if (route.request().method() === 'GET')
      return route.fulfill({
        json: { code: 0, data: { ...EXAMPLE_AGENT, runtimeRevision: 3 } },
      })
    expect(route.request().headers()['x-agent-revision']).toBe('3')
    expect(route.request().postDataJSON().agentName).toBe(configuration.agentName)
    writes += 1
    return route.fulfill({
      json:
        writes === 1
          ? { code: 30001, message: '知识版本已不可用，请重新选择' }
          : { code: 0, data: null },
    })
  })
  await page.goto('/aiconfig/agent')
  await page.getByRole('button', { name: '我的草稿', exact: true }).click()
  await page.getByRole('button', { name: '继续编辑', exact: true }).click()
  await page.getByRole('button', { name: '保存智能体', exact: true }).first().click()
  await expect(page.locator('.el-message')).toContainText('知识版本已不可用')
  await expect(page.getByLabel('名称', { exact: true })).toHaveValue(configuration.agentName)
  expect(server.drafts.has(id)).toBe(true)
  await page.getByRole('button', { name: '保存智能体', exact: true }).first().click()
  await expect(page.getByRole('region', { name: '智能体配置' })).toHaveCount(0)
  expect(server.drafts.has(id)).toBe(false)
  expect(writes).toBe(2)
})

test('草稿列表读取失败可以重试，删除个人草稿需要确认并携带版本', async ({ page }) => {
  const server = await draftServer(page)
  await create(page)
  await page.getByLabel('名称', { exact: true }).fill('准备清理的个人草稿')
  await page.getByRole('button', { name: '保存草稿', exact: true }).click()
  await expect(page.getByRole('status')).toContainText('个人草稿已保存')
  await page.getByRole('button', { name: '返回列表', exact: true }).click()
  let reads = 0
  await page.route(
    (url) => url.pathname === '/api/aiconfig/agent-drafts',
    (route) => {
      reads += 1
      return reads === 1
        ? route.fulfill({ json: { code: 50000, message: '草稿列表读取失败' } })
        : route.fallback()
    },
  )
  await page.getByRole('button', { name: '我的草稿', exact: true }).click()
  const drawer = page.getByRole('dialog', { name: '我的配置草稿' })
  await expect(drawer).toContainText('草稿列表读取失败')
  await drawer.getByRole('button', { name: '重新加载', exact: true }).click()
  await expect(drawer).toContainText('准备清理的个人草稿')
  await drawer.getByRole('button', { name: '删除', exact: true }).click()
  expect(server.drafts.size).toBe(1)
  await page
    .getByRole('dialog', { name: '删除草稿', exact: true })
    .getByRole('button', { name: '确定', exact: true })
    .click()
  await expect(drawer).toContainText('暂无草稿')
  expect(server.drafts.size).toBe(0)
  expect(server.writes).toEqual(['PUT', 'DELETE'])
})
