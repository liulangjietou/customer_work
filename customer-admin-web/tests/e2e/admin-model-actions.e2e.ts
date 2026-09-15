import type { Page, Route } from '@playwright/test'
import { expect, test } from './fixtures/adminTestFixture'

const ok = (data: unknown) => ({ code: 0, data })
const permissions = ['model:view', 'model:add', 'model:edit', 'model:delete', 'model:health-test',
  'model-experiment:view', 'model-experiment:create', 'model-experiment:start', 'model-experiment:stop']
const models = [11, 12].map((id, index) => ({
  id, modelName: `验收部署 ${index ? 'B' : 'A'}`, assetId: 21, assetCode: 'acceptance-asset',
  assetName: '验收资产', provider: 'ollama', protocolAdapter: 'ollama', model: 'acceptance-model',
  baseUrl: 'http://localhost:11434', apiKey: '', status: index ? 0 : 1, lifecycleStatus: index ? 'DRAFT' : 'ACTIVE',
  environment: 'DEVELOPMENT', region: 'local', isDefault: false, endpointRevision: 1,
  contextWindow: 8192, maxOutputTokens: 2048, supportsStream: true, supportsTool: true,
  supportsJsonSchema: true, supportsMultimodal: false, certificationRequired: true,
  credential: { status: 'ACTIVE', currentVersion: 1, secretRef: `acceptance-${id}` },
  health: { healthStatus: 'HEALTHY', effectiveHealthStatus: 'HEALTHY' },
}))
const impact = { action: 'DELETE', allowed: true, blockerCount: 0, items: [], references: [] }
const certification = (id: number) => ({ modelId: id, runId: `cert-${id}`, status: 'PASSED',
  effectiveStatus: 'PASSED', passedChecks: 7, failedChecks: 0, checks: [], latencyP95Ms: id,
  validUntil: '2099-01-01T00:00:00', certifiedEndpointRevision: 1, certifiedSecretVersion: 1 })
type Write = { path: string; method: string; data: unknown }

async function setup(page: Page, granted = permissions) {
  const writes: Write[] = []
  await page.route('**/api/auth/permissions', r => r.fulfill({ json: ok(granted) }))
  await page.route(/\/api\/aiconfig\/model(?:\/.*|\?.*)?$/, async route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (request.method() !== 'GET') {
      writes.push({ path, method: request.method(), data: request.postData() ? request.postDataJSON() : null })
      await route.fulfill({ json: ok(path.endsWith('/health-checks') ? { testStatus: 1, latencyMs: 11 } : null) })
      return
    }
    if (path.endsWith('/asset-options')) {
      await route.fulfill({ json: ok([{ id: 21, assetName: '验收资产', assetCode: 'acceptance-asset' }]) })
      return
    }
    if (path === '/api/aiconfig/model') {
      await route.fulfill({ json: ok({ list: models, total: models.length, pageNum: 1, pageSize: 10 }) })
      return
    }
    const id = Number(path.split('/')[4])
    if (/\/model\/\d+$/.test(path)) await route.fulfill({ json: ok(models.find(row => row.id === id)) })
    else if (path.endsWith('/impact')) await route.fulfill({ json: ok(impact) })
    else if (path.endsWith('/health')) await route.fulfill({ json: ok(models[0].health) })
    else if (path.endsWith('/certification')) await route.fulfill({ json: ok(certification(id)) })
    else if (path.endsWith('/health-events') || path.endsWith('/certification-runs')) await route.fulfill({ json: ok([]) })
    else await route.fallback()
  })
  await page.goto('/aiconfig/model')
  await expect(page.getByRole('row').filter({ hasText: '验收部署 A' })).toBeVisible()
  return writes
}

async function identity(page: Page, granted = permissions) {
  await page.evaluate(async permissions => {
    const source = '/src/store/auth.ts'
    const { useAuthStore } = await import(source)
    const auth = useAuthStore()
    auth.applyLoginResult({ token: auth.token, nickname: '后续身份', forceChangePassword: false,
      approvalStatus: 'APPROVED', approvalRemark: null }, 'model-later-user')
    auth.permissions = permissions
  }, granted)
  await settle(page)
}
async function settle(page: Page) {
  await page.evaluate(async () => {
    const paint = () => new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve())))
    await paint()
    await Promise.all(document.getAnimations().filter(a => Number.isFinite(Number(a.effect?.getComputedTiming().endTime)))
      .map(a => a.finished.catch(() => {})))
    await paint()
  })
}
async function release(page: Page, route: Route, data: unknown) {
  const request = route.request()
  const waiting = page.waitForResponse(response => response.request() === request)
  await route.fulfill({ json: ok(data) })
  await (await waiting).finished()
  await settle(page)
}
const row = (page: Page, name = 'A') => page.getByRole('row').filter({ hasText: `验收部署 ${name}` })
const form = (page: Page) => page.getByRole('dialog', { name: '编辑模型部署', exact: true })
const drawer = (page: Page) => page.locator('.el-drawer').filter({ has: page.locator('.governance-drawer') })
const rotationInput = (page: Page) => drawer(page).locator('.el-form-item').filter({ hasText: /^新凭据/ }).locator('input')
async function governance(page: Page, name = 'A') {
  await row(page, name).getByRole('button', { name: '治理详情', exact: true }).click()
  await expect(drawer(page).getByRole('heading', { name: `验收部署 ${name}`, exact: true })).toBeVisible()
}

test('部署停用预检失败保留输入，重试期间锁定正在提交的表单', async ({ page }) => {
  const writes = await setup(page)
  let held: Route | undefined
  await page.route('**/api/aiconfig/model/11/impact?*', route => { held = route })
  await row(page).getByRole('button', { name: '编辑', exact: true }).click()
  await form(page).getByLabel('部署名称', { exact: true }).fill('验收停用名称')
  await form(page).locator('.el-form-item').filter({ hasText: /^部署状态/ }).locator('.el-switch__core').click()
  await form(page).getByRole('button', { name: '保存部署', exact: true }).click()
  await expect.poll(() => Boolean(held)).toBe(true)
  await held!.fulfill({ json: { code: 50000, message: '验收预检暂时失败' } })
  await expect(form(page).getByRole('button', { name: '保存部署', exact: true })).toBeEnabled()
  await expect(form(page).getByLabel('部署名称', { exact: true })).toHaveValue('验收停用名称')
  held = undefined
  await form(page).getByRole('button', { name: '保存部署', exact: true }).click()
  await expect.poll(() => Boolean(held)).toBe(true)
  await expect(form(page).getByLabel('部署名称', { exact: true })).toBeDisabled()
  await release(page, held!, impact)
  await expect(form(page)).not.toBeVisible()
  expect(writes).toHaveLength(1)
  expect(writes[0]).toMatchObject({ path: '/api/aiconfig/model/11', method: 'PUT', data: { status: 0, modelName: '验收停用名称' } })
})

test('关闭停用表单后，旧预检不得提交新打开的另一个部署', async ({ page }) => {
  const writes = await setup(page)
  let held: Route | undefined
  await page.route('**/api/aiconfig/model/11/impact?*', route => { held = route })
  await row(page).getByRole('button', { name: '编辑', exact: true }).click()
  await form(page).locator('.el-form-item').filter({ hasText: /^部署状态/ }).locator('.el-switch__core').click()
  await form(page).getByRole('button', { name: '保存部署', exact: true }).click()
  await expect.poll(() => Boolean(held)).toBe(true)
  await form(page).getByRole('button', { name: '取消', exact: true }).click()
  await row(page, 'B').getByRole('button', { name: '编辑', exact: true }).click()
  await form(page).getByLabel('部署名称', { exact: true }).fill('新表单尚未提交')
  await release(page, held!, impact)
  expect(writes).toEqual([])
  await expect(form(page).getByLabel('部署名称', { exact: true })).toHaveValue('新表单尚未提交')
  await expect(form(page)).toBeVisible()
})

test('删除确认期间重新登录不得以新身份发送旧部署删除', async ({ page }) => {
  const writes = await setup(page)
  await row(page).getByRole('button', { name: '删除', exact: true }).click()
  const confirmation = page.getByRole('dialog', { name: '删除模型部署', exact: true })
  await expect(confirmation).toBeVisible()
  await identity(page)
  if (await confirmation.isVisible()) await confirmation.getByRole('button', { name: '确定', exact: true }).click()
  await settle(page)
  expect(writes).toEqual([])
})

test('旧身份的健康探测成功响应不提示成功或重载新身份列表', async ({ page }) => {
  await setup(page)
  let held: Route | undefined
  await page.route('**/api/aiconfig/model/11/health-checks', route => { held = route })
  await row(page).getByRole('button', { name: '健康探测', exact: true }).click()
  await expect.poll(() => Boolean(held)).toBe(true)
  await identity(page)
  await settle(page)
  let reads = 0
  await page.route('**/api/aiconfig/model?*', route => { reads += 1; return route.fulfill({ json: ok({ list: models, total: 2 }) }) })
  await release(page, held!, { testStatus: 1, latencyMs: 11 })
  await expect(page.locator('.el-message--success')).toHaveCount(0)
  expect(reads).toBe(0)
})

test('治理抽屉旧详情迟到不得覆盖另一个部署的详情', async ({ page }) => {
  await setup(page)
  let held: Route | undefined
  await page.route('**/api/aiconfig/model/11', route => { held = route })
  await row(page).getByRole('button', { name: '治理详情', exact: true }).click()
  await expect.poll(() => Boolean(held)).toBe(true)
  await drawer(page).locator('.el-drawer__close-btn').click()
  await expect(drawer(page)).not.toBeVisible()
  await governance(page, 'B')
  await release(page, held!, models[0])
  await expect(drawer(page).getByRole('heading', { name: '验收部署 B', exact: true })).toBeVisible()
  await expect(drawer(page)).not.toContainText('验收部署 A')
})

test('凭据轮换从预检起锁定输入，失败后保留新值供重试', async ({ page }) => {
  const writes = await setup(page)
  await governance(page)
  await drawer(page).getByRole('tab', { name: '凭据治理', exact: true }).click()
  await rotationInput(page).fill('synthetic-rotation-value')
  let held: Route | undefined
  await page.route('**/api/aiconfig/model/11/impact?*', route => { held = route })
  await drawer(page).getByRole('button', { name: '确认轮换', exact: true }).click()
  await expect.poll(() => Boolean(held)).toBe(true)
  await expect(rotationInput(page)).toBeDisabled()
  await held!.fulfill({ json: { code: 50000, message: '验收轮换预检失败' } })
  await expect(rotationInput(page)).toBeEnabled()
  await expect(rotationInput(page)).toHaveValue('synthetic-rotation-value')
  expect(writes).toEqual([])
})

test('旧轮换预检返回时不得读取新抽屉凭据或写入新部署', async ({ page }) => {
  const writes = await setup(page)
  await governance(page)
  await drawer(page).getByRole('tab', { name: '凭据治理', exact: true }).click()
  await rotationInput(page).fill('old-synthetic-secret')
  let held: Route | undefined
  await page.route('**/api/aiconfig/model/11/impact?*', route => { held = route })
  await drawer(page).getByRole('button', { name: '确认轮换', exact: true }).click()
  await expect.poll(() => Boolean(held)).toBe(true)
  const old = held!
  await drawer(page).locator('.el-drawer__close-btn').click()
  await expect(drawer(page)).not.toBeVisible()
  await governance(page, 'B')
  await drawer(page).getByRole('tab', { name: '凭据治理', exact: true }).click()
  await rotationInput(page).fill('new-unsent-synthetic-secret')
  await release(page, old, impact)
  expect(writes).toEqual([])
  await expect(rotationInput(page)).toHaveValue('new-unsent-synthetic-secret')
})

test('认证激活确认期间重新登录不能提交旧部署', async ({ page }) => {
  const writes = await setup(page)
  await governance(page, 'B')
  await drawer(page).getByRole('tab', { name: '上线认证', exact: true }).click()
  await drawer(page).getByRole('button', { name: '激活部署', exact: true }).click()
  const confirmation = page.getByRole('dialog', { name: '激活模型部署', exact: true })
  await expect(confirmation).toBeVisible()
  await identity(page)
  if (await confirmation.isVisible()) await confirmation.getByRole('button', { name: '确定', exact: true }).click()
  await settle(page)
  expect(writes).toEqual([])
  await expect(drawer(page)).not.toBeVisible()
})

const policies = [31, 32].map((id, index) => ({ id, policyCode: `acceptance-${id}`,
  policyName: `验收路由 ${index ? 'B' : 'A'}`, description: '验收用途', status: 'ACTIVE',
  currentVersionNo: 1, latestVersionNo: 2, currentVersion: null, updateTime: null }))
const version = { id: 41, versionNo: 2, status: 'DRAFT', contentHash: 'acceptance',
  changeNote: '验收版本', rules: [], createTime: null, activatedAt: null }
async function routing(page: Page) {
  await page.route('**/api/aiconfig/model-routing-policies', route => route.fulfill({ json: ok(policies) }))
  await page.route('**/api/aiconfig/model-routing-policies/*/versions', route => route.fulfill({ json: ok([version]) }))
  await page.getByRole('tab', { name: '路由策略', exact: true }).click()
}
const routeRow = (page: Page, name = 'A') => page.getByRole('row').filter({ hasText: `验收路由 ${name}` })

test('路由列表读取失败有明确重试，恢复后显示策略', async ({ page }) => {
  await setup(page)
  let failed = true
  await page.route('**/api/aiconfig/model-routing-policies', route => route.fulfill({ json: failed
    ? { code: 50000, message: '验收路由查询失败' } : ok(policies) }))
  await page.getByRole('tab', { name: '路由策略', exact: true }).click()
  const panel = page.getByRole('tabpanel', { name: '路由策略', exact: true })
  await expect(panel.getByRole('button', { name: /重新加载|重试/ })).toBeVisible()
  failed = false
  await panel.getByRole('button', { name: /重新加载|重试/ }).click()
  await expect(routeRow(page)).toBeVisible()
})

test('路由版本激活确认期间身份改变不得发送激活请求', async ({ page }) => {
  await setup(page)
  await routing(page)
  const writes: string[] = []
  await page.route('**/api/aiconfig/model-routing-policies/*/versions/*/activate', route => {
    writes.push(route.request().url()); return route.fulfill({ json: ok(version) })
  })
  await routeRow(page).getByRole('button', { name: '版本与规则', exact: true }).click()
  await page.getByRole('button', { name: '激活版本', exact: true }).click()
  const confirmation = page.getByRole('dialog', { name: '激活路由版本', exact: true })
  await expect(confirmation).toBeVisible()
  await identity(page)
  if (await confirmation.isVisible()) await confirmation.getByRole('button', { name: '确定', exact: true }).click()
  await settle(page)
  expect(writes).toEqual([])
})

test('旧路由试运行响应不得进入新打开的策略表单', async ({ page }) => {
  await setup(page)
  await routing(page)
  let held: Route | undefined
  await page.route('**/api/aiconfig/model-routing-policies/31/dry-run', route => { held = route })
  await routeRow(page).getByRole('button', { name: 'Dry-run', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '路由 Dry-run', exact: true })
  await dialog.getByRole('button', { name: '执行 Dry-run', exact: true }).click()
  await expect.poll(() => Boolean(held)).toBe(true)
  await dialog.locator('.el-dialog__headerbtn').click()
  await expect(dialog).not.toBeVisible()
  await routeRow(page, 'B').getByRole('button', { name: 'Dry-run', exact: true }).click()
  await dialog.getByLabel('渠道编码', { exact: true }).fill('new-channel')
  await release(page, held!, { failClosed: false, deploymentName: '旧路由验收结果', purpose: 'DEFAULT',
    explanation: '属于原策略', candidates: [] })
  await expect(dialog).not.toContainText('旧路由验收结果')
  await expect(dialog.getByLabel('渠道编码', { exact: true })).toHaveValue('new-channel')
})

const experiments = [51, 52].map((id, index) => ({ id, experimentCode: `exp-${id}`,
  experimentName: `验收实验 ${index ? 'B' : 'A'}`, agentId: 7, controlDeploymentId: 11,
  treatmentDeploymentId: 12, controlModelRef: 'acceptance-a', treatmentModelRef: 'acceptance-b',
  controlEndpointRevision: 1, treatmentEndpointRevision: 1, revision: 1, treatmentBps: 1000,
  datasetReleaseId: 'acceptance-release', datasetVersionName: '验收数据集', judgeModelRef: 'acceptance-judge',
  judgeEndpointRevision: 1, offlineEvalStatus: 'NOT_STARTED', status: index ? 'RUNNING' : 'DRAFT',
  effectiveState: index ? 'ACTIVE' : 'INACTIVE', minSample: 100, maxErrorRate: 0.05, maxP95LatencyMs: 3000 }))
async function experimentPanel(page: Page) {
  await page.route('**/api/aiconfig/model-experiments*', route => route.fulfill({ json: ok(experiments) }))
  await page.route('**/api/aiconfig/model-experiments/*/events', route => route.fulfill({ json: ok([]) }))
  await page.route('**/api/aiconfig/model-experiments/*/arm-evaluations', route => route.fulfill({ json: ok([]) }))
  await page.route('**/api/aiconfig/model-experiments/*/metrics', route => route.fulfill({ json: ok({
    availability: 'READY', samples: 17, errorRate: 0, p95LatencyMs: 52,
  }) }))
  await page.getByRole('tab', { name: '认证与实验', exact: true }).click()
}
const experimentRow = (page: Page, name = 'A') => page.getByRole('row').filter({ hasText: `验收实验 ${name}` })

for (const action of ['启动', '停止'] as const) {
  test(`实验${action}确认期间重新登录不能发送旧实验操作`, async ({ page }) => {
    await setup(page)
    await experimentPanel(page)
    const writes: string[] = []
    await page.route(`**/api/aiconfig/model-experiments/*/${action === '启动' ? 'start' : 'stop'}`, route => {
      writes.push(route.request().url()); return route.fulfill({ json: ok(experiments[0]) })
    })
    await experimentRow(page, action === '启动' ? 'A' : 'B').getByRole('button', { name: action, exact: true }).click()
    const confirmation = page.getByRole('dialog', { name: `${action}实验`, exact: true })
    await expect(confirmation).toBeVisible()
    if (action === '停止') await confirmation.getByRole('textbox').fill('验收停止原因')
    await identity(page)
    if (await confirmation.isVisible()) await confirmation.getByRole('button', { name: '确定', exact: true }).click()
    await settle(page)
    expect(writes).toEqual([])
  })
}

test('实验详情切换后不能混入上一个实验的迟到指标', async ({ page }) => {
  await setup(page)
  await experimentPanel(page)
  let held: Route | undefined
  await page.route('**/api/aiconfig/model-experiments/51/metrics', route => { held = route })
  await experimentRow(page).getByRole('button', { name: '指标与事件', exact: true }).click()
  await expect.poll(() => Boolean(held)).toBe(true)
  const detail = page.getByRole('dialog', { name: '实验指标与事件', exact: true })
  await detail.locator('.el-drawer__close-btn').click()
  await expect(detail).not.toBeVisible()
  await experimentRow(page, 'B').getByRole('button', { name: '指标与事件', exact: true }).click()
  await expect(detail).toContainText('52 ms')
  await release(page, held!, { availability: 'READY', samples: 999, errorRate: 0, p95LatencyMs: 951 })
  await expect(detail).toContainText('验收实验 B')
  await expect(detail).toContainText('52 ms')
  await expect(detail).not.toContainText('951 ms')
})


test('本地 Ollama 部署按照页面说明允许不填写凭据创建', async ({ page }) => {
  const writes = await setup(page)
  await page.getByRole('button', { name: '新建部署', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '新建模型部署', exact: true })
  await dialog.getByLabel('部署名称', { exact: true }).fill('验收本地部署')
  await dialog.locator('.el-form-item').filter({ hasText: '接入协议' }).locator('.el-select').click()
  await page.getByRole('option', { name: 'Ollama（本地部署）', exact: true }).click()
  await dialog.getByLabel('模型标识', { exact: true }).fill('acceptance-local-model')
  await dialog.getByLabel('Base URL', { exact: true }).fill('http://localhost:11434')
  await expect(dialog).toContainText('凭据留空即可')
  await dialog.getByRole('button', { name: '保存部署', exact: true }).click()
  await expect(dialog).not.toBeVisible()
  expect(writes).toHaveLength(1)
  expect(writes[0]).toMatchObject({ method: 'POST', path: '/api/aiconfig/model',
    data: { provider: 'ollama', apiKey: '', modelName: '验收本地部署' } })
})

const editableVersion = { ...version, rules: [{ purpose: 'DEFAULT', deploymentId: 11, priority: 100,
  condition: { agentIds: [], channelCodes: [], minInputTokens: null, maxInputTokens: null,
    requiresTools: null, requiresStructuredOutput: null, complexity: null } }] }
async function editableRouting(page: Page) {
  await routing(page)
  const writes: string[] = []
  await page.route('**/api/aiconfig/model-routing-policies/*/versions', route => {
    if (route.request().method() === 'GET') return route.fulfill({ json: ok([editableVersion]) })
    writes.push(new URL(route.request().url()).pathname)
    return route.fulfill({ json: ok(editableVersion) })
  })
  return writes
}
const routeEditor = (page: Page, name = 'A') => page.getByRole('dialog', { name: `创建 验收路由 ${name} 的新版本`, exact: true })

test('路由版本预检从开始锁定输入，失败后保留版本说明供重试', async ({ page }) => {
  await setup(page)
  const writes = await editableRouting(page)
  let held: Route | undefined
  await page.route('**/api/aiconfig/model-routing-policies/31/versions/validate', route => { held = route })
  await routeRow(page).getByRole('button', { name: '新版本', exact: true }).click()
  await routeEditor(page).getByLabel('版本变更说明', { exact: true }).fill('保留验收版本说明')
  await routeEditor(page).getByRole('button', { name: '创建不可变版本', exact: true }).click()
  await expect.poll(() => Boolean(held)).toBe(true)
  await held!.fulfill({ json: { code: 50000, message: '验收路由预检失败' } })
  await settle(page)
  await expect(routeEditor(page).getByLabel('版本变更说明', { exact: true })).toHaveValue('保留验收版本说明')
  held = undefined
  await routeEditor(page).getByRole('button', { name: '创建不可变版本', exact: true }).click()
  await expect.poll(() => Boolean(held)).toBe(true)
  await expect(routeEditor(page).getByLabel('版本变更说明', { exact: true })).toBeDisabled()
  await release(page, held!, { valid: true, conflicts: [] })
  await expect(routeEditor(page)).not.toBeVisible()
  expect(writes).toEqual(['/api/aiconfig/model-routing-policies/31/versions'])
})

test('路由旧版本预检完成不能把新打开策略草稿提交出去', async ({ page }) => {
  await setup(page)
  const writes = await editableRouting(page)
  let held: Route | undefined
  await page.route('**/api/aiconfig/model-routing-policies/31/versions/validate', route => { held = route })
  await routeRow(page).getByRole('button', { name: '新版本', exact: true }).click()
  await routeEditor(page).getByRole('button', { name: '创建不可变版本', exact: true }).click()
  await expect.poll(() => Boolean(held)).toBe(true)
  await routeEditor(page).getByRole('button', { name: '取消', exact: true }).click()
  await routeRow(page, 'B').getByRole('button', { name: '新版本', exact: true }).click()
  await routeEditor(page, 'B').getByLabel('版本变更说明', { exact: true }).fill('尚未提交的新策略说明')
  await release(page, held!, { valid: true, conflicts: [] })
  expect(writes).toEqual([])
  await expect(routeEditor(page, 'B').getByLabel('版本变更说明', { exact: true })).toHaveValue('尚未提交的新策略说明')
})

const createPermissions = [...permissions, 'agent:view', 'eval:view']
const approvedDataset = { releaseId: 'acceptance-release', versionName: '验收已审核数据集',
  contentHash: 'acceptance-dataset-hash', caseCount: 1, status: 'APPROVED' }
async function experimentOptions(page: Page) {
  await page.route('**/api/aiconfig/agent?*', route => route.fulfill({ json: ok({ list: [{ id: 7, agentName: '验收智能体', status: 1 }], total: 1 }) }))
  await page.route('**/api/aiconfig/model?*', route => route.fulfill({ json: ok({ list: models.map(model => ({ ...model, status: 1, lifecycleStatus: 'ACTIVE' })), total: 2 }) }))
  await page.route('**/api/eval/datasets/QUALITY/versions', route => route.fulfill({ json: ok([approvedDataset]) }))
}
const experimentForm = (page: Page) => page.getByRole('dialog', { name: '新建不可变双臂实验', exact: true })

test('实验选项查询迟到不能在重新登录后打开旧创建表单', async ({ page }) => {
  await setup(page, createPermissions)
  await experimentPanel(page)
  await experimentOptions(page)
  let held: Route | undefined
  await page.route('**/api/aiconfig/agent?*', route => { held = route })
  await page.getByRole('button', { name: '新建双臂实验', exact: true }).click()
  await expect.poll(() => Boolean(held)).toBe(true)
  await identity(page, createPermissions)
  await release(page, held!, { list: [{ id: 7, agentName: '旧身份智能体', status: 1 }], total: 1 })
  await expect(experimentForm(page)).not.toBeVisible()
})

test('实验创建失败保留所选部署与数据集，重试期间锁定输入', async ({ page }) => {
  await setup(page, createPermissions)
  await experimentPanel(page)
  await experimentOptions(page)
  let held: Route | undefined
  const payloads: unknown[] = []
  await page.route('**/api/aiconfig/model-experiments', route => {
    if (route.request().method() === 'GET') return route.fulfill({ json: ok(experiments) })
    payloads.push(route.request().postDataJSON()); held = route
  })
  await page.getByRole('button', { name: '新建双臂实验', exact: true }).click()
  await experimentForm(page).getByLabel('实验名称', { exact: true }).fill('验收新实验')
  await experimentForm(page).getByRole('button', { name: '创建草稿', exact: true }).click()
  await expect.poll(() => Boolean(held)).toBe(true)
  await held!.fulfill({ json: { code: 50000, message: '验收实验创建失败' } })
  await settle(page)
  await expect(experimentForm(page).getByLabel('实验名称', { exact: true })).toHaveValue('验收新实验')
  held = undefined
  await experimentForm(page).getByRole('button', { name: '创建草稿', exact: true }).click()
  await expect.poll(() => Boolean(held)).toBe(true)
  await expect(experimentForm(page).getByLabel('实验名称', { exact: true })).toBeDisabled()
  await release(page, held!, experiments[0])
  await expect(experimentForm(page)).not.toBeVisible()
  expect(payloads).toHaveLength(2)
  expect(payloads[1]).toMatchObject({ experimentName: '验收新实验', controlDeploymentId: 11,
    treatmentDeploymentId: 12, datasetReleaseId: 'acceptance-release' })
})

// 正向提交与只读边界补充原有异步竞争用例，所有写请求只到显式登记的合成接口。
test('治理详情读取失败可重试，恢复完整部署快照', async ({ page }) => {
  await setup(page)
  let failed = true
  await page.route('**/api/aiconfig/model/11', r => r.fulfill({ json: failed
    ? { code: 50000, message: '验收详情暂时失败' } : ok(models[0]) }))
  await row(page).getByRole('button', { name: '治理详情', exact: true }).click()
  const retry = drawer(page).getByRole('button', { name: '重新加载', exact: true })
  await expect(retry).toBeVisible()
  failed = false
  await retry.click()
  await expect(drawer(page).getByRole('heading', { name: '验收部署 A', exact: true })).toBeVisible()
  await expect(drawer(page)).toContainText('acceptance-asset')
  await expect(retry).not.toBeVisible()
})

test('认证失败保留门槛，重试锁定输入并显示当前认证证据', async ({ page }) => {
  await setup(page, [...permissions, 'model:certify'])
  await governance(page)
  await drawer(page).getByRole('tab', { name: '上线认证', exact: true }).click()
  const input = drawer(page).getByLabel('最低上下文 Token', { exact: true })
  await input.fill('4096')
  const payloads: unknown[] = []
  let held: Route | undefined
  await page.route('**/api/aiconfig/model/11/certifications', r => {
    payloads.push(r.request().postDataJSON())
    if (payloads.length === 1) return r.fulfill({ json: { code: 50000, message: '验收认证暂时失败' } })
    held = r
  })
  const submit = drawer(page).getByRole('button', { name: '运行上线认证', exact: true })
  await submit.click()
  await expect(submit).toBeEnabled()
  await expect(input).toHaveValue('4096')
  await submit.click()
  await expect.poll(() => Boolean(held)).toBe(true)
  await expect(input).toBeDisabled()
  await release(page, held!, certification(11))
  await expect(submit).toBeEnabled()
  expect(payloads).toHaveLength(2)
  expect(payloads[1]).toMatchObject({ requiredContextTokens: 4096 })
  await expect(drawer(page).locator('.cert-hero')).toContainText('PASSED')
})

test('路由版本正常创建与激活只写入当前策略和版本', async ({ page }) => {
  await setup(page)
  const writes = await editableRouting(page)
  await routeRow(page).getByRole('button', { name: '新版本', exact: true }).click()
  await routeEditor(page).getByLabel('版本变更说明', { exact: true }).fill('已核对的路由版本')
  await page.route('**/api/aiconfig/model-routing-policies/31/versions/validate', r => r.fulfill({ json: ok({ valid: true, conflicts: [] }) }))
  await routeEditor(page).getByRole('button', { name: '创建不可变版本', exact: true }).click()
  await expect(routeEditor(page)).not.toBeVisible()
  expect(writes).toEqual(['/api/aiconfig/model-routing-policies/31/versions'])
  let active = false
  await page.route('**/api/aiconfig/model-routing-policies/31/versions/41/activate', r => {
    active = true; return r.fulfill({ json: ok({ ...editableVersion, status: 'ACTIVE' }) })
  })
  await page.route('**/api/aiconfig/model-routing-policies/31/versions', r => r.fulfill({
    json: ok([{ ...editableVersion, status: active ? 'ACTIVE' : 'DRAFT' }]),
  }))
  await page.getByRole('button', { name: '激活版本', exact: true }).click()
  await page.getByRole('dialog', { name: '激活路由版本', exact: true }).getByRole('button', { name: '确定', exact: true }).click()
  await expect.poll(() => active).toBe(true)
  await expect(page.locator('.version-card')).toContainText('ACTIVE')
  await expect(page.getByRole('button', { name: '激活版本', exact: true })).not.toBeVisible()
})

test('停止实验失败保留原因，重试锁定输入并显示撤流状态', async ({ page }) => {
  await setup(page)
  await experimentPanel(page)
  const payloads: unknown[] = []
  let held: Route | undefined
  await page.route('**/api/aiconfig/model-experiments/52/stop', r => {
    payloads.push(r.request().postDataJSON())
    if (payloads.length === 1) return r.fulfill({ json: { code: 50000, message: '验收撤流暂时失败' } })
    held = r
  })
  await experimentRow(page, 'B').getByRole('button', { name: '停止', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '停止实验', exact: true })
  await dialog.getByRole('textbox').fill('已核对，停止验收实验')
  await dialog.getByRole('button', { name: '确定', exact: true }).click()
  await expect(dialog.getByRole('button', { name: '确定', exact: true })).toBeEnabled()
  await expect(dialog.getByRole('textbox')).toHaveValue('已核对，停止验收实验')
  await dialog.getByRole('button', { name: '确定', exact: true }).click()
  await expect.poll(() => Boolean(held)).toBe(true)
  await expect(dialog.getByRole('textbox')).toBeDisabled()
  await page.route('**/api/aiconfig/model-experiments*', r => r.fulfill({ json: ok([
    experiments[0], { ...experiments[1], status: 'STOPPED', effectiveState: 'DEACTIVATING' },
  ]) }))
  await release(page, held!, { ...experiments[1], status: 'STOPPED', effectiveState: 'DEACTIVATING' })
  await expect(dialog).not.toBeVisible()
  expect(payloads).toEqual([{ reason: '已核对，停止验收实验' }, { reason: '已核对，停止验收实验' }])
  await expect(experimentRow(page, 'B')).toContainText('DEACTIVATING')
})

test('只读模型可查看治理路由实验，缺少选项权限不查询受限资源', async ({ page }) => {
  const granted = ['model:view', 'model-experiment:view', 'model-experiment:create']
  const writes = await setup(page, granted)
  const restricted: string[] = []
  page.on('request', r => {
    const path = new URL(r.url()).pathname
    if (path === '/api/aiconfig/agent' || path.startsWith('/api/eval/')) restricted.push(path)
  })
  await governance(page)
  await drawer(page).getByRole('tab', { name: '凭据治理', exact: true }).click()
  await expect(rotationInput(page)).toBeDisabled()
  await expect(drawer(page).getByRole('button', { name: '确认轮换', exact: true })).not.toBeVisible()
  await drawer(page).locator('.el-drawer__close-btn').click()
  await routing(page)
  await expect(routeRow(page)).toBeVisible()
  await expect(routeRow(page).getByRole('button', { name: '新版本', exact: true })).not.toBeVisible()
  await experimentPanel(page)
  await expect(experimentRow(page)).toBeVisible()
  await expect(page.getByRole('button', { name: '新建双臂实验', exact: true })).toBeDisabled()
  await expect(page.getByText('创建实验需要智能体、模型和评测数据的查看权限，请联系管理员配置。', { exact: true })).toBeVisible()
  expect(restricted).toEqual([])
  expect(writes).toEqual([])
})

for (const theme of ['ocean', 'night'] as const) {
  test(`模型表单在 ${theme} 窄屏保留输入与操作区`, async ({ page }, testInfo) => {
    test.setTimeout(60_000)
    await page.addInitScript(theme => localStorage.setItem('customer-admin-theme-selection',
      JSON.stringify({ version: 1, kind: 'preset', id: theme })), theme)
    await page.setViewportSize({ width: 390, height: 760 })
    await setup(page)
    if (theme === 'ocean') {
      await governance(page)
      await drawer(page).getByRole('tab', { name: '凭据治理', exact: true }).click()
      await rotationInput(page).fill('synthetic-review-secret')
      const submit = drawer(page).getByRole('button', { name: '确认轮换', exact: true })
      await submit.scrollIntoViewIfNeeded()
      await expect(submit).toBeVisible()
      expect((await rotationInput(page).boundingBox())!.width).toBeGreaterThan(160)
    } else {
      await editableRouting(page)
      await routeRow(page).getByRole('button', { name: '新版本', exact: true }).click()
      await routeEditor(page).getByLabel('版本变更说明', { exact: true }).fill('核对后再发布流量策略')
      const submit = routeEditor(page).getByRole('button', { name: '创建不可变版本', exact: true })
      await submit.scrollIntoViewIfNeeded()
      await expect(submit).toBeVisible()
      const bounds = await routeEditor(page).locator('.el-dialog').evaluate(el => ({ width: el.clientWidth, scroll: el.scrollWidth }))
      expect(bounds.scroll).toBeLessThanOrEqual(bounds.width + 1)
    }
    await settle(page)
    await expect(page.locator('html')).toHaveAttribute('data-theme', theme)
    await expect(page.locator('.el-message')).toHaveCount(0)
    await page.screenshot({ path: testInfo.outputPath(`model-${theme}-390.png`), animations: 'disabled' })
  })
}

for (const changeIdentity of [false, true]) {
  test(`健康覆盖确认${changeIdentity ? '换身份后不提交' : '写入当前部署并可恢复自动'}`, async ({ page }) => {
    const granted = [...permissions, 'model:health-override']
    await setup(page, granted)
    let health = { ...models[0].health, overrideMode: 'AUTO', overrideReason: '', overrideUntil: null as string | null }
    const writes: Array<{ mode: string; reason: string; expiresAt: string | null }> = []
    await page.route('**/api/aiconfig/model/11/health', r => r.fulfill({ json: ok(health) }))
    await page.route('**/api/aiconfig/model/11/health-override', r => {
      const value = r.request().postDataJSON()
      writes.push(value)
      health = { ...health, overrideMode: value.mode, overrideReason: value.reason, overrideUntil: value.expiresAt }
      return r.fulfill({ json: ok(health) })
    })
    await governance(page)
    await drawer(page).getByRole('tab', { name: '健康历史', exact: true }).click()
    await drawer(page).getByLabel('原因', { exact: true }).fill('验收维护窗口')
    const expires = drawer(page).getByLabel('到期时间', { exact: true })
    await expires.fill('2099-01-01 00:00:00')
    await expires.press('Tab')
    await drawer(page).getByRole('button', { name: '应用覆盖', exact: true }).click()
    const confirmation = page.getByRole('dialog', { name: '模型健康路由覆盖', exact: true })
    await expect(confirmation).toBeVisible()
    if (changeIdentity) await identity(page, granted)
    if (await confirmation.isVisible()) await confirmation.getByRole('button', { name: '确定', exact: true }).click()
    await settle(page)
    if (changeIdentity) {
      expect(writes).toEqual([])
      await expect(drawer(page)).not.toBeVisible()
    } else {
      await expect(drawer(page)).toContainText('验收维护窗口')
      expect(writes).toHaveLength(1)
      expect(writes[0]).toMatchObject({ mode: 'FORCE_UNHEALTHY', reason: '验收维护窗口' })
      await drawer(page).getByRole('button', { name: '恢复自动', exact: true }).click()
      await expect(drawer(page).getByRole('button', { name: '恢复自动', exact: true })).not.toBeVisible()
      expect(writes).toHaveLength(2)
      expect(writes[1]).toMatchObject({ mode: 'AUTO', reason: '清除人工健康路由覆盖', expiresAt: null })
    }
  })
}

test('实验正常启动显示激活中并保留后端生效状态', async ({ page }) => {
  await setup(page)
  await experimentPanel(page)
  let started = false
  await page.route('**/api/aiconfig/model-experiments/51/start', r => {
    started = true; return r.fulfill({ json: ok({ ...experiments[0], status: 'RUNNING', effectiveState: 'ACTIVATING' }) })
  })
  await page.route('**/api/aiconfig/model-experiments*', r => r.fulfill({ json: ok([
    { ...experiments[0], status: started ? 'RUNNING' : 'DRAFT', effectiveState: started ? 'ACTIVATING' : 'INACTIVE' }, experiments[1],
  ]) }))
  await experimentRow(page).getByRole('button', { name: '启动', exact: true }).click()
  await page.getByRole('dialog', { name: '启动实验', exact: true }).getByRole('button', { name: '确定', exact: true }).click()
  await expect(experimentRow(page)).toContainText('ACTIVATING')
  expect(started).toBe(true)
  await expect(experimentRow(page).getByRole('button', { name: '启动', exact: true })).not.toBeVisible()
})
