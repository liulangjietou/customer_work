<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import CrudLoadState from '@/components/CrudLoadState.vue'
import { useCrudForm } from '@/composables/useCrudForm'
import { useQueryState } from '@/composables/useQueryState'
import { useRowMutation } from '@/composables/useRowMutation'
import { useAuthStore } from '@/store/auth'
import { pageAgents } from '@/api/agent'
import { listDatasetVersions, type EvalDatasetRelease } from '@/api/eval'
import { pageModels } from '@/api/model'
import {
  createModelExperiment,
  getModelExperimentMetrics,
  listModelExperimentArmEvaluations,
  listModelExperimentEvents,
  listModelExperiments,
  startModelExperiment,
  stopModelExperiment,
  type ModelExperiment,
  type ModelExperimentArmEvaluation,
  type ModelExperimentCreateRequest,
  type ModelExperimentEvent,
  type ModelExperimentEffectiveState,
  type ModelExperimentMetrics,
  type ModelExperimentStatus,
} from '@/api/modelExperiment'
import type { AgentVO, ModelVO } from '@/types/api'

const auth = useAuthStore()
const filter = reactive<{ agentId?: number; status?: ModelExperimentStatus }>({})
const primary = useQueryState(() => listModelExperiments({ ...filter }), () => [] as ModelExperiment[])
const { data: experiments, loading, error: loadError, loaded } = primary
const optionsAllowed = computed(() => ['agent:view', 'model:view', 'eval:view'].every(permission => auth.hasPermission(permission)))
const options = useQueryState(async () => {
  const [agentPage, modelPage, releases] = await Promise.all([
    pageAgents({ pageNum: 1, pageSize: 200 }), pageModels({ pageNum: 1, pageSize: 200 }),
    listDatasetVersions('QUALITY'),
  ])
  return { agents: agentPage.list, deployments: modelPage.list,
    datasetReleases: releases.filter(item => item.status === 'APPROVED') }
}, () => ({ agents: [] as AgentVO[], deployments: [] as ModelVO[], datasetReleases: [] as EvalDatasetRelease[] }))
const { loading: optionLoading, error: optionError, loaded: optionsLoaded } = options
const agents = computed(() => options.data.value.agents)
const deployments = computed(() => options.data.value.deployments)
const datasetReleases = computed(() => options.data.value.datasetReleases)
const detailVisible = ref(false)
const selected = ref<ModelExperiment | null>(null)
const details = useQueryState(async () => {
  const id = selected.value!.id
  const [metrics, events, armEvaluations] = await Promise.all([
    getModelExperimentMetrics(id), listModelExperimentEvents(id), listModelExperimentArmEvaluations(id),
  ])
  return { metrics, events, armEvaluations }
}, () => ({ metrics: null as ModelExperimentMetrics | null, events: [] as ModelExperimentEvent[],
  armEvaluations: [] as ModelExperimentArmEvaluation[] }))
const { loading: detailLoading, error: detailError, loaded: detailLoaded } = details
const metrics = computed(() => details.data.value.metrics)
const events = computed(() => details.data.value.events)
const armEvaluations = computed(() => details.data.value.armEvaluations)
const startMutation = useRowMutation('model-experiment:start')

const { dialogVisible: createVisible, submitting: saving, form, openCreate: openCreateForm,
  handleSubmit: createExperiment } = useCrudForm<ModelExperiment, ModelExperimentCreateRequest>({
  initForm: () => ({
    experimentName: '', agentId: 0, controlDeploymentId: 0, treatmentDeploymentId: 0,
    treatmentBps: 1000, minSample: 1000, maxErrorRate: 0.05, maxP95LatencyMs: 3000,
    expiresAt: futureDateTimeValue(7), datasetReleaseId: '',
  }),
  create: value => createModelExperiment({ ...value, experimentName: value.experimentName.trim() }),
  beforeSubmit: (_, value) => {
    if (!auth.hasPermission('model-experiment:create') || !optionsAllowed.value) return false
    if (!value.experimentName.trim() || !value.agentId || !value.controlDeploymentId
      || !value.treatmentDeploymentId || !value.datasetReleaseId) {
      ElMessage.warning('请完整填写实验名称、智能体、双臂部署和审核数据集版本')
      return false
    }
    if (value.controlDeploymentId === value.treatmentDeploymentId) {
      ElMessage.warning('对照组和实验组必须选择不同部署')
      return false
    }
    return true
  },
  messages: { created: '实验草稿已创建；revision 与分桶 salt 已固化' },
  onSaved: reloadExperiments,
})

const { dialogVisible: stopVisible, submitting: stopping, form: stopForm,
  openEdit: openStopForm, handleSubmit: submitStop } = useCrudForm<ModelExperiment, { reason: string }>({
  initForm: () => ({ reason: '' }),
  update: (id, value) => stopModelExperiment(id, value.reason.trim()),
  beforeSubmit: (_, value) => {
    if (!auth.hasPermission('model-experiment:stop')) return false
    if (!value.reason.trim()) { ElMessage.warning('请输入停止原因'); return false }
    return true
  },
  messages: { updated: '撤流任务已入队，运行时 APPLIED 后才会显示 INACTIVE' },
  onSaved: reloadExperiments,
})

watch(detailVisible, visible => { if (!visible) { details.reset(); selected.value = null } }, { flush: 'sync' })
watch([() => auth.token, () => auth.loginGeneration, () => auth.permissions.join('\0')], () => {
  detailVisible.value = false
  selected.value = null
  delete filter.agentId
  delete filter.status
  void reloadExperiments()
}, { flush: 'sync' })

const runningCount = computed(() => experiments.value.filter((item) => item.status === 'RUNNING').length)
const activeCount = computed(() => experiments.value.filter((item) => item.effectiveState === 'ACTIVE').length)
const agentMap = computed(() => new Map(agents.value.map((item) => [item.id, item.agentName])))
const deploymentMap = computed(() => new Map(deployments.value.map((item) => [item.id, item.modelName])))
const filterAgents = computed(() => [...new Set(experiments.value.map((item) => item.agentId))])

onMounted(() => void reloadExperiments())

async function reloadExperiments() {
  if (auth.hasPermission('model-experiment:view')) await primary.load()
}

async function openCreate() {
  if (optionLoading.value || !auth.hasPermission('model-experiment:create') || !optionsAllowed.value) return
  const result = await options.load()
  if (!result) return
  const firstAgent = result.agents.find(item => item.status === 1)
  const activeDeployments = result.deployments.filter(item => item.status === 1 && item.lifecycleStatus === 'ACTIVE')
  openCreateForm()
  Object.assign(form, {
    agentId: firstAgent?.id ?? 0, controlDeploymentId: activeDeployments[0]?.id ?? 0,
    treatmentDeploymentId: activeDeployments[1]?.id ?? 0,
    datasetReleaseId: result.datasetReleases[0]?.releaseId ?? '',
  })
}

async function startExperiment(row: ModelExperiment) {
  let started = false
  await startMutation.run(row.id, async isCurrent => {
    await ElMessageBox.confirm(
      '后端会先用审核数据集分别评测 control/treatment；两臂均达到平均分 3.0、通过率 80% 且无错误后，才进入运行时激活。确认启动？',
      '启动实验', { type: 'warning' },
    )
    if (!isCurrent()) return
    await startModelExperiment(row.id)
    started = true
  }, async () => {
    if (!started) return
    ElMessage.success('激活任务已入队，运行时 APPLIED 后才会显示 ACTIVE')
    await reloadExperiments()
  })
}

function stopExperiment(row: ModelExperiment) {
  if (auth.hasPermission('model-experiment:stop')) openStopForm(row)
}

async function inspectExperiment(row: ModelExperiment) {
  details.reset()
  selected.value = row
  detailVisible.value = true
  await loadDetails()
}

async function loadDetails() {
  if (detailVisible.value && selected.value && auth.hasPermission('model-experiment:view')) await details.load()
}

function experimentStatusType(status: ModelExperimentStatus) {
  if (status === 'RUNNING') return 'success'
  if (status === 'STOPPED') return 'danger'
  if (status === 'COMPLETED') return 'info'
  return 'warning'
}

function effectiveStateType(state: ModelExperimentEffectiveState) {
  if (state === 'ACTIVE') return 'success'
  if (state === 'ACTIVATION_FAILED' || state === 'DEACTIVATION_FAILED') return 'danger'
  if (state === 'ACTIVATING' || state === 'DEACTIVATING') return 'warning'
  return 'info'
}

function effectiveStateLabel(state: ModelExperimentEffectiveState) {
  const labels: Record<ModelExperimentEffectiveState, string> = {
    INACTIVE: '未生效',
    ACTIVATING: '激活中',
    ACTIVE: '已生效',
    ACTIVATION_FAILED: '激活失败',
    DEACTIVATING: '撤流中',
    DEACTIVATION_FAILED: '撤流失败',
  }
  return labels[state]
}

function isEffectiveFailure(state: ModelExperimentEffectiveState) {
  return state === 'ACTIVATION_FAILED' || state === 'DEACTIVATION_FAILED'
}

function offlineStatusType(status: string) {
  if (status === 'PASSED') return 'success'
  if (status === 'FAILED') return 'danger'
  if (status === 'RUNNING') return 'warning'
  return 'info'
}

function formatTime(value: string | null | undefined) {
  if (!value) return '—'
  return new Date(value).toLocaleString('zh-CN', { hour12: false })
}

function formatPercent(value: number | null | undefined) {
  if (value == null) return '—'
  return `${(value * 100).toFixed(2)}%`
}

function futureDateTimeValue(days: number) {
  const date = new Date(Date.now() + days * 24 * 60 * 60 * 1000)
  const local = new Date(date.getTime() - date.getTimezoneOffset() * 60 * 1000)
  return local.toISOString().slice(0, 19)
}
</script>

<template>
  <el-card v-loading="loading" class="experiment-card" shadow="never">
    <template #header>
      <div class="section-header">
        <div>
          <p class="eyebrow">ONLINE EXPERIMENT CONTROL</p>
          <h2>模型在线实验</h2>
          <span>{{ loaded ? experiments.length : '—' }} 个定义 · {{ loaded ? runningCount : '—' }} 个期望 RUNNING · {{ loaded ? activeCount : '—' }} 个运行时 ACTIVE</span>
        </div>
        <el-button v-permission="'model-experiment:create'" class="cw-final-action" type="primary" :loading="optionLoading" :disabled="!optionsAllowed" @click="openCreate">新建双臂实验</el-button>
      </div>
    </template>

    <CrudLoadState :error="loadError" :has-stale-data="loaded" :loading="loading" @retry="reloadExperiments" />
    <CrudLoadState :error="optionError" :has-stale-data="optionsLoaded" :loading="optionLoading" @retry="openCreate" />
    <el-alert v-if="auth.hasPermission('model-experiment:create') && !optionsAllowed"
      title="创建实验需要智能体、模型和评测数据的查看权限，请联系管理员配置。" type="info" :closable="false" />
    <el-alert
      title="生命周期是期望状态，生效状态以可靠发布任务与实例 ACK 为准"
      description="RUNNING 不等于已经承接流量；只有 ACTIVATE 任务 APPLIED 才显示 ACTIVE。停止、护栏触发或到期后，也只有 DEACTIVATE 任务 APPLIED 才显示 INACTIVE。"
      type="info"
      :closable="false"
      show-icon
      class="runtime-alert"
    />

    <div class="filter-row">
      <el-select v-model="filter.agentId" clearable placeholder="全部智能体" style="width: 220px" @change="reloadExperiments">
        <el-option v-for="agentId in filterAgents" :key="agentId" :label="agentMap.get(agentId) || `agent-${agentId}`" :value="agentId" />
      </el-select>
      <el-select v-model="filter.status" clearable placeholder="全部状态" style="width: 180px" @change="reloadExperiments">
        <el-option v-for="status in ['DRAFT', 'RUNNING', 'STOPPED', 'COMPLETED']" :key="status" :label="status" :value="status" />
      </el-select>
    </div>

    <el-table :data="experiments" row-key="id">
      <el-table-column label="实验" min-width="210">
        <template #default="{ row }">
          <div class="primary-cell">
            <strong>{{ row.experimentName }}</strong>
            <span>{{ row.experimentCode }} · r{{ row.revision }}</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="智能体" min-width="150">
        <template #default="{ row }">{{ agentMap.get(row.agentId) || `agent-${row.agentId}` }}</template>
      </el-table-column>
      <el-table-column label="双臂部署" min-width="260">
        <template #default="{ row }">
          <div class="arm-cell">
            <span>C · {{ deploymentMap.get(row.controlDeploymentId) || row.controlModelRef }} · r{{ row.controlEndpointRevision }}</span>
            <span>T · {{ deploymentMap.get(row.treatmentDeploymentId) || row.treatmentModelRef }} · r{{ row.treatmentEndpointRevision }}</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="离线门禁" min-width="190">
        <template #default="{ row }">
          <div class="guardrail-cell">
            <span>{{ row.datasetVersionName || '未绑定版本' }}</span>
            <el-tag :type="offlineStatusType(row.offlineEvalStatus)" size="small">{{ row.offlineEvalStatus }}</el-tag>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="流量" width="118">
        <template #default="{ row }">C {{ 100 - row.treatmentBps / 100 }}% / T {{ row.treatmentBps / 100 }}%</template>
      </el-table-column>
      <el-table-column label="护栏" min-width="180">
        <template #default="{ row }">
          <div class="guardrail-cell">
            <span>样本 ≥ {{ row.minSample }}</span>
            <span>错误 ≤ {{ formatPercent(row.maxErrorRate) }} · P95 ≤ {{ row.maxP95LatencyMs }} ms</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="生命周期 / 生效状态" width="205">
        <template #default="{ row }">
          <div class="status-cell">
            <el-tag :type="experimentStatusType(row.status)" size="small">期望 {{ row.status }}</el-tag>
            <el-tag :type="effectiveStateType(row.effectiveState)" size="small">
              {{ effectiveStateLabel(row.effectiveState) }} · {{ row.effectiveState }}
            </el-tag>
          </div>
          <small>{{ formatTime(row.expiresAt) }}</small>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="210" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="inspectExperiment(row)">指标与事件</el-button>
          <el-button v-if="row.status === 'DRAFT'" v-permission="'model-experiment:start'" link type="success" :loading="startMutation.isPending(row.id)" @click="startExperiment(row)">启动</el-button>
          <el-button v-if="row.status === 'RUNNING'" v-permission="'model-experiment:stop'" link type="danger" @click="stopExperiment(row)">停止</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="createVisible" title="新建不可变双臂实验" width="min(720px, 94vw)" destroy-on-close>
      <el-form :model="form" label-position="top" :disabled="saving">
        <div class="form-grid">
          <el-form-item label="实验名称" class="full-row"><el-input v-model="form.experimentName" maxlength="128" show-word-limit /></el-form-item>
          <el-form-item label="智能体">
            <el-select v-model="form.agentId" filterable style="width: 100%">
              <el-option v-for="agent in agents.filter((item) => item.status === 1)" :key="agent.id" :label="agent.agentName" :value="agent.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="实验截止时间">
            <el-date-picker v-model="form.expiresAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" style="width: 100%" />
          </el-form-item>
          <el-form-item label="对照组部署">
            <el-select v-model="form.controlDeploymentId" filterable style="width: 100%">
              <el-option v-for="model in deployments" :key="model.id" :label="`${model.modelName} · ${model.model} · r${model.endpointRevision || 1}`" :value="model.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="实验组部署">
            <el-select v-model="form.treatmentDeploymentId" filterable style="width: 100%">
              <el-option v-for="model in deployments" :key="model.id" :label="`${model.modelName} · ${model.model} · r${model.endpointRevision || 1}`" :value="model.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="审核通过的 QUALITY 数据集版本" class="full-row">
            <el-select v-model="form.datasetReleaseId" filterable style="width: 100%" placeholder="请先到评测中心创建并审核版本">
              <el-option
                v-for="release in datasetReleases"
                :key="release.releaseId"
                :label="`${release.versionName} · ${release.caseCount} 条 · ${release.contentHash.slice(0, 10)}`"
                :value="release.releaseId"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="实验组流量（bps）"><el-input-number v-model="form.treatmentBps" :min="1" :max="9999" :step="100" style="width: 100%" /></el-form-item>
          <el-form-item label="最小样本"><el-input-number v-model="form.minSample" :min="1" :step="100" style="width: 100%" /></el-form-item>
          <el-form-item label="最大错误率（0~1）"><el-input-number v-model="form.maxErrorRate" :min="0" :max="1" :step="0.01" :precision="4" style="width: 100%" /></el-form-item>
          <el-form-item label="最大 P95 延迟（ms）"><el-input-number v-model="form.maxP95LatencyMs" :min="1" :step="100" style="width: 100%" /></el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button class="cw-final-action" type="primary" :loading="saving" @click="createExperiment">创建草稿</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="stopVisible" title="停止实验" width="min(520px, 94vw)" destroy-on-close>
      <p>停止原因会写入追加式事件，不能为空。</p>
      <el-form label-position="top" :disabled="stopping">
        <el-form-item label="停止原因"><el-input v-model="stopForm.reason" type="textarea" :rows="3" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="stopVisible = false">取消</el-button>
        <el-button type="primary" :loading="stopping" @click="submitStop">确定</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="detailVisible" title="实验指标与事件" size="min(620px, 94vw)">
      <CrudLoadState :error="detailError" :has-stale-data="detailLoaded" :loading="detailLoading" @retry="loadDetails" />
      <div v-if="detailLoading" role="status">正在加载指标与事件…</div>
      <template v-if="selected">
        <el-descriptions :column="1" border>
          <el-descriptions-item label="实验">{{ selected.experimentName }} · r{{ selected.revision }}</el-descriptions-item>
          <el-descriptions-item label="期望生命周期">
            <el-tag :type="experimentStatusType(selected.status)">{{ selected.status }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="运行时生效状态">
            <el-tag :type="effectiveStateType(selected.effectiveState)">
              {{ effectiveStateLabel(selected.effectiveState) }} · {{ selected.effectiveState }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="离线评测数据集">
            {{ selected.datasetVersionName || '—' }} · {{ selected.datasetContentHash?.slice(0, 12) || '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="离线门禁">
            <el-tag :type="offlineStatusType(selected.offlineEvalStatus)">{{ selected.offlineEvalStatus }}</el-tag>
            <span v-if="selected.offlineEvalError"> · {{ selected.offlineEvalError }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="Judge 部署">
            {{ selected.judgeModelRef }} · r{{ selected.judgeEndpointRevision }}
          </el-descriptions-item>
          <el-descriptions-item label="当前生效任务">
            <template v-if="selected.effectiveTaskId">
              <code>{{ selected.effectiveTaskId }}</code>
              · {{ selected.effectiveTaskStatus || 'UNKNOWN' }}
              · gate {{ selected.effectiveTaskGateStatus || 'UNKNOWN' }}
            </template>
            <span v-else>—</span>
          </el-descriptions-item>
          <el-descriptions-item label="ACTIVATE 任务">
            <template v-if="selected.activationTaskId">
              <code>{{ selected.activationTaskId }}</code>
              · {{ selected.activationTaskStatus || 'UNKNOWN' }}
              · gate {{ selected.activationTaskGateStatus || 'UNKNOWN' }}
            </template>
            <span v-else>—</span>
          </el-descriptions-item>
          <el-descriptions-item label="DEACTIVATE 任务">
            <template v-if="selected.deactivationTaskId">
              <code>{{ selected.deactivationTaskId }}</code>
              · {{ selected.deactivationTaskStatus || 'UNKNOWN' }}
              · gate {{ selected.deactivationTaskGateStatus || 'UNKNOWN' }}
            </template>
            <span v-else>—</span>
          </el-descriptions-item>
          <el-descriptions-item label="停止原因">{{ selected.stopReason || '—' }}</el-descriptions-item>
        </el-descriptions>

        <el-alert
          v-if="isEffectiveFailure(selected.effectiveState)"
          title="运行时状态变更失败，页面不会把期望状态冒充为已生效"
          :description="selected.effectiveTaskLastError || `发布状态 ${selected.effectiveTaskStatus || 'UNKNOWN'}，门禁 ${selected.effectiveTaskGateStatus || 'UNKNOWN'}`"
          type="error"
          :closable="false"
          show-icon
          class="effective-error"
        />

        <h3>启动前双臂离线评测</h3>
        <el-empty v-if="armEvaluations.length === 0" description="尚未执行离线评测" :image-size="72" />
        <el-table v-else :data="armEvaluations" size="small" border>
          <el-table-column prop="arm" label="实验臂" width="110" />
          <el-table-column prop="attemptNo" label="尝试" width="70" />
          <el-table-column label="状态" width="100">
            <template #default="{ row }"><el-tag :type="offlineStatusType(row.status)" size="small">{{ row.status }}</el-tag></template>
          </el-table-column>
          <el-table-column label="平均分/通过率" min-width="170">
            <template #default="{ row }">
              {{ row.avgScore == null ? '—' : Number(row.avgScore).toFixed(2) }} /
              {{ formatPercent(row.passRate) }}
            </template>
          </el-table-column>
          <el-table-column prop="errorMessage" label="错误" min-width="180" show-overflow-tooltip />
        </el-table>

        <h3>在线指标</h3>
        <el-alert
          v-if="metrics?.availability === 'AWAITING_RUNTIME'"
          :title="metrics.message"
          type="warning"
          :closable="false"
          show-icon
        />
        <el-descriptions v-else-if="metrics" :column="2" border>
          <el-descriptions-item label="样本">{{ metrics.samples ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="错误率">{{ formatPercent(metrics.errorRate) }}</el-descriptions-item>
          <el-descriptions-item label="P95">{{ metrics.p95LatencyMs == null ? '—' : `${metrics.p95LatencyMs} ms` }}</el-descriptions-item>
          <el-descriptions-item label="评估时间">{{ formatTime(metrics.evaluatedAt) }}</el-descriptions-item>
        </el-descriptions>
        <el-descriptions
          v-if="metrics?.availability === 'READY' && (metrics.control || metrics.treatment)"
          class="arm-metrics"
          :column="3"
          border
        >
          <el-descriptions-item label="对照组样本">{{ metrics.control?.samples ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="对照组错误率">{{ formatPercent(metrics.control?.errorRate) }}</el-descriptions-item>
          <el-descriptions-item label="对照组 P95">{{ metrics.control?.p95LatencyMs == null ? '—' : `${metrics.control.p95LatencyMs} ms` }}</el-descriptions-item>
          <el-descriptions-item label="实验组样本">{{ metrics.treatment?.samples ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="实验组错误率">{{ formatPercent(metrics.treatment?.errorRate) }}</el-descriptions-item>
          <el-descriptions-item label="实验组 P95">{{ metrics.treatment?.p95LatencyMs == null ? '—' : `${metrics.treatment.p95LatencyMs} ms` }}</el-descriptions-item>
        </el-descriptions>

        <h3>追加式事件</h3>
        <el-empty v-if="events.length === 0" description="尚无生命周期事件" :image-size="72" />
        <el-timeline v-else>
          <el-timeline-item v-for="event in events" :key="event.id" :timestamp="formatTime(event.occurredAt)">
            <strong>{{ event.eventType }}</strong> · {{ event.fromStatus }} → {{ event.toStatus }}
            <p v-if="event.reason">{{ event.reason }}</p>
          </el-timeline-item>
        </el-timeline>
      </template>
    </el-drawer>
  </el-card>
</template>

<style scoped>
.experiment-card { margin-top: 20px; border: 0; border-radius: 16px; }
.section-header { display: flex; align-items: flex-end; justify-content: space-between; gap: 20px; }
.section-header h2 { margin: 2px 0 4px; color: var(--cw-text); font-size: 21px; }
.section-header span { color: var(--cw-text-muted); font-size: 12px; }
.eyebrow { margin: 0; color: var(--cw-cobalt); font-size: 10px; font-weight: 800; letter-spacing: .15em; }
.runtime-alert { margin-bottom: 16px; }
.filter-row { display: flex; gap: 10px; margin-bottom: 14px; }
.primary-cell strong, .primary-cell span, .arm-cell span, .guardrail-cell span { display: block; }
.status-cell { display: flex; align-items: flex-start; flex-direction: column; gap: 5px; }
.primary-cell span, .arm-cell span, .guardrail-cell span, small { margin-top: 4px; color: var(--cw-text-muted); font-size: 11px; }
.arm-cell span:first-child { color: var(--el-text-color-regular); }
.form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); column-gap: 16px; }
.full-row { grid-column: 1 / -1; }
h3 { margin: 24px 0 12px; color: var(--cw-text); font-size: 15px; }
.arm-metrics { margin-top: 12px; }
.effective-error { margin-top: 14px; }
code { font-size: 11px; word-break: break-all; }
.el-timeline p { margin: 5px 0 0; color: var(--cw-text-muted); }
@media (max-width: 760px) {
  .section-header { align-items: flex-start; flex-direction: column; }
  .filter-row, .form-grid { display: flex; flex-direction: column; }
}
</style>
