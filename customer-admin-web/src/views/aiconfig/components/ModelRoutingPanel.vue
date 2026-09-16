<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import CrudLoadState from '@/components/CrudLoadState.vue'
import { useAuthSubmissionScope } from '@/composables/useAuthSubmissionScope'
import { useQueryState } from '@/composables/useQueryState'
import { useRowMutation } from '@/composables/useRowMutation'
import { useAuthStore } from '@/store/auth'
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

const auth = useAuthStore()
const captureIdentity = useAuthSubmissionScope()
const activation = useRowMutation('model:edit')
const saving = ref(false)
const validating = ref(false)
const primary = useQueryState(async () => {
  const [policies, deployments] = await Promise.all([
    listModelRoutePolicies(), pageModels({ pageNum: 1, pageSize: 200 }),
  ])
  return { policies, deployments: deployments.list }
}, () => ({ policies: [] as ModelRoutePolicy[], deployments: [] as ModelVO[] }))
const { loading, error: loadError, loaded } = primary
const policies = computed(() => primary.data.value.policies)
const deployments = computed(() => primary.data.value.deployments)
const selectedPolicy = ref<ModelRoutePolicy | null>(null)
const versionQuery = useQueryState(() => listModelRouteVersions(selectedPolicy.value!.id), () => [] as ModelRouteVersion[])
const { data: versions, loading: versionsLoading, error: versionsError, loaded: versionsLoaded } = versionQuery
const detailVisible = ref(false)
const editorVisible = ref(false)
const editorMode = ref<'create' | 'version'>('create')
const validation = ref<ModelRouteValidation | null>(null)
const dryRunVisible = ref(false)
const dryRunLoading = ref(false)
const dryRunResult = ref<ModelRouteDryRunResult | null>(null)
let nextRuleKey = 1
let selectionGeneration = 0
const editorPending = computed(() => saving.value || validating.value || versionsLoading.value)

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
  if (auth.hasPermission('model:view')) await primary.load()
}

function invalidateSelection() {
  selectionGeneration += 1
  saving.value = validating.value = dryRunLoading.value = false
  validation.value = null
  dryRunResult.value = null
  versionQuery.reset()
}

watch([detailVisible, editorVisible, dryRunVisible], (visible, previous) => {
  if (visible.some((value, index) => !value && previous[index])) invalidateSelection()
}, { flush: 'sync' })

watch([() => auth.token, () => auth.loginGeneration, () => auth.permissions.join('\0')], () => {
  detailVisible.value = editorVisible.value = dryRunVisible.value = false
  invalidateSelection()
  selectedPolicy.value = null
  resetDraft()
  void load()
}, { flush: 'sync' })

function selectPolicy(policy: ModelRoutePolicy | null) {
  detailVisible.value = editorVisible.value = dryRunVisible.value = false
  invalidateSelection()
  selectedPolicy.value = policy
}

function captureSelection() {
  const generation = selectionGeneration
  const identity = captureIdentity()
  return () => identity() && generation === selectionGeneration
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
  if (!auth.hasPermission('model:edit')) return
  selectPolicy(null)
  editorMode.value = 'create'
  resetDraft()
  editorVisible.value = true
}

async function openDetail(policy: ModelRoutePolicy) {
  selectPolicy(policy)
  detailVisible.value = true
  await loadVersions()
}

async function openNewVersion(policy: ModelRoutePolicy) {
  if (!auth.hasPermission('model:edit')) return
  selectPolicy(policy)
  editorMode.value = 'version'
  draft.policyCode = policy.policyCode
  draft.policyName = policy.policyName
  draft.description = policy.description ?? ''
  draft.changeNote = ''
  draft.rules = []
  editorVisible.value = true
  await loadVersions()
}

async function loadVersions() {
  if (!selectedPolicy.value || !auth.hasPermission('model:view')) return
  const current = captureSelection()
  const result = await versionQuery.load()
  if (!result || !current() || !editorVisible.value) return
  const base = result[0] ?? selectedPolicy.value?.currentVersion
  draft.rules = base?.rules.map(rule => editableRule(rule.purpose, rule.priority, rule))
    ?? [editableRule('DEFAULT', 100), editableRule('FALLBACK', 900)]
}

function addRule() {
  if (editorPending.value) return
  const priority = Math.max(0, ...draft.rules.map((rule) => rule.priority)) + 10
  draft.rules.push(editableRule('ECONOMY', priority))
}

function removeRule(index: number) {
  if (editorPending.value) return
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
  if (editorPending.value || !editorVisible.value || !auth.hasPermission('model:edit')) return
  const current = captureSelection()
  const id = editorMode.value === 'version' ? selectedPolicy.value?.id : undefined
  const payload = { changeNote: draft.changeNote, rules: requestRules() }
  validating.value = true
  try {
    const result = await validateRules(id, payload)
    if (current()) validation.value = result
  } catch {
    // 保留输入；请求层反馈校验服务故障。
  } finally {
    if (current()) validating.value = false
  }
}

function validateRules(id: number | undefined, payload: { changeNote: string; rules: ModelRouteRuleRequest[] }) {
  return id ? validateModelRouteVersion(id, payload) : Promise.resolve(localValidation())
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
  if (editorPending.value || !editorVisible.value || !auth.hasPermission('model:edit')) return
  if (editorMode.value === 'version' && !versionsLoaded.value) return
  if (!draft.rules.length) {
    ElMessage.warning('至少配置一条路由规则')
    return
  }
  const current = captureSelection()
  const mode = editorMode.value
  const id = selectedPolicy.value?.id
  const payload = {
    policyCode: draft.policyCode, policyName: draft.policyName, description: draft.description,
    changeNote: draft.changeNote, rules: requestRules(),
  }
  saving.value = true
  try {
    const result = await validateRules(mode === 'version' ? id : undefined, payload)
    if (!current()) return
    validation.value = result
    if (!result.valid) {
      ElMessage.error(result.conflicts[0]?.message ?? '规则校验未通过')
      return
    }
    if (mode === 'create') await createModelRoutePolicy(payload)
    else if (id) await createModelRouteVersion(id, { changeNote: payload.changeNote, rules: payload.rules })
    else return
    if (!current()) return
    ElMessage.success(mode === 'create' ? '路由策略与 v1 草稿已创建' : '新的不可变版本已创建')
    await load()
    if (!current()) return
    const refreshed = policies.value.find(item => item.id === id)
    editorVisible.value = false
    if (refreshed) await openDetail(refreshed)
  } catch {
    // 预检与提交共用一次输入快照；失败保留草稿，旧回调不能提交后续策略。
  } finally {
    if (current()) saving.value = false
  }
}

async function activate(policy: ModelRoutePolicy, version: ModelRouteVersion) {
  const current = captureSelection()
  let written = false
  await activation.run(version.id, async isCurrent => {
    await ElMessageBox.confirm(
      `激活 v${version.versionNo} 前，后端会逐个校验部署 ACTIVE 状态与未过期认证。确认继续？`,
      '激活路由版本', { type: 'warning' },
    )
    if (!isCurrent() || !current() || !detailVisible.value) return
    await activateModelRouteVersion(policy.id, version.id)
    written = true
  }, async () => {
    if (!written || !current()) return
    ElMessage.success(`v${version.versionNo} 已激活`)
    await load()
    if (!current()) return
    const refreshed = policies.value.find(item => item.id === policy.id)
    if (refreshed) selectedPolicy.value = refreshed
    await loadVersions()
  })
}

function openDryRun(policy: ModelRoutePolicy) {
  selectPolicy(policy)
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
  if (!selectedPolicy.value || !dryRunVisible.value || dryRunLoading.value || !auth.hasPermission('model:view')) return
  const current = captureSelection()
  const id = selectedPolicy.value.id
  const payload = { ...dryRun }
  dryRunLoading.value = true
  try {
    const result = await dryRunModelRoutePolicy(id, payload)
    if (current()) dryRunResult.value = result
  } catch {
    // 失败保留查询输入；结果始终属于打开时选定的策略。
  } finally {
    if (current()) dryRunLoading.value = false
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
          <span>{{ loaded ? activeCount : '—' }} 个生效策略 / {{ loaded ? policies.length : '—' }} 个策略</span>
          <el-button v-permission="'model:edit'" class="cw-final-action" type="primary" @click="openCreate">新建策略</el-button>
        </div>
      </div>
    </template>

    <CrudLoadState :error="loadError" :has-stale-data="loaded" :loading="loading" @retry="load" />
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
      <CrudLoadState :error="versionsError" :has-stale-data="versionsLoaded" :loading="versionsLoading" @retry="loadVersions" />
      <div v-loading="versionsLoading" class="version-list">
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
              :loading="activation.isPending(version.id)"
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
      <CrudLoadState :error="versionsError" :has-stale-data="versionsLoaded" :loading="versionsLoading" @retry="loadVersions" />
      <el-form :model="draft" label-position="top" :disabled="editorPending || !auth.hasPermission('model:edit')">
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
        <el-button :loading="validating" :disabled="saving || versionsLoading" @click="validateDraft">校验冲突</el-button>
        <el-button class="cw-final-action" type="primary" :loading="saving" :disabled="validating || (editorMode === 'version' && !versionsLoaded)" @click="saveDraft">{{ editorMode === 'create' ? '创建 v1 草稿' : '创建不可变版本' }}</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="dryRunVisible" title="路由 Dry-run" width="min(860px, 94vw)" destroy-on-close>
      <el-form :model="dryRun" label-position="top" :disabled="dryRunLoading">
        <div class="policy-form-grid">
          <el-form-item label="Agent ID"><el-input-number v-model="dryRun.agentId" :min="1" style="width: 100%" /></el-form-item>
          <el-form-item label="渠道编码"><el-input v-model="dryRun.channelCode" placeholder="如 wechat" /></el-form-item>
          <el-form-item label="输入 Token"><el-input-number v-model="dryRun.inputTokens" :min="0" style="width: 100%" /></el-form-item>
          <el-form-item label="复杂度"><el-select v-model="dryRun.complexity" clearable style="width: 100%"><el-option label="LOW" value="LOW" /><el-option label="MEDIUM" value="MEDIUM" /><el-option label="HIGH" value="HIGH" /></el-select></el-form-item>
          <el-form-item label="能力要求"><el-checkbox v-model="dryRun.requiresTools">工具调用</el-checkbox><el-checkbox v-model="dryRun.requiresStructuredOutput">结构化输出</el-checkbox></el-form-item>
          <el-form-item label="配额降级"><el-switch v-model="dryRun.preferFallback" active-text="仅备用候选" inactive-text="常规规则" /></el-form-item>
        </div>
        <el-button class="cw-final-action" type="primary" :loading="dryRunLoading" @click="executeDryRun">执行 Dry-run</el-button>
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
.panel-header h2, .drawer-title h2, .rules-heading h3 { margin: 2px 0 5px; color: var(--cw-text); }
.panel-header p, .rules-heading p { margin: 0; color: var(--cw-text-muted); font-size: 13px; }
.eyebrow { color: var(--cw-cobalt) !important; font-size: 10px !important; font-weight: 700; letter-spacing: .15em; }
.header-actions { flex-shrink: 0; color: var(--cw-text-muted); font-size: 12px; }
.policy-table { margin-top: 14px; }
.primary-cell strong, .primary-cell span { display: block; }
.primary-cell span { margin-top: 3px; color: var(--cw-text-muted); font-size: 11px; }
.version-card { margin-bottom: 14px; padding: 16px; border: 1px solid var(--cw-line); border-radius: 12px; background: var(--el-fill-color-extra-light); }
.version-heading > div { display: flex; align-items: center; gap: 9px; }
.version-heading > div > strong { font-size: 20px; }
.version-heading > div > span { color: var(--cw-text-muted); font-size: 13px; }
.version-meta { display: flex; flex-wrap: wrap; gap: 16px; margin: 8px 0 12px; color: var(--cw-text-muted); font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 11px; }
.policy-form-grid, .rule-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 14px; }
.full-row { grid-column: 1 / -1; }
.rules-heading { margin: 12px 0; }
.rule-editor { margin-bottom: 12px; padding: 14px; border: 1px solid var(--cw-line); border-radius: 12px; background: var(--el-fill-color-extra-light); }
.rule-index { display: flex; align-items: center; justify-content: space-between; margin-bottom: 8px; color: var(--cw-cobalt); font-size: 11px; letter-spacing: .08em; }
.dry-result { margin-top: 18px; padding: 16px; border: 1px solid color-mix(in srgb, var(--cw-success) 42%, var(--cw-line)); border-radius: 12px; background: color-mix(in srgb, var(--cw-success) 8%, var(--cw-paper)); }
.dry-result.is-closed { border-color: color-mix(in srgb, var(--cw-danger) 42%, var(--cw-line)); background: color-mix(in srgb, var(--cw-danger) 8%, var(--cw-paper)); }
.dry-summary span, .dry-summary strong { display: block; }
.dry-summary span { color: var(--cw-text-muted); font-size: 11px; letter-spacing: .1em; }
.dry-summary strong { margin-top: 3px; font-size: 18px; }
.dry-result > p { color: var(--el-text-color-regular); font-size: 13px; }
@media (max-width: 760px) {
  .panel-header, .header-actions, .rules-heading { align-items: flex-start; flex-direction: column; }
  .policy-form-grid, .rule-grid { grid-template-columns: 1fr; }
  .full-row { grid-column: auto; }
}
</style>
