<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch, type Ref } from 'vue'
import { useRouter } from 'vue-router'
import { buildNavigationCommands, buildNavigationSections } from '@/layouts/navigationModel'
import type { FormInstance } from 'element-plus'
import { Loading } from '@element-plus/icons-vue'
import { usePagedList } from '@/composables/usePagedList'
import CrudLoadState from '@/components/CrudLoadState.vue'
import {
  clearAgentMemory,
  createAgent,
  deleteAgent,
  disableAgent,
  enableAgent,
  getAgentMemory,
  getAgent,
  pageAgents,
  updateAgent,
} from '@/api/agent'
import { listModelRoutePolicies, pageModels, testModelConnectivity } from '@/api/model'
import { pageMcps } from '@/api/mcp'
import { pageSkills } from '@/api/skill'
import { fetchSystemTools } from '@/api/system-tool'
import { fetchKnowledgeBaseOptions } from '@/api/knowledgeBase'
import { useMenuStore } from '@/store/menu'
import IconPicker from '@/components/IconPicker.vue'
import ChannelBindingDrawer from '@/views/aiconfig/ChannelBindingDrawer.vue'
import AgentActions from './components/AgentActions.vue'
import AgentDraftDrawer from './components/AgentDraftDrawer.vue'
import AgentScenarioTemplates, { type AgentScenario } from './components/AgentScenarioTemplates.vue'
import { useAgentDraftWorkflow } from './useAgentDraftWorkflow'
import type { AgentDraft } from '@/api/agentDraft'
import { useAuthStore } from '@/store/auth'
import { getRequestErrorMessage } from '@/api/request'
import AgentOverviewCard from './components/AgentOverviewCard.vue'
import type {
  AgentSaveRequest,
  AgentVO,
  KnowledgeBaseOption,
  McpVO,
  ModelRoutePolicy,
  ModelVO,
  PageQuery,
  SkillVO,
  SystemToolVO,
} from '@/types/api'

const menuStore = useMenuStore()
const auth = useAuthStore()
const draftDrawerVisible = ref(false)
const scenarioChecks = ref<string[]>([])
const restoreConflict = ref('')
const submitError = ref('')
const templatesVisible = ref(true)
const optionsLoading = ref(false)
const optionsError = ref('')
let editorGeneration = 0
let optionsRequest = 0
const canManageDrafts = computed(
  () => auth.hasPermission('agent:add') || auth.hasPermission('agent:edit'),
)
const router = useRouter()
const workspacePaths = computed(
  () =>
    new Set(
      buildNavigationCommands(buildNavigationSections(menuStore.tree))
        .filter((entry) => entry.dynamic)
        .map((entry) => entry.path),
    ),
)
function openWorkspace(row: AgentVO) {
  router.push(`/workspace/${row.agentCode}`)
}
function scrollToSection(id: string) {
  document.getElementById(id)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

// 渠道绑定抽屉：后台菜单为动态 DB 驱动，不新增菜单种子，改为在本页复用 agent 权限入口打开。
const channelBindingVisible = ref(false)
const displayMode = ref<'cards' | 'table'>('cards')

const saving = ref(false)
const { loading, loadError, list, total, query, loadList, handleSearch } = usePagedList<
  AgentVO,
  PageQuery
>({
  initQuery: () => ({ pageNum: 1, pageSize: 10, keyword: '' }),
  page: pageAgents,
})

const modelOptions = ref<ModelVO[]>([])
const routePolicyOptions = ref<ModelRoutePolicy[]>([])
const mcpOptions = ref<McpVO[]>([])
const skillOptions = ref<SkillVO[]>([])
const systemToolOptions = ref<SystemToolVO[]>([])
// 知识库选项：/options 接口已过滤"启用且测试通过"，无需前端二次筛选
const knowledgeBaseOptions = ref<KnowledgeBaseOption[]>([])

function skillName(skillId: number) {
  return skillOptions.value.find((skill) => skill.id === skillId)?.skillName ?? `Skill ${skillId}`
}
// 智能体选项复用于「子Agent协作」多选，无独立全量接口，拉大页分页兜底
const AGENT_OPTION_PAGE_SIZE = 200
const agentOptions = ref<AgentVO[]>([])

// 能力编码：value 与后端 capabilities 字段一一对应，tip 为勾选提示
const CAPABILITY_SUBAGENT = 'subagent'
const CAPABILITY_MEMORY = 'memory'
const CAPABILITY_OPTIONS: { value: string; label: string; tip?: string }[] = [
  { value: 'chat', label: 'chat（对话）' },
  { value: 'vibecoding', label: 'vibecoding（代码生成）' },
  {
    value: CAPABILITY_SUBAGENT,
    label: '子Agent协作',
    tip: '允许使用子 Agent 协作',
  },
  { value: 'plan', label: '计划模式', tip: '支持多步骤计划推演' },
  { value: 'tasklist', label: '任务列表', tip: '跟踪和维护任务列表' },
  {
    value: 'skill-learning',
    label: '学习新技能',
    tip: '与用户互动学习并沉淀新技能',
  },
  {
    value: 'dynamic-subagent',
    label: '动态子Agent',
    tip: '运行时按任务临时创建子 Agent，无需预先配置',
  },
  {
    value: CAPABILITY_MEMORY,
    label: '长期记忆',
    tip: '跨会话记住对话中的关键事实，自动沉淀与归并',
  },
]

function capabilityLabel(code: string) {
  return CAPABILITY_OPTIONS.find((o) => o.value === code)?.label ?? code
}

// 高级参数取值范围，需与后端保持一致
const MAX_ITERS_RANGE = { min: 1, max: 100 }
const TOOL_TIMEOUT_SECONDS_RANGE = { min: 1, max: 3600 }
const TOOL_MAX_ATTEMPTS_RANGE = { min: 1, max: 10 }
const COMPRESS_TRIGGER_MSGS_RANGE = { min: 2, max: 1000 }
const COMPRESS_KEEP_MSGS_RANGE = { min: 0, max: 500 }

const editorVisible = ref(false)
const editorMode = ref<'create' | 'edit'>('create')
const formRef = ref<FormInstance>()
const editingId = ref<number | null>(null)
const form = reactive<AgentSaveRequest>({
  agentName: '',
  agentCode: '',
  modelId: undefined as unknown as number,
  backupModelIds: [],
  mcpIds: [],
  skillIds: [],
  systemToolIds: [],
  knowledgeBaseIds: [],
  modelRoutePolicyId: null,
  systemPrompt: '',
  capabilities: ['chat'],
  icon: '',
  status: 1,
  subAgentIds: [],
  maxIters: null,
  toolTimeoutSeconds: null,
  toolMaxAttempts: null,
  compressTriggerMsgs: null,
  compressKeepMsgs: null,
})

const draftWorkflow = useAgentDraftWorkflow(form, editorVisible, editingId, saving)
const {
  saving: draftSaving,
  error: draftError,
  dirty: draftDirty,
  updatedAtMs: draftSavedAt,
} = draftWorkflow

const agentCodePattern = /^[a-z0-9-]+$/

// ---------- 主模型连通性门禁 ----------
// 状态机：untested（未选/未测）→ testing（测试中）→ passed（通过，可提交）/ failed（失败，需重测）。
// 编辑态打开弹窗时，若主模型未被改动则直接视为 passed（复用后端已验证过的状态），一旦切换主模型必须重新测试通过。
type PrimaryTestState = 'untested' | 'testing' | 'passed' | 'failed'
const primaryTestState = ref<PrimaryTestState>('untested')
const primaryTestMessage = ref<string | null>(null)
const originalModelId = ref<number | null>(null)
let primaryTestRequest = 0

// 验证结果属于一次表单操作；切模型、关闭或切换编辑对象时立即使旧请求失效。
watch(
  [() => form.modelId, editorVisible, editorMode, editingId],
  () => {
    primaryTestRequest += 1
  },
  { flush: 'sync' },
)
onBeforeUnmount(() => {
  primaryTestRequest += 1
  optionsRequest += 1
  editorGeneration += 1
})

const enabledModelOptions = computed(() => modelOptions.value.filter((m) => m.status === 1))
// 备用模型候选需排除当前已选的主模型，避免主备重复。
const backupModelOptions = computed(() =>
  enabledModelOptions.value.filter((m) => m.id !== form.modelId),
)
const canSubmit = computed(() => primaryTestState.value === 'passed' && !restoreConflict.value)

async function runPrimaryModelTest(modelId: number) {
  if (!auth.hasPermission('model:edit')) {
    primaryTestState.value = 'untested'
    return
  }
  const requestId = ++primaryTestRequest
  primaryTestState.value = 'testing'
  primaryTestMessage.value = null
  try {
    const result = await testModelConnectivity(modelId, {
      suppressErrorMessage: true,
    })
    if (requestId !== primaryTestRequest) return
    if (result.testStatus === 1) {
      primaryTestState.value = 'passed'
    } else {
      primaryTestState.value = 'failed'
      primaryTestMessage.value = result.message || '连通性测试失败'
    }
  } catch {
    if (requestId !== primaryTestRequest) return
    primaryTestState.value = 'failed'
    primaryTestMessage.value = '连通性测试请求异常'
  }
}

function handlePrimaryModelChange(modelId: number | undefined) {
  // 主模型切换后，备用模型里若含新主模型需自动移除
  if (modelId != null && form.backupModelIds?.includes(modelId)) {
    form.backupModelIds = form.backupModelIds.filter((id) => id !== modelId)
  }
  if (modelId == null) {
    primaryTestState.value = 'untested'
    primaryTestMessage.value = null
    return
  }
  if (editorMode.value === 'edit' && modelId === originalModelId.value) {
    primaryTestState.value = 'passed'
    primaryTestMessage.value = null
    return
  }
  runPrimaryModelTest(modelId)
}

function handleRetestPrimaryModel() {
  if (form.modelId != null) {
    runPrimaryModelTest(form.modelId)
  }
}

// 仅勾选「子Agent协作」时才展示子Agent选择框
const showSubAgentSelect = computed(() => form.capabilities?.includes(CAPABILITY_SUBAGENT) ?? false)
// 可选子Agent：仅启用状态的智能体，编辑时排除自身，避免自引用
const subAgentSelectOptions = computed(() =>
  agentOptions.value.filter((a) => a.status === 1 && a.id !== editingId.value),
)

watch(showSubAgentSelect, (visible) => {
  if (!visible) {
    form.subAgentIds = []
  }
})

async function loadOptions() {
  const request = ++optionsRequest
  optionsLoading.value = true
  optionsError.value = ''
  const failed: string[] = []
  async function read<T>(
    permission: string,
    label: string,
    action: () => Promise<T[]>,
    target: Ref<T[]>,
  ) {
    if (!auth.hasPermission(permission)) {
      target.value = []
      return
    }
    try {
      const result = await action()
      if (request === optionsRequest) target.value = result
    } catch {
      failed.push(label)
    }
  }
  await Promise.all([
    read(
      'model:view',
      '模型',
      async () => (await pageModels({ pageNum: 1, pageSize: 100 })).list,
      modelOptions,
    ),
    read(
      'mcp:view',
      'MCP',
      async () => (await pageMcps({ pageNum: 1, pageSize: 100 })).list,
      mcpOptions,
    ),
    read(
      'skill:view',
      'Skill',
      async () => (await pageSkills({ pageNum: 1, pageSize: 100 })).list,
      skillOptions,
    ),
    read(
      'system-tool:view',
      '系统工具',
      async () =>
        (await fetchSystemTools({ pageNum: 1, pageSize: 100 })).list.filter(
          (tool) => tool.enabled === 1,
        ),
      systemToolOptions,
    ),
    read(
      'agent:view',
      '子智能体',
      async () => (await pageAgents({ pageNum: 1, pageSize: AGENT_OPTION_PAGE_SIZE })).list,
      agentOptions,
    ),
    read('knowledge-base:view', '知识库', fetchKnowledgeBaseOptions, knowledgeBaseOptions),
    read(
      'model:view',
      '路由策略',
      async () => (await listModelRoutePolicies()).filter((policy) => policy.status === 'ACTIVE'),
      routePolicyOptions,
    ),
  ])
  if (request !== optionsRequest) return
  optionsLoading.value = false
  optionsError.value = failed.length
    ? `${failed.join('、')}选项加载失败，已保留当前配置。可重新加载后继续选择。`
    : ''
}

function openCreate() {
  submitError.value = ''
  editorGeneration += 1
  templatesVisible.value = true
  restoreConflict.value = ''
  scenarioChecks.value = []
  editorMode.value = 'create'
  editingId.value = null
  Object.assign(form, {
    agentName: '',
    agentCode: '',
    modelId: undefined,
    backupModelIds: [],
    mcpIds: [],
    skillIds: [],
    systemToolIds: [],
    knowledgeBaseIds: [],
    modelRoutePolicyId: null,
    systemPrompt: '',
    capabilities: ['chat'],
    icon: '',
    status: 1,
    subAgentIds: [],
    maxIters: null,
    toolTimeoutSeconds: null,
    toolMaxAttempts: null,
    compressTriggerMsgs: null,
    compressKeepMsgs: null,
  })
  originalModelId.value = null
  primaryTestState.value = 'untested'
  primaryTestMessage.value = null
  editorVisible.value = true
  draftWorkflow.begin()
}

function openEdit(row: AgentVO) {
  submitError.value = ''
  editorGeneration += 1
  templatesVisible.value = false
  restoreConflict.value = ''
  scenarioChecks.value = []
  editorMode.value = 'edit'
  editingId.value = row.id
  Object.assign(form, {
    agentName: row.agentName,
    agentCode: row.agentCode,
    modelId: row.modelId,
    backupModelIds: [...(row.backupModelIds ?? [])],
    mcpIds: [...(row.mcpIds ?? [])],
    skillIds: [...(row.skillIds ?? [])],
    systemToolIds: [...(row.systemToolIds ?? [])],
    knowledgeBaseIds: [...(row.knowledgeBaseIds ?? [])],
    modelRoutePolicyId: row.modelRoutePolicyId ?? null,
    systemPrompt: row.systemPrompt,
    capabilities: [...(row.capabilities ?? [])],
    icon: row.icon,
    status: row.status,
    subAgentIds: [...(row.subAgentIds ?? [])],
    maxIters: row.maxIters ?? null,
    toolTimeoutSeconds: row.toolTimeoutSeconds ?? null,
    toolMaxAttempts: row.toolMaxAttempts ?? null,
    compressTriggerMsgs: row.compressTriggerMsgs ?? null,
    compressKeepMsgs: row.compressKeepMsgs ?? null,
  })
  originalModelId.value = row.modelId
  // 未改动主模型视为已通过历史校验，无需强制重测
  primaryTestState.value = 'passed'
  primaryTestMessage.value = null
  editorVisible.value = true
  draftWorkflow.begin(undefined, row.runtimeRevision)
}

async function chooseScenario(scenario: AgentScenario) {
  if (form.agentName || form.systemPrompt) {
    try {
      await ElMessageBox.confirm(
        '使用模板会替换名称和提示词，其他配置保持当前选择。',
        '使用场景模板',
        { type: 'warning' },
      )
    } catch {
      return
    }
  }
  form.agentName = `${scenario.title}助手`
  form.systemPrompt = scenario.prompt
  scenarioChecks.value = scenario.checks
  templatesVisible.value = false
}

async function restoreDraft(draft: AgentDraft) {
  if (!draft.configuration || !auth.hasPermission(draft.agentId ? 'agent:edit' : 'agent:add'))
    return
  const token = auth.token
  const opening = ++editorGeneration
  let current: AgentVO | undefined
  if (draft.agentId != null) {
    try {
      current = await getAgent(draft.agentId)
    } catch (cause) {
      ElMessage.error(getRequestErrorMessage(cause, '原智能体暂时无法读取，草稿仍已保存'))
      return
    }
    if (auth.token !== token || opening !== editorGeneration) return
    openEdit(current)
  } else openCreate()
  Object.assign(form, JSON.parse(JSON.stringify(draft.configuration)), {
    modelId: draft.configuration.modelId ?? undefined,
  })
  draftWorkflow.begin(draft)
  templatesVisible.value = false
  if (current && current.runtimeRevision !== draft.baseRevision) {
    restoreConflict.value =
      '正式配置已更新。当前草稿已保留，请返回列表打开最新配置核对，避免覆盖他人的修改。'
  }
  // 恢复个人草稿不执行模型请求；用户明确点击测试后才使用模型。
  primaryTestState.value = current && current.modelId === form.modelId ? 'passed' : 'untested'
  primaryTestMessage.value = null
}

/** 压缩触发消息数与保留消息数同时填写时，保留数须小于触发数，否则压缩逻辑无意义 */
function validateCompressParams(): boolean {
  const { compressTriggerMsgs, compressKeepMsgs } = form
  if (
    compressTriggerMsgs != null &&
    compressKeepMsgs != null &&
    compressKeepMsgs >= compressTriggerMsgs
  ) {
    ElMessage.error('压缩保留消息数必须小于压缩触发消息数')
    return false
  }
  return true
}

/** 勾选「子Agent协作」后必须至少选择一个子智能体，否则该能力是空转配置 */
function validateSubAgents(): boolean {
  if (showSubAgentSelect.value && (form.subAgentIds?.length ?? 0) === 0) {
    ElMessage.error('勾选「子Agent协作」后需至少选择一个子智能体')
    return false
  }
  return true
}

async function handleSubmit() {
  if (saving.value || draftSaving.value) return
  if (!canSubmit.value) {
    ElMessage.warning('主模型连通性测试尚未通过，无法提交')
    return
  }
  const submittingToken = auth.token
  submitError.value = ''
  saving.value = true
  try {
    const valid = await formRef.value?.validate().catch(() => false)
    if (!valid) {
      return
    }
    if (!validateCompressParams() || !validateSubAgents()) {
      return
    }
    if (editorMode.value === 'create') {
      await createAgent(form)
      if (auth.token !== submittingToken) return
      ElMessage.success('新建成功')
    } else if (editingId.value) {
      await updateAgent(editingId.value, form, draftWorkflow.baseRevision.value)
      if (auth.token !== submittingToken) return
      ElMessage.success('保存成功')
    }
    await draftWorkflow.applied()
    editorVisible.value = false
    await loadList()
    await loadOptions()
    await menuStore.refreshMenu()
  } catch (cause) {
    if (auth.token === submittingToken) {
      submitError.value = getRequestErrorMessage(cause, '智能体保存失败，当前内容已保留。')
    }
  } finally {
    saving.value = false
  }
}

async function handleDelete(row: AgentVO) {
  await ElMessageBox.confirm(`确认删除智能体「${row.agentName}」？`, '提示', {
    type: 'warning',
  })
  await deleteAgent(row.id)
  ElMessage.success('删除成功')
  await loadList()
  await loadOptions()
  await menuStore.refreshMenu()
}

// ---------- 长期记忆查看/清空 ----------
const memoryDialogVisible = ref(false)
const memoryLoading = ref(false)
const memoryAgent = ref<AgentVO | null>(null)
const memoryExists = ref(false)
const memoryContent = ref('')
const memoryUpdateTime = ref<string | null>(null)

async function openMemory(row: AgentVO) {
  memoryAgent.value = row
  memoryDialogVisible.value = true
  memoryLoading.value = true
  try {
    const result = await getAgentMemory(row.id)
    memoryExists.value = result.exists
    memoryContent.value = result.content
    memoryUpdateTime.value = result.updateTime
  } finally {
    memoryLoading.value = false
  }
}

async function handleClearMemory() {
  if (!memoryAgent.value) {
    return
  }
  await ElMessageBox.confirm(
    `确认清空智能体「${memoryAgent.value.agentName}」的全部长期记忆？清空后不可恢复`,
    '提示',
    { type: 'warning' },
  )
  await clearAgentMemory(memoryAgent.value.id)
  ElMessage.success('记忆已清空')
  memoryExists.value = false
  memoryContent.value = ''
  memoryUpdateTime.value = null
}

async function handleToggleStatus(row: AgentVO) {
  if (row.status === 1) {
    await disableAgent(row.id)
    ElMessage.success('已停用')
  } else {
    await enableAgent(row.id)
    ElMessage.success('已启用')
  }
  await loadList()
  await loadOptions()
  await menuStore.refreshMenu()
}

onMounted(() => {
  loadList()
  loadOptions()
})
</script>

<template>
  <div class="page">
    <CrudLoadState
      v-if="!editorVisible"
      :error="loadError"
      :has-stale-data="list.length > 0"
      :loading="loading"
      @retry="loadList"
    />
    <el-card
      v-show="!editorVisible"
      class="agent-catalog"
      :class="{ 'is-card-view': displayMode === 'cards' }"
    >
      <div class="toolbar">
        <el-input
          v-model="query.keyword"
          placeholder="按名称搜索"
          style="width: 220px"
          clearable
          @keyup.enter="handleSearch"
        />
        <el-button @click="handleSearch">搜索</el-button>
        <el-radio-group v-model="displayMode" aria-label="智能体展示方式">
          <el-radio-button value="cards">卡片</el-radio-button>
          <el-radio-button value="table">列表</el-radio-button>
        </el-radio-group>
        <div class="toolbar-actions">
          <el-button v-if="canManageDrafts" @click="draftDrawerVisible = true">我的草稿</el-button>
          <el-button v-permission="'agent:view'" @click="channelBindingVisible = true"
            >渠道绑定</el-button
          >
          <el-button
            v-permission="'agent:add'"
            class="cw-final-action"
            type="primary"
            @click="openCreate"
            >新建智能体</el-button
          >
        </div>
      </div>

      <div
        v-if="displayMode === 'cards'"
        v-loading="loading"
        class="agent-catalog-body"
        :aria-busy="loading"
      >
        <div v-if="list.length" class="agent-cards">
          <AgentOverviewCard
            v-for="row in list"
            :key="row.id"
            :agent="row"
            :capability-labels="(row.capabilities ?? []).map(capabilityLabel)"
          >
            <template #actions>
              <AgentActions
                :agent="row"
                :can-open="workspacePaths.has(`/workspace/${row.agentCode}`)"
                prominent
                @open="openWorkspace(row)"
                @edit="openEdit(row)"
                @toggle="handleToggleStatus(row)"
                @memory="openMemory(row)"
                @delete="handleDelete(row)"
              />
            </template>
          </AgentOverviewCard>
        </div>
        <el-empty
          v-else
          :description="
            loading ? '正在加载智能体…' : loadError ? '智能体暂时无法加载' : '暂无符合条件的智能体'
          "
        />
      </div>

      <el-table
        v-else
        v-loading="loading"
        :data="list"
        class="data-table"
        :empty-text="loadError ? '智能体暂时无法加载' : '暂无符合条件的智能体'"
      >
        <el-table-column label="智能体" min-width="210" class-name="primary-column">
          <template #default="{ row }"
            ><div class="agent-list-identity">
              <span class="agent-list-icon"
                ><el-icon><component :is="row.icon || 'Cpu'" /></el-icon></span
              ><span
                ><strong>{{ row.agentName }}</strong
                ><small>{{ row.agentCode }}</small></span
              >
            </div></template
          >
        </el-table-column>
        <el-table-column label="模型" min-width="150" show-overflow-tooltip>
          <template #default="{ row }"
            ><div class="model-summary">
              <span>{{ row.modelName }}</span
              ><small :title="row.backupModelNames?.join('、')">{{
                row.backupModelNames?.length
                  ? `${row.backupModelNames.length} 个备用模型`
                  : '未配置备用模型'
              }}</small>
            </div></template
          >
        </el-table-column>
        <el-table-column label="知识与能力" min-width="180">
          <template #default="{ row }">
            <el-popover placement="bottom" :width="340" trigger="click">
              <template #reference
                ><el-button link type="primary"
                  >{{ row.knowledgeBaseNames?.length || 0 }} 个知识库 ·
                  {{ row.capabilities?.length || 0 }} 项能力</el-button
                ></template
              >
              <div class="agent-capability-detail">
                <h3>知识库</h3>
                <el-tag v-for="(name, index) in row.knowledgeBaseNames" :key="name" type="success"
                  >{{ name }} · 版本 {{ row.knowledgeBaseVersionIds?.[index] ?? '—' }}</el-tag
                ><span v-if="!row.knowledgeBaseNames?.length">未配置</span>
                <h3>Skill</h3>
                <el-tag v-for="(id, index) in row.skillIds" :key="id" type="warning"
                  >{{ skillName(id) }} · 版本 {{ row.skillVersionIds?.[index] ?? '—' }}</el-tag
                ><span v-if="!row.skillIds?.length">未配置</span>
                <h3>能力</h3>
                <el-tag v-for="capability in row.capabilities" :key="capability">{{
                  capabilityLabel(capability)
                }}</el-tag>
              </div>
            </el-popover>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="80"
          ><template #default="{ row }"
            ><el-tag :type="row.status === 1 ? 'success' : 'info'">{{
              row.status === 1 ? '已启用' : '已停用'
            }}</el-tag></template
          ></el-table-column
        >
        <el-table-column label="创建时间" width="125"
          ><template #default="{ row }"
            ><span :title="row.createTime">{{
              row.createTime?.slice(0, 10) || '—'
            }}</span></template
          ></el-table-column
        >
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <AgentActions
              :agent="row"
              :can-open="workspacePaths.has(`/workspace/${row.agentCode}`)"
              @open="openWorkspace(row)"
              @edit="openEdit(row)"
              @toggle="handleToggleStatus(row)"
              @memory="openMemory(row)"
              @delete="handleDelete(row)"
            />
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-model:current-page="query.pageNum"
        v-model:page-size="query.pageSize"
        :total="total"
        layout="total, prev, pager, next"
        class="pagination"
        @current-change="loadList"
      />
    </el-card>

    <section v-if="editorVisible" class="agent-editor" aria-label="智能体配置">
      <header class="editor-header">
        <div>
          <el-button text :disabled="saving || draftSaving" @click="draftWorkflow.close"
            ><el-icon><ArrowLeft /></el-icon>返回列表</el-button
          >
          <h2>{{ editorMode === 'create' ? '新建智能体' : form.agentName }}</h2>
        </div>
        <el-button
          class="cw-final-action"
          type="primary"
          :disabled="!canSubmit || draftSaving"
          :loading="saving"
          @click="handleSubmit"
          >保存智能体</el-button
        >
      </header>
      <div class="draft-status" role="status">
        <span v-if="draftDirty">有未保存的修改</span>
        <span v-else-if="draftSavedAt"
          >个人草稿已保存 · {{ new Date(draftSavedAt).toLocaleTimeString() }}</span
        >
        <span v-else>可以先保存草稿，稍后继续完成配置</span>
        <el-button :disabled="saving" :loading="draftSaving" @click="draftWorkflow.save"
          >保存草稿</el-button
        >
      </div>
      <el-alert
        v-if="draftError || restoreConflict || submitError"
        :title="draftError || restoreConflict || submitError"
        type="error"
        :closable="false"
        show-icon
        class="draft-error"
      />
      <el-button
        v-if="editorMode === 'create' && !templatesVisible"
        class="template-toggle"
        :disabled="saving || draftSaving"
        @click="templatesVisible = true"
        >选择其他场景模板</el-button
      >
      <AgentScenarioTemplates
        v-if="editorMode === 'create' && templatesVisible && !saving && !draftSaving"
        @choose="chooseScenario"
      />
      <el-alert
        v-if="optionsError"
        :title="optionsError"
        type="warning"
        :closable="false"
        class="draft-error"
      >
        <el-button link type="primary" :disabled="optionsLoading" @click="loadOptions"
          >重新加载配置选项</el-button
        >
      </el-alert>
      <div class="editor-body">
        <nav class="editor-nav" aria-label="配置章节">
          <button
            v-for="section in [
              { id: 'agent-basics', title: '基本信息' },
              { id: 'agent-models', title: '模型与路由' },
              { id: 'agent-capabilities', title: '知识与能力' },
              { id: 'agent-prompt', title: '提示词与行为' },
            ]"
            :key="section.id"
            type="button"
            @click="scrollToSection(section.id)"
          >
            {{ section.title }}
          </button>
        </nav>
        <el-form
          ref="formRef"
          :disabled="saving || draftSaving"
          :model="form"
          label-position="top"
          class="editor-form"
        >
          <section id="agent-basics" class="editor-section">
            <h3>基本信息</h3>
            <p>定义智能体的名称与访问标识。</p>
            <el-form-item
              label="名称"
              prop="agentName"
              :rules="[{ required: true, message: '请输入名称' }]"
            >
              <el-input v-model="form.agentName" />
            </el-form-item>
            <el-form-item
              label="编码"
              prop="agentCode"
              :rules="[
                { required: true, message: '请输入编码' },
                {
                  pattern: agentCodePattern,
                  message: '仅支持小写字母/数字/短横线',
                },
              ]"
            >
              <el-input
                v-model="form.agentCode"
                :disabled="editorMode === 'edit'"
                placeholder="用于工作区路由，如 sales-assistant"
              />
            </el-form-item>
          </section>
          <section id="agent-models" class="editor-section">
            <h3>模型与路由</h3>
            <p>选择主模型、备用模型与调用策略。</p>
            <el-form-item
              label="主模型"
              prop="modelId"
              :rules="[{ required: true, message: '请选择主模型' }]"
            >
              <div style="width: 100%">
                <el-select
                  v-model="form.modelId"
                  :disabled="!auth.hasPermission('model:view')"
                  style="width: 100%"
                  @change="handlePrimaryModelChange"
                >
                  <el-option
                    v-for="m in enabledModelOptions"
                    :key="m.id"
                    :label="m.modelName"
                    :value="m.id"
                  />
                </el-select>
                <div class="connectivity-row">
                  <el-tag v-if="primaryTestState === 'testing'" type="info">
                    <el-icon class="is-loading"><Loading /></el-icon>
                    测试中
                  </el-tag>
                  <el-tag v-else-if="primaryTestState === 'passed'" type="success"
                    >连通性通过</el-tag
                  >
                  <el-tag v-else-if="primaryTestState === 'failed'" type="danger"
                    >连通性失败：{{ primaryTestMessage }}</el-tag
                  >
                  <el-tag v-else type="info">未测试</el-tag>
                  <el-button
                    v-if="form.modelId != null"
                    link
                    type="primary"
                    :disabled="primaryTestState === 'testing' || !auth.hasPermission('model:edit')"
                    @click="handleRetestPrimaryModel"
                  >
                    重新测试
                  </el-button>
                </div>
                <div v-if="!canSubmit" class="connectivity-hint">
                  {{
                    auth.hasPermission('model:edit')
                      ? '主模型连通性测试通过后才能提交'
                      : '当前账号没有模型测试权限，可先保存草稿'
                  }}
                </div>
              </div>
            </el-form-item>
            <el-form-item label="备用模型">
              <el-select
                v-model="form.backupModelIds"
                :disabled="!auth.hasPermission('model:view')"
                multiple
                style="width: 100%"
                placeholder="可选，主模型异常时的降级候选"
              >
                <el-option
                  v-for="m in backupModelOptions"
                  :key="m.id"
                  :label="m.modelName"
                  :value="m.id"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="路由策略">
              <el-select
                v-model="form.modelRoutePolicyId"
                :disabled="!auth.hasPermission('model:view')"
                clearable
                style="width: 100%"
                placeholder="可选；绑定后按 ACTIVE 不可变版本在线选模"
              >
                <el-option
                  v-for="policy in routePolicyOptions"
                  :key="policy.id"
                  :label="`${policy.policyName} · v${policy.currentVersionNo}`"
                  :value="policy.id"
                />
              </el-select>
              <div class="version-hint">
                未绑定策略时使用主模型与备用模型；已绑定策略按当前生效版本选择模型。
              </div>
            </el-form-item>
          </section>
          <section id="agent-capabilities" class="editor-section">
            <h3>知识与能力</h3>
            <p>选择完成任务所需的知识、技能与工具。</p>
            <el-form-item label="MCP">
              <el-select
                v-model="form.mcpIds"
                :disabled="!auth.hasPermission('mcp:view')"
                multiple
                style="width: 100%"
                placeholder="可选"
              >
                <el-option v-for="m in mcpOptions" :key="m.id" :label="m.mcpName" :value="m.id" />
              </el-select>
            </el-form-item>
            <el-form-item label="Skill">
              <el-select
                v-model="form.skillIds"
                :disabled="!auth.hasPermission('skill:view')"
                multiple
                style="width: 100%"
                placeholder="可选"
              >
                <el-option
                  v-for="s in skillOptions"
                  :key="s.id"
                  :label="`${s.skillName} · v${s.latestVersionNo}`"
                  :value="s.id"
                />
              </el-select>
              <div class="version-hint">
                保存 Agent 时冻结所选 Skill 的当前版本；后续编辑 Skill 不会影响已运行 Agent。
              </div>
            </el-form-item>
            <el-form-item label="系统工具">
              <el-select
                v-model="form.systemToolIds"
                :disabled="!auth.hasPermission('system-tool:view')"
                multiple
                style="width: 100%"
                placeholder="可选"
              >
                <el-option
                  v-for="t in systemToolOptions"
                  :key="t.id"
                  :label="t.toolName"
                  :value="t.id"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="知识库">
              <el-select
                v-model="form.knowledgeBaseIds"
                :disabled="!auth.hasPermission('knowledge-base:view')"
                multiple
                style="width: 100%"
                placeholder="可选，仅展示连通性测试通过的知识库"
              >
                <el-option
                  v-for="k in knowledgeBaseOptions"
                  :key="k.id"
                  :label="`${k.kbName} · v${k.latestVersionNo}`"
                  :value="k.id"
                />
              </el-select>
              <div class="version-hint">
                保存 Agent 时冻结所选知识库当前版本；升级需重新保存 Agent。
              </div>
            </el-form-item>
            <el-form-item label="能力">
              <el-checkbox-group v-model="form.capabilities">
                <el-tooltip
                  v-for="opt in CAPABILITY_OPTIONS"
                  :key="opt.value"
                  :content="opt.tip"
                  :disabled="!opt.tip"
                  placement="top"
                >
                  <el-checkbox :value="opt.value">{{ opt.label }}</el-checkbox>
                </el-tooltip>
              </el-checkbox-group>
            </el-form-item>
            <el-form-item v-if="showSubAgentSelect" label="子Agent">
              <el-select
                v-model="form.subAgentIds"
                multiple
                style="width: 100%"
                placeholder="选择可协作的子智能体（仅展示启用状态）"
              >
                <el-option
                  v-for="a in subAgentSelectOptions"
                  :key="a.id"
                  :label="a.agentName"
                  :value="a.id"
                />
              </el-select>
            </el-form-item>
          </section>
          <section id="agent-prompt" class="editor-section">
            <h3>提示词与行为</h3>
            <p>明确角色、任务边界与执行约束。</p>
            <el-form-item label="系统提示词">
              <el-input
                v-model="form.systemPrompt!"
                type="textarea"
                :rows="10"
                placeholder="描述智能体的职责、处理步骤与输出要求…"
              />
            </el-form-item>
            <el-form-item label="图标">
              <IconPicker v-model="form.icon!" />
            </el-form-item>
            <el-form-item label="状态">
              <el-switch v-model="form.status" :active-value="1" :inactive-value="0" />
            </el-form-item>
            <el-collapse class="advanced-params">
              <el-collapse-item title="高级参数" name="advanced">
                <el-form-item label="最大迭代次数">
                  <el-input-number
                    v-model="form.maxIters"
                    :min="MAX_ITERS_RANGE.min"
                    :max="MAX_ITERS_RANGE.max"
                    :value-on-clear="null"
                    placeholder="默认 10"
                    style="width: 100%"
                  />
                </el-form-item>
                <el-form-item label="工具超时（秒）">
                  <el-input-number
                    v-model="form.toolTimeoutSeconds"
                    :min="TOOL_TIMEOUT_SECONDS_RANGE.min"
                    :max="TOOL_TIMEOUT_SECONDS_RANGE.max"
                    :value-on-clear="null"
                    placeholder="默认 300"
                    style="width: 100%"
                  />
                </el-form-item>
                <el-form-item label="工具最大尝试次数">
                  <el-input-number
                    v-model="form.toolMaxAttempts"
                    :min="TOOL_MAX_ATTEMPTS_RANGE.min"
                    :max="TOOL_MAX_ATTEMPTS_RANGE.max"
                    :value-on-clear="null"
                    placeholder="默认 1"
                    style="width: 100%"
                  />
                </el-form-item>
                <el-form-item label="压缩触发消息数">
                  <el-input-number
                    v-model="form.compressTriggerMsgs"
                    :min="COMPRESS_TRIGGER_MSGS_RANGE.min"
                    :max="COMPRESS_TRIGGER_MSGS_RANGE.max"
                    :value-on-clear="null"
                    placeholder="默认不压缩"
                    style="width: 100%"
                  />
                </el-form-item>
                <el-form-item label="压缩保留消息数">
                  <el-input-number
                    v-model="form.compressKeepMsgs"
                    :min="COMPRESS_KEEP_MSGS_RANGE.min"
                    :max="COMPRESS_KEEP_MSGS_RANGE.max"
                    :value-on-clear="null"
                    placeholder="默认 10"
                    style="width: 100%"
                  />
                </el-form-item>
              </el-collapse-item>
            </el-collapse>
          </section>
        </el-form>
        <aside class="editor-summary">
          <span class="agent-preview-icon"
            ><el-icon><component :is="form.icon || 'Cpu'" /></el-icon
          ></span>
          <h3>{{ form.agentName || '未命名智能体' }}</h3>
          <p>{{ form.agentCode || '设置访问编码' }}</p>
          <dl>
            <div>
              <dt>知识库</dt>
              <dd>{{ form.knowledgeBaseIds?.length || 0 }}</dd>
            </div>
            <div>
              <dt>Skill</dt>
              <dd>{{ form.skillIds?.length || 0 }}</dd>
            </div>
            <div>
              <dt>能力</dt>
              <dd>{{ form.capabilities?.length || 0 }}</dd>
            </div>
          </dl>
          <el-tag :type="canSubmit ? 'success' : 'info'">{{
            canSubmit ? '模型连接已验证' : '等待模型验证'
          }}</el-tag>
          <p>保存智能体后配置生效。知识库与 Skill 将绑定当前版本；渠道发布仍执行后端门禁。</p>
          <div v-if="scenarioChecks.length" class="scenario-checks">
            <h4>建议试用的场景</h4>
            <ul>
              <li v-for="check in scenarioChecks" :key="check">{{ check }}</li>
            </ul>
            <p>这些是待验证场景，不代表测试已通过。</p>
          </div>
        </aside>
      </div>
      <div class="editor-footer">
        <el-button :disabled="saving || draftSaving" @click="draftWorkflow.close">取消</el-button>
        <el-button
          class="cw-final-action"
          type="primary"
          :disabled="!canSubmit || draftSaving"
          :loading="saving"
          @click="handleSubmit"
          >保存智能体</el-button
        >
      </div>
    </section>

    <el-dialog
      v-model="memoryDialogVisible"
      :title="`长期记忆 - ${memoryAgent?.agentName ?? ''}`"
      width="640px"
    >
      <div v-loading="memoryLoading">
        <template v-if="memoryExists">
          <div class="memory-meta">最近更新：{{ memoryUpdateTime ?? '-' }}</div>
          <pre class="memory-content">{{ memoryContent }}</pre>
        </template>
        <el-empty v-else description="暂无记忆，与该智能体对话后会自动沉淀" :image-size="80" />
      </div>
      <template #footer>
        <el-button
          v-permission="'agent:edit'"
          type="danger"
          :disabled="!memoryExists"
          @click="handleClearMemory"
        >
          清空记忆
        </el-button>
        <el-button @click="memoryDialogVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <AgentDraftDrawer v-model="draftDrawerVisible" @restore="restoreDraft" />
    <ChannelBindingDrawer v-model="channelBindingVisible" />
  </div>
</template>

<style scoped>
.template-toggle {
  margin-bottom: 18px;
}
.draft-status {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 16px;
  margin-bottom: 18px;
  background: var(--cw-canvas);
  border: 1px solid var(--cw-line);
  border-radius: 10px;
  font-size: 13px;
  color: var(--cw-text-muted);
}
.draft-error {
  margin-bottom: 18px;
}
.scenario-checks h4 {
  font-size: 14px;
  margin: 20px 0 10px;
}
.scenario-checks ul {
  padding-left: 18px;
  font-size: 13px;
  line-height: 1.8;
}

.agent-catalog.is-card-view {
  border: 0;
  background: transparent;
  box-shadow: none;
}
.agent-catalog.is-card-view :deep(> .el-card__body) {
  padding: 0;
}
.agent-catalog-body {
  min-height: 260px;
}
.agent-cards {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 18px;
}
@media (max-width: 1199px) {
  .agent-cards {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
@media (max-width: 767px) {
  .agent-cards {
    grid-template-columns: minmax(0, 1fr);
  }
}
.agent-list-identity {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}
.agent-list-identity > span:last-child,
.model-summary {
  display: grid;
  gap: 5px;
  min-width: 0;
}
.agent-list-identity strong {
  font-size: 13px;
  font-weight: 600;
}
.agent-list-identity small,
.model-summary small {
  color: var(--cw-text-muted);
  font-size: 12px;
}
.agent-list-icon,
.agent-preview-icon {
  display: inline-grid;
  place-items: center;
  width: 36px;
  height: 36px;
  flex: 0 0 36px;
  border-radius: 9px;
  background: var(--cw-canvas);
  color: var(--cw-cobalt);
  border: 1px solid var(--cw-line);
  font-size: 20px;
}
.agent-capability-detail h3 {
  font-size: 12px;
  margin: 12px 0 8px;
}
.agent-capability-detail .el-tag {
  margin: 2px;
  white-space: normal;
  height: auto;
  min-height: 24px;
}
.agent-editor {
  min-width: 0;
}
.editor-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 20px;
}
.editor-header > div {
  display: flex;
  align-items: center;
  gap: 16px;
  min-width: 0;
}
.editor-header h2 {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
  overflow-wrap: anywhere;
}
.editor-body {
  display: grid;
  grid-template-columns: 140px minmax(0, 1fr) 220px;
  gap: 24px;
  align-items: start;
}
.editor-nav {
  display: grid;
  position: sticky;
  top: 0;
  gap: 4px;
}
.editor-nav button {
  padding: 12px;
  border: 0;
  border-radius: 6px;
  text-align: left;
  background: transparent;
  color: var(--cw-text-muted);
  cursor: pointer;
  font: inherit;
  font-size: 13px;
}
.editor-nav button:hover {
  background: var(--cw-paper);
  color: var(--cw-cobalt);
}
.editor-section {
  padding: 24px;
  margin-bottom: 20px;
  border: 1px solid var(--cw-line);
  border-radius: 9px;
  background: var(--cw-paper);
  scroll-margin-top: 12px;
}
.editor-section h3 {
  font-size: 15px;
  font-weight: 600;
  margin: 0 0 8px;
}
.editor-section > p,
.editor-summary p {
  color: var(--cw-text-muted);
  font-size: 12px;
  line-height: 1.7;
  margin: 0 0 24px;
}
.editor-summary {
  position: sticky;
  top: 0;
  background: var(--cw-paper);
  border: 1px solid var(--cw-line);
  border-radius: 9px;
  padding: 20px;
  text-align: center;
  overflow-wrap: anywhere;
}
.editor-summary h3 {
  font-size: 15px;
  font-weight: 600;
  margin: 16px 0 6px;
}
.editor-summary dl > div {
  display: flex;
  justify-content: space-between;
  padding: 10px 0;
  border-bottom: 1px solid var(--cw-line);
  font-size: 12px;
}
.editor-summary dt {
  color: var(--cw-text-muted);
}
.editor-summary p:last-child {
  margin: 18px 0 0;
}
.editor-footer {
  display: flex;
  justify-content: flex-end;
  padding: 12px 0 24px;
}
@media (max-width: 1200px) {
  .editor-body {
    grid-template-columns: minmax(0, 1fr) 210px;
    gap: 16px;
  }
  .editor-nav {
    display: none;
  }
}
@media (max-width: 760px) {
  .editor-body {
    grid-template-columns: minmax(0, 1fr);
  }
  .editor-summary {
    position: static;
  }
  .editor-section {
    padding: 18px;
  }
  .editor-header > div {
    gap: 6px;
  }
  .editor-header h2 {
    font-size: 16px;
  }
}

.toolbar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 16px;
}

.toolbar-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-left: auto;
}

.data-table {
  min-width: 0;
  max-width: 100%;
}

:deep(.primary-column .cell) {
  color: var(--cw-text);
  font-weight: 650;
}

.connectivity-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 8px;
}

.connectivity-hint {
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-color-danger);
}

.version-hint {
  margin-top: 4px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.5;
}

.memory-meta {
  margin-bottom: 8px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.muted {
  color: var(--el-text-color-placeholder);
}

.memory-content {
  max-height: 420px;
  margin: 0;
  padding: 12px;
  overflow: auto;
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
  background: var(--el-fill-color-light);
  border-radius: var(--cw-radius-sm);
}

@media (max-width: 767px) {
  .toolbar-actions {
    margin-left: 0;
  }

  .toolbar-actions > .el-button {
    flex: 1 1 auto;
    margin-left: 0;
  }
}
</style>
