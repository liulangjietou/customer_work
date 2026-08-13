<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import EvalTrendChart from '@/components/EvalTrendChart.vue'
import {
  getComparison,
  listRuns,
  triggerEval,
  type EvalComparison,
  type EvalRun,
  type EvalTypeCode,
  type EvalVerdict,
} from '@/api/eval'

// 评测中心：跑标准集 → 出报告 → 与上一版对比。
// 评测跑在客服端（那里才有真实的 orchestrator 与模型链），后台只做触发与展示。
//
// 页面刻意把"总分方向"和"回归用例"并列展示：准确率涨了 2 个点，同时有一个用例从通过变失败，
// 这是最容易被总分掩盖、也最需要人立刻去看的情形。

/** 两类评测的指标口径不同，标签跟着类型走，避免运营对着"准确率"看质量评测。 */
const METRIC_LABELS: Record<EvalTypeCode, { primary: string; secondary: string; hint: string }> = {
  INTENT: {
    primary: '准确率',
    secondary: '快车道覆盖率',
    hint: '离线确定性评测，不调模型、无 token 成本，可放心随时跑',
  },
  QUALITY: {
    primary: '平均分(折算)',
    secondary: '通过率',
    hint: 'LLM-as-Judge 逐条打分，需真实模型 Key，耗时按分钟计且有 token 成本',
  },
}

const VERDICT_LABELS: Record<EvalVerdict, { text: string; type: 'success' | 'danger' | 'info' | 'primary' }> = {
  FIRST_RUN: { text: '首次运行', type: 'primary' },
  IMPROVED: { text: '变好', type: 'success' },
  REGRESSED: { text: '变差', type: 'danger' },
  UNCHANGED: { text: '持平', type: 'info' },
}

const TRIGGER_LABELS: Record<string, string> = {
  MANUAL: '人工',
  SCHEDULED: '定时',
  API: '接口',
}

const evalType = ref<EvalTypeCode>('INTENT')
const loading = ref(false)
const running = ref(false)
const runs = ref<EvalRun[]>([])

const labels = computed(() => METRIC_LABELS[evalType.value])

async function loadRuns() {
  loading.value = true
  try {
    runs.value = await listRuns(evalType.value)
  } finally {
    loading.value = false
  }
}

function handleTypeChange() {
  detailVisible.value = false
  return loadRuns()
}

function formatPercent(value: number | null | undefined): string {
  if (value === null || value === undefined) return '-'
  return `${(value * 100).toFixed(2)}%`
}

/** 带符号的变化量：+0.00% 与 -0.00% 要能一眼分清方向。 */
function formatDelta(value: number): string {
  const percent = value * 100
  const sign = percent > 0 ? '+' : ''
  return `${sign}${percent.toFixed(2)}%`
}

function formatTime(ms: number): string {
  return new Date(ms).toLocaleString('zh-CN', { hour12: false })
}

// ---------- 触发评测 ----------

async function handleRun() {
  const label = labels.value
  const isQuality = evalType.value === 'QUALITY'
  try {
    const { value: remark } = await ElMessageBox.prompt(
      isQuality
        ? '质量评测会逐条生成回复再调 Judge 打分，耗时按分钟计且产生真实 token 费用，确认跑吗？'
        : '意图评测为离线确定性评测，不调模型、无额外成本。',
      `立即跑一次${label.primary === '准确率' ? '意图' : '质量'}评测`,
      {
        confirmButtonText: '开始评测',
        cancelButtonText: '取消',
        inputPlaceholder: '备注（可选），如"换 qwen-max 后重跑"',
        inputValue: '',
        inputValidator: () => true,
      },
    )
    running.value = true
    const comparison = await triggerEval(evalType.value, remark || undefined)
    ElMessage.success(
      comparison.regressions.length > 0
        ? `评测完成，发现 ${comparison.regressions.length} 个回归用例，请查看详情`
        : '评测完成',
    )
    await loadRuns()
    openDetail(comparison.current)
  } catch (error) {
    // ElMessageBox 取消时抛 'cancel'，不当作错误
    if (error !== 'cancel') {
      throw error
    }
  } finally {
    running.value = false
  }
}

// ---------- 详情与对比 ----------

const detailVisible = ref(false)
const detailLoading = ref(false)
const comparison = ref<EvalComparison | null>(null)

async function openDetail(run: EvalRun) {
  detailVisible.value = true
  detailLoading.value = true
  comparison.value = null
  try {
    comparison.value = await getComparison(run.runId)
  } finally {
    detailLoading.value = false
  }
}

/** 原始指标字典转成可渲染的行；归一化后的主/次指标已单独展示，这里给的是未折算的原值。 */
const metricRows = computed(() => {
  const metrics = comparison.value?.current.metrics
  if (!metrics) return []
  return Object.entries(metrics).map(([key, value]) => ({
    key,
    value: typeof value === 'number' ? Number(value.toFixed(4)) : value,
  }))
})

onMounted(loadRuns)
</script>

<template>
  <div class="eval-center">
    <el-card shadow="never">
      <div class="toolbar">
        <el-radio-group v-model="evalType" @change="handleTypeChange">
          <el-radio-button value="INTENT">意图路由</el-radio-button>
          <el-radio-button value="QUALITY">回复质量</el-radio-button>
        </el-radio-group>
        <span class="hint">{{ labels.hint }}</span>
        <div class="spacer" />
        <el-button
          v-permission="'eval:run'"
          type="primary"
          :loading="running"
          @click="handleRun"
        >
          立即评测
        </el-button>
        <el-button :loading="loading" @click="loadRuns">刷新</el-button>
      </div>

      <EvalTrendChart
        :runs="runs"
        :loading="loading"
        :primary-label="labels.primary"
        :secondary-label="labels.secondary"
      />
    </el-card>

    <el-card shadow="never" class="list-card">
      <el-table v-loading="loading" :data="runs" style="width: 100%">
        <el-table-column label="运行时间" width="180">
          <template #default="{ row }">{{ formatTime(row.createdAtMs) }}</template>
        </el-table-column>
        <el-table-column label="触发" width="80">
          <template #default="{ row }">{{ TRIGGER_LABELS[row.trigger] ?? row.trigger }}</template>
        </el-table-column>
        <el-table-column :label="labels.primary" width="130">
          <template #default="{ row }">{{ formatPercent(row.primaryMetric) }}</template>
        </el-table-column>
        <el-table-column :label="labels.secondary" width="140">
          <template #default="{ row }">{{ formatPercent(row.secondaryMetric) }}</template>
        </el-table-column>
        <el-table-column label="通过/总数" width="110">
          <template #default="{ row }">{{ row.passed }} / {{ row.total }}</template>
        </el-table-column>
        <el-table-column label="失败用例" width="100">
          <template #default="{ row }">
            <el-tag v-if="row.failedCaseIds.length > 0" type="danger">
              {{ row.failedCaseIds.length }}
            </el-tag>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column prop="remark" label="备注" show-overflow-tooltip />
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情与对比</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-drawer v-model="detailVisible" title="运行详情与版本对比" size="620px">
      <div v-loading="detailLoading">
        <template v-if="comparison">
          <el-descriptions :column="2" border>
            <el-descriptions-item label="结论">
              <el-tag :type="VERDICT_LABELS[comparison.verdict].type">
                {{ VERDICT_LABELS[comparison.verdict].text }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="运行时间">
              {{ formatTime(comparison.current.createdAtMs) }}
            </el-descriptions-item>
            <el-descriptions-item :label="labels.primary">
              {{ formatPercent(comparison.current.primaryMetric) }}
              <span
                v-if="comparison.baseline"
                :class="comparison.primaryDelta >= 0 ? 'delta-up' : 'delta-down'"
              >
                （{{ formatDelta(comparison.primaryDelta) }}）
              </span>
            </el-descriptions-item>
            <el-descriptions-item :label="labels.secondary">
              {{ formatPercent(comparison.current.secondaryMetric) }}
              <span
                v-if="comparison.baseline"
                :class="comparison.secondaryDelta >= 0 ? 'delta-up' : 'delta-down'"
              >
                （{{ formatDelta(comparison.secondaryDelta) }}）
              </span>
            </el-descriptions-item>
            <el-descriptions-item label="通过/总数">
              {{ comparison.current.passed }} / {{ comparison.current.total }}
            </el-descriptions-item>
            <el-descriptions-item label="备注">
              {{ comparison.current.remark || '-' }}
            </el-descriptions-item>
          </el-descriptions>

          <el-alert
            v-if="comparison.datasetChanged"
            class="section"
            type="warning"
            show-icon
            :closable="false"
            title="评测集规模变了，两次指标不可直接比较"
            :description="`基线 ${comparison.baseline?.datasetSize} 个用例 → 本次 ${comparison.current.datasetSize} 个用例。新增用例若失败会被计入回归。`"
          />

          <!-- 回归项单列一块并用 error 级样式：它会被总分上涨掩盖，是最该被看见的信息 -->
          <el-alert
            v-if="comparison.regressions.length > 0"
            class="section"
            type="error"
            show-icon
            :closable="false"
            :title="`发现 ${comparison.regressions.length} 个回归用例（上版通过、这版失败）`"
          >
            <div class="case-tags">
              <el-tag v-for="id in comparison.regressions" :key="id" type="danger" effect="plain">
                {{ id }}
              </el-tag>
            </div>
          </el-alert>

          <el-alert
            v-if="comparison.fixes.length > 0"
            class="section"
            type="success"
            show-icon
            :closable="false"
            :title="`修复了 ${comparison.fixes.length} 个用例（上版失败、这版通过）`"
          >
            <div class="case-tags">
              <el-tag v-for="id in comparison.fixes" :key="id" type="success" effect="plain">
                {{ id }}
              </el-tag>
            </div>
          </el-alert>

          <div class="section">
            <div class="section-title">原始指标</div>
            <el-table :data="metricRows" size="small" border>
              <el-table-column prop="key" label="指标" width="180" />
              <el-table-column prop="value" label="值" />
            </el-table>
          </div>

          <div class="section">
            <div class="section-title">
              失败明细（{{ comparison.current.failures.length }}）
            </div>
            <el-empty
              v-if="comparison.current.failures.length === 0"
              description="本次全部通过"
              :image-size="60"
            />
            <ul v-else class="failure-list">
              <li v-for="(item, index) in comparison.current.failures" :key="index">{{ item }}</li>
            </ul>
          </div>
        </template>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.eval-center {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}

.spacer {
  flex: 1;
}

.hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.section {
  margin-top: 16px;
}

.section-title {
  font-weight: 600;
  margin-bottom: 8px;
}

.case-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 6px;
}

.delta-up {
  color: var(--el-color-success);
}

.delta-down {
  color: var(--el-color-danger);
}

.failure-list {
  margin: 0;
  padding-left: 18px;
  font-size: 13px;
  line-height: 1.8;
  color: var(--el-text-color-regular);
  word-break: break-all;
}
</style>
