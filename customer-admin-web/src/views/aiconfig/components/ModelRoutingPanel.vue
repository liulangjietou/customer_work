<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import {
  activateModelRouteVersion,
  createModelRoutePolicy,
  createModelRouteVersion,
  dryRunModelRoutePolicy,
  listModelRoutePolicies,
  listModelRouteVersions,
  pageModels,
  validateModelRouteVersion,
} from '@/api/model'
import type {
  ModelRouteCondition,
  ModelRouteDryRunRequest,
  ModelRouteDryRunResult,
  ModelRoutePolicy,
  ModelRoutePurpose,
  ModelRouteRule,
  ModelRouteRuleRequest,
  ModelRouteValidation,
  ModelRouteVersion,
  ModelVO,
} from '@/types/api'

interface EditableRule {
  key: number
  purpose: ModelRoutePurpose
  deploymentId: number | null
  priority: number
  condition: ModelRouteCondition
}

const loading = ref(false)
const saving = ref(false)
const policies = ref<ModelRoutePolicy[]>([])
const deployments = ref<ModelVO[]>([])
const selectedPolicy = ref<ModelRoutePolicy | null>(null)
const versions = ref<ModelRouteVersion[]>([])
const detailVisible = ref(false)
const editorVisible = ref(false)
const editorMode = ref<'create' | 'version'>('create')
const validation = ref<ModelRouteValidation | null>(null)
const dryRunVisible = ref(false)
const dryRunLoading = ref(false)
const dryRunResult = ref<ModelRouteDryRunResult | null>(null)
let nextRuleKey = 1

const draft = reactive({
  policyCode: '',
  policyName: '',
  description: '',
  changeNote: '',
  rules: [] as EditableRule[],
})

const dryRun = reactive<ModelRouteDryRunRequest>({
  agentId: null,
  channelCode: '',
  inputTokens: 1000,
  requiresTools: null,
  requiresStructuredOutput: null,
  complexity: 'LOW',
  preferFallback: false,
})

const purposeOptions: Array<{ value: ModelRoutePurpose; label: string; hint: string }> = [
  { value: 'DEFAULT', label: '默认', hint: '无条件基线，只允许一条' },
  { value: 'ECONOMY', label: '经济', hint: '低复杂度或小请求' },
  { value: 'COMPLEX_REASONING', label: '复杂推理', hint: '高复杂度或能力要求' },
  { value: 'FALLBACK', label: '故障兜底', hint: 'DEGRADE 时只走此类' },
]

const activeCount = computed(() => policies.value.filter((policy) => policy.status === 'ACTIVE').length)

onMounted(() => void load())

async function load() {
  loading.value = true
  try {
    const [policyList, deploymentPage] = await Promise.all([
      listModelRoutePolicies(),
      pageModels({ pageNum: 1, pageSize: 200 }),
    ])
    policies.value = policyList
    deployments.value = deploymentPage.list
  } finally {
    loading.value = false
  }
}

function emptyCondition(): ModelRouteCondition {
  return {
    agentIds: [],
    channelCodes: [],
    minInputTokens: null,
    maxInputTokens: null,
    requiresTools: null,
    requiresStructuredOutput: null,
    complexity: null,
  }
}

function editableRule(purpose: ModelRoutePurpose, priority: number, source?: ModelRouteRule): EditableRule {
  return {
    key: nextRuleKey++,
    purpose,
    deploymentId: source?.deploymentId ?? deployments.value[0]?.id ?? null,
    priority,
    condition: source ? {
      agentIds: [...(source.condition.agentIds ?? [])],
      channelCodes: [...(source.condition.channelCodes ?? [])],
      minInputTokens: source.condition.minInputTokens,
      maxInputTokens: source.condition.maxInputTokens,
      requiresTools: source.condition.requiresTools,
      requiresStructuredOutput: source.condition.requiresStructuredOutput,
      complexity: source.condition.complexity,
    } : emptyCondition(),
  }
}

function resetDraft() {
  draft.policyCode = ''
  draft.policyName = ''
  draft.description = ''
  draft.changeNote = ''
  draft.rules = [editableRule('DEFAULT', 100), editableRule('FALLBACK', 900)]
  validation.value = null
}

function openCreate() {
  editorMode.value = 'create'
  selectedPolicy.value = null
  resetDraft()
  editorVisible.value = true
}

async function openDetail(policy: ModelRoutePolicy) {
  selectedPolicy.value = policy
  versions.value = await listModelRouteVersions(policy.id)
  detailVisible.value = true
}

async function openNewVersion(policy: ModelRoutePolicy) {
  selectedPolicy.value = policy
  versions.value = await listModelRouteVersions(policy.id)
  editorMode.value = 'version'
  draft.policyCode = policy.policyCode
  draft.policyName = policy.policyName
  draft.description = policy.description ?? ''
  draft.changeNote = ''
  const base = versions.value[0] ?? policy.currentVersion
  draft.rules = base?.rules.map((rule) => editableRule(rule.purpose, rule.priority, rule))
    ?? [editableRule('DEFAULT', 100), editableRule('FALLBACK', 900)]
  validation.value = null
  editorVisible.value = true
}

function addRule() {
  const priority = Math.max(0, ...draft.rules.map((rule) => rule.priority)) + 10
  draft.rules.push(editableRule('ECONOMY', priority))
}

function removeRule(index: number) {
  draft.rules.splice(index, 1)
  validation.value = null
}

function requestRules(): ModelRouteRuleRequest[] {
  return draft.rules.map((rule) => ({
    purpose: rule.purpose,
    deploymentId: rule.deploymentId,
    priority: rule.priority,
    condition: {
      ...rule.condition,
      agentIds: rule.condition.agentIds
        .map((value) => Number(value)).filter((value) => Number.isInteger(value) && value > 0),
      channelCodes: rule.condition.channelCodes.map((value) => String(value).trim()).filter(Boolean),
    },
  }))
}

async function validateDraft() {
  if (editorMode.value !== 'version' || !selectedPolicy.value) {
    validation.value = localValidation()
    return
  }
  validation.value = await validateModelRouteVersion(selectedPolicy.value.id, {
    changeNote: draft.changeNote,
    rules: requestRules(),
  })
}

function localValidation(): ModelRouteValidation {
  const conflicts = []
  const defaults = draft.rules.filter((rule) => rule.purpose === 'DEFAULT')
  if (defaults.length !== 1) {
    conflicts.push({ code: 'DEFAULT_REQUIRED', ruleIndex: null, conflictingRuleIndex: null, message: '必须且只能配置一条 DEFAULT 规则' })
  }
  if (draft.rules.some((rule) => !rule.deploymentId)) {
    conflicts.push({ code: 'DEPLOYMENT_REQUIRED', ruleIndex: null, conflictingRuleIndex: null, message: '每条规则都必须引用一个部署' })
  }
  return { valid: conflicts.length === 0, conflicts }
}

async function saveDraft() {
  if (!draft.rules.length) {
    ElMessage.warning('至少配置一条路由规则')
    return
  }
  await validateDraft()
  if (!validation.value?.valid) {
    ElMessage.error(validation.value?.conflicts[0]?.message ?? '规则校验未通过')
    return
  }
  saving.value = true
  try {
    if (editorMode.value === 'create') {
      await createModelRoutePolicy({
        policyCode: draft.policyCode,
        policyName: draft.policyName,
        description: draft.description,
        changeNote: draft.changeNote,
        rules: requestRules(),
      })
      ElMessage.success('路由策略与 v1 草稿已创建')
    } else if (selectedPolicy.value) {
      await createModelRouteVersion(selectedPolicy.value.id, {
        changeNote: draft.changeNote,
        rules: requestRules(),
      })
      ElMessage.success('新的不可变版本已创建')
    }
    editorVisible.value = false
    await load()
    if (selectedPolicy.value) {
      const refreshed = policies.value.find((item) => item.id === selectedPolicy.value?.id)
      if (refreshed) await openDetail(refreshed)
    }
  } finally {
    saving.value = false
  }
}

async function activate(policy: ModelRoutePolicy, version: ModelRouteVersion) {
  await ElMessageBox.confirm(
    `激活 v${version.versionNo} 前，后端会逐个校验部署 ACTIVE 状态与未过期认证。确认继续？`,
    '激活路由版本',
    { type: 'warning' },
  )
  await activateModelRouteVersion(policy.id, version.id)
  ElMessage.success(`v${version.versionNo} 已激活`)
  await load()
  const refreshed = policies.value.find((item) => item.id === policy.id)
  if (refreshed) await openDetail(refreshed)
}

function openDryRun(policy: ModelRoutePolicy) {
  selectedPolicy.value = policy
  dryRunResult.value = null
  Object.assign(dryRun, {
    agentId: null,
    channelCode: '',
    inputTokens: 1000,
    requiresTools: null,
    requiresStructuredOutput: null,
    complexity: 'LOW',
    preferFallback: false,
  })
  dryRunVisible.value = true
}

async function executeDryRun() {
  if (!selectedPolicy.value) return
  dryRunLoading.value = true
  try {
    dryRunResult.value = await dryRunModelRoutePolicy(selectedPolicy.value.id, { ...dryRun })
  } finally {
    dryRunLoading.value = false
  }
}

function policyTagType(status: string) {
  if (status === 'ACTIVE') return 'success'
  if (status === 'DRAFT') return 'warning'
  return 'info'
}

function purposeLabel(purpose: ModelRoutePurpose) {
  return purposeOptions.find((item) => item.value === purpose)?.label ?? purpose
}

function formatTime(value: string | null | undefined) {
  if (!value) return '—'
  return new Date(value).toLocaleString('zh-CN', { hour12: false })
}
</script>

<template>
  <el-card v-loading="loading" class="routing-panel" shadow="never">
    <template #header>
      <div class="panel-header">
        <div>
          <p class="eyebrow">POLICY ROUTING</p>
          <h2>显式路由策略</h2>
          <p>规则只引用部署 ID；版本内容只增不改，并在激活前校验所有目标部署的上线认证。</p>
        </div>
        <div class="header-actions">
          <span>{{ activeCount }} 个生效策略 / {{ policies.length }} 个策略</span>
          <el-button v-permission="'model:edit'" type="primary" @click="openCreate">新建策略</el-button>
        </div>
      </div>
    </template>

    <el-alert
      title="ACTIVE 策略只有绑定到智能体后才影响流量；激活新版本会清理 Admin 实例缓存并经可靠任务重发 starter 运行时配置。"
      type="info"
      :closable="false"
      show-icon
    />

    <el-table class="policy-table" :data="policies" row-key="id" empty-text="尚未创建路由策略">
      <el-table-column label="策略" min-width="210">
        <template #default="{ row }">
          <div class="primary-cell"><strong>{{ row.policyName }}</strong><span>{{ row.policyCode }}</span></div>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="105">
        <template #default="{ row }"><el-tag :type="policyTagType(row.status)">{{ row.status }}</el-tag></template>
      </el-table-column>
      <el-table-column label="版本" width="130">
        <template #default="{ row }"><strong>v{{ row.currentVersionNo ?? '—' }}</strong><small> / latest v{{ row.latestVersionNo }}</small></template>
      </el-table-column>
      <el-table-column prop="description" label="用途说明" min-width="230" show-overflow-tooltip />
      <el-table-column label="最近更新" width="180">
        <template #default="{ row }">{{ formatTime(row.updateTime) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="260" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDetail(row)">版本与规则</el-button>
          <el-button v-permission="'model:edit'" link type="primary" @click="openNewVersion(row)">新版本</el-button>
          <el-button link type="primary" :disabled="row.status !== 'ACTIVE'" @click="openDryRun(row)">Dry-run</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-drawer v-model="detailVisible" size="min(840px, 94vw)" destroy-on-close>
      <template #header>
        <div class="drawer-title">
          <div><p class="eyebrow">IMMUTABLE VERSIONS</p><h2>{{ selectedPolicy?.policyName }}</h2></div>
          <el-tag v-if="selectedPolicy" :type="policyTagType(selectedPolicy.status)">{{ selectedPolicy.status }}</el-tag>
        </div>
      </template>
      <div class="version-list">
        <section v-for="version in versions" :key="version.id" class="version-card">
          <div class="version-heading">
            <div>
              <strong>v{{ version.versionNo }}</strong>
              <el-tag :type="policyTagType(version.status)" size="small">{{ version.status }}</el-tag>
              <span>{{ version.changeNote || '无变更说明' }}</span>
            </div>
            <el-button
              v-if="version.status === 'DRAFT' && selectedPolicy"
              v-permission="'model:edit'"
              type="primary"
              plain
              @click="activate(selectedPolicy, version)"
            >激活版本</el-button>
          </div>
          <div class="version-meta">
            <span>sha256 {{ version.contentHash.slice(0, 16) }}…</span>
            <span>创建 {{ formatTime(version.createTime) }}</span>
            <span v-if="version.activatedAt">激活 {{ formatTime(version.activatedAt) }}</span>
          </div>
          <el-table :data="version.rules" size="small">
            <el-table-column label="用途" width="115">
              <template #default="{ row }"><el-tag effect="plain">{{ purposeLabel(row.purpose) }}</el-tag></template>
            </el-table-column>
            <el-table-column label="部署" min-width="180">
              <template #default="{ row }">{{ row.deploymentName || row.deploymentCode || `#${row.deploymentId}` }}</template>
            </el-table-column>
            <el-table-column prop="priority" label="优先级" width="90" />
            <el-table-column prop="conditionSummary" label="命中条件" min-width="250" show-overflow-tooltip />
          </el-table>
        </section>
      </div>
    </el-drawer>

    <el-dialog v-model="editorVisible" :title="editorMode === 'create' ? '新建路由策略' : `创建 ${selectedPolicy?.policyName} 的新版本`" width="min(980px, 96vw)" destroy-on-close>
      <el-form :model="draft" label-position="top">
        <div v-if="editorMode === 'create'" class="policy-form-grid">
          <el-form-item label="策略编码" required><el-input v-model="draft.policyCode" placeholder="如 customer-service-main" /></el-form-item>
          <el-form-item label="策略名称" required><el-input v-model="draft.policyName" placeholder="如 客服主路由" /></el-form-item>
          <el-form-item class="full-row" label="用途说明"><el-input v-model="draft.description" type="textarea" :rows="2" /></el-form-item>
        </div>
        <el-form-item label="版本变更说明"><el-input v-model="draft.changeNote" placeholder="说明本次流量决策变更" /></el-form-item>

        <div class="rules-heading">
          <div><h3>规则清单</h3><p>数字越小优先级越高；所有条件维度按 AND 匹配。</p></div>
          <el-button @click="addRule">添加规则</el-button>
        </div>
        <section v-for="(rule, index) in draft.rules" :key="rule.key" class="rule-editor">
          <div class="rule-index"><strong>RULE {{ String(index + 1).padStart(2, '0') }}</strong><el-button link type="danger" @click="removeRule(index)">移除</el-button></div>
          <div class="rule-grid">
            <el-form-item label="用途">
              <el-select v-model="rule.purpose" style="width: 100%">
                <el-option v-for="option in purposeOptions" :key="option.value" :label="`${option.label} · ${option.hint}`" :value="option.value" />
              </el-select>
            </el-form-item>
            <el-form-item label="目标部署">
              <el-select v-model="rule.deploymentId" filterable style="width: 100%">
                <el-option v-for="deployment in deployments" :key="deployment.id" :label="`${deployment.modelName} · ${deployment.lifecycleStatus}`" :value="deployment.id" />
              </el-select>
            </el-form-item>
            <el-form-item label="优先级"><el-input-number v-model="rule.priority" :min="0" style="width: 100%" /></el-form-item>
            <el-form-item label="复杂度">
              <el-select v-model="rule.condition.complexity" clearable placeholder="不限" style="width: 100%">
                <el-option label="低 LOW" value="LOW" /><el-option label="中 MEDIUM" value="MEDIUM" /><el-option label="高 HIGH" value="HIGH" />
              </el-select>
            </el-form-item>
            <el-form-item label="最小输入 Token"><el-input-number v-model="rule.condition.minInputTokens" :min="0" style="width: 100%" /></el-form-item>
            <el-form-item label="最大输入 Token"><el-input-number v-model="rule.condition.maxInputTokens" :min="0" style="width: 100%" /></el-form-item>
            <el-form-item label="Agent ID">
              <el-select v-model="rule.condition.agentIds" multiple filterable allow-create default-first-option placeholder="不限" style="width: 100%" />
            </el-form-item>
            <el-form-item label="渠道编码">
              <el-select v-model="rule.condition.channelCodes" multiple filterable allow-create default-first-option placeholder="不限" style="width: 100%" />
            </el-form-item>
            <el-form-item label="能力条件">
              <el-select v-model="rule.condition.requiresTools" clearable placeholder="工具不限" style="width: 49%"><el-option label="需要工具" :value="true" /><el-option label="不需要工具" :value="false" /></el-select>
              <el-select v-model="rule.condition.requiresStructuredOutput" clearable placeholder="结构化不限" style="width: 49%; margin-left: 2%"><el-option label="需要结构化" :value="true" /><el-option label="不需要结构化" :value="false" /></el-select>
            </el-form-item>
          </div>
        </section>

        <el-alert
          v-if="validation"
          :title="validation.valid ? '规则校验通过' : `发现 ${validation.conflicts.length} 个冲突`"
          :type="validation.valid ? 'success' : 'error'"
          :closable="false"
          show-icon
        >
          <template v-if="!validation.valid" #default>
            <div v-for="conflict in validation.conflicts" :key="`${conflict.code}-${conflict.ruleIndex}-${conflict.conflictingRuleIndex}`">{{ conflict.message }}</div>
          </template>
        </el-alert>
      </el-form>
      <template #footer>
        <el-button @click="editorVisible = false">取消</el-button>
        <el-button @click="validateDraft">校验冲突</el-button>
        <el-button type="primary" :loading="saving" @click="saveDraft">{{ editorMode === 'create' ? '创建 v1 草稿' : '创建不可变版本' }}</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="dryRunVisible" title="路由 Dry-run" width="min(860px, 94vw)" destroy-on-close>
      <el-form :model="dryRun" label-position="top">
        <div class="policy-form-grid">
          <el-form-item label="Agent ID"><el-input-number v-model="dryRun.agentId" :min="1" style="width: 100%" /></el-form-item>
          <el-form-item label="渠道编码"><el-input v-model="dryRun.channelCode" placeholder="如 wechat" /></el-form-item>
          <el-form-item label="输入 Token"><el-input-number v-model="dryRun.inputTokens" :min="0" style="width: 100%" /></el-form-item>
          <el-form-item label="复杂度"><el-select v-model="dryRun.complexity" clearable style="width: 100%"><el-option label="LOW" value="LOW" /><el-option label="MEDIUM" value="MEDIUM" /><el-option label="HIGH" value="HIGH" /></el-select></el-form-item>
          <el-form-item label="能力要求"><el-checkbox v-model="dryRun.requiresTools">工具调用</el-checkbox><el-checkbox v-model="dryRun.requiresStructuredOutput">结构化输出</el-checkbox></el-form-item>
          <el-form-item label="配额降级"><el-switch v-model="dryRun.preferFallback" active-text="仅备用候选" inactive-text="常规规则" /></el-form-item>
        </div>
        <el-button type="primary" :loading="dryRunLoading" @click="executeDryRun">执行 Dry-run</el-button>
      </el-form>

      <section v-if="dryRunResult" class="dry-result" :class="{ 'is-closed': dryRunResult.failClosed }">
        <div class="dry-summary">
          <div><span>{{ dryRunResult.failClosed ? 'FAIL-CLOSED' : 'MATCHED' }}</span><strong>{{ dryRunResult.deploymentName || dryRunResult.deploymentCode || '无可用部署' }}</strong></div>
          <el-tag :type="dryRunResult.failClosed ? 'danger' : 'success'">{{ dryRunResult.purpose || 'NO MATCH' }}</el-tag>
        </div>
        <p>{{ dryRunResult.explanation }}</p>
        <el-table :data="dryRunResult.candidates" size="small">
          <el-table-column prop="priority" label="优先级" width="80" />
          <el-table-column prop="purpose" label="用途" width="130" />
          <el-table-column prop="deploymentId" label="部署 ID" width="100" />
          <el-table-column label="命中" width="80"><template #default="{ row }"><el-tag :type="row.matched ? 'success' : 'info'">{{ row.matched ? '是' : '否' }}</el-tag></template></el-table-column>
          <el-table-column label="逐维解释" min-width="280"><template #default="{ row }">{{ row.reasons.join('；') }}</template></el-table-column>
        </el-table>
      </section>
    </el-dialog>
  </el-card>
</template>

<style scoped>
.routing-panel { margin-top: 20px; border: 0; border-radius: 16px; }
.panel-header, .header-actions, .drawer-title, .version-heading, .rules-heading, .dry-summary { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.panel-header h2, .drawer-title h2, .rules-heading h3 { margin: 2px 0 5px; color: #172033; }
.panel-header p, .rules-heading p { margin: 0; color: #64748b; font-size: 13px; }
.eyebrow { color: #2457d6 !important; font-size: 10px !important; font-weight: 700; letter-spacing: .15em; }
.header-actions { flex-shrink: 0; color: #64748b; font-size: 12px; }
.policy-table { margin-top: 14px; }
.primary-cell strong, .primary-cell span { display: block; }
.primary-cell span { margin-top: 3px; color: #64748b; font-size: 11px; }
.version-card { margin-bottom: 14px; padding: 16px; border: 1px solid #e2e8f0; border-radius: 12px; background: #fbfcfe; }
.version-heading > div { display: flex; align-items: center; gap: 9px; }
.version-heading > div > strong { font-size: 20px; }
.version-heading > div > span { color: #64748b; font-size: 13px; }
.version-meta { display: flex; flex-wrap: wrap; gap: 16px; margin: 8px 0 12px; color: #64748b; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 11px; }
.policy-form-grid, .rule-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 14px; }
.full-row { grid-column: 1 / -1; }
.rules-heading { margin: 12px 0; }
.rule-editor { margin-bottom: 12px; padding: 14px; border: 1px solid #e2e8f0; border-radius: 12px; background: #f8fafc; }
.rule-index { display: flex; align-items: center; justify-content: space-between; margin-bottom: 8px; color: #2457d6; font-size: 11px; letter-spacing: .08em; }
.dry-result { margin-top: 18px; padding: 16px; border: 1px solid #a7e1d0; border-radius: 12px; background: #effbf7; }
.dry-result.is-closed { border-color: #fecaca; background: #fff5f5; }
.dry-summary span, .dry-summary strong { display: block; }
.dry-summary span { color: #64748b; font-size: 11px; letter-spacing: .1em; }
.dry-summary strong { margin-top: 3px; font-size: 18px; }
.dry-result > p { color: #475569; font-size: 13px; }
@media (max-width: 760px) {
  .panel-header, .header-actions, .rules-heading { align-items: flex-start; flex-direction: column; }
  .policy-form-grid, .rule-grid { grid-template-columns: 1fr; }
  .full-row { grid-column: auto; }
}
</style>
