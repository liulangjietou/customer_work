<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
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

const props = defineProps<{
  sourceType: ImprovementSourceType
  sourceKey: string
}>()

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

const statusType = computed(() => {
  const status = improvement.value?.status
  if (status === 'VERIFIED') return 'success'
  if (status === 'INEFFECTIVE' || status === 'PUBLISH_FAILED' || status === 'REEVALUATION_FAILED') return 'danger'
  if (status === 'INCONCLUSIVE') return 'warning'
  return 'primary'
})

const canBind = computed(() => {
  const status = improvement.value?.status
  return !!status && !['REEVALUATING', 'PUBLISHING', 'OBSERVING', 'VERIFIED', 'CANCELLED'].includes(status)
})

function formatTime(ms?: number | null) {
  return ms ? new Date(ms).toLocaleString('zh-CN', { hour12: false }) : '-'
}

function shortHash(value?: string | null) {
  return value ? `${value.slice(0, 12)}…` : '-'
}

async function load() {
  loading.value = true
  try {
    improvement.value = await getImprovementCase(props.sourceType, props.sourceKey)
    if (improvement.value) {
      ownerId.value = improvement.value.ownerId
      slaDueAtMs.value = improvement.value.slaDueAtMs
      artifactForm.agentId = improvement.value.agentId || undefined
      artifactForm.evalType = improvement.value.evalType || 'QUALITY'
      artifactForm.evalCaseId = improvement.value.evalCaseId || ''
    } else {
      evalCaseForm.caseId = `${props.sourceType === 'BADCASE' ? 'bc' : 'gap'}-${props.sourceKey.slice(0, 8)}`
    }
  } finally {
    loading.value = false
  }
}

async function submitTriage() {
  submitting.value = true
  try {
    improvement.value = await triageImprovementCase(props.sourceType, props.sourceKey, {
      ownerId: ownerId.value || undefined,
      slaDueAtMs: Number(slaDueAtMs.value),
    })
    ElMessage.success('责任人与 SLA 已保存')
  } finally {
    submitting.value = false
  }
}

async function submitEvalCase() {
  if (!improvement.value) return
  submitting.value = true
  try {
    improvement.value = await createImprovementEvalCase(improvement.value.id, { ...evalCaseForm })
    artifactForm.evalType = evalCaseForm.evalType
    artifactForm.evalCaseId = evalCaseForm.caseId
    ElMessage.success('回归用例已创建并绑定来源')
  } finally {
    submitting.value = false
  }
}

async function submitArtifact() {
  if (!improvement.value || !artifactForm.agentId) return
  submitting.value = true
  try {
    improvement.value = await bindImprovementArtifact(improvement.value.id, {
      agentId: artifactForm.agentId,
      evalType: artifactForm.evalType,
      evalCaseId: artifactForm.evalCaseId || undefined,
    })
    ElMessage.success('已冻结当前可发布候选，后续漂移会被拒绝')
  } finally {
    submitting.value = false
  }
}

async function submitReevaluation() {
  if (!improvement.value) return
  submitting.value = true
  try {
    improvement.value = await reevaluateImprovementCase(improvement.value.id, reevaluationRemark.value || undefined)
    ElMessage.success(improvement.value.reevaluationStatus === 'PASSED' ? '复评通过' : '复评未通过')
  } finally {
    submitting.value = false
  }
}

async function submitPublish() {
  if (!improvement.value) return
  submitting.value = true
  try {
    improvement.value = await publishImprovementCase(improvement.value.id)
    ElMessage.success(`可靠发布任务已创建：${improvement.value.publishTaskId}`)
  } finally {
    submitting.value = false
  }
}

async function submitRefresh() {
  if (!improvement.value) return
  improvement.value = await refreshImprovementCase(improvement.value.id)
  ElMessage.success('已安排立即同步，稍后刷新查看结果')
}

watch(() => [props.sourceType, props.sourceKey], load)
onMounted(load)
</script>

<template>
  <div v-loading="loading" class="improvement-closure">
    <el-alert
      type="info"
      show-icon
      :closable="false"
      title="完成不等于点过“补知识”"
      description="只有责任人和 SLA 明确、目标用例在同一候选版本复评通过、可靠发布全目标 APPLIED，且观察窗内达到最小曝光后同类信号不再复发，才进入效果已验证。"
    />

    <el-card v-if="!improvement" shadow="never" class="section">
      <template #header>认领治理</template>
      <el-form label-width="88px">
        <el-form-item label="责任人">
          <el-input v-model="ownerId" placeholder="留空则取当前登录人" />
        </el-form-item>
        <el-form-item label="SLA 截止">
          <el-date-picker v-model="slaDueAtMs" type="datetime" value-format="x" style="width: 100%" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="submitting" @click="submitTriage">认领</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <template v-else>
      <el-descriptions :column="2" border class="section">
        <el-descriptions-item label="闭环状态">
          <el-tag :type="statusType">{{ STATUS_LABELS[improvement.status] }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="SLA">
          <el-tag :type="improvement.slaStatus === 'OVERDUE' ? 'danger' : 'success'">
            {{ improvement.slaStatus === 'OVERDUE' ? '已逾期' : improvement.slaStatus === 'CLOSED' ? '已关闭' : '正常' }}
          </el-tag>
          {{ formatTime(improvement.slaDueAtMs) }}
        </el-descriptions-item>
        <el-descriptions-item label="责任人">{{ improvement.ownerId }}</el-descriptions-item>
        <el-descriptions-item label="来源信号">认领时 {{ improvement.sourceSignalCount }} 次</el-descriptions-item>
        <el-descriptions-item label="制品版本">{{ shortHash(improvement.artifactVersion) }}</el-descriptions-item>
        <el-descriptions-item label="目标用例">{{ improvement.evalCaseId || '-' }}</el-descriptions-item>
        <el-descriptions-item label="复评运行">{{ improvement.evalRunId || '-' }}</el-descriptions-item>
        <el-descriptions-item label="复评结论">
          {{ improvement.reevaluationStatus }} / {{ improvement.reevaluationVerdict || '-' }}
        </el-descriptions-item>
        <el-descriptions-item label="发布任务">{{ improvement.publishTaskId || '-' }}</el-descriptions-item>
        <el-descriptions-item label="发布状态">{{ improvement.publishStatus || '-' }}</el-descriptions-item>
        <el-descriptions-item label="发布 revision">{{ improvement.publishRevision || '-' }}</el-descriptions-item>
        <el-descriptions-item label="观察窗口">
          {{ formatTime(improvement.observationStartedAtMs) }} → {{ formatTime(improvement.observationEndsAtMs) }}
        </el-descriptions-item>
        <el-descriptions-item label="线上曝光">
          {{ improvement.observedCalls }} / {{ improvement.minExposureCalls ?? '-' }} 次
        </el-descriptions-item>
        <el-descriptions-item label="同类复发">
          {{ improvement.observedSignals }} / 最多 {{ improvement.maxRecurrenceSignals ?? '-' }} 次
        </el-descriptions-item>
      </el-descriptions>

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
        <el-form inline>
          <el-form-item label="责任人"><el-input v-model="ownerId" style="width: 150px" /></el-form-item>
          <el-form-item label="截止">
            <el-date-picker v-model="slaDueAtMs" type="datetime" value-format="x" style="width: 200px" />
          </el-form-item>
          <el-form-item>
            <el-button :loading="submitting" @click="submitTriage">更新</el-button>
          </el-form-item>
        </el-form>
      </el-card>

      <el-card v-if="!improvement.evalCaseId" shadow="never" class="section">
        <template #header>建立目标回归用例</template>
        <el-form :model="evalCaseForm" label-width="88px">
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
            <el-button type="primary" :loading="submitting" @click="submitEvalCase">创建用例</el-button>
          </el-form-item>
        </el-form>
      </el-card>

      <el-card v-if="canBind" shadow="never" class="section">
        <template #header>冻结待复评制品</template>
        <el-form :model="artifactForm" inline>
          <el-form-item label="Agent ID">
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
            <el-button type="primary" :loading="submitting" @click="submitArtifact">冻结候选</el-button>
          </el-form-item>
        </el-form>
      </el-card>

      <div class="actions section">
        <el-input v-model="reevaluationRemark" placeholder="复评备注" style="width: 220px" />
        <el-button
          v-permission="'eval:run'"
          type="primary"
          :disabled="!['READY_FOR_REEVALUATION', 'REEVALUATION_FAILED'].includes(improvement.status)"
          :loading="submitting"
          @click="submitReevaluation"
        >
          运行复评
        </el-button>
        <el-button
          v-permission="'agent:edit'"
          type="success"
          :disabled="improvement.status !== 'READY_TO_PUBLISH'"
          :loading="submitting"
          @click="submitPublish"
        >
          创建可靠发布任务
        </el-button>
        <el-button
          :disabled="!['PUBLISHING', 'OBSERVING'].includes(improvement.status)"
          @click="submitRefresh"
        >
          立即同步状态
        </el-button>
        <el-button @click="load">刷新</el-button>
      </div>
    </template>
  </div>
</template>

<style scoped>
.improvement-closure {
  min-height: 160px;
}

.section {
  margin-top: 14px;
}

.actions {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
}
</style>
