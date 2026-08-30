<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  acknowledgeSloAlert,
  evaluateSloPolicy,
  getSloAlertSummary,
  listSloAlertEvents,
  listSloAlerts,
  listSloPolicies,
  saveSloPolicy,
  type SloAlert,
  type SloAlertEvent,
  type SloAlertStatus,
  type SloAlertSummary,
  type SloEvaluation,
  type SloPolicy,
  type SloPolicySaveRequest,
  type SloScopeType,
} from '@/api/slo'

const SCOPE_LABELS: Record<SloScopeType, string> = {
  TENANT: '当前租户',
  AGENT: '智能体',
  CHANNEL: '渠道',
}

const STATUS_LABELS: Record<SloEvaluation['status'], string> = {
  HEALTHY: '健康',
  BURNING: '预算燃烧',
  NO_DATA: '无数据',
  INSUFFICIENT_DATA: '样本不足',
}

const loading = ref(false)
const policies = ref<SloPolicy[]>([])
const alerts = ref<SloAlert[]>([])
const alertSummary = ref<SloAlertSummary>({ openCount: 0, acknowledgedCount: 0 })
const dialogVisible = ref(false)
const evaluationVisible = ref(false)
const eventVisible = ref(false)
const evaluation = ref<SloEvaluation | null>(null)
const alertEvents = ref<SloAlertEvent[]>([])
const activeAlert = ref<SloAlert | null>(null)
const evaluatingId = ref<number | null>(null)
const acknowledgingId = ref<number | null>(null)
const eventLoadingId = ref<number | null>(null)
const alertStatus = ref<SloAlertStatus | undefined>(undefined)
const saving = ref(false)
let loadRequestId = 0
let eventRequestId = 0

const enabledPolicyCount = computed(() => policies.value.filter((item) => item.enabled).length)
const healthyPolicyCount = computed(() => (
  policies.value.filter((item) => item.lastEvaluationStatus === 'HEALTHY').length
))
// 顶部指标由服务端直接聚合事实表，不能被下方筛选或 200 条明细上限污染。
const openAlertCount = computed(() => alertSummary.value.openCount)
const acknowledgedAlertCount = computed(() => alertSummary.value.acknowledgedCount)

const emptyForm = (): SloPolicySaveRequest => ({
  policyName: '',
  scopeType: 'TENANT',
  scopeKey: null,
  availabilityTarget: 0.99,
  latencyTarget: 0.95,
  latencyThresholdMs: 3000,
  shortWindowMinutes: 5,
  longWindowMinutes: 60,
  minimumSampleCount: 100,
  burnRateThreshold: 2,
  enabled: true,
})
const form = reactive<SloPolicySaveRequest>(emptyForm())

async function loadPolicies() {
  const requestId = ++loadRequestId
  loading.value = true
  try {
    const [policyRows, alertRows, summary] = await Promise.all([
      listSloPolicies(),
      listSloAlerts(alertStatus.value),
      getSloAlertSummary(),
    ])
    if (requestId === loadRequestId) {
      policies.value = policyRows
      alerts.value = alertRows
      alertSummary.value = summary
    }
  } finally {
    if (requestId === loadRequestId) {
      loading.value = false
    }
  }
}

function openCreate() {
  // Object.assign 不会移除编辑态遗留的可选 id；必须显式删除，避免“新建”误走服务端 upsert 更新旧策略。
  delete form.id
  Object.assign(form, emptyForm())
  dialogVisible.value = true
}

function openEdit(row: SloPolicy) {
  Object.assign(form, {
    id: row.id,
    policyName: row.policyName,
    scopeType: row.scopeType,
    scopeKey: row.scopeKey,
    availabilityTarget: row.availabilityTarget,
    latencyTarget: row.latencyTarget,
    latencyThresholdMs: row.latencyThresholdMs,
    shortWindowMinutes: row.shortWindowMinutes,
    longWindowMinutes: row.longWindowMinutes,
    minimumSampleCount: row.minimumSampleCount,
    burnRateThreshold: row.burnRateThreshold,
    enabled: row.enabled,
  })
  dialogVisible.value = true
}

async function submit() {
  if (saving.value) return
  if (!form.policyName.trim()) {
    ElMessage.warning('请填写策略名称')
    return
  }
  if (form.scopeType !== 'TENANT' && !form.scopeKey?.trim()) {
    ElMessage.warning('请填写 Agent 编码或渠道编码')
    return
  }
  if (form.shortWindowMinutes >= form.longWindowMinutes) {
    ElMessage.warning('短窗口必须小于长窗口')
    return
  }
  if (form.minimumSampleCount < 1) {
    ElMessage.warning('最低样本数必须大于 0')
    return
  }
  saving.value = true
  try {
    await saveSloPolicy({ ...form, scopeKey: form.scopeType === 'TENANT' ? null : form.scopeKey })
    ElMessage.success('SLO 策略已保存')
    dialogVisible.value = false
    await loadPolicies()
  } finally {
    saving.value = false
  }
}

async function evaluate(row: SloPolicy) {
  if (evaluatingId.value !== null) return
  evaluatingId.value = row.id
  try {
    evaluation.value = await evaluateSloPolicy(row.id)
    evaluationVisible.value = true
    if (evaluation.value.alertCreated) {
      ElMessage.warning('短、长窗口均超过阈值，已打开告警并进入可靠通知队列')
    } else if (evaluation.value.alertTransition === 'RESOLVED') {
      ElMessage.success('指标已恢复，告警状态与恢复通知已持久化')
    }
    await loadPolicies()
  } finally {
    if (evaluatingId.value === row.id) {
      evaluatingId.value = null
    }
  }
}

async function acknowledge(row: SloAlert) {
  if (acknowledgingId.value !== null) return
  acknowledgingId.value = row.id
  try {
    await acknowledgeSloAlert(row.id)
    ElMessage.success('告警已确认')
    await loadPolicies()
  } finally {
    if (acknowledgingId.value === row.id) {
      acknowledgingId.value = null
    }
  }
}

async function showEvents(row: SloAlert) {
  const requestId = ++eventRequestId
  activeAlert.value = row
  alertEvents.value = []
  eventVisible.value = true
  eventLoadingId.value = row.id
  try {
    const events = await listSloAlertEvents(row.id)
    if (requestId === eventRequestId && activeAlert.value?.id === row.id) {
      alertEvents.value = events
    }
  } finally {
    if (requestId === eventRequestId) {
      eventLoadingId.value = null
    }
  }
}

function percent(value: number) {
  return `${(Number(value) * 100).toFixed(2)}%`
}

function statusType(status: SloEvaluation['status']) {
  return status === 'HEALTHY' ? 'success' : status === 'BURNING' ? 'danger' : 'info'
}

function alertStatusType(status: SloAlertStatus) {
  return status === 'OPEN' ? 'danger' : status === 'ACKED' ? 'warning' : 'success'
}

function alertStatusLabel(status: SloAlertStatus) {
  return status === 'OPEN' ? '待确认' : status === 'ACKED' ? '已确认' : '已恢复'
}

function evaluationStatusLabel(status: SloPolicy['lastEvaluationStatus']) {
  if (!status) return '待评估'
  if (status === 'FAILED') return '执行失败'
  return STATUS_LABELS[status]
}

onMounted(loadPolicies)
</script>

<template>
  <div class="slo-page">
    <div class="summary-row" v-loading="loading">
      <div class="stat">
        <strong>{{ enabledPolicyCount }}</strong>
        <span>启用策略</span>
      </div>
      <div class="stat">
        <strong>{{ healthyPolicyCount }}</strong>
        <span>最近评估健康</span>
      </div>
      <div class="stat stat-danger">
        <strong>{{ openAlertCount }}</strong>
        <span>全部待确认</span>
      </div>
      <div class="stat stat-warning">
        <strong>{{ acknowledgedAlertCount }}</strong>
        <span>全部已确认待恢复</span>
      </div>
    </div>

    <el-card shadow="never" class="policy-card">
      <template #header>
        <div class="header">
          <div>
            <h2>SLO 错误预算</h2>
            <p>多副本通过数据库租约周期评估；短、长窗口同时超限打开告警，恢复后自动闭环并可靠投递通知。</p>
          </div>
          <el-button v-permission="'slo:edit'" class="cw-final-action" type="primary" @click="openCreate">新建策略</el-button>
        </div>
      </template>

      <el-table v-loading="loading" :data="policies" stripe>
        <el-table-column prop="policyName" label="策略" min-width="150" />
        <el-table-column label="范围" min-width="150">
          <template #default="{ row }">
            <el-tag effect="plain">{{ SCOPE_LABELS[row.scopeType as SloScopeType] }}</el-tag>
            <span v-if="row.scopeKey" class="scope-key">{{ row.scopeKey }}</span>
          </template>
        </el-table-column>
        <el-table-column label="可用性目标" width="120">
          <template #default="{ row }">{{ percent(row.availabilityTarget) }}</template>
        </el-table-column>
        <el-table-column label="延迟目标" min-width="170">
          <template #default="{ row }">{{ percent(row.latencyTarget) }} ≤ {{ row.latencyThresholdMs }}ms</template>
        </el-table-column>
        <el-table-column label="观测窗口" width="140">
          <template #default="{ row }">{{ row.shortWindowMinutes }}m / {{ row.longWindowMinutes }}m</template>
        </el-table-column>
        <el-table-column prop="minimumSampleCount" label="最低样本" width="100" />
        <el-table-column label="燃烧阈值" width="100">
          <template #default="{ row }">{{ row.burnRateThreshold }}×</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? '启用' : '停用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="最近自动评估" min-width="170">
          <template #default="{ row }">
            <el-tag :type="row.lastEvaluationStatus === 'FAILED' ? 'danger' : row.lastEvaluationStatus === 'HEALTHY' ? 'success' : 'info'" effect="plain">
              {{ evaluationStatusLabel(row.lastEvaluationStatus) }}
            </el-tag>
            <div class="subtle">{{ row.lastEvaluatedAt || '等待 Worker 领取' }}</div>
            <div v-if="row.lastEvaluationError" class="evaluation-error">{{ row.lastEvaluationError }}</div>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button v-permission="'slo:evaluate'" link type="primary" :loading="evaluatingId === row.id" :disabled="evaluatingId !== null && evaluatingId !== row.id" @click="evaluate(row)">
              立即评估
            </el-button>
            <el-button v-permission="'slo:edit'" link @click="openEdit(row)">编辑</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card shadow="never" class="alert-card">
      <template #header>
        <div class="header">
          <div>
            <h2>告警生命周期</h2>
            <p>每个策略只保留一个活跃告警；确认不等于恢复，只有足量健康样本才能进入 RESOLVED。</p>
          </div>
          <el-select v-model="alertStatus" clearable placeholder="全部状态" style="width: 140px" @change="loadPolicies">
            <el-option label="待确认" value="OPEN" />
            <el-option label="已确认" value="ACKED" />
            <el-option label="已恢复" value="RESOLVED" />
          </el-select>
        </div>
      </template>
      <el-table v-loading="loading" :data="alerts" stripe>
        <el-table-column prop="policyName" label="策略" min-width="150" />
        <el-table-column label="范围" min-width="150">
          <template #default="{ row }">{{ row.scopeType || '-' }}<span v-if="row.scopeKey"> · {{ row.scopeKey }}</span></template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }"><el-tag :type="alertStatusType(row.status)">{{ alertStatusLabel(row.status) }}</el-tag></template>
        </el-table-column>
        <el-table-column label="短/长窗燃烧率" min-width="150">
          <template #default="{ row }">{{ row.shortBurnRate }}× / {{ row.longBurnRate }}×</template>
        </el-table-column>
        <el-table-column prop="firstSeenAt" label="首次发现" min-width="165" />
        <el-table-column prop="lastSeenAt" label="最近观测" min-width="165" />
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="row.status === 'OPEN'"
              v-permission="'slo:ack'"
              link
              type="primary"
              :loading="acknowledgingId === row.id"
              :disabled="acknowledgingId !== null && acknowledgingId !== row.id"
              @click="acknowledge(row)"
            >确认</el-button>
            <el-button link :loading="eventLoadingId === row.id" @click="showEvents(row)">事件</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑 SLO 策略' : '新建 SLO 策略'" width="620px">
      <el-form label-width="130px">
        <el-form-item label="策略名称"><el-input v-model="form.policyName" maxlength="128" /></el-form-item>
        <el-form-item label="统计范围">
          <el-select v-model="form.scopeType" @change="form.scopeKey = null">
            <el-option label="当前租户" value="TENANT" />
            <el-option label="智能体" value="AGENT" />
            <el-option label="渠道" value="CHANNEL" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="form.scopeType !== 'TENANT'" :label="form.scopeType === 'AGENT' ? 'Agent 编码' : '渠道编码'">
          <el-input v-model="form.scopeKey" placeholder="请输入精确编码" />
        </el-form-item>
        <el-form-item label="可用性目标">
          <el-input-number v-model="form.availabilityTarget" :min="0.000001" :max="0.999999" :step="0.001" :precision="6" />
          <span class="hint">0.99 表示 99%</span>
        </el-form-item>
        <el-form-item label="延迟目标">
          <el-input-number v-model="form.latencyTarget" :min="0.000001" :max="0.999999" :step="0.01" :precision="6" />
          <span class="hint">达到阈值内完成的比例</span>
        </el-form-item>
        <el-form-item label="延迟阈值 (ms)"><el-input-number v-model="form.latencyThresholdMs" :min="1" :step="100" /></el-form-item>
        <el-form-item label="短/长窗口">
          <el-input-number v-model="form.shortWindowMinutes" :min="1" />
          <span class="split">/</span>
          <el-input-number v-model="form.longWindowMinutes" :min="2" />
          <span class="hint">分钟</span>
        </el-form-item>
        <el-form-item label="最低样本数">
          <el-input-number v-model="form.minimumSampleCount" :min="1" :step="10" />
          <span class="hint">两个窗口各自达到门槛才评估；默认 100，低于门槛不告警</span>
        </el-form-item>
        <el-form-item label="燃烧率阈值"><el-input-number v-model="form.burnRateThreshold" :min="0.01" :step="0.5" :precision="2" /></el-form-item>
        <el-form-item label="启用"><el-switch v-model="form.enabled" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="dialogVisible = false">取消</el-button><el-button class="cw-final-action" type="primary" :loading="saving" @click="submit">保存策略</el-button></template>
    </el-dialog>

    <el-dialog v-model="evaluationVisible" title="错误预算评估结果" width="760px">
      <template v-if="evaluation">
        <el-descriptions :column="3" border class="evaluation-summary">
          <el-descriptions-item label="策略">{{ evaluation.policyName }}</el-descriptions-item>
          <el-descriptions-item label="状态"><el-tag :type="statusType(evaluation.status)">{{ STATUS_LABELS[evaluation.status] }}</el-tag></el-descriptions-item>
          <el-descriptions-item label="评估时间">{{ evaluation.evaluatedAt }}</el-descriptions-item>
        </el-descriptions>
        <el-table :data="[evaluation.shortWindow, evaluation.longWindow]" class="result-table">
          <el-table-column label="窗口"><template #default="{ row }">{{ row.windowMinutes }} 分钟</template></el-table-column>
          <el-table-column label="样本/门槛" width="110"><template #default="{ row }">{{ row.total }} / {{ evaluation.minimumSampleCount }}</template></el-table-column>
          <el-table-column prop="good" label="达标" />
          <el-table-column prop="bad" label="未达标" />
          <el-table-column label="可用性"><template #default="{ row }">{{ percent(row.availabilityRatio) }}</template></el-table-column>
          <el-table-column label="延迟达标"><template #default="{ row }">{{ percent(row.latencyRatio) }}</template></el-table-column>
          <el-table-column prop="remainingErrorBudget" label="剩余错误次数" width="120" />
          <el-table-column label="燃烧率"><template #default="{ row }">{{ row.burnRate }}×</template></el-table-column>
        </el-table>
      </template>
    </el-dialog>

    <el-dialog v-model="eventVisible" v-loading="eventLoadingId !== null" :title="`${activeAlert?.policyName || 'SLO'} · 状态事件`" width="720px">
      <el-timeline>
        <el-timeline-item v-for="item in alertEvents" :key="item.id" :timestamp="item.occurredAt" placement="top">
          <strong>{{ item.eventType }}</strong>
          <span class="event-rate">短窗 {{ item.shortBurnRate }}× / 长窗 {{ item.longBurnRate }}×</span>
          <span v-if="item.actorUserId" class="subtle">操作人 #{{ item.actorUserId }}</span>
        </el-timeline-item>
      </el-timeline>
    </el-dialog>
  </div>
</template>

<style scoped>
.slo-page { display: flex; flex-direction: column; gap: 14px; }
.summary-row { display: grid; grid-template-columns: repeat(4, minmax(150px, 1fr)); gap: 12px; }
.stat { min-height: 88px; padding: 15px 16px 14px; border: 1px solid var(--cw-line); border-radius: var(--cw-radius-md); background: var(--cw-paper); box-shadow: var(--cw-shadow-xs); }
.stat strong { display: block; color: var(--cw-text); font-size: 25px; font-weight: 720; font-variant-numeric: tabular-nums; line-height: 1.2; }
.stat span { display: block; margin-top: 7px; color: var(--cw-text-muted); font-size: 12px; }
.stat-danger strong { color: var(--cw-danger); }
.stat-warning strong { color: var(--cw-amber); }
.header { display: flex; align-items: center; justify-content: space-between; gap: 24px; }
.header h2 { margin: 0 0 6px; color: var(--cw-text); font-size: 18px; }
.header p { max-width: 820px; margin: 0; color: var(--cw-text-muted); line-height: 1.55; }
.scope-key { margin-left: 8px; font-family: ui-monospace, monospace; }
.hint { margin-left: 10px; color: var(--cw-text-muted); }
.split { margin: 0 10px; }
.result-table { margin-top: 18px; }
.subtle { margin-top: 4px; color: var(--cw-text-muted); font-size: 12px; }
.evaluation-error { max-width: 260px; margin-top: 4px; color: var(--cw-danger); font-size: 12px; white-space: normal; }
.event-rate { margin-left: 14px; }

@media (max-width: 1023px) {
  .summary-row { grid-template-columns: repeat(2, minmax(150px, 1fr)); }
  .header { align-items: flex-start; flex-wrap: wrap; }
}

@media (max-width: 767px) {
  .summary-row { grid-template-columns: 1fr 1fr; }
  .header { flex-direction: column; gap: 12px; }
  .header > .el-button,
  .header > .el-select { width: 100%; }
  .evaluation-summary { overflow-x: auto; }
  .event-rate,
  .subtle { display: block; margin: 5px 0 0; }
}

@media (max-width: 480px) {
  .summary-row { grid-template-columns: 1fr; }
}
</style>
