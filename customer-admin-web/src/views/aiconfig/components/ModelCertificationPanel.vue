<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import {
  certifyModel,
  getModelCertification,
  listModelCertificationRuns,
  updateModel,
} from '@/api/model'
import {
  canActivateByCertification,
  certificationResultMessage,
} from '@/utils/certificationResult'
import type {
  ModelCertification,
  ModelCertificationRequest,
  ModelSaveRequest,
  ModelVO,
} from '@/types/api'

const props = defineProps<{
  modelId: number | null
  model: ModelVO | null
}>()

const emit = defineEmits<{
  updated: []
}>()

const loading = ref(false)
const certifying = ref(false)
const activating = ref(false)
const current = ref<ModelCertification | null>(null)
const history = ref<ModelCertification[]>([])
const form = reactive<ModelCertificationRequest>({
  requiredContextTokens: 8192,
  maxLatencyMs: 3000,
  maxInputPrice: 100,
  maxOutputPrice: 300,
  validDays: 30,
  requireStreaming: true,
  requireToolCall: true,
  requireStructuredOutput: true,
})

const effectiveStatus = computed(() => current.value?.effectiveStatus ?? 'UNKNOWN')
const canActivate = computed(() => canActivateByCertification(effectiveStatus.value)
  && (props.model?.status !== 1 || props.model?.lifecycleStatus !== 'ACTIVE'))

watch(() => props.modelId, (id) => {
  if (id) void load(id)
  else {
    current.value = null
    history.value = []
  }
}, { immediate: true })

async function load(id = props.modelId) {
  if (!id) return
  loading.value = true
  try {
    const [snapshot, runs] = await Promise.all([
      getModelCertification(id),
      listModelCertificationRuns(id),
    ])
    current.value = snapshot
    history.value = runs
  } finally {
    loading.value = false
  }
}

async function certify() {
  if (!props.modelId) return
  certifying.value = true
  try {
    current.value = await certifyModel(props.modelId, { ...form })
    const message = certificationResultMessage(current.value)
    ElMessage[message.type](message.text)
    await load()
    emit('updated')
  } finally {
    certifying.value = false
  }
}

async function activate() {
  if (!props.modelId || !props.model || !canActivate.value) return
  await ElMessageBox.confirm(
    '后端将再次校验认证有效期、端点修订号和 SecretRef 版本。确认激活此部署？',
    '激活模型部署',
    { type: 'warning' },
  )
  activating.value = true
  try {
    await updateModel(props.modelId, activeRequest(props.model))
    ElMessage.success('部署已激活')
    emit('updated')
  } finally {
    activating.value = false
  }
}

function activeRequest(model: ModelVO): ModelSaveRequest {
  return {
    assetId: model.assetId,
    assetCode: model.assetCode,
    assetName: model.assetName,
    vendor: model.vendor,
    family: model.family,
    assetVersion: model.assetVersion,
    modality: model.modality,
    contextWindow: model.contextWindow,
    maxOutputTokens: model.maxOutputTokens,
    supportsStream: model.supportsStream,
    supportsTool: model.supportsTool,
    supportsJsonSchema: model.supportsJsonSchema,
    supportsMultimodal: model.supportsMultimodal,
    modelName: model.modelName,
    deploymentCode: model.deploymentCode,
    provider: model.protocolAdapter ?? model.provider,
    apiKey: '',
    baseUrl: model.baseUrl,
    region: model.region,
    environment: model.environment,
    model: model.model,
    isDefault: model.isDefault,
    status: 1,
    lifecycleStatus: 'ACTIVE',
  }
}

function statusType(status: string | null | undefined) {
  if (status === 'PASSED' || status === 'NOT_REQUIRED') return 'success'
  if (status === 'FAILED') return 'danger'
  if (status === 'EXPIRED' || status === 'STALE') return 'warning'
  return 'info'
}

function checkType(status: string) {
  if (status === 'PASSED') return 'success'
  if (status === 'FAILED') return 'danger'
  return 'info'
}

function formatTime(value: string | null | undefined) {
  if (!value) return '—'
  return new Date(value).toLocaleString('zh-CN', { hour12: false })
}
</script>

<template>
  <div v-loading="loading" class="certification-panel">
    <section class="cert-hero" :class="`is-${effectiveStatus.toLowerCase()}`">
      <div>
        <span>当前上线认证</span>
        <strong>
          {{ effectiveStatus }}
          <el-tag v-if="current?.runId" :type="checkType(current.status)" size="small" effect="plain">
            最近一次运行 {{ current.status }}
          </el-tag>
        </strong>
        <small v-if="current?.staleReason">{{ current.staleReason }}</small>
        <small v-else>有效至 {{ formatTime(current?.validUntil) }}</small>
      </div>
      <el-button
        v-if="canActivate"
        v-permission="'model:edit'"
        type="success"
        :loading="activating"
        @click="activate"
      >
        激活部署
      </el-button>
    </section>

    <div class="evidence-grid">
      <div><span>通过 / 失败</span><strong>{{ current?.passedChecks ?? 0 }} / {{ current?.failedChecks ?? 0 }}</strong></div>
      <div><span>P95 延迟</span><strong>{{ current?.latencyP95Ms == null ? '—' : `${current.latencyP95Ms} ms` }}</strong></div>
      <div><span>上下文证据</span><strong>{{ current?.verifiedContextTokens ?? '—' }}</strong></div>
      <div><span>认证配置</span><strong>r{{ current?.certifiedEndpointRevision ?? '—' }} · secret v{{ current?.certifiedSecretVersion ?? '—' }}</strong></div>
    </div>

    <el-alert
      title="上线门禁按真实端点验证连通性、流式、工具、结构化输出、上下文、延迟与成本；失败、过期或配置漂移均不能激活。"
      type="info"
      :closable="false"
      show-icon
    />

    <el-table class="check-table" :data="current?.checks ?? []" empty-text="尚无认证证据" max-height="300">
      <el-table-column prop="name" label="检查项" min-width="150" />
      <el-table-column label="结果" width="100">
        <template #default="{ row }"><el-tag :type="checkType(row.status)">{{ row.status }}</el-tag></template>
      </el-table-column>
      <el-table-column prop="measuredValue" label="实测" min-width="120" />
      <el-table-column prop="threshold" label="门槛" min-width="120" />
      <el-table-column prop="message" label="脱敏摘要" min-width="180" show-overflow-tooltip />
    </el-table>

    <section class="cert-form">
      <div class="section-heading">
        <div>
          <h3>执行认证</h3>
          <p>认证会产生不可变运行记录；有效期不超过当前凭据到期时间。</p>
        </div>
      </div>
      <el-form :model="form" label-position="top">
        <div class="form-grid">
          <el-form-item label="最低上下文 Token"><el-input-number v-model="form.requiredContextTokens" :min="1" style="width: 100%" /></el-form-item>
          <el-form-item label="P95 延迟上限(ms)"><el-input-number v-model="form.maxLatencyMs" :min="1" style="width: 100%" /></el-form-item>
          <el-form-item label="输入价格上限"><el-input-number v-model="form.maxInputPrice" :min="0" :precision="4" style="width: 100%" /></el-form-item>
          <el-form-item label="输出价格上限"><el-input-number v-model="form.maxOutputPrice" :min="0" :precision="4" style="width: 100%" /></el-form-item>
          <el-form-item label="有效天数"><el-input-number v-model="form.validDays" :min="1" :max="365" style="width: 100%" /></el-form-item>
          <el-form-item label="能力门槛">
            <el-checkbox v-model="form.requireStreaming">流式</el-checkbox>
            <el-checkbox v-model="form.requireToolCall">工具</el-checkbox>
            <el-checkbox v-model="form.requireStructuredOutput">结构化</el-checkbox>
          </el-form-item>
        </div>
        <el-button v-permission="'model:certify'" class="cw-final-action" type="primary" :loading="certifying" @click="certify">
          运行上线认证
        </el-button>
      </el-form>
    </section>

    <section class="history-section">
      <h3>认证历史</h3>
      <el-table :data="history" max-height="260" empty-text="暂无历史">
        <el-table-column prop="runId" label="Run" width="80" />
        <!-- 历史要回答的是「那次跑得怎么样」，所以取 status；effectiveStatus 是按当前配置算的
             门禁态，对每条历史行都算成同一个值，看不出哪次通过哪次失败 -->
        <el-table-column label="状态" width="110">
          <template #default="{ row }"><el-tag :type="statusType(row.status)">{{ row.status }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="latencyP95Ms" label="P95(ms)" width="100" />
        <el-table-column label="完成时间" min-width="170">
          <template #default="{ row }">{{ formatTime(row.completedAt) }}</template>
        </el-table-column>
        <el-table-column label="有效期" min-width="170">
          <template #default="{ row }">{{ formatTime(row.validUntil) }}</template>
        </el-table-column>
      </el-table>
    </section>
  </div>
</template>

<style scoped>
.certification-panel { color: var(--cw-text); }
.cert-hero { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 18px 20px; border: 1px solid var(--cw-line); border-radius: 12px; background: var(--el-fill-color-extra-light); }
.cert-hero span, .cert-hero small { display: block; color: var(--cw-text-muted); font-size: 12px; }
.cert-hero strong { display: block; margin: 4px 0; font-size: 24px; letter-spacing: .04em; }
.cert-hero.is-passed { border-color: color-mix(in srgb, var(--cw-success) 42%, var(--cw-line)); background: color-mix(in srgb, var(--cw-success) 8%, var(--cw-paper)); }
.cert-hero.is-failed { border-color: color-mix(in srgb, var(--cw-danger) 42%, var(--cw-line)); background: color-mix(in srgb, var(--cw-danger) 8%, var(--cw-paper)); }
.cert-hero.is-expired, .cert-hero.is-stale { border-color: color-mix(in srgb, var(--cw-amber) 42%, var(--cw-line)); background: color-mix(in srgb, var(--cw-amber) 8%, var(--cw-paper)); }
.evidence-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 10px; margin: 14px 0; }
.evidence-grid > div { padding: 13px; border: 1px solid var(--cw-line); border-radius: 10px; background: var(--cw-paper); }
.evidence-grid span { display: block; margin-bottom: 5px; color: var(--cw-text-muted); font-size: 11px; }
.evidence-grid strong { font-size: 13px; }
.check-table { margin-top: 16px; }
.cert-form { margin-top: 22px; padding: 18px; border: 1px solid var(--cw-line); border-radius: 12px; background: var(--el-fill-color-extra-light); }
.section-heading h3, .history-section h3 { margin: 0; }
.section-heading p { margin: 5px 0 16px; color: var(--cw-text-muted); font-size: 13px; }
.form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 14px; }
.history-section { margin-top: 22px; }
.history-section h3 { margin-bottom: 12px; }
@media (max-width: 760px) {
  .evidence-grid, .form-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .cert-hero { align-items: flex-start; flex-direction: column; }
}
</style>
