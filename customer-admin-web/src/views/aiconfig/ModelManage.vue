<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import type { FormInstance } from 'element-plus'
import {
  createModel,
  deleteModel,
  getModelImpact,
  listModelAssetOptions,
  pageModels,
  runModelHealthCheck,
  updateModel,
} from '@/api/model'
import { useCrudPage } from '@/composables/useCrudPage'
import CrudLoadState from '@/components/CrudLoadState.vue'
import { useAuthStore } from '@/store/auth'
import type { ModelAssetOption, ModelSaveRequest, ModelVO, PageQuery } from '@/types/api'
import ModelExperimentPanel from './components/ModelExperimentPanel.vue'
import ModelGovernanceDrawer from './components/ModelGovernanceDrawer.vue'
import ModelRoutingPanel from './components/ModelRoutingPanel.vue'

const testingId = ref<number | null>(null)
const preflightingSave = ref(false)
const deletingId = ref<number | null>(null)
const formRef = ref<FormInstance>()
const assets = ref<ModelAssetOption[]>([])
const assetMode = ref<'existing' | 'new'>('new')
const editingRow = ref<ModelVO | null>(null)
const governanceVisible = ref(false)
const governanceModelId = ref<number | null>(null)
const auth = useAuthStore()

const {
  loading, loadError, submitting, list, total, query,
  dialogVisible, dialogMode, editingId, form,
  loadList, handleSearch, openCreate, openEdit, handleSubmit: submitCrud,
} = useCrudPage<ModelVO, PageQuery, ModelSaveRequest>({
  page: pageModels,
  formRef,
  create: createModel,
  update: updateModel,
  initQuery: () => ({ pageNum: 1, pageSize: 10, keyword: '' }),
  initForm: () => ({
    assetId: null,
    assetCode: '',
    assetName: '',
    vendor: '',
    family: '',
    assetVersion: '',
    modality: 'TEXT',
    contextWindow: null,
    maxOutputTokens: null,
    supportsStream: true,
    supportsTool: true,
    supportsJsonSchema: false,
    supportsMultimodal: false,
    modelName: '',
    deploymentCode: '',
    provider: 'openai',
    apiKey: '',
    secretExpiresAt: null,
    baseUrl: '',
    region: '',
    environment: 'PRODUCTION',
    model: '',
    isDefault: false,
    status: 1,
    lifecycleStatus: 'ACTIVE',
  }),
  // 编辑不回填任何凭据值；空值表示保持当前 SecretRef 版本。
  toForm: (row) => ({
    assetId: row.assetId,
    assetCode: row.assetCode,
    assetName: row.assetName,
    vendor: row.vendor,
    family: row.family,
    assetVersion: row.assetVersion,
    modality: row.modality,
    contextWindow: row.contextWindow,
    maxOutputTokens: row.maxOutputTokens,
    supportsStream: row.supportsStream,
    supportsTool: row.supportsTool,
    supportsJsonSchema: row.supportsJsonSchema,
    supportsMultimodal: row.supportsMultimodal,
    modelName: row.modelName,
    deploymentCode: row.deploymentCode,
    provider: row.protocolAdapter ?? row.provider,
    apiKey: '',
    secretExpiresAt: row.credential?.expiresAt ?? null,
    baseUrl: row.baseUrl,
    region: row.region,
    environment: row.environment ?? 'PRODUCTION',
    model: row.model,
    isDefault: row.isDefault,
    status: row.status,
    lifecycleStatus: row.lifecycleStatus ?? 'ACTIVE',
  }),
  beforeSubmit: (mode, value) => {
    if (mode === 'create' && !value.apiKey?.trim()) {
      ElMessage.warning('新建部署必须填写凭据')
      return false
    }
    if (assetMode.value === 'existing' && !value.assetId) {
      ElMessage.warning('请选择模型资产')
      return false
    }
    if (assetMode.value === 'new' && !value.assetName?.trim()) {
      ElMessage.warning('请填写新资产名称')
      return false
    }
    return true
  },
})

const healthyCount = computed(() => list.value.filter((row) => row.health?.healthStatus === 'HEALTHY').length)
const unknownCount = computed(() => list.value.filter((row) => !row.health || row.health.healthStatus === 'UNKNOWN').length)
const credentialRiskCount = computed(() => list.value.filter((row) =>
  row.credential && row.credential.status !== 'ACTIVE').length)
const savePending = computed(() => preflightingSave.value || submitting.value)

interface ProviderPreset {
  value: string
  label: string
  defaultBaseUrl: string
  modelPlaceholder: string
}

const providerPresets: ProviderPreset[] = [
  { value: 'openai', label: 'OpenAI 兼容', defaultBaseUrl: 'https://api.openai.com/v1', modelPlaceholder: '如 gpt-4o-mini' },
  { value: 'dashscope', label: '百炼 DashScope', defaultBaseUrl: 'https://dashscope.aliyuncs.com', modelPlaceholder: '如 qwen-max' },
  { value: 'anthropic', label: 'Anthropic Claude', defaultBaseUrl: 'https://api.anthropic.com', modelPlaceholder: '如 claude-sonnet-4-5' },
  { value: 'gemini', label: 'Google Gemini', defaultBaseUrl: 'https://generativelanguage.googleapis.com', modelPlaceholder: '如 gemini-2.5-flash' },
]

function presetOf(provider: string | null | undefined): ProviderPreset {
  return providerPresets.find((preset) => preset.value === provider) ?? providerPresets[0]
}

function handleProviderChange(provider: string) {
  const preset = presetOf(provider)
  if (!form.baseUrl || providerPresets.some((item) => item.defaultBaseUrl === form.baseUrl)) {
    form.baseUrl = preset.defaultBaseUrl
  }
}

function handleAssetSelected(assetId: number | null | undefined) {
  const asset = assets.value.find((item) => item.id === assetId)
  if (!asset) return
  form.model = asset.modelKey
  form.vendor = asset.vendor
}

async function loadAssets() {
  assets.value = await listModelAssetOptions()
}

function openCreateModel() {
  editingRow.value = null
  assetMode.value = assets.value.length > 0 ? 'existing' : 'new'
  openCreate()
  if (assetMode.value === 'existing') {
    form.assetId = assets.value[0]?.id ?? null
    handleAssetSelected(form.assetId)
  }
}

function openEditModel(row: ModelVO) {
  editingRow.value = row
  assetMode.value = row.assetId ? 'existing' : 'new'
  openEdit(row)
}

async function handleSubmitModel() {
  if (savePending.value) return
  preflightingSave.value = true
  try {
    if (dialogMode.value === 'edit'
      && editingId.value
      && editingRow.value?.status === 1
      && form.status === 0) {
      const impact = await getModelImpact(editingId.value, 'DISABLE')
      if (!impact.allowed) {
        ElMessage.error(`禁用被阻断：仍有 ${impact.blockerCount} 个生效引用`)
        openGovernance(editingRow.value)
        return
      }
    }
    if (assetMode.value === 'new') {
      form.assetId = null
    }
    await submitCrud()
    if (!dialogVisible.value) {
      await loadAssets()
    }
  } finally {
    preflightingSave.value = false
  }
}

async function handleTest(row: ModelVO) {
  if (testingId.value !== null) return
  testingId.value = row.id
  try {
    const result = await runModelHealthCheck(row.id)
    if (result.testStatus === 1) {
      ElMessage.success(`健康探测通过 · ${result.latencyMs ?? 0} ms`)
    } else {
      ElMessage.error(`${result.errorCategory ?? 'UNKNOWN'} · ${result.message || '健康探测失败'}`)
    }
    await loadList()
  } finally {
    testingId.value = null
  }
}

async function handleDelete(row: ModelVO) {
  if (deletingId.value !== null) return
  deletingId.value = row.id
  try {
    const impact = await getModelImpact(row.id, 'DELETE')
    if (!impact.allowed) {
      ElMessage.error(`删除被阻断：仍有 ${impact.blockerCount} 个生效引用`)
      openGovernance(row)
      return
    }
    await ElMessageBox.confirm(
      `预检已通过。确认删除部署「${row.modelName}」？资产和凭据审计记录将保留。`,
      '删除模型部署',
      { type: 'warning' },
    )
    await deleteModel(row.id)
    ElMessage.success('删除成功')
    const currentPage = query.pageNum ?? 1
    if (list.value.length === 1 && currentPage > 1) {
      query.pageNum = currentPage - 1
    }
    await loadList()
  } finally {
    deletingId.value = null
  }
}

function openGovernance(row: ModelVO) {
  governanceModelId.value = row.id
  governanceVisible.value = true
}

function healthTagType(status: string | null | undefined) {
  if (status === 'HEALTHY') return 'success'
  if (status === 'DEGRADED' || status === 'RECOVERING') return 'warning'
  if (status === 'UNHEALTHY') return 'danger'
  return 'info'
}

function credentialTagType(row: ModelVO) {
  if (!row.credential) return 'info'
  return row.credential.status === 'ACTIVE' ? 'success' : 'danger'
}

function formatTime(value: string | null | undefined) {
  if (!value) return '—'
  return new Date(value).toLocaleString('zh-CN', { hour12: false })
}

onMounted(async () => {
  await Promise.all([loadList(), loadAssets()])
})
</script>

<template>
  <div class="modelops-page">
    <CrudLoadState :error="loadError" :has-stale-data="list.length > 0" :loading="loading" @retry="loadList" />
    <section class="page-hero">
      <div>
        <p class="eyebrow">ENTERPRISE MODEL CONTROL PLANE</p>
        <h2>模型治理工作台</h2>
        <p>把模型能力资产、运行部署、SecretRef 和健康证据放在同一条可审计链路中。</p>
      </div>
      <el-button v-permission="'model:add'" class="cw-final-action" type="primary" size="large" @click="openCreateModel">新建部署</el-button>
    </section>

    <section class="summary-strip">
      <div><span>当前页部署</span><strong>{{ list.length }}</strong></div>
      <div class="is-good"><span>健康</span><strong>{{ healthyCount }}</strong></div>
      <div class="is-risk"><span>凭据风险</span><strong>{{ credentialRiskCount }}</strong></div>
      <div><span>待建立基线</span><strong>{{ unknownCount }}</strong></div>
    </section>

    <el-card class="workspace-card" shadow="never">
      <div class="toolbar">
        <div>
          <el-input v-model="query.keyword" placeholder="搜索部署名称" style="width: 240px" clearable @keyup.enter="handleSearch" />
          <el-button @click="handleSearch">搜索</el-button>
        </div>
        <span>总计 {{ total }} 个部署</span>
      </div>

      <el-table v-loading="loading" :data="list" row-key="id" style="width: 100%">
        <el-table-column label="资产 / 部署" min-width="230">
          <template #default="{ row }">
            <div class="primary-cell">
              <strong>{{ row.assetName || row.model }}</strong>
              <span>{{ row.modelName }} · {{ row.deploymentCode || `deployment-${row.id}` }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="端点" min-width="220">
          <template #default="{ row }">
            <div class="endpoint-cell">
              <span>{{ presetOf(row.protocolAdapter || row.provider).label }}</span>
              <small>{{ row.environment || 'PRODUCTION' }} · {{ row.region || 'GLOBAL' }} · r{{ row.endpointRevision || 1 }}</small>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="凭据" width="160">
          <template #default="{ row }">
            <el-tag :type="credentialTagType(row)" effect="plain">
              {{ row.credential?.status || 'LEGACY' }} · v{{ row.credential?.currentVersion || 1 }}
            </el-tag>
            <small class="cell-note">{{ row.credential?.expiresAt ? formatTime(row.credential.expiresAt) : '无到期时间' }}</small>
          </template>
        </el-table-column>
        <el-table-column label="健康" width="150">
          <template #default="{ row }">
            <el-tag :type="healthTagType(row.health?.healthStatus)">{{ row.health?.healthStatus || 'UNKNOWN' }}</el-tag>
            <small class="cell-note">{{ row.health?.lastLatencyMs == null ? '尚无延迟数据' : `${row.health.lastLatencyMs} ms` }}</small>
          </template>
        </el-table-column>
        <el-table-column label="生命周期" width="120">
          <template #default="{ row }">
            <span class="lifecycle" :class="{ muted: row.status !== 1 }">
              {{ row.status === 1 ? (row.lifecycleStatus || 'ACTIVE') : 'DISABLED' }}
            </span>
            <el-tag v-if="row.isDefault" class="default-tag" type="warning" size="small">默认</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="286" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openGovernance(row)">治理详情</el-button>
            <el-button
              v-permission="'model:health-test'"
              link
              type="primary"
              :loading="testingId === row.id"
              :disabled="testingId !== null && testingId !== row.id"
              @click="handleTest(row)"
            >健康探测</el-button>
            <el-button v-permission="'model:edit'" link type="primary" @click="openEditModel(row)">编辑</el-button>
            <el-button v-permission="'model:delete'" link type="danger" :loading="deletingId === row.id" @click="handleDelete(row)">删除</el-button>
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

    <ModelRoutingPanel v-if="auth.hasPermission('model:view')" />

    <ModelExperimentPanel v-if="auth.hasPermission('model-experiment:view')" />

    <el-dialog
      v-model="dialogVisible"
      :title="dialogMode === 'create' ? '新建模型部署' : '编辑模型部署'"
      width="min(760px, 94vw)"
      destroy-on-close
    >
      <el-form ref="formRef" :model="form" label-position="top">
        <div class="form-section">
          <div class="form-section-title">
            <span>01</span>
            <div><strong>模型资产</strong><small>描述模型本身的稳定能力，不包含端点与密钥。</small></div>
          </div>
          <el-radio-group v-model="assetMode" :disabled="dialogMode === 'edit' && !!form.assetId">
            <el-radio-button value="existing">选择已有资产</el-radio-button>
            <el-radio-button value="new">登记新资产</el-radio-button>
          </el-radio-group>
          <el-form-item v-if="assetMode === 'existing'" label="模型资产" prop="assetId">
            <el-select v-model="form.assetId" filterable style="width: 100%" @change="handleAssetSelected">
              <el-option v-for="asset in assets" :key="asset.id" :label="`${asset.assetName} · ${asset.vendor} · ${asset.modelKey}`" :value="asset.id" />
            </el-select>
          </el-form-item>
          <div v-else class="form-grid">
            <el-form-item label="资产名称" prop="assetName"><el-input v-model="form.assetName!" placeholder="如 GPT-4o Mini" /></el-form-item>
            <el-form-item label="资产编码"><el-input v-model="form.assetCode!" placeholder="留空自动生成" /></el-form-item>
            <el-form-item label="厂商"><el-input v-model="form.vendor!" placeholder="如 OPENAI / ALIBABA" /></el-form-item>
            <el-form-item label="家族 / 版本"><el-input v-model="form.family!" placeholder="如 GPT-4o / 2026-08" /></el-form-item>
            <el-form-item label="上下文窗口"><el-input-number v-model="form.contextWindow!" :min="1" controls-position="right" style="width: 100%" /></el-form-item>
            <el-form-item label="最大输出 Token"><el-input-number v-model="form.maxOutputTokens!" :min="1" controls-position="right" style="width: 100%" /></el-form-item>
            <el-form-item label="能力声明" class="full-row">
              <el-checkbox v-model="form.supportsStream">流式</el-checkbox>
              <el-checkbox v-model="form.supportsTool">工具调用</el-checkbox>
              <el-checkbox v-model="form.supportsJsonSchema">JSON Schema</el-checkbox>
              <el-checkbox v-model="form.supportsMultimodal">多模态</el-checkbox>
            </el-form-item>
          </div>
        </div>

        <div class="form-section">
          <div class="form-section-title">
            <span>02</span>
            <div><strong>运行部署</strong><small>Agent 继续引用部署 ID，端点变更通过修订号追踪。</small></div>
          </div>
          <div class="form-grid">
            <el-form-item label="部署名称" prop="modelName" :rules="[{ required: true, message: '请输入部署名称' }]"><el-input v-model="form.modelName" /></el-form-item>
            <el-form-item label="部署编码"><el-input v-model="form.deploymentCode!" placeholder="留空自动生成" /></el-form-item>
            <el-form-item label="接入协议">
              <el-select v-model="form.provider" style="width: 100%" @change="handleProviderChange">
                <el-option v-for="preset in providerPresets" :key="preset.value" :label="preset.label" :value="preset.value" />
              </el-select>
            </el-form-item>
            <el-form-item label="模型标识" prop="model" :rules="[{ required: true, message: '请输入模型标识' }]"><el-input v-model="form.model" :placeholder="presetOf(form.provider).modelPlaceholder" /></el-form-item>
            <el-form-item label="环境">
              <el-select v-model="form.environment" style="width: 100%">
                <el-option label="生产 PRODUCTION" value="PRODUCTION" />
                <el-option label="预发 STAGING" value="STAGING" />
                <el-option label="开发 DEVELOPMENT" value="DEVELOPMENT" />
              </el-select>
            </el-form-item>
            <el-form-item label="地域"><el-input v-model="form.region!" placeholder="如 cn-hangzhou / global" /></el-form-item>
            <el-form-item label="Base URL" prop="baseUrl" class="full-row" :rules="[{ required: true, message: '请输入 Base URL' }]"><el-input v-model="form.baseUrl" :placeholder="presetOf(form.provider).defaultBaseUrl" /></el-form-item>
          </div>
        </div>

        <div class="form-section">
          <div class="form-section-title">
            <span>03</span>
            <div><strong>凭据与状态</strong><small>编辑留空不会读取旧值；独立轮换请进入治理详情。</small></div>
          </div>
          <div class="form-grid">
            <el-form-item :label="dialogMode === 'edit' ? '新凭据（留空不变）' : '凭据'"><el-input v-model="form.apiKey!" type="password" show-password autocomplete="new-password" /></el-form-item>
            <el-form-item label="凭据到期时间">
              <el-date-picker v-model="form.secretExpiresAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" placeholder="可选" style="width: 100%" />
            </el-form-item>
            <el-form-item label="部署状态"><el-switch v-model="form.status" :active-value="1" :inactive-value="0" active-text="启用" inactive-text="禁用" /></el-form-item>
            <el-form-item label="设为默认"><el-switch v-model="form.isDefault" /></el-form-item>
          </div>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button class="cw-final-action" type="primary" :loading="savePending" @click="handleSubmitModel">保存部署</el-button>
      </template>
    </el-dialog>

    <ModelGovernanceDrawer v-model="governanceVisible" :model-id="governanceModelId" @refreshed="loadList" />
  </div>
</template>

<style scoped>
.modelops-page { --ink: var(--cw-text); --muted: var(--cw-text-muted); --line: var(--cw-line); --accent: var(--cw-cobalt); min-height: 100%; background: var(--cw-canvas); }
.page-hero { display: flex; align-items: flex-end; justify-content: space-between; gap: 28px; padding: 30px 34px 28px; color: white; border-radius: 16px 16px 0 0; background: linear-gradient(120deg, rgb(10 22 43 / 98%), rgb(31 55 91 / 94%)), repeating-linear-gradient(90deg, transparent 0 47px, rgb(255 255 255 / 4%) 48px); }
.page-hero h2 { margin: 3px 0 8px; font-size: 30px; letter-spacing: -.03em; }
.page-hero p { margin: 0; color: #cbd5e1; }
.eyebrow { font-size: 11px; font-weight: 700; letter-spacing: .16em; }
.summary-strip { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); border: 1px solid var(--line); border-top: 0; background: var(--cw-paper); }
.summary-strip > div { padding: 18px 24px; border-right: 1px solid var(--line); }
.summary-strip > div:last-child { border-right: 0; }
.summary-strip span { display: block; color: var(--muted); font-size: 12px; }
.summary-strip strong { display: block; margin-top: 4px; color: var(--ink); font-size: 24px; }
.summary-strip .is-good strong { color: var(--cw-success); }
.summary-strip .is-risk strong { color: var(--cw-danger); }
.workspace-card { border: 0; border-radius: 0 0 16px 16px; }
.toolbar { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-bottom: 16px; color: var(--muted); font-size: 13px; }
.toolbar > div { display: flex; gap: 8px; }
.primary-cell strong, .primary-cell span, .endpoint-cell span, .endpoint-cell small, .cell-note { display: block; }
.primary-cell strong { color: var(--ink); font-size: 14px; }
.primary-cell span, .endpoint-cell small, .cell-note { margin-top: 4px; color: var(--muted); font-size: 11px; }
.endpoint-cell span { color: var(--el-text-color-regular); }
.lifecycle { color: var(--cw-success); font-size: 12px; font-weight: 700; letter-spacing: .04em; }
.lifecycle.muted { color: var(--cw-text-muted); }
.default-tag { display: block; width: fit-content; margin-top: 5px; }
.pagination { margin-top: 18px; justify-content: flex-end; }
.form-section { margin-bottom: 20px; padding: 18px; border: 1px solid var(--line); border-radius: 12px; background: var(--el-fill-color-extra-light); }
.form-section-title { display: flex; gap: 11px; margin-bottom: 16px; }
.form-section-title > span { display: grid; place-items: center; width: 28px; height: 28px; color: white; border-radius: 8px; background: var(--accent); font-size: 11px; font-weight: 700; }
.form-section-title strong, .form-section-title small { display: block; }
.form-section-title strong { color: var(--ink); }
.form-section-title small { margin-top: 3px; color: var(--muted); }
.form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); column-gap: 16px; }
.full-row { grid-column: 1 / -1; }
@media (max-width: 760px) {
  .page-hero { align-items: flex-start; flex-direction: column; padding: 24px; }
  .summary-strip { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .summary-strip > div:nth-child(2) { border-right: 0; }
  .summary-strip > div:nth-child(-n + 2) { border-bottom: 1px solid var(--line); }
  .form-grid { grid-template-columns: 1fr; }
  .full-row { grid-column: auto; }
  .toolbar { align-items: stretch; flex-direction: column; }
}
</style>
