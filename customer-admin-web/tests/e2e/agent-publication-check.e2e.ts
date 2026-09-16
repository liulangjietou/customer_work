import type { Page } from '@playwright/test'
import { expect, test } from './fixtures/adminTestFixture'
import { EXAMPLE_AGENT } from './fixtures/adminEntities'
import type { AgentPublicationCheck } from '../../src/api/agentPublication'

const permissions = ['agent:view', 'eval:view']
const candidate = { datasetVersion: '', datasetFingerprint: '', modelVersion: 'model-a', promptVersion: 'prompt-a',
  agentVersion: 'agent-a', knowledgeBaseVersion: '', toolVersion: 'tool-a', judgeVersion: '', rubricVersion: '' }
function facts(): AgentPublicationCheck {
  return { agentId: 7, agentName: EXAMPLE_AGENT.agentName, runtimeRevision: 5, agentEnabled: true, publishingEnabled: true,
    channels: [{ code: 'web', enabled: true }], candidateStatus: 'READY', currentCandidate: candidate,
    currentRuntimeConfirmed: false, checkedAtMs: Date.now(), latestPublication: {
      taskId: 'task-a', intent: 'NORMAL', status: 'PUBLISHED', revision: 'revision-a', candidateContentHash: 'hash-a',
      candidateVersions: candidate, currentMatch: 'MATCH', gateStatus: 'PASSED', gateDecision: null,
      evalRunIds: ['eval-1'], evaluatedAtMs: Date.now(), overrideId: null,
      targetInstances: ['instance-a', 'instance-b'], acknowledgements: [{ instanceId: 'instance-a', status: 'APPLIED', appliedAtMs: Date.now() }],
      confirmation: 'PARTIAL', updatedAtMs: Date.now(),
    } }
}
async function server(page: Page, allowed = permissions) {
  const state = { value: facts(), calls: 0, fail: false, hold: null as Promise<void> | null }
  const writes: string[] = []
  page.on('request', request => {
    if (request.method() !== 'GET' && request.url().includes('/api/aiconfig/')) writes.push(request.url())
  })
  await page.route('**/api/auth/permissions', route => route.fulfill({ json: { code: 0, data: allowed } }))
  await page.route('**/api/aiconfig/agent?*', route => route.fulfill({ json: { code: 0,
    data: { list: [EXAMPLE_AGENT], total: 1, pageNum: 1, pageSize: 20 } } }))
  await page.route('**/api/aiconfig/agent/7/publication-check', async route => {
    expect(route.request().method()).toBe('GET')
    state.calls += 1
    const result = state.fail ? { code: 50000, message: '发布状态暂不可用，请重试' } : { code: 0, data: state.value }
    if (state.hold) await state.hold
    await route.fulfill({ json: result })
  })
  return { state, writes }
}
async function open(page: Page) {
  await page.getByRole('button', { name: `${EXAMPLE_AGENT.agentName}的更多操作`, exact: true }).click()
  await page.getByRole('menuitem', { name: '发布检查', exact: true }).click()
  await expect(page.getByRole('dialog', { name: '发布检查', exact: true })).toBeVisible()
}

test('发布检查把正式配置、评测、投递和实例确认分开显示，读取不产生写入', async ({ page }) => {
  const api = await server(page)
  await page.goto('/aiconfig/agent'); await open(page)
  await expect(page.getByRole('region', { name: '正式配置', exact: true })).toContainText('已保存修订 5')
  await expect(page.getByRole('region', { name: '评测门禁', exact: true })).toContainText('评测已通过')
  await expect(page.getByRole('region', { name: '发布投递', exact: true })).toContainText('已投递，等待实例确认')
  await expect(page.getByRole('region', { name: '实例确认', exact: true })).toContainText('仍有目标未确认')
  await expect(page.getByText('当前发布控制的配置已获目标实例确认', { exact: true })).toHaveCount(0)
  await page.getByRole('button', { name: '刷新检查', exact: true }).click()
  await expect.poll(() => api.state.calls).toBe(2)
  expect(api.writes).toEqual([])
})

test('旧版本的评测通过和全部回执只显示为历史事实', async ({ page }) => {
  const api = await server(page)
  api.state.value.latestPublication!.currentMatch = 'CHANGED'
  api.state.value.latestPublication!.status = 'APPLIED'
  api.state.value.latestPublication!.confirmation = 'CONFIRMED'
  await page.goto('/aiconfig/agent'); await open(page)
  await expect(page.getByText('当前正式配置已变化，下面的发布记录属于旧配置', { exact: true })).toBeVisible()
  await expect(page.getByText('当前发布控制的配置已获目标实例确认', { exact: true })).toHaveCount(0)
  await expect(page.getByText('评测已通过', { exact: true })).toBeVisible()
})

test('未要求评测不会显示通过，历史目标缺失不会显示全部确认', async ({ page }) => {
  const api = await server(page)
  api.state.value.latestPublication!.gateStatus = 'NOT_REQUIRED'
  api.state.value.latestPublication!.targetInstances = null
  api.state.value.latestPublication!.confirmation = 'LEGACY_UNVERIFIED'
  await page.goto('/aiconfig/agent'); await open(page)
  await expect(page.getByText('本次未要求评测', { exact: true })).toBeVisible()
  await expect(page.getByText('该记录不代表已通过评测。', { exact: true })).toBeVisible()
  await expect(page.getByText('历史任务未保存目标清单，无法核对全部实例', { exact: true })).toBeVisible()
  await expect(page.getByText('评测已通过', { exact: true })).toHaveCount(0)
})

test('刷新失败清除旧结果，重试后恢复当前事实', async ({ page }) => {
  const api = await server(page)
  await page.goto('/aiconfig/agent'); await open(page)
  await expect(page.getByText('评测已通过', { exact: true })).toBeVisible()
  api.state.fail = true
  await page.getByRole('button', { name: '刷新检查', exact: true }).click()
  await expect(page.getByText('发布状态暂不可用，请重试', { exact: true })).toBeVisible()
  await expect(page.getByText('评测已通过', { exact: true })).toHaveCount(0)
  api.state.fail = false
  await page.getByRole('button', { name: '刷新检查', exact: true }).click()
  await expect(page.getByText('评测已通过', { exact: true })).toBeVisible()
})

test('没有发布任务和未启用渠道时给出明确状态', async ({ page }) => {
  const api = await server(page)
  api.state.value.latestPublication = null; api.state.value.channels = []
  api.state.value.candidateStatus = 'CHANNEL_MISSING'; api.state.value.currentCandidate = null
  await page.goto('/aiconfig/agent'); await open(page)
  await expect(page.getByText('尚无启用的渠道绑定', { exact: true })).toBeVisible()
  await expect(page.getByText('尚无发布任务记录', { exact: true })).toBeVisible()
})

test('缺少评测查看权限时不提供发布检查入口', async ({ page }) => {
  const api = await server(page, ['agent:view'])
  await page.goto('/aiconfig/agent')
  await expect(page.getByRole('heading', { name: EXAMPLE_AGENT.agentName, exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: `${EXAMPLE_AGENT.agentName}的更多操作`, exact: true })).toHaveCount(0)
  expect(api.state.calls).toBe(0)
})

test('同令牌重新登录后关闭旧检查并丢弃迟到状态', async ({ page }) => {
  const api = await server(page)
  let release!: () => void
  api.state.hold = new Promise<void>(resolve => { release = resolve })
  try {
    await page.goto('/aiconfig/agent'); await open(page)
    await expect.poll(() => api.state.calls).toBe(1)
    await page.evaluate(async permissions => {
      const path = '/src/store/auth.ts'
      const { useAuthStore } = await import(path)
      const auth = useAuthStore()
      auth.applyLoginResult({ token: auth.token, nickname: '新登录', username: 'new-login', forceChangePassword: false, approvalStatus: 'APPROVED' })
      auth.permissions = permissions
    }, permissions)
    release()
    await expect(page.getByRole('dialog', { name: '发布检查', exact: true })).not.toBeVisible()
    await expect(page.getByText('评测已通过', { exact: true })).toHaveCount(0)
  } finally { release() }
})

for (const preset of ['ember', 'night'] as const) {
  test(`${preset} 主题的发布检查在 390px 下可读`, async ({ page }, testInfo) => {
    const api = await server(page)
    api.state.value.latestPublication!.targetInstances = ['运行实例名称较长-'.repeat(8)]
    await page.setViewportSize({ width: 390, height: 844 })
    await page.goto('/aiconfig/agent')
    await page.evaluate(async preset => {
      const path = '/src/store/theme.ts'
      const { useThemeStore } = await import(path)
      useThemeStore().selectPreset(preset)
    }, preset)
    await open(page)
    await expect(page.getByText('当前候选已核对', { exact: true })).toBeVisible()
    const drawer = page.getByRole('dialog', { name: '发布检查', exact: true })
    expect(await drawer.evaluate(node => node.scrollWidth <= node.clientWidth + 1)).toBe(true)
    await drawer.screenshot({ path: testInfo.outputPath(`agent-publication-${preset}-390.png`) })
  })
}
