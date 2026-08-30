<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import EvalTrendChart from '@/components/EvalTrendChart.vue'
import {
  createDatasetCase,
  createDatasetVersion,
  deleteDatasetCase,
  diffDatasetVersions,
  exportDatasetCases,
  getComparison,
  importDatasetCases,
  listDatasetCases,
  listDatasetVersions,
  listRuns,
  reviewDatasetVersion,
  triggerEval,
  updateDatasetCase,
  type EvalComparison,
  type EvalDatasetCase,
  type EvalDatasetCaseInput,
  type EvalDatasetDiff,
  type EvalDatasetRelease,
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
  ERROR: { text: '运行异常', type: 'danger' },
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
const datasetLoading = ref(false)
const datasetCases = ref<EvalDatasetCase[]>([])
const datasetVersions = ref<EvalDatasetRelease[]>([])
let runRequestId = 0
let datasetRequestId = 0
let detailRequestId = 0
let diffRequestId = 0

const labels = computed(() => METRIC_LABELS[evalType.value])
// 后端按持久化 seq 倒序返回；同毫秒内 createdAtMs 无法区分先后，首条才是真正的最新运行。
const latestRun = computed(() => runs.value[0] ?? null)

async function loadRuns() {
  const requestId = ++runRequestId
  const selectedType = evalType.value
  loading.value = true
  try {
    const nextRuns = await listRuns(selectedType)
    if (requestId === runRequestId && evalType.value === selectedType) {
      runs.value = nextRuns
    }
  } finally {
    if (requestId === runRequestId) {
      loading.value = false
    }
  }
}

function handleTypeChange() {
  detailVisible.value = false
  diffVisible.value = false
  cancelDetailRequest()
  cancelVersionDiffRequest()
  return Promise.all([loadRuns(), loadDatasetGovernance()])
}

async function loadDatasetGovernance() {
  const requestId = ++datasetRequestId
  const selectedType = evalType.value
  datasetLoading.value = true
  try {
    const [nextCases, nextVersions] = await Promise.all([
      listDatasetCases(selectedType),
      listDatasetVersions(selectedType),
    ])
    if (requestId === datasetRequestId && evalType.value === selectedType) {
      datasetCases.value = nextCases
      datasetVersions.value = nextVersions
    }
  } finally {
    if (requestId === datasetRequestId) {
      datasetLoading.value = false
    }
  }
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
  if (running.value) return
  const selectedType = evalType.value
  const label = labels.value
  const isQuality = selectedType === 'QUALITY'
  running.value = true
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
    const comparison = await triggerEval(selectedType, remark || undefined)
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
  const requestId = ++detailRequestId
  const runId = run.runId
  detailVisible.value = true
  detailLoading.value = true
  comparison.value = null
  try {
    const nextComparison = await getComparison(runId)
    if (requestId === detailRequestId && detailVisible.value && nextComparison.current.runId === runId) {
      comparison.value = nextComparison
    }
  } finally {
    if (requestId === detailRequestId) {
      detailLoading.value = false
    }
  }
}

function cancelDetailRequest() {
  detailRequestId += 1
  detailLoading.value = false
  comparison.value = null
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

// ---------- 数据集工作区与命名版本 ----------

const caseDialogVisible = ref(false)
const editingCase = ref(false)
const caseForm = reactive<EvalDatasetCaseInput>({
  caseId: '',
  input: '',
  expected: '',
  category: '',
  enabled: true,
  originRef: null,
})
const diffVisible = ref(false)
const diffLoading = ref(false)
const datasetDiff = ref<EvalDatasetDiff | null>(null)

function openCreateCase() {
  editingCase.value = false
  Object.assign(caseForm, { caseId: '', input: '', expected: '', category: '', enabled: true, originRef: null })
  caseDialogVisible.value = true
}

function openEditCase(row: EvalDatasetCase) {
  editingCase.value = true
  Object.assign(caseForm, {
    caseId: row.caseId,
    input: row.input,
    expected: row.expected,
    category: row.category,
    enabled: row.enabled,
    originRef: row.originRef,
  })
  caseDialogVisible.value = true
}

async function saveCase() {
  if (!caseForm.caseId.trim() || !caseForm.input.trim()) {
    ElMessage.warning('用例编号和用户输入不能为空')
    return
  }
  if (evalType.value === 'QUALITY' && !caseForm.expected?.trim()) {
    ElMessage.warning('回复质量用例必须填写期望要点')
    return
  }
  const payload = { ...caseForm, caseId: caseForm.caseId.trim(), input: caseForm.input.trim() }
  if (editingCase.value) {
    await updateDatasetCase(evalType.value, payload.caseId, payload)
  } else {
    await createDatasetCase(evalType.value, payload)
  }
  ElMessage.success(editingCase.value ? '用例已更新' : '用例已创建')
  caseDialogVisible.value = false
  await loadDatasetGovernance()
}

async function removeCase(row: EvalDatasetCase) {
  await ElMessageBox.confirm(
    '删除后若该编号来自种子，将恢复为种子内容；种子本身只能通过编辑 enabled=false 停用。',
    '删除数据库覆盖',
    { type: 'warning' },
  )
  await deleteDatasetCase(evalType.value, row.caseId)
  ElMessage.success('数据库覆盖已删除')
  await loadDatasetGovernance()
}

async function importCases() {
  const { value } = await ElMessageBox.prompt(
    '粘贴 JSON 数组。服务端会先校验整批，再以单条批量 SQL 原子写入（最多 1000 条）。',
    '导入评测用例',
    { inputType: 'textarea', inputPlaceholder: '[{"caseId":"...","input":"..."}]' },
  )
  let parsed: EvalDatasetCaseInput[]
  try {
    parsed = JSON.parse(value) as EvalDatasetCaseInput[]
    if (!Array.isArray(parsed)) throw new Error('not array')
  } catch {
    ElMessage.error('JSON 必须是用例数组')
    return
  }
  await importDatasetCases(evalType.value, parsed)
  ElMessage.success(`已导入 ${parsed.length} 条用例`)
  await loadDatasetGovernance()
}

async function exportCases() {
  const data = await exportDatasetCases(evalType.value)
  const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `eval-${evalType.value.toLowerCase()}-${new Date().toISOString().slice(0, 10)}.json`
  link.click()
  URL.revokeObjectURL(url)
}

async function createVersion() {
  const { value } = await ElMessageBox.prompt(
    '命名版本会固化当前有效工作集，创建后内容不可修改。',
    '创建数据集版本',
    { inputPlaceholder: '例如 quality-2026.08.24' },
  )
  await createDatasetVersion(evalType.value, value.trim())
  ElMessage.success('DRAFT 版本已创建，需由另一位有审核权限的用户审核')
  await loadDatasetGovernance()
}

async function reviewVersion(row: EvalDatasetRelease, decision: 'APPROVED' | 'REJECTED') {
  const { value } = await ElMessageBox.prompt(
    decision === 'APPROVED' ? '审核通过后可绑定模型实验，结论不可撤销。' : '驳回后结论不可撤销。',
    decision === 'APPROVED' ? '通过版本' : '驳回版本',
    { inputPlaceholder: '审核意见（可选）' },
  )
  await reviewDatasetVersion(row.releaseId, decision, value || undefined)
  ElMessage.success(decision === 'APPROVED' ? '版本已通过' : '版本已驳回')
  await loadDatasetGovernance()
}

async function openVersionDiff(row: EvalDatasetRelease, index: number) {
  const previous = datasetVersions.value[index + 1]
  if (!previous) {
    ElMessage.info('没有更早版本可比较')
    return
  }
  const requestId = ++diffRequestId
  const fromReleaseId = previous.releaseId
  const toReleaseId = row.releaseId
  datasetDiff.value = null
  diffVisible.value = true
  diffLoading.value = true
  try {
    const nextDiff = await diffDatasetVersions(fromReleaseId, toReleaseId)
    if (requestId === diffRequestId && diffVisible.value) {
      datasetDiff.value = nextDiff
    }
  } finally {
    if (requestId === diffRequestId) {
      diffLoading.value = false
    }
  }
}

function cancelVersionDiffRequest() {
  diffRequestId += 1
  diffLoading.value = false
  datasetDiff.value = null
}

function reviewTagType(status: string) {
  if (status === 'APPROVED') return 'success'
  if (status === 'REJECTED') return 'danger'
  return 'warning'
}

onMounted(() => void Promise.all([loadRuns(), loadDatasetGovernance()]))
</script>

<template>
  <div class="eval-center">
    <el-card shadow="never" class="filter-card">
      <div class="toolbar">
        <el-radio-group v-model="evalType" :disabled="running" @change="handleTypeChange">
          <el-radio-button value="INTENT">意图路由</el-radio-button>
          <el-radio-button value="QUALITY">回复质量</el-radio-button>
        </el-radio-group>
        <span class="hint">{{ labels.hint }}</span>
        <div class="spacer" />
        <el-button
          v-permission="'eval:run'"
          class="cw-final-action"
          type="primary"
          :loading="running"
          @click="handleRun"
        >
          立即评测
        </el-button>
        <el-button :loading="loading" @click="loadRuns">刷新</el-button>
      </div>
    </el-card>

    <div class="summary-row" v-loading="loading">
      <div class="stat">
        <strong>{{ runs.length }}</strong>
        <span>当前类型运行记录</span>
      </div>
      <div class="stat">
        <strong>{{ formatPercent(latestRun?.primaryMetric) }}</strong>
        <span>最新{{ labels.primary }}</span>
      </div>
      <div class="stat">
        <strong>{{ formatPercent(latestRun?.secondaryMetric) }}</strong>
        <span>最新{{ labels.secondary }}</span>
      </div>
      <div class="stat" :class="{ 'stat-danger': (latestRun?.failedCaseIds.length ?? 0) > 0 }">
        <strong>{{ latestRun?.failedCaseIds.length ?? 0 }}</strong>
        <span>最新失败用例</span>
      </div>
    </div>

    <el-card shadow="never" class="trend-card">
      <div class="section-heading">
        <strong>评测趋势</strong>
        <span>先看指标方向，再到运行明细确认失败与版本变化</span>
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

    <el-card v-loading="datasetLoading" shadow="never" class="list-card">
      <template #header>
        <div class="dataset-header">
          <div>
            <strong>评测数据集治理</strong>
            <span>当前有效 {{ datasetCases.length }} 条 · {{ datasetVersions.length }} 个不可变版本</span>
          </div>
          <div class="dataset-actions">
            <el-button v-permission="'eval:dataset-edit'" @click="importCases">导入 JSON</el-button>
            <el-button @click="exportCases">导出 JSON</el-button>
            <el-button v-permission="'eval:dataset-edit'" @click="createVersion">创建命名版本</el-button>
            <el-button v-permission="'eval:dataset-edit'" class="cw-final-action" type="primary" @click="openCreateCase">新增用例</el-button>
          </div>
        </div>
      </template>

      <el-tabs>
        <el-tab-pane label="工作集用例">
          <el-table :data="datasetCases" row-key="caseId">
            <el-table-column prop="caseId" label="用例编号" width="170" />
            <el-table-column prop="input" label="用户输入" min-width="230" show-overflow-tooltip />
            <el-table-column prop="expected" label="期望" min-width="260" show-overflow-tooltip />
            <el-table-column prop="category" label="分类" width="120" />
            <el-table-column label="来源/状态" width="150">
              <template #default="{ row }">
                <el-tag size="small" effect="plain">{{ row.source }}</el-tag>
                <el-tag :type="row.enabled ? 'success' : 'info'" size="small">
                  {{ row.enabled ? '启用' : '停用' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="145" fixed="right">
              <template #default="{ row }">
                <el-button v-permission="'eval:dataset-edit'" link type="primary" @click="openEditCase(row)">编辑</el-button>
                <el-button
                  v-if="row.source !== 'SEED'"
                  v-permission="'eval:dataset-edit'"
                  link
                  type="danger"
                  @click="removeCase(row)"
                >删除覆盖</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="命名版本与审核">
          <el-table :data="datasetVersions" row-key="releaseId">
            <el-table-column prop="versionName" label="版本名" min-width="190" />
            <el-table-column label="快照" min-width="220">
              <template #default="{ row }">
                <code>{{ row.snapshotVersionId.slice(0, 12) }}</code>
                · {{ row.caseCount }} 条
              </template>
            </el-table-column>
            <el-table-column label="状态" width="120">
              <template #default="{ row }"><el-tag :type="reviewTagType(row.status)">{{ row.status }}</el-tag></template>
            </el-table-column>
            <el-table-column label="创建时间" width="180">
              <template #default="{ row }">{{ formatTime(row.createdAtMs) }}</template>
            </el-table-column>
            <el-table-column prop="reviewComment" label="审核意见" min-width="180" show-overflow-tooltip />
            <el-table-column label="操作" width="220" fixed="right">
              <template #default="{ row, $index }">
                <el-button link type="primary" @click="openVersionDiff(row, $index)">与前版 diff</el-button>
                <template v-if="row.status === 'DRAFT'">
                  <el-button v-permission="'eval:dataset-review'" link type="success" @click="reviewVersion(row, 'APPROVED')">通过</el-button>
                  <el-button v-permission="'eval:dataset-review'" link type="danger" @click="reviewVersion(row, 'REJECTED')">驳回</el-button>
                </template>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <el-dialog v-model="caseDialogVisible" :title="editingCase ? '编辑评测用例' : '新增评测用例'" width="min(620px, 94vw)">
      <el-form :model="caseForm" label-position="top">
        <el-form-item label="用例编号"><el-input v-model="caseForm.caseId" :disabled="editingCase" maxlength="64" /></el-form-item>
        <el-form-item label="用户输入"><el-input v-model="caseForm.input" type="textarea" :rows="3" maxlength="1024" show-word-limit /></el-form-item>
        <el-form-item :label="evalType === 'QUALITY' ? '期望要点' : '期望意图（留空表示不应命中快车道）'">
          <el-input v-model="caseForm.expected" type="textarea" :rows="3" maxlength="1024" show-word-limit />
        </el-form-item>
        <el-form-item label="分类"><el-input v-model="caseForm.category" maxlength="64" /></el-form-item>
        <el-form-item label="参与评测"><el-switch v-model="caseForm.enabled" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="caseDialogVisible = false">取消</el-button>
        <el-button class="cw-final-action" type="primary" @click="saveCase">保存用例</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="diffVisible" v-loading="diffLoading" title="数据集版本差异" width="min(760px, 94vw)" @close="cancelVersionDiffRequest">
      <template v-if="datasetDiff">
        <el-descriptions :column="3" border>
          <el-descriptions-item label="新增">{{ datasetDiff.addedCaseIds.length }}</el-descriptions-item>
          <el-descriptions-item label="删除">{{ datasetDiff.removedCaseIds.length }}</el-descriptions-item>
          <el-descriptions-item label="修改">{{ datasetDiff.changedCases.length }}</el-descriptions-item>
        </el-descriptions>
        <pre class="diff-json">{{ JSON.stringify(datasetDiff, null, 2) }}</pre>
      </template>
    </el-dialog>

    <el-drawer v-model="detailVisible" title="运行详情与版本对比" size="620px" @close="cancelDetailRequest">
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
  gap: 14px;
}

.toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}

.filter-card .toolbar {
  margin-bottom: 0;
  padding: 0;
  border: 0;
  background: transparent;
  box-shadow: none;
}

.spacer {
  flex: 1;
}

.hint {
  color: var(--cw-text-muted);
  font-size: 12px;
}

.summary-row {
  display: grid;
  grid-template-columns: repeat(4, minmax(150px, 1fr));
  gap: 12px;
}

.stat {
  min-height: 88px;
  padding: 15px 16px 14px;
  border: 1px solid var(--cw-line);
  border-radius: var(--cw-radius-md);
  background: var(--cw-paper);
  box-shadow: var(--cw-shadow-xs);
}

.stat strong {
  display: block;
  color: var(--cw-text);
  font-size: 25px;
  font-weight: 720;
  font-variant-numeric: tabular-nums;
  line-height: 1.2;
}

.stat span {
  display: block;
  margin-top: 7px;
  color: var(--cw-text-muted);
  font-size: 12px;
}

.stat-danger strong {
  color: var(--cw-danger);
}

.section-heading {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 12px;
}

.section-heading strong {
  color: var(--cw-text);
  font-size: 14px;
  font-weight: 700;
}

.section-heading span {
  color: var(--cw-text-muted);
  font-size: 12px;
  text-align: right;
}

.dataset-header,
.dataset-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.dataset-header {
  justify-content: space-between;
}

.dataset-header > div:first-child {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.dataset-header span {
  color: var(--cw-text-muted);
  font-size: 12px;
}

.diff-json {
  max-height: 420px;
  overflow: auto;
  padding: 12px;
  border: 1px solid var(--cw-line);
  border-radius: var(--cw-radius-sm);
  background: var(--cw-canvas);
  color: var(--cw-text);
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-all;
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
  color: var(--cw-text);
  word-break: break-all;
}

@media (max-width: 1023px) {
  .summary-row {
    grid-template-columns: repeat(2, minmax(150px, 1fr));
  }
}

@media (max-width: 767px) {
  .summary-row {
    grid-template-columns: 1fr 1fr;
  }

  .section-heading {
    align-items: flex-start;
    flex-direction: column;
  }

  .section-heading span {
    text-align: left;
  }

  .dataset-header {
    align-items: stretch;
    flex-direction: column;
  }

  .dataset-actions > .el-button {
    flex: 1 1 calc(50% - 5px);
    margin-left: 0;
  }

  .eval-center :deep(.el-dialog .el-descriptions) {
    overflow-x: auto;
  }
}

@media (max-width: 480px) {
  .summary-row {
    grid-template-columns: 1fr;
  }

  .dataset-actions > .el-button {
    flex-basis: 100%;
  }
}
</style>
