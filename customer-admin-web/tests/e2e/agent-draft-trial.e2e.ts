import type { Page } from '@playwright/test'
import { expect, test } from './fixtures/adminTestFixture'
import type { AgentDraft } from '../../src/api/agentDraft'
import type { AgentDraftTrialReceipt, AgentDraftTrialPreview } from '../../src/api/agentDraftTrial'

const permissions = ['agent:view', 'agent:add', 'agent:edit', 'model:view']
const draftId = 'df318d12-49a8-423c-b47f-2d5bc66c3d71'
const ok = (data: unknown) => ({ code: 0, data })
const saved: AgentDraft = { id: draftId, agentId: null, baseRevision: null, title: '售后客服',
  version: 3, updatedAtMs: Date.now(), configuration: { agentName: '售后客服', agentCode: 'service', modelId: 1 } }
const preview: AgentDraftTrialPreview = { draftVersion: 3, configurationFingerprint: 'current-config', models: ['1: 客服模型'],
  skills: [{ id: 2, versionId: 4, name: '售后处理', contentHash: 'skill-v4' }], knowledgeBases: [],
  restrictions: ['MCP 工具不执行，本次不能验证外部工具行为', 'Skill 只读取冻结说明和文本附件，不执行脚本'] }

async function server(page: Page, mode: 'success' | 'drop' | 'reject-once' | 'hold' = 'success') {
  const records = new Map<string, AgentDraftTrialReceipt>()
  const puts: { id: string; data: { expectedDraftVersion: number; input: string } }[] = []
  let release!: () => void
  const held = new Promise<void>(resolve => { release = resolve })
  await page.route('**/api/auth/permissions', route => route.fulfill({ json: ok(permissions) }))
  await page.route('**/api/aiconfig/agent-drafts**', async route => {
    const request = route.request()
    const url = new URL(request.url())
    const parts = url.pathname.slice('/api/aiconfig/agent-drafts'.length).split('/').filter(Boolean)
    if (!parts.length && request.method() === 'GET') return route.fulfill({ json: ok([{ ...saved, configuration: null }]) })
    if (parts.length === 1 && request.method() === 'GET') return route.fulfill({ json: ok(saved) })
    if (parts[1] === 'trials') {
      const id = parts[2]
      if (request.method() === 'GET') {
        if (id === 'preview') return route.fulfill({ json: ok(preview) })
        if (!id) return route.fulfill({ json: ok([...records.values()].map(item => ({ ...item, inputExcerpt: item.input }))) })
        const found = records.get(id)
        return route.fulfill({ json: found ? ok(found) : { code: 30003, message: '回执尚未找到' } })
      }
      if (request.method() === 'PUT' && id) {
        const data = request.postDataJSON()
        puts.push({ id, data })
        expect(data.expectedDraftVersion).toBe(3)
        if (mode === 'reject-once' && puts.length === 1) return route.fulfill({ json: { code: 30001, message: '模型已停用，试用未受理' } })
        records.set(id, receipt(id, data.input, mode === 'hold' ? 'RUNNING' : 'SUCCEEDED'))
        if (mode === 'drop') return route.abort('failed')
        if (mode === 'hold') await held
        return route.fulfill({ json: ok(records.get(id)) })
      }
    }
    throw new Error(`Unexpected trial fixture request: ${request.method()} ${url.pathname}`)
  })
  return { puts, records, release }
}
function receipt(id: string, input: string, phase: 'RUNNING' | 'SUCCEEDED'): AgentDraftTrialReceipt {
  return { id, input, draftVersion: 3, configurationFingerprint: 'current-config', configurationMatch: 'MATCH', phase,
    errorCode: null, acceptedAtMs: Date.now(), deadlineAtMs: Date.now() + 120000, finishedAtMs: phase === 'RUNNING' ? null : Date.now(),
    restrictions: preview.restrictions,
    result: phase === 'RUNNING' ? null : { answer: '请先确认订单状态，再依据冻结的售后规则处理。', answerTruncated: false,
      durationMs: 260, attemptedTools: ['trial_read_skill'], restrictions: preview.restrictions } }
}
async function open(page: Page) {
  await page.getByRole('button', { name: '我的草稿', exact: true }).click()
  await page.getByRole('button', { name: '受控试用', exact: true }).click()
  await expect(page.getByRole('dialog', { name: '草稿受控试用', exact: true })).toBeVisible()
  await expect(page.getByText('1: 客服模型', { exact: true })).toBeVisible()
}

test('受控试用展示冻结范围，单次提交后可以核对已有结果', async ({ page }) => {
  const api = await server(page)
  await page.goto('/aiconfig/agent'); await open(page)
  await page.getByRole('textbox', { name: '试用问题', exact: true }).fill('如何申请退款？')
  await page.getByRole('button', { name: '开始试用', exact: true }).click()
  await expect(page.getByText('回答已完成', { exact: true })).toBeVisible()
  await expect(page.getByText('请先确认订单状态，再依据冻结的售后规则处理。', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '核对回执', exact: true }).click()
  await expect.poll(() => api.puts.length).toBe(1)
  expect(api.puts[0].data.input).toBe('如何申请退款？')
  await expect(page.getByText(/回答完成不等于通过发布评测/)).toBeVisible()
})

test('提交响应丢失后仅查询原回执，不重复发送模型试用', async ({ page, adminHarness }) => {
  const api = await server(page, 'drop')
  await page.goto('/aiconfig/agent'); await open(page)
  await page.getByRole('textbox', { name: '试用问题', exact: true }).fill('丢失响应后的问题')
  await page.getByRole('button', { name: '开始试用', exact: true }).click()
  await expect(page.getByText('回答已完成', { exact: true })).toBeVisible()
  expect(api.puts).toHaveLength(1)
  await expect(page.getByRole('textbox', { name: '试用问题', exact: true })).toHaveValue('丢失响应后的问题')
  // 只消费本用例主动断开 PUT 产生的一条浏览器网络报错，其他异常继续由公共夹具拦截。
  await expect.poll(() => adminHarness.consoleErrors).toEqual(['Failed to load resource: net::ERR_FAILED'])
  adminHarness.consoleErrors.splice(0, 1)
})

test('明确未受理的配置失败保留问题，重新核对后允许发起新试用', async ({ page }) => {
  const api = await server(page, 'reject-once')
  await page.goto('/aiconfig/agent'); await open(page)
  await page.getByRole('textbox', { name: '试用问题', exact: true }).fill('需要保留的问题')
  await page.getByRole('button', { name: '开始试用', exact: true }).click()
  await expect(page.getByText('模型已停用，试用未受理', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '重新核对', exact: true }).click()
  await expect(page.getByRole('textbox', { name: '试用问题', exact: true })).toHaveValue('需要保留的问题')
  await page.getByRole('button', { name: '开始试用', exact: true }).click()
  await expect(page.getByText('回答已完成', { exact: true })).toBeVisible()
  expect(api.puts).toHaveLength(2)
  expect(api.records.size).toBe(1)
})

test('关闭再打开保留原受理标识，只核对而不重复发起', async ({ page }) => {
  const api = await server(page, 'hold')
  try {
    await page.goto('/aiconfig/agent'); await open(page)
    await page.getByRole('textbox', { name: '试用问题', exact: true }).fill('正在执行的问题')
    await page.getByRole('button', { name: '开始试用', exact: true }).click()
    await expect.poll(() => api.puts.length).toBe(1)
    await page.getByRole('dialog', { name: '草稿受控试用' }).getByRole('button', { name: /close|关闭/i }).click()
    await open(page)
    await expect(page.getByText('执行中', { exact: true })).toBeVisible()
    await expect(page.getByRole('button', { name: '开始试用', exact: true })).toBeDisabled()
    const id = api.puts[0].id
    api.records.set(id, receipt(id, api.puts[0].data.input, 'SUCCEEDED'))
    api.release()
    await page.getByRole('button', { name: '核对回执', exact: true }).click()
    await expect(page.getByText('回答已完成', { exact: true })).toBeVisible()
    expect(api.puts).toHaveLength(1)
  } finally { api.release() }
})

test('同令牌重登关闭旧试用，迟到回答不进入新登录页面', async ({ page }) => {
  const api = await server(page, 'hold')
  try {
    await page.goto('/aiconfig/agent'); await open(page)
    await page.getByRole('textbox', { name: '试用问题', exact: true }).fill('旧登录问题')
    await page.getByRole('button', { name: '开始试用', exact: true }).click()
    await expect.poll(() => api.puts.length).toBe(1)
    await page.evaluate(async permissions => {
      const path = '/src/store/auth.ts'
      const { useAuthStore } = await import(path)
      const auth = useAuthStore()
      auth.applyLoginResult({ token: auth.token, nickname: '新登录', forceChangePassword: false,
        approvalStatus: 'APPROVED', approvalRemark: null }, 'admin')
      auth.permissions = permissions
    }, permissions)
    api.records.set(api.puts[0].id, receipt(api.puts[0].id, '旧登录问题', 'SUCCEEDED'))
    api.release()
    await expect(page.getByRole('dialog', { name: '草稿受控试用' })).not.toBeVisible()
    await expect(page.getByText('请先确认订单状态，再依据冻结的售后规则处理。', { exact: true })).not.toBeVisible()
  } finally { api.release() }
})

for (const preset of ['ember', 'night'] as const) {
  test(`${preset} 主题下 390px 试用结果可读且不横向溢出`, async ({ page }, testInfo) => {
    await server(page)
    await page.setViewportSize({ width: 390, height: 844 })
    await page.goto('/aiconfig/agent')
    await page.evaluate(async preset => {
      const path = '/src/store/theme.ts'
      const { useThemeStore } = await import(path)
      useThemeStore().selectPreset(preset)
    }, preset)
    await open(page)
    await page.getByRole('textbox', { name: '试用问题', exact: true }).fill('窄屏下如何查看售后处理要求？')
    await page.getByRole('button', { name: '开始试用', exact: true }).click()
    await expect(page.getByText('回答已完成', { exact: true })).toBeVisible()
    const drawer = page.getByRole('dialog', { name: '草稿受控试用' })
    expect(await drawer.evaluate(node => node.scrollWidth <= node.clientWidth + 1)).toBe(true)
    await drawer.screenshot({ path: testInfo.outputPath(`agent-trial-${preset}-390.png`) })
  })
}

test('我的草稿在同令牌重登后关闭，旧删除确认不能写入新登录', async ({ page }) => {
  await server(page)
  let deletes = 0
  await page.route(`**/api/aiconfig/agent-drafts/${draftId}`, async route => {
    if (route.request().method() !== 'DELETE') return route.fallback()
    deletes += 1
    await route.fulfill({ json: ok(null) })
  })
  await page.goto('/aiconfig/agent')
  await page.getByRole('button', { name: '我的草稿', exact: true }).click()
  const drawer = page.getByRole('dialog', { name: '我的配置草稿', exact: true })
  await drawer.getByRole('button', { name: '删除', exact: true }).click()
  await expect(page.getByRole('dialog', { name: '删除草稿', exact: true })).toBeVisible()
  await page.evaluate(async permissions => {
    const path = '/src/store/auth.ts'
    const { useAuthStore } = await import(path)
    const auth = useAuthStore()
    auth.applyLoginResult({ token: auth.token, nickname: '新登录', forceChangePassword: false,
      approvalStatus: 'APPROVED', approvalRemark: null }, 'admin')
    auth.permissions = permissions
  }, permissions)
  await expect(drawer).not.toBeVisible()
  await page.getByRole('dialog', { name: '删除草稿', exact: true }).getByRole('button', { name: '确定', exact: true }).click()
  await expect(page.getByRole('dialog', { name: '删除草稿', exact: true })).not.toBeVisible()
  expect(deletes).toBe(0)
})
