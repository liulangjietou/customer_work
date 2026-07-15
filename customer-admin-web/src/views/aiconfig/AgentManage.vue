<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance } from 'element-plus'
import { Loading } from '@element-plus/icons-vue'
import { createAgent, deleteAgent, disableAgent, enableAgent, pageAgents, updateAgent } from '@/api/agent'
import { pageModels, testModelConnectivity } from '@/api/model'
import { pageMcps } from '@/api/mcp'
import { pageSkills } from '@/api/skill'
import { fetchSystemTools } from '@/api/system-tool'
import { useMenuStore } from '@/store/menu'
import IconPicker from '@/components/IconPicker.vue'
import type { AgentSaveRequest, AgentVO, McpVO, ModelVO, PageQuery, SkillVO, SystemToolVO } from '@/types/api'

const menuStore = useMenuStore()

const loading = ref(false)
const list = ref<AgentVO[]>([])
const total = ref(0)
const query = reactive<PageQuery>({ pageNum: 1, pageSize: 10, keyword: '' })

const modelOptions = ref<ModelVO[]>([])
const mcpOptions = ref<McpVO[]>([])
const skillOptions = ref<SkillVO[]>([])
const systemToolOptions = ref<SystemToolVO[]>([])
// 智能体选项复用于「子Agent协作」多选，无独立全量接口，拉大页分页兜底
const AGENT_OPTION_PAGE_SIZE = 200
const agentOptions = ref<AgentVO[]>([])

// 能力编码：value 与后端 capabilities 字段一一对应，tip 为勾选提示
const CAPABILITY_SUBAGENT = 'subagent'
const CAPABILITY_OPTIONS: { value: string; label: string; tip?: string }[] = [
  { value: 'chat', label: 'chat（对话）' },
  { value: 'vibecoding', label: 'vibecoding（代码生成）' },
  { value: CAPABILITY_SUBAGENT, label: '子Agent协作', tip: '允许使用子 Agent 协作' },
  { value: 'plan', label: '计划模式', tip: '支持多步骤计划推演' },
  { value: 'tasklist', label: '任务列表', tip: '跟踪和维护任务列表' },
  { value: 'skill-learning', label: '学习新技能', tip: '与用户互动学习并沉淀新技能' },
  { value: 'dynamic-subagent', label: '动态子Agent', tip: '运行时按任务临时创建子 Agent，无需预先配置' },
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

const dialogVisible = ref(false)
const dialogMode = ref<'create' | 'edit'>('create')
const formRef = ref<FormInstance>()
const editingId = ref<number | null>(null)
const form = reactive<AgentSaveRequest>({
  agentName: '', agentCode: '', modelId: undefined as unknown as number, backupModelIds: [], mcpIds: [], skillIds: [], systemToolIds: [],
  systemPrompt: '', capabilities: ['chat'], icon: '', status: 1,
  subAgentIds: [], maxIters: null, toolTimeoutSeconds: null, toolMaxAttempts: null,
  compressTriggerMsgs: null, compressKeepMsgs: null,
})

const agentCodePattern = /^[a-z0-9-]+$/

// ---------- 主模型连通性门禁 ----------
// 状态机：untested（未选/未测）→ testing（测试中）→ passed（通过，可提交）/ failed（失败，需重测）。
// 编辑态打开弹窗时，若主模型未被改动则直接视为 passed（复用后端已验证过的状态），一旦切换主模型必须重新测试通过。
type PrimaryTestState = 'untested' | 'testing' | 'passed' | 'failed'
const primaryTestState = ref<PrimaryTestState>('untested')
const primaryTestMessage = ref<string | null>(null)
const originalModelId = ref<number | null>(null)

const enabledModelOptions = computed(() => modelOptions.value.filter((m) => m.status === 1))
// 备用模型候选需排除当前已选的主模型，避免主备重复。
const backupModelOptions = computed(() => enabledModelOptions.value.filter((m) => m.id !== form.modelId))
const canSubmit = computed(() => primaryTestState.value === 'passed')

async function runPrimaryModelTest(modelId: number) {
  primaryTestState.value = 'testing'
  primaryTestMessage.value = null
  try {
    const result = await testModelConnectivity(modelId)
    if (result.testStatus === 1) {
      primaryTestState.value = 'passed'
    } else {
      primaryTestState.value = 'failed'
      primaryTestMessage.value = result.message || '连通性测试失败'
    }
  } catch {
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
  if (dialogMode.value === 'edit' && modelId === originalModelId.value) {
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

async function loadList() {
  loading.value = true
  try {
    const result = await pageAgents(query)
    list.value = result.list
    total.value = result.total
  } finally {
    loading.value = false
  }
}

async function loadOptions() {
  const [models, mcps, skills, systemTools, agents] = await Promise.all([
    pageModels({ pageNum: 1, pageSize: 100 }),
    pageMcps({ pageNum: 1, pageSize: 100 }),
    pageSkills({ pageNum: 1, pageSize: 100 }),
    fetchSystemTools({ pageNum: 1, pageSize: 100 }),
    pageAgents({ pageNum: 1, pageSize: AGENT_OPTION_PAGE_SIZE }),
  ])
  modelOptions.value = models.list
  mcpOptions.value = mcps.list
  skillOptions.value = skills.list
  // 只展示已启用的系统工具供挂载（停用的不出现在下拉里）。
  systemToolOptions.value = systemTools.list.filter((t) => t.enabled === 1)
  agentOptions.value = agents.list
}

function handleSearch() {
  query.pageNum = 1
  loadList()
}

function openCreate() {
  dialogMode.value = 'create'
  editingId.value = null
  Object.assign(form, {
    agentName: '', agentCode: '', modelId: undefined, backupModelIds: [], mcpIds: [], skillIds: [], systemToolIds: [],
    systemPrompt: '', capabilities: ['chat'], icon: '', status: 1,
    subAgentIds: [], maxIters: null, toolTimeoutSeconds: null, toolMaxAttempts: null,
    compressTriggerMsgs: null, compressKeepMsgs: null,
  })
  originalModelId.value = null
  primaryTestState.value = 'untested'
  primaryTestMessage.value = null
  dialogVisible.value = true
}

function openEdit(row: AgentVO) {
  dialogMode.value = 'edit'
  editingId.value = row.id
  Object.assign(form, {
    agentName: row.agentName, agentCode: row.agentCode, modelId: row.modelId, backupModelIds: [...(row.backupModelIds ?? [])],
    mcpIds: row.mcpIds, skillIds: row.skillIds, systemToolIds: row.systemToolIds, systemPrompt: row.systemPrompt,
    capabilities: row.capabilities, icon: row.icon, status: row.status,
    subAgentIds: row.subAgentIds ?? [], maxIters: row.maxIters ?? null,
    toolTimeoutSeconds: row.toolTimeoutSeconds ?? null, toolMaxAttempts: row.toolMaxAttempts ?? null,
    compressTriggerMsgs: row.compressTriggerMsgs ?? null, compressKeepMsgs: row.compressKeepMsgs ?? null,
  })
  originalModelId.value = row.modelId
  // 未改动主模型视为已通过历史校验，无需强制重测
  primaryTestState.value = 'passed'
  primaryTestMessage.value = null
  dialogVisible.value = true
}

/** 压缩触发消息数与保留消息数同时填写时，保留数须小于触发数，否则压缩逻辑无意义 */
function validateCompressParams(): boolean {
  const { compressTriggerMsgs, compressKeepMsgs } = form
  if (compressTriggerMsgs != null && compressKeepMsgs != null && compressKeepMsgs >= compressTriggerMsgs) {
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
  if (!canSubmit.value) {
    ElMessage.warning('主模型连通性测试尚未通过，无法提交')
    return
  }
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) {
    return
  }
  if (!validateCompressParams() || !validateSubAgents()) {
    return
  }
  if (dialogMode.value === 'create') {
    await createAgent(form)
    ElMessage.success('新建成功')
  } else if (editingId.value) {
    await updateAgent(editingId.value, form)
    ElMessage.success('保存成功')
  }
  dialogVisible.value = false
  await loadList()
  await loadOptions()
  await menuStore.refreshMenu()
}

async function handleDelete(row: AgentVO) {
  await ElMessageBox.confirm(`确认删除智能体「${row.agentName}」？`, '提示', { type: 'warning' })
  await deleteAgent(row.id)
  ElMessage.success('删除成功')
  await loadList()
  await loadOptions()
  await menuStore.refreshMenu()
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
    <el-card>
      <div class="toolbar">
        <el-input v-model="query.keyword" placeholder="按名称搜索" style="width: 220px" clearable @keyup.enter="handleSearch" />
        <el-button type="primary" @click="handleSearch">搜索</el-button>
        <el-button v-permission="'agent:add'" type="primary" @click="openCreate">新建智能体</el-button>
      </div>

      <el-table v-loading="loading" :data="list" style="width: 100%">
        <el-table-column label="图标" width="70">
          <template #default="{ row }">
            <el-icon v-if="row.icon" :size="18">
              <component :is="row.icon" />
            </el-icon>
          </template>
        </el-table-column>
        <el-table-column prop="agentName" label="名称" />
        <el-table-column prop="agentCode" label="编码" width="140" />
        <el-table-column prop="modelName" label="主模型" width="140" />
        <el-table-column label="备用模型" width="180">
          <template #default="{ row }">
            <el-tag v-for="n in row.backupModelNames" :key="n" type="info" style="margin-right: 4px">{{ n }}</el-tag>
            <span v-if="!row.backupModelNames?.length" style="color: #909399">-</span>
          </template>
        </el-table-column>
        <el-table-column label="能力" width="260">
          <template #default="{ row }">
            <el-tag v-for="c in row.capabilities" :key="c" style="margin: 2px">{{ capabilityLabel(c) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'">{{ row.status === 1 ? '启用' : '停用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="260" fixed="right">
          <template #default="{ row }">
            <el-button v-permission="'agent:edit'" link type="primary" @click="handleToggleStatus(row)">
              {{ row.status === 1 ? '停用' : '启用' }}
            </el-button>
            <el-button v-permission="'agent:edit'" link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button v-permission="'agent:delete'" link type="danger" @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-model:current-page="query.pageNum"
        v-model:page-size="query.pageSize"
        :total="total"
        layout="total, prev, pager, next"
        style="margin-top: 16px; justify-content: flex-end"
        @current-change="loadList"
      />
    </el-card>

    <el-dialog v-model="dialogVisible" :title="dialogMode === 'create' ? '新建智能体' : '编辑智能体'" width="600px">
      <el-form ref="formRef" :model="form" label-width="100px">
        <el-form-item label="名称" prop="agentName" :rules="[{ required: true, message: '请输入名称' }]">
          <el-input v-model="form.agentName" />
        </el-form-item>
        <el-form-item
          label="编码"
          prop="agentCode"
          :rules="[{ required: true, message: '请输入编码' }, { pattern: agentCodePattern, message: '仅支持小写字母/数字/短横线' }]"
        >
          <el-input v-model="form.agentCode" :disabled="dialogMode === 'edit'" placeholder="用于工作区路由，如 sales-assistant" />
        </el-form-item>
        <el-form-item label="主模型" prop="modelId" :rules="[{ required: true, message: '请选择主模型' }]">
          <div style="width: 100%">
            <el-select v-model="form.modelId" style="width: 100%" @change="handlePrimaryModelChange">
              <el-option v-for="m in enabledModelOptions" :key="m.id" :label="m.modelName" :value="m.id" />
            </el-select>
            <div class="connectivity-row">
              <el-tag v-if="primaryTestState === 'testing'" type="info">
                <el-icon class="is-loading"><Loading /></el-icon>
                测试中
              </el-tag>
              <el-tag v-else-if="primaryTestState === 'passed'" type="success">连通性通过</el-tag>
              <el-tag v-else-if="primaryTestState === 'failed'" type="danger">连通性失败：{{ primaryTestMessage }}</el-tag>
              <el-tag v-else type="info">未测试</el-tag>
              <el-button
                v-if="form.modelId != null"
                link
                type="primary"
                :disabled="primaryTestState === 'testing'"
                @click="handleRetestPrimaryModel"
              >
                重新测试
              </el-button>
            </div>
            <div v-if="!canSubmit" class="connectivity-hint">主模型连通性测试通过后才能提交</div>
          </div>
        </el-form-item>
        <el-form-item label="备用模型">
          <el-select v-model="form.backupModelIds" multiple style="width: 100%" placeholder="可选，主模型异常时的降级候选">
            <el-option v-for="m in backupModelOptions" :key="m.id" :label="m.modelName" :value="m.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="MCP">
          <el-select v-model="form.mcpIds" multiple style="width: 100%" placeholder="可选">
            <el-option v-for="m in mcpOptions" :key="m.id" :label="m.mcpName" :value="m.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="Skill">
          <el-select v-model="form.skillIds" multiple style="width: 100%" placeholder="可选">
            <el-option v-for="s in skillOptions" :key="s.id" :label="s.skillName" :value="s.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="系统工具">
          <el-select v-model="form.systemToolIds" multiple style="width: 100%" placeholder="可选">
            <el-option v-for="t in systemToolOptions" :key="t.id" :label="t.toolName" :value="t.id" />
          </el-select>
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
          <el-select v-model="form.subAgentIds" multiple style="width: 100%" placeholder="选择可协作的子智能体（仅展示启用状态）">
            <el-option v-for="a in subAgentSelectOptions" :key="a.id" :label="a.agentName" :value="a.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="系统提示词">
          <el-input v-model="form.systemPrompt!" type="textarea" :rows="4" />
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
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :disabled="!canSubmit" @click="handleSubmit">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  gap: 8px;
  margin-bottom: 16px;
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
  color: #f56c6c;
}
</style>
