<script setup lang="ts">
import { computed, onScopeDispose, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import {
  bindImprovementArtifact,
  createImprovementEvalCase,
  getImprovementCase,
  publishImprovementCase,
  reevaluateImprovementCase,
  refreshImprovementCase,
  triageImprovementCase,
  type ImprovementCase,
  type ImprovementCaseStatus,
  type ImprovementSourceType,
} from '@/api/improvement'
import type { EvalTypeCode } from '@/api/eval'
import { getRequestErrorMessage } from '@/api/request'
import { useAuthStore } from '@/store/auth'
import CrudLoadState from './CrudLoadState.vue'

const props = withDefaults(
  defineProps<{
    sourceType: ImprovementSourceType
    sourceKey: string
    active?: boolean
  }>(),
  { active: true },
)

const compactQuery = window.matchMedia('(max-width: 640px)')
const compact = ref(compactQuery.matches)
const syncCompact = () => {
  compact.value = compactQuery.matches
}
compactQuery.addEventListener('change', syncCompact)
onScopeDispose(() => compactQuery.removeEventListener('change', syncCompact))

const auth = useAuthStore()
const canManage = computed(() => auth.hasPermission('improvement:manage'))
const active = computed(() => props.active !== false && canManage.value && !!auth.token)
const loaded = ref(false)
const loadError = ref<unknown>(null)
const actionError = ref('')
const needsReconciliation = ref(false)
let generation = 0
const loading = ref(false)
const submitting = ref(false)
const improvement = ref<ImprovementCase | null>(null)
const ownerId = ref('')
const slaDueAtMs = ref(Date.now() + 24 * 60 * 60 * 1000)
const artifactForm = reactive({
  agentId: undefined as number | undefined,
  evalType: 'QUALITY' as EvalTypeCode,
  evalCaseId: '',
})
const evalCaseForm = reactive({
  caseId: '',
  evalType: 'QUALITY' as EvalTypeCode,
  expected: '',
  category: '',
})
const reevaluationRemark = ref('')
const busy = computed(() => loading.value || submitting.value)
const canAct = computed(
  () =>
    active.value && loaded.value && !busy.value && !loadError.value && !needsReconciliation.value,
)
const isTerminal = computed(() =>
  ['VERIFIED', 'INEFFECTIVE', 'INCONCLUSIVE', 'CANCELLED'].includes(
    improvement.value?.status ?? '',
  ),
)

const REEVALUATION_LABELS = {
  NOT_RUN: '尚未运行',
  RUNNING: '正在评测',
  PASSED: '已通过',
  FAILED: '未通过',
} as const

const STATUS_LABELS: Record<ImprovementCaseStatus, string> = {
  OWNED: '已认领',
  READY_FOR_REEVALUATION: '待复评',
  REEVALUATING: '复评中',
  REEVALUATION_FAILED: '复评未通过',
  READY_TO_PUBLISH: '待发布',
  PUBLISHING: '发布中',
  PUBLISH_FAILED: '发布失败',
  OBSERVING: '效果观察中',
  VERIFIED: '效果已验证',
  INEFFECTIVE: '上线后复发',
  INCONCLUSIVE: '流量不足',
  CANCELLED: '已取消',
}

// 发布任务状态（RuntimePublishStatus）的中文标签。此前"发布状态"那一格直接渲染后端枚举名，
// 门禁阻断时运营看到的是一个没有翻译的裸 BLOCKED，而那恰恰是最需要看懂的时刻。
// 用词与渠道绑定抽屉（ChannelBindingDrawer.vue）保持一致。
const PUBLISH_STATUS_LABELS: Record<string, string> = {
  PENDING: '待调度',
  PROCESSING: '处理中',
  BLOCKED: '门禁阻断',
  PUBLISHED: '已投递',
  PARTIAL: '部分生效',
  APPLIED: '已生效',
  SUPERSEDED: '已被新版本取代',
  FAILED: '发布失败',
}
const publishStatusLabel = computed(() => {
  const raw = improvement.value?.publishStatus
  if (!raw) return '-'
  return PUBLISH_STATUS_LABELS[raw] ?? raw
})

const statusType = computed(() => {
  const status = improvement.value?.status
  if (status === 'VERIFIED') return 'success'
  if (status === 'INEFFECTIVE' || status === 'PUBLISH_FAILED' || status === 'REEVALUATION_FAILED')
    return 'danger'
  if (status === 'INCONCLUSIVE') return 'warning'
  return 'primary'
})

const canBind = computed(() => {
  const status = improvement.value?.status
  return (
    !!status &&
    !['REEVALUATING', 'PUBLISHING', 'OBSERVING', 'VERIFIED', 'CANCELLED'].includes(status)
  )
})

const candidateChanged = computed(() => {
  const current = improvement.value
  return (
    !!current &&
    (artifactForm.agentId !== (current.agentId || undefined) ||
      artifactForm.evalType !== (current.evalType || 'QUALITY') ||
      artifactForm.evalCaseId.trim() !== (current.evalCaseId || ''))
  )
})

function formatTime(ms?: number | null) {
  return ms ? new Date(ms).toLocaleString('zh-CN', { hour12: false }) : '-'
}

function shortHash(value?: string | null) {
  return value ? `${value.slice(0, 12)}…` : '-'
}

/** 每个来源及登录周期各自接收响应，关闭抽屉也会使在途操作失效。 */
function reset() {
  generation += 1
  loaded.value = false
  loading.value = false
  submitting.value = false
  improvement.value = null
  loadError.value = null
  actionError.value = ''
  needsReconciliation.value = false
  ownerId.value = ''
  slaDueAtMs.value = Date.now() + 24 * 60 * 60 * 1000
  Object.assign(artifactForm, { agentId: undefined, evalType: 'QUALITY', evalCaseId: '' })
  Object.assign(evalCaseForm, {
    caseId: `${props.sourceType === 'BADCASE' ? 'bc' : 'gap'}-${props.sourceKey.slice(0, 8)}`,
    evalType: 'QUALITY',
    expected: '',
    category: '',
  })
  reevaluationRemark.value = ''
}

function applyResult(result: ImprovementCase | null) {
  improvement.value = result
  if (!result) return
  ownerId.value = result.ownerId
  slaDueAtMs.value = result.slaDueAtMs
  artifactForm.agentId = result.agentId || undefined
  artifactForm.evalType = result.evalType || 'QUALITY'
  artifactForm.evalCaseId = result.evalCaseId || ''
}

async function load() {
  if (!active.value || submitting.value) return
  const requestId = ++generation
  loading.value = true
  try {
    const result = await getImprovementCase(props.sourceType, props.sourceKey)
    if (requestId !== generation) return
    applyResult(result)
    loaded.value = true
    loadError.value = null
    actionError.value = ''
    needsReconciliation.value = false
  } catch (error) {
    if (requestId === generation) loadError.value = error
  } finally {
    if (requestId === generation) loading.value = false
  }
}

/** 写入失败保留输入并要求读取核对，避免把丢失响应直接当作未执行而重复操作。 */
async function submit(
  action: () => Promise<ImprovementCase>,
  message: (result: ImprovementCase) => string,
) {
  if (!canAct.value) return
  const requestId = ++generation
  submitting.value = true
  actionError.value = ''
  try {
    const result = await action()
    if (requestId !== generation) return
    applyResult(result)
    if (result.status === 'REEVALUATION_FAILED') ElMessage.warning(message(result))
    else ElMessage.success(message(result))
  } catch (error) {
    if (requestId !== generation) return
    actionError.value = getRequestErrorMessage(error, '操作结果尚未核实')
    needsReconciliation.value = true
  } finally {
    if (requestId === generation) submitting.value = false
  }
}

function submitTriage() {
  if (isTerminal.value) return
  return submit(
    () =>
      triageImprovementCase(props.sourceType, props.sourceKey, {
        ownerId: ownerId.value.trim() || undefined,
        slaDueAtMs: Number(slaDueAtMs.value),
      }),
    () => '责任人与截止时间已保存',
  )
}

function submitEvalCase() {
  const current = improvement.value
  if (!current || !canBind.value || !evalCaseForm.caseId.trim()) return
  return submit(
    () => createImprovementEvalCase(current.id, { ...evalCaseForm }),
    () => '回归用例已创建并绑定原始问题',
  )
}

function submitArtifact() {
  const current = improvement.value
  const agentId = artifactForm.agentId
  if (!current || !canBind.value || !agentId) return
  return submit(
    () =>
      bindImprovementArtifact(current.id, {
        agentId,
        evalType: artifactForm.evalType,
        evalCaseId: artifactForm.evalCaseId.trim() || undefined,
      }),
    () => '候选版本已冻结，可以继续复评',
  )
}

function submitReevaluation() {
  const current = improvement.value
  if (
    !current ||
    candidateChanged.value ||
    !auth.hasPermission('eval:run') ||
    !['READY_FOR_REEVALUATION', 'REEVALUATION_FAILED'].includes(current.status)
  )
    return
  return submit(
    () => reevaluateImprovementCase(current.id, reevaluationRemark.value || undefined),
    (result) =>
      result.reevaluationStatus === 'PASSED' ? '复评通过' : '复评未通过，请查看失败原因',
  )
}

function submitPublish() {
  const current = improvement.value
  if (
    !current ||
    candidateChanged.value ||
    !auth.hasPermission('agent:edit') ||
    current.status !== 'READY_TO_PUBLISH'
  )
    return
  return submit(
    () => publishImprovementCase(current.id),
    () => '发布任务已创建，等待渠道确认生效',
  )
}

function submitRefresh() {
  const current = improvement.value
  if (!current || !['PUBLISHING', 'OBSERVING'].includes(current.status)) return
  return submit(
    () => refreshImprovementCase(current.id),
    () => '已安排同步，请稍后刷新查看结果',
  )
}

watch(
  () => [props.sourceType, props.sourceKey, active.value, auth.token],
  () => {
    reset()
    void load()
  },
  { immediate: true, flush: 'sync' },
)
onScopeDispose(() => {
  generation += 1
})
</script>

<template>
  <div v-if="canManage" v-loading="loading" class="improvement-closure" :aria-busy="busy">
    <el-alert
      type="info"
      show-icon
      :closable="false"
      title="从原始问题到上线验证"
      description="认领后建立回归用例，绑定修复候选并运行复评。所有目标渠道确认生效后进入效果观察；达到样本要求且未超过复发阈值，才标记效果已验证。"
    />

    <CrudLoadState :error="loadError" :has-stale-data="loaded" :loading="loading" @retry="load" />
    <el-alert
      v-if="actionError"
      class="section"
      type="error"
      :closable="false"
      show-icon
      :title="actionError"
      role="status"
    >
      <p>输入已保留。请先刷新核对服务器记录，再决定下一步。</p>
      <el-button :loading="loading" @click="load">核对服务器记录</el-button>
    </el-alert>
    <el-card v-if="loaded && !loadError && !improvement" shadow="never" class="section">
      <template #header>认领治理</template>
      <el-form label-width="88px" :disabled="!canAct">
        <el-form-item label="责任人">
          <el-input v-model="ownerId" placeholder="留空则取当前登录人" />
        </el-form-item>
        <el-form-item label="SLA 截止">
          <el-date-picker
            v-model="slaDueAtMs"
            type="datetime"
            value-format="x"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="submitting" @click="submitTriage">认领</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <template v-else-if="improvement">
      <el-descriptions :column="compact ? 1 : 2" border class="section">
        <el-descriptions-item label="闭环状态">
          <el-tag :type="statusType">{{ STATUS_LABELS[improvement.status] }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="SLA">
          <el-tag :type="improvement.slaStatus === 'OVERDUE' ? 'danger' : 'success'">
            {{
              improvement.slaStatus === 'OVERDUE'
                ? '已逾期'
                : improvement.slaStatus === 'CLOSED'
                  ? '已关闭'
                  : '正常'
            }}
          </el-tag>
          {{ formatTime(improvement.slaDueAtMs) }}
        </el-descriptions-item>
        <el-descriptions-item label="责任人">{{ improvement.ownerId }}</el-descriptions-item>
        <el-descriptions-item label="来源信号"
          >认领时 {{ improvement.sourceSignalCount }} 次</el-descriptions-item
        >
      </el-descriptions>

      <details class="section evidence-details">
        <summary>候选、评测与发布记录</summary>
        <el-descriptions :column="compact ? 1 : 2" border class="section">
          <el-descriptions-item label="候选版本">{{
            shortHash(improvement.artifactVersion)
          }}</el-descriptions-item>
          <el-descriptions-item label="目标用例">{{
            improvement.evalCaseId || '-'
          }}</el-descriptions-item>
          <el-descriptions-item label="复评运行">{{
            improvement.evalRunId || '-'
          }}</el-descriptions-item>
          <el-descriptions-item label="复评结论">
            {{ REEVALUATION_LABELS[improvement.reevaluationStatus] }} /
            {{ improvement.reevaluationVerdict || '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="发布任务">{{
            improvement.publishTaskId || '-'
          }}</el-descriptions-item>
          <el-descriptions-item label="发布状态">{{ publishStatusLabel }}</el-descriptions-item>
          <el-descriptions-item label="发布修订">{{
            improvement.publishRevision || '-'
          }}</el-descriptions-item>
          <el-descriptions-item label="观察窗口">
            {{ formatTime(improvement.observationStartedAtMs) }} →
            {{ formatTime(improvement.observationEndsAtMs) }}
          </el-descriptions-item>
          <el-descriptions-item label="线上曝光">
            {{ improvement.observedCalls }} / {{ improvement.minExposureCalls ?? '-' }} 次
          </el-descriptions-item>
          <el-descriptions-item label="同类复发">
            {{ improvement.observedSignals }} / 最多
            {{ improvement.maxRecurrenceSignals ?? '-' }} 次
          </el-descriptions-item>
        </el-descriptions>
      </details>

      <el-alert
        v-if="improvement.reevaluationError || improvement.lastError"
        class="section"
        type="error"
        show-icon
        :closable="false"
        :title="improvement.reevaluationError || improvement.lastError || ''"
      />

      <el-card shadow="never" class="section">
        <template #header>责任与 SLA</template>
        <el-form inline :disabled="!canAct || isTerminal">
          <el-form-item label="责任人"
            ><el-input v-model="ownerId" style="width: 150px"
          /></el-form-item>
          <el-form-item label="截止">
            <el-date-picker
              v-model="slaDueAtMs"
              type="datetime"
              value-format="x"
              style="width: 200px"
            />
          </el-form-item>
          <el-form-item>
            <el-button :loading="submitting" @click="submitTriage">更新</el-button>
          </el-form-item>
        </el-form>
      </el-card>

      <el-card v-if="!improvement.evalCaseId && canBind" shadow="never" class="section">
        <template #header>建立目标回归用例</template>
        <el-form :model="evalCaseForm" label-width="88px" :disabled="!canAct">
          <el-form-item label="用例编号"><el-input v-model="evalCaseForm.caseId" /></el-form-item>
          <el-form-item label="类型">
            <el-radio-group v-model="evalCaseForm.evalType">
              <el-radio-button value="INTENT">意图</el-radio-button>
              <el-radio-button value="QUALITY">质量</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="期望"><el-input v-model="evalCaseForm.expected" /></el-form-item>
          <el-form-item label="分类"><el-input v-model="evalCaseForm.category" /></el-form-item>
          <el-form-item>
            <el-button
              type="primary"
              :disabled="!evalCaseForm.caseId.trim()"
              :loading="submitting"
              @click="submitEvalCase"
              >创建用例</el-button
            >
          </el-form-item>
        </el-form>
      </el-card>

      <el-card v-if="canBind" shadow="never" class="section">
        <template #header>绑定修复候选</template>
        <el-alert
          v-if="candidateChanged"
          type="warning"
          :closable="false"
          show-icon
          title="候选绑定已修改，请先冻结候选，再复评或发布"
          class="candidate-change"
        />
        <el-form :model="artifactForm" inline :disabled="!canAct">
          <el-form-item label="智能体编号">
            <el-input-number v-model="artifactForm.agentId" :min="1" />
          </el-form-item>
          <el-form-item label="评测类型">
            <el-select v-model="artifactForm.evalType" style="width: 110px">
              <el-option label="意图" value="INTENT" />
              <el-option label="质量" value="QUALITY" />
            </el-select>
          </el-form-item>
          <el-form-item label="目标用例">
            <el-input v-model="artifactForm.evalCaseId" style="width: 170px" />
          </el-form-item>
          <el-form-item>
            <el-button
              type="primary"
              :disabled="!artifactForm.agentId || !artifactForm.evalCaseId.trim()"
              :loading="submitting"
              @click="submitArtifact"
              >冻结候选</el-button
            >
          </el-form-item>
        </el-form>
      </el-card>

      <div class="actions section">
        <el-input
          :disabled="!canAct"
          v-model="reevaluationRemark"
          placeholder="复评备注"
          style="width: 220px"
        />
        <el-button
          v-permission="'eval:run'"
          type="primary"
          :disabled="
            !canAct ||
            candidateChanged ||
            !['READY_FOR_REEVALUATION', 'REEVALUATION_FAILED'].includes(improvement.status)
          "
          :loading="submitting"
          @click="submitReevaluation"
        >
          运行复评
        </el-button>
        <el-button
          v-permission="'agent:edit'"
          type="success"
          :disabled="!canAct || candidateChanged || improvement.status !== 'READY_TO_PUBLISH'"
          :loading="submitting"
          @click="submitPublish"
        >
          创建可靠发布任务
        </el-button>
        <el-button
          :disabled="!canAct || !['PUBLISHING', 'OBSERVING'].includes(improvement.status)"
          @click="submitRefresh"
        >
          立即同步状态
        </el-button>
        <el-button :disabled="busy" @click="load">刷新</el-button>
      </div>
    </template>
  </div>
</template>

<style scoped>
.improvement-closure {
  min-height: 160px;
  overflow-wrap: anywhere;
}

.evidence-details {
  border: 1px solid var(--cw-line);
  border-radius: var(--cw-radius-md);
  padding: 12px;
  background: var(--cw-paper);
}

.evidence-details summary {
  cursor: pointer;
  color: var(--cw-text-muted);
  font-size: 14px;
}

.improvement-closure :deep(.el-descriptions__label) {
  width: 110px;
}

.improvement-closure :deep(.el-form--inline .el-form-item) {
  max-width: 100%;
}

@media (max-width: 640px) {
  .improvement-closure :deep(.el-form-item) {
    display: flex;
    flex-direction: column;
    align-items: stretch;
    margin-right: 0;
  }

  .improvement-closure :deep(.el-form-item__label) {
    justify-content: flex-start;
    width: auto !important;
  }

  .improvement-closure :deep(.el-form-item__content) {
    margin-left: 0 !important;
  }

  .improvement-closure :deep(.el-button) {
    min-height: 44px;
    margin-left: 0;
  }
}

.section {
  margin-top: 14px;
}

.candidate-change {
  margin-bottom: 12px;
}

.actions {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
}
</style>
