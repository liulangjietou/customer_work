<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  evaluateSloPolicy,
  listSloPolicies,
  saveSloPolicy,
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
const dialogVisible = ref(false)
const evaluationVisible = ref(false)
const evaluation = ref<SloEvaluation | null>(null)
const evaluatingId = ref<number | null>(null)

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
  loading.value = true
  try {
    policies.value = await listSloPolicies()
  } finally {
    loading.value = false
  }
}

function openCreate() {
  Object.assign(form, emptyForm())
  dialogVisible.value = true
}

function openEdit(row: SloPolicy) {
  Object.assign(form, row)
  dialogVisible.value = true
}

async function submit() {
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
  await saveSloPolicy({ ...form, scopeKey: form.scopeType === 'TENANT' ? null : form.scopeKey })
  ElMessage.success('SLO 策略已保存')
  dialogVisible.value = false
  await loadPolicies()
}

async function evaluate(row: SloPolicy) {
  evaluatingId.value = row.id
  try {
    evaluation.value = await evaluateSloPolicy(row.id)
    evaluationVisible.value = true
    if (evaluation.value.alertCreated) {
      ElMessage.warning('短、长窗口均超过燃烧率阈值，已记录幂等告警事实')
    }
  } finally {
    evaluatingId.value = null
  }
}

function percent(value: number) {
  return `${(Number(value) * 100).toFixed(2)}%`
}

function statusType(status: SloEvaluation['status']) {
  return status === 'HEALTHY' ? 'success' : status === 'BURNING' ? 'danger' : 'info'
}

onMounted(loadPolicies)
</script>

<template>
  <div class="slo-page">
    <el-card shadow="never">
      <template #header>
        <div class="header">
          <div>
            <h2>SLO 错误预算</h2>
            <p>以真实调用的成功标记和耗时阈值计算；短、长窗口都达到最低样本并同时超限才产生告警事实。</p>
          </div>
          <el-button v-permission="'slo:edit'" type="primary" @click="openCreate">新建策略</el-button>
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
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button v-permission="'slo:evaluate'" link type="primary" :loading="evaluatingId === row.id" @click="evaluate(row)">
              立即评估
            </el-button>
            <el-button v-permission="'slo:edit'" link @click="openEdit(row)">编辑</el-button>
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
      <template #footer><el-button @click="dialogVisible = false">取消</el-button><el-button type="primary" @click="submit">保存</el-button></template>
    </el-dialog>

    <el-dialog v-model="evaluationVisible" title="错误预算评估结果" width="760px">
      <template v-if="evaluation">
        <el-descriptions :column="3" border>
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
  </div>
</template>

<style scoped>
.slo-page { padding: 20px; }
.header { display: flex; align-items: center; justify-content: space-between; gap: 24px; }
.header h2 { margin: 0 0 6px; font-size: 20px; }
.header p { margin: 0; color: var(--el-text-color-secondary); }
.scope-key { margin-left: 8px; font-family: ui-monospace, monospace; }
.hint { margin-left: 10px; color: var(--el-text-color-secondary); }
.split { margin: 0 10px; }
.result-table { margin-top: 18px; }
</style>
