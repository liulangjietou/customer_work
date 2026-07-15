<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
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

const dialogVisible = ref(false)
const dialogMode = ref<'create' | 'edit'>('create')
const formRef = ref<FormInstance>()
const editingId = ref<number | null>(null)
const form = reactive<AgentSaveRequest>({
  agentName: '', agentCode: '', modelId: undefined as unknown as number, backupModelIds: [], mcpIds: [], skillIds: [], systemToolIds: [],
  systemPrompt: '', capabilities: ['chat'], icon: '', status: 1,
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
  const [models, mcps, skills, systemTools] = await Promise.all([
    pageModels({ pageNum: 1, pageSize: 100 }),
    pageMcps({ pageNum: 1, pageSize: 100 }),
    pageSkills({ pageNum: 1, pageSize: 100 }),
    fetchSystemTools({ pageNum: 1, pageSize: 100 }),
  ])
  modelOptions.value = models.list
  mcpOptions.value = mcps.list
  skillOptions.value = skills.list
  // 只展示已启用的系统工具供挂载（停用的不出现在下拉里）。
  systemToolOptions.value = systemTools.list.filter((t) => t.enabled === 1)
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
  })
  originalModelId.value = row.modelId
  // 未改动主模型视为已通过历史校验，无需强制重测
  primaryTestState.value = 'passed'
  primaryTestMessage.value = null
  dialogVisible.value = true
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
  if (dialogMode.value === 'create') {
    await createAgent(form)
    ElMessage.success('新建成功')
  } else if (editingId.value) {
    await updateAgent(editingId.value, form)
    ElMessage.success('保存成功')
  }
  dialogVisible.value = false
  await loadList()
  await menuStore.refreshMenu()
}

async function handleDelete(row: AgentVO) {
  await ElMessageBox.confirm(`确认删除智能体「${row.agentName}」？`, '提示', { type: 'warning' })
  await deleteAgent(row.id)
  ElMessage.success('删除成功')
  await loadList()
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
        <el-table-column label="能力" width="160">
          <template #default="{ row }">
            <el-tag v-for="c in row.capabilities" :key="c" style="margin-right: 4px">{{ c }}</el-tag>
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
            <el-checkbox value="chat">chat（对话）</el-checkbox>
            <el-checkbox value="vibecoding">vibecoding（代码生成）</el-checkbox>
          </el-checkbox-group>
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
