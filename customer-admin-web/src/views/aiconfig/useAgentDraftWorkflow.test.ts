import { createPinia, setActivePinia } from 'pinia'
import { effectScope, reactive, ref, type EffectScope } from 'vue'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { useAuthStore } from '@/store/auth'
import { deleteAgentDraft, getAgentDraft, saveAgentDraft, type AgentDraft } from '@/api/agentDraft'
import { useAgentDraftWorkflow } from './useAgentDraftWorkflow'

const feedback = vi.hoisted(() => ({ confirm: vi.fn(), warning: vi.fn() }))
vi.mock('element-plus/es', () => ({ ElMessageBox: { confirm: feedback.confirm }, ElMessage: { warning: feedback.warning } }))
vi.mock('vue-router', () => ({ onBeforeRouteLeave: vi.fn() }))
vi.mock('vue', async original => {
  const actual = await original<typeof import('vue')>()
  return { ...actual, onMounted: (callback: () => void) => callback(), onBeforeUnmount: actual.onScopeDispose }
})
vi.mock('@/api/agentDraft', () => ({ deleteAgentDraft: vi.fn(), getAgentDraft: vi.fn(), saveAgentDraft: vi.fn() }))
vi.mock('@/api/auth', () => ({ fetchMyPermissions: vi.fn(), logout: vi.fn() }))
vi.mock('@/api/request', () => ({ getRequestErrorMessage: (_error: unknown, fallback: string) => fallback }))

const scopes: EffectScope[] = []
const saved: AgentDraft = { id: 'draft', agentId: null, baseRevision: null, title: '客服', version: 1, updatedAtMs: 10,
  configuration: { agentName: '客服', agentCode: 'service', modelId: 1, capabilities: ['chat'] } }
function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (error: unknown) => void
  const promise = new Promise<T>((done, fail) => { resolve = done; reject = fail })
  return { promise, resolve, reject }
}
function login() {
  useAuthStore().applyLoginResult({ token: 'same-token', nickname: '管理员', forceChangePassword: false,
    approvalStatus: 'APPROVED', approvalRemark: null }, 'admin')
  useAuthStore().permissions = ['agent:view', 'agent:add', 'agent:edit']
}
function setup() {
  const scope = effectScope(); scopes.push(scope)
  const visible = ref(true)
  const form = reactive({ ...saved.configuration! })
  const workflow = scope.run(() => useAgentDraftWorkflow(form, visible, ref(null), ref(false)))!
  workflow.begin(saved)
  return { workflow, form, visible }
}
beforeEach(() => {
  vi.resetAllMocks()
  vi.stubGlobal('window', { addEventListener: vi.fn(), removeEventListener: vi.fn() })
  const storage = new Map<string, string>()
  vi.stubGlobal('localStorage', { getItem: (key: string) => storage.get(key) ?? null,
    setItem: (key: string, value: string) => storage.set(key, value), removeItem: (key: string) => storage.delete(key) })
  setActivePinia(createPinia()); login()
})
afterEach(() => { scopes.splice(0).forEach(scope => scope.stop()); vi.unstubAllGlobals() })

it('同令牌重登使旧保存无效并关闭旧草稿上下文', async () => {
  const response = deferred<AgentDraft>()
  vi.mocked(saveAgentDraft).mockReturnValue(response.promise)
  const { workflow, visible } = setup()
  const pending = workflow.save()
  login()
  response.resolve({ ...saved, version: 2 })
  expect(await pending).toBe(false)
  expect(visible.value).toBe(false)
  expect(workflow.version.value).not.toBe(2)
})

it('旧身份保存失败不能用新登录查询恢复旧草稿', async () => {
  const response = deferred<AgentDraft>()
  vi.mocked(saveAgentDraft).mockReturnValue(response.promise)
  const { workflow } = setup()
  const pending = workflow.save()
  login()
  response.reject(new Error('late failure'))
  expect(await pending).toBe(false)
  expect(getAgentDraft).not.toHaveBeenCalled()
})

it('旧离开确认不能关闭后来打开的编辑目标', async () => {
  const confirmation = deferred<void>()
  feedback.confirm.mockReturnValue(confirmation.promise)
  const { workflow, form, visible } = setup()
  form.agentName = '未保存修改'
  const closing = workflow.close()
  workflow.begin({ ...saved, id: 'new-draft' })
  confirmation.resolve()
  await closing
  expect(visible.value).toBe(true)
})

it('正式保存后的旧清理失败不向新登录提示', async () => {
  const cleanup = deferred<void>()
  vi.mocked(deleteAgentDraft).mockReturnValue(cleanup.promise)
  const { workflow } = setup()
  const pending = workflow.applied()
  login()
  cleanup.reject(new Error('old cleanup failure'))
  await pending
  expect(feedback.warning).not.toHaveBeenCalled()
})

it('当前身份保存成功仍更新版本并保留编辑界面', async () => {
  vi.mocked(saveAgentDraft).mockResolvedValue({ ...saved, version: 2 })
  const { workflow, visible } = setup()
  expect(await workflow.save()).toBe(true)
  expect(workflow.version.value).toBe(2)
  expect(visible.value).toBe(true)
})
