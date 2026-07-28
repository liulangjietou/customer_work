<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import {
  deleteAgentCallStats,
  getAgentCallStatsDetail,
  getAgentCallStatsSummary,
  getAgentCallStatsTrend,
  pageAgentCallStats,
} from '@/api/agentCallStats'
import { pageAgents } from '@/api/agent'
import AgentCallTrendChart from '@/components/AgentCallTrendChart.vue'
import type {
  AgentCallSegmentKind,
  AgentCallSource,
  AgentCallStatsDetail,
  AgentCallStatsQuery,
  AgentCallStatsRow,
  AgentCallStatsSegment,
  AgentCallStatsSummary,
  AgentCallStatsTrendPoint,
  AgentCallTrendGranularity,
  AgentVO,
} from '@/types/api'

const router = useRouter()

const DEFAULT_PAGE_SIZE = 10
// 智能体下拉复用「智能体管理」的分页接口，无独立全量接口时拉大页兜底（同 ChannelBindingDrawer 用法）。
const AGENT_OPTION_PAGE_SIZE = 200

const SOURCE_OPTIONS: Array<{ label: string; value: AgentCallSource }> = [
  { label: '工作台（Admin）', value: 'ADMIN' },
  { label: '客服端（App）', value: 'APP' },
]
const SESSION_TYPE_OPTIONS: Array<{ label: string; value: 'CHAT' | 'VIBE_CODING' }> = [
  { label: '对话', value: 'CHAT' },
  { label: 'VibeCoding', value: 'VIBE_CODING' },
]

/** 分段耗时类型元信息：展示名 + 统一配色，明细表迷你条形图与详情抽屉标签共用。 */
const SEGMENT_KIND_META: Record<AgentCallSegmentKind, { label: string; color: string }> = {
  MODEL: { label: '大模型', color: '#f59e0b' },
  TOOL: { label: '工具', color: '#67c23a' },
  MCP: { label: 'MCP', color: '#409eff' },
  SKILL: { label: 'Skill', color: '#8b5cf6' },
}

interface FilterState {
  source: AgentCallSource
  username: string
  agentCode: string
  sessionType: 'CHAT' | 'VIBE_CODING' | ''
  requestId: string
  sessionId: string
}

const filters = reactive<FilterState>({
  source: 'ADMIN',
  username: '',
  agentCode: '',
  sessionType: '',
  requestId: '',
  sessionId: '',
})

function pad(n: number): string {
  return String(n).padStart(2, '0')
}

function formatDateTime(d: Date): string {
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

/** 默认展示最近 7 天，避免首屏空筛选把全量历史记录一次性拉回来。 */
function defaultTimeRange(): [string, string] {
  const end = new Date()
  const start = new Date(end.getTime() - 7 * 24 * 60 * 60 * 1000)
  return [formatDateTime(start), formatDateTime(end)]
}

const timeRange = ref<[string, string] | null>(defaultTimeRange())
const sourceIsApp = computed(() => filters.source === 'APP')

// 客服端（App）恒为普通对话，没有 VibeCoding 概念：切到 APP 时清空并禁用会话类型筛选，
// 与「打开会话」按钮仅 ADMIN 行可用是同一条业务约束（源头见需求 §2/§6）。
watch(
  () => filters.source,
  (source) => {
    if (source === 'APP') {
      filters.sessionType = ''
    }
  },
)

/** 分页/汇总/趋势三个接口共用的筛选参数：空值一律不传，交给后端默认语义。 */
function buildFilterParams(): AgentCallStatsQuery {
  const q: AgentCallStatsQuery = { source: filters.source }
  if (filters.username) q.username = filters.username
  if (filters.agentCode) q.agentCode = filters.agentCode
  const sessionType = sourceIsApp.value ? 'CHAT' : filters.sessionType
  if (sessionType) q.sessionType = sessionType
  if (filters.requestId) q.requestId = filters.requestId
  if (filters.sessionId) q.sessionId = filters.sessionId
  if (timeRange.value?.[0]) q.startTime = timeRange.value[0]
  if (timeRange.value?.[1]) q.endTime = timeRange.value[1]
  return q
}

// ---------- 智能体下拉选项 ----------
const agentOptions = ref<AgentVO[]>([])
async function loadAgentOptions() {
  const result = await pageAgents({ pageNum: 1, pageSize: AGENT_OPTION_PAGE_SIZE })
  agentOptions.value = result.list
}

// ---------- 明细分页列表 ----------
const loading = ref(false)
const list = ref<AgentCallStatsRow[]>([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(DEFAULT_PAGE_SIZE)

async function loadList() {
  loading.value = true
  try {
    const res = await pageAgentCallStats({ ...buildFilterParams(), pageNum: pageNum.value, pageSize: pageSize.value })
    list.value = res.rows
    total.value = res.total
  } finally {
    loading.value = false
  }
}

// ---------- 汇总卡片 ----------
const summaryLoading = ref(false)
const summary = ref<AgentCallStatsSummary | null>(null)
async function loadSummary() {
  summaryLoading.value = true
  try {
    summary.value = await getAgentCallStatsSummary(buildFilterParams())
  } finally {
    summaryLoading.value = false
  }
}

const SUMMARY_CARDS = [
  { key: 'totalCalls', label: '总调用数', unit: '次' },
  { key: 'totalTokens', label: '总 Token 消耗', unit: 'token' },
  { key: 'avgTotalTokens', label: '平均 Token/次', unit: 'token' },
  // 缓存命中率单列一张卡：它是判断 prompt 缓存有没有真生效的唯一直接信号，
  // 长期为 0 说明系统提示词/历史每次都在变，那笔本可省下的钱一直在白花
  { key: 'cacheHitRate', label: '缓存命中率', unit: 'percent' },
  { key: 'avgDurationMs', label: '平均耗时', unit: 'ms' },
  { key: 'maxDurationMs', label: '最大耗时', unit: 'ms' },
  { key: 'avgModelMs', label: '大模型平均耗时', unit: 'ms' },
  { key: 'avgToolMs', label: '工具平均耗时', unit: 'ms' },
  { key: 'avgMcpMs', label: 'MCP平均耗时', unit: 'ms' },
  { key: 'avgSkillMs', label: 'Skill平均耗时', unit: 'ms' },
] as const

function summaryValue(key: (typeof SUMMARY_CARDS)[number]['key']): number {
  return summary.value?.[key] ?? 0
}

/** 卡片取值格式化：ms 走人性化耗时，token 走千分位（均值四舍五入取整），其余原样。 */
function summaryDisplay(card: (typeof SUMMARY_CARDS)[number]): string {
  const value = summaryValue(card.key)
  if (card.unit === 'ms') return formatDuration(value)
  if (card.unit === 'token') return formatTokens(Math.round(value))
  if (card.unit === 'percent') return `${(value * 100).toFixed(1)}%`
  return String(value)
}

// ---------- 趋势图 ----------
const trendLoading = ref(false)
const trendPoints = ref<AgentCallStatsTrendPoint[]>([])
const trendGranularityMode = ref<'auto' | AgentCallTrendGranularity>('auto')

/** auto 模式按当前时间跨度自动选粒度：跨度 <= 2 天用小时，否则按天，避免小跨度下按天只出一两个点。 */
const effectiveGranularity = computed<AgentCallTrendGranularity>(() => {
  if (trendGranularityMode.value !== 'auto') return trendGranularityMode.value
  const start = timeRange.value?.[0]
  const end = timeRange.value?.[1]
  if (!start || !end) return 'day'
  const spanMs = new Date(end).getTime() - new Date(start).getTime()
  const TWO_DAYS_MS = 2 * 24 * 60 * 60 * 1000
  return spanMs > 0 && spanMs <= TWO_DAYS_MS ? 'hour' : 'day'
})

async function loadTrend() {
  trendLoading.value = true
  try {
    trendPoints.value = await getAgentCallStatsTrend(buildFilterParams(), effectiveGranularity.value)
  } finally {
    trendLoading.value = false
  }
}

// 用户切换粒度开关时立即重绘趋势图，不需要重新点搜索
watch(trendGranularityMode, () => {
  loadTrend()
})

async function refreshAll() {
  await Promise.all([loadList(), loadSummary(), loadTrend()])
}

function handleSearch() {
  pageNum.value = 1
  refreshAll()
}

function handleReset() {
  filters.source = 'ADMIN'
  filters.username = ''
  filters.agentCode = ''
  filters.sessionType = ''
  filters.requestId = ''
  filters.sessionId = ''
  timeRange.value = defaultTimeRange()
  pageNum.value = 1
  refreshAll()
}

// ---------- 耗时格式化 ----------
/** 人性化耗时：<1s 展示毫秒；<60s 展示整秒；否则 分+秒（如 1分13秒）。 */
function formatDuration(ms: number | null | undefined): string {
  if (ms == null) return '—'
  if (ms < 1000) return `${ms}ms`
  const totalSeconds = Math.round(ms / 1000)
  if (totalSeconds < 60) return `${totalSeconds}秒`
  const minutes = Math.floor(totalSeconds / 60)
  const seconds = totalSeconds % 60
  return seconds === 0 ? `${minutes}分` : `${minutes}分${seconds}秒`
}

/** token 数千分位展示；null/undefined 显示 —（区分"未采集到用量"与 0）。 */
function formatTokens(tokens: number | null | undefined): string {
  if (tokens == null) return '—'
  return tokens.toLocaleString('en-US')
}

function sessionTypeLabel(type: string): string {
  return type === 'VIBE_CODING' ? 'VibeCoding' : '对话'
}

/** 明细行的分段耗时迷你条形展示：按各段耗时占比拉伸宽度，全零时不渲染任何条。 */
function segmentBreakdown(row: AgentCallStatsRow): Array<{ kind: AgentCallSegmentKind; ms: number }> {
  return (
    [
      { kind: 'MODEL', ms: row.modelMs },
      { kind: 'TOOL', ms: row.toolMs },
      { kind: 'MCP', ms: row.mcpMs },
      { kind: 'SKILL', ms: row.skillMs },
    ] as const
  ).filter((s) => s.ms > 0)
}

// ---------- 行操作：耗时详情 ----------
const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref<AgentCallStatsDetail | null>(null)

async function openDetail(row: AgentCallStatsRow) {
  detailVisible.value = true
  detailLoading.value = true
  detail.value = null
  try {
    detail.value = await getAgentCallStatsDetail(row.id, filters.source)
  } catch (error) {
    ElMessage.error('详情加载失败：' + (error instanceof Error ? error.message : String(error)))
    detailVisible.value = false
  } finally {
    detailLoading.value = false
  }
}

/** 分段时间轴的 CSS 条形定位：相对本次调用开始时间的偏移与占比，均以总耗时归一化。 */
function segmentOffsetPercent(seg: AgentCallStatsSegment): number {
  if (!detail.value || !detail.value.durationMs) return 0
  const offset = new Date(seg.startTime).getTime() - new Date(detail.value.startTime).getTime()
  return Math.min(Math.max((offset / detail.value.durationMs) * 100, 0), 100)
}

function segmentWidthPercent(seg: AgentCallStatsSegment): number {
  if (!detail.value || !detail.value.durationMs) return 0
  return Math.min(Math.max((seg.durationMs / detail.value.durationMs) * 100, 0.6), 100)
}

// ---------- 行操作：打开会话 ----------
function openInWorkspace(row: AgentCallStatsRow) {
  router.push({
    name: 'Workspace',
    params: { agentCode: row.agentCode },
    query: { sessionId: row.sessionId, mode: row.sessionType === 'VIBE_CODING' ? 'vibecoding' : 'chat' },
  })
}

// ---------- 行操作：删除 ----------
async function handleDelete(row: AgentCallStatsRow) {
  await ElMessageBox.confirm(`确认删除这条调用记录？（请求ID：${row.requestId}）`, '提示', { type: 'warning' })
  await deleteAgentCallStats(row.id, filters.source)
  ElMessage.success('删除成功')
  await refreshAll()
}

onMounted(() => {
  loadAgentOptions()
  refreshAll()
})
</script>

<template>
  <div class="page">
    <el-card class="filter-card">
      <div class="toolbar">
        <el-select v-model="filters.source" placeholder="来源" style="width: 160px">
          <el-option v-for="opt in SOURCE_OPTIONS" :key="opt.value" :label="opt.label" :value="opt.value" />
        </el-select>
        <el-input v-model="filters.username" placeholder="用户名" style="width: 140px" clearable @keyup.enter="handleSearch" />
        <el-select v-model="filters.agentCode" placeholder="智能体" style="width: 160px" clearable filterable>
          <el-option v-for="a in agentOptions" :key="a.agentCode" :label="a.agentName" :value="a.agentCode" />
        </el-select>
        <el-select
          v-model="filters.sessionType"
          placeholder="会话类型"
          style="width: 140px"
          clearable
          :disabled="sourceIsApp"
        >
          <el-option v-for="opt in SESSION_TYPE_OPTIONS" :key="opt.value" :label="opt.label" :value="opt.value" />
        </el-select>
        <el-date-picker
          v-model="timeRange"
          type="datetimerange"
          range-separator="至"
          start-placeholder="开始时间"
          end-placeholder="结束时间"
          value-format="YYYY-MM-DD HH:mm:ss"
          style="width: 360px"
        />
        <el-input v-model="filters.requestId" placeholder="请求ID" style="width: 160px" clearable @keyup.enter="handleSearch" />
        <el-input v-model="filters.sessionId" placeholder="会话ID" style="width: 160px" clearable @keyup.enter="handleSearch" />
        <el-button type="primary" @click="handleSearch">搜索</el-button>
        <el-button @click="handleReset">重置</el-button>
      </div>
      <div v-if="sourceIsApp" class="filter-tip">客服端来源恒为「对话」，无 VibeCoding 会话</div>
    </el-card>

    <div class="summary-row">
      <div v-for="card in SUMMARY_CARDS" :key="card.key" class="stat-card" v-loading="summaryLoading">
        <div class="stat-label">{{ card.label }}</div>
        <div class="stat-value">{{ summaryDisplay(card) }}</div>
      </div>
    </div>

    <el-card class="trend-card">
      <div class="trend-header">
        <span class="trend-title">调用趋势</span>
        <el-radio-group v-model="trendGranularityMode" size="small">
          <el-radio-button value="auto">自动</el-radio-button>
          <el-radio-button value="day">按天</el-radio-button>
          <el-radio-button value="hour">按小时</el-radio-button>
        </el-radio-group>
      </div>
      <AgentCallTrendChart :points="trendPoints" :granularity="effectiveGranularity" :loading="trendLoading" />
    </el-card>

    <el-card>
      <el-table v-loading="loading" :data="list" style="width: 100%">
        <el-table-column prop="username" label="用户名" width="110">
          <template #default="{ row }: { row: AgentCallStatsRow }">{{ row.username || '—' }}</template>
        </el-table-column>
        <el-table-column prop="agentName" label="智能体" width="130">
          <template #default="{ row }: { row: AgentCallStatsRow }">{{ row.agentName || row.agentCode }}</template>
        </el-table-column>
        <el-table-column label="会话类型" width="100">
          <template #default="{ row }: { row: AgentCallStatsRow }">
            <el-tag :type="row.sessionType === 'VIBE_CODING' ? 'warning' : 'primary'" size="small">
              {{ sessionTypeLabel(row.sessionType) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="question" label="问题" min-width="180" show-overflow-tooltip />
        <el-table-column prop="answerPreview" label="回答预览" min-width="180" show-overflow-tooltip />
        <el-table-column prop="requestId" label="请求ID" width="160" show-overflow-tooltip />
        <el-table-column label="开始时间" width="150">
          <template #default="{ row }: { row: AgentCallStatsRow }">{{ row.startTime }}</template>
        </el-table-column>
        <el-table-column label="结束时间" width="150">
          <template #default="{ row }: { row: AgentCallStatsRow }">{{ row.endTime }}</template>
        </el-table-column>
        <el-table-column label="总耗时" width="100" align="right">
          <template #default="{ row }: { row: AgentCallStatsRow }">{{ formatDuration(row.durationMs) }}</template>
        </el-table-column>
        <el-table-column label="Token" width="110" align="right">
          <template #default="{ row }: { row: AgentCallStatsRow }">
            <el-tooltip v-if="row.totalTokens != null" placement="top">
              <template #content>
                <div>输入：{{ formatTokens(row.inputTokens) }}</div>
                <div>输出：{{ formatTokens(row.outputTokens) }}</div>
                <div>其中命中缓存：{{ formatTokens(row.cachedTokens) }}</div>
              </template>
              <span>{{ formatTokens(row.totalTokens) }}</span>
            </el-tooltip>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="分段耗时" width="140">
          <template #default="{ row }: { row: AgentCallStatsRow }">
            <el-tooltip placement="top">
              <template #content>
                <div v-for="s in segmentBreakdown(row)" :key="s.kind">
                  {{ SEGMENT_KIND_META[s.kind].label }}：{{ formatDuration(s.ms) }}
                </div>
                <div v-if="segmentBreakdown(row).length === 0">暂无分段耗时</div>
              </template>
              <div class="segment-bar">
                <span
                  v-for="s in segmentBreakdown(row)"
                  :key="s.kind"
                  class="segment-piece"
                  :style="{ flexGrow: s.ms, backgroundColor: SEGMENT_KIND_META[s.kind].color }"
                />
                <span v-if="segmentBreakdown(row).length === 0" class="muted">—</span>
              </div>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }: { row: AgentCallStatsRow }">
            <el-button link type="primary" @click="openDetail(row)">耗时详情</el-button>
            <el-button v-if="filters.source === 'ADMIN'" link type="primary" @click="openInWorkspace(row)">打开会话</el-button>
            <el-button link type="danger" @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-model:current-page="pageNum"
        v-model:page-size="pageSize"
        :total="total"
        :page-sizes="[10, 20, 50, 100]"
        layout="total, sizes, prev, pager, next"
        style="margin-top: 16px; justify-content: flex-end"
        @current-change="loadList"
        @size-change="loadList"
      />
    </el-card>

    <el-drawer v-model="detailVisible" title="调用耗时详情" size="640px" destroy-on-close>
      <div v-loading="detailLoading">
        <template v-if="detail">
          <el-descriptions :column="2" border size="small">
            <el-descriptions-item label="请求ID" :span="2">{{ detail.requestId }}</el-descriptions-item>
            <el-descriptions-item label="用户名">{{ detail.username || '—' }}</el-descriptions-item>
            <el-descriptions-item label="智能体">{{ detail.agentName || detail.agentCode }}</el-descriptions-item>
            <el-descriptions-item label="会话类型">{{ sessionTypeLabel(detail.sessionType) }}</el-descriptions-item>
            <el-descriptions-item label="会话ID">{{ detail.sessionId }}</el-descriptions-item>
            <el-descriptions-item label="开始时间">{{ detail.startTime }}</el-descriptions-item>
            <el-descriptions-item label="结束时间">{{ detail.endTime }}</el-descriptions-item>
            <el-descriptions-item label="总耗时" :span="2">{{ formatDuration(detail.durationMs) }}</el-descriptions-item>
            <el-descriptions-item label="输入 Token">{{ formatTokens(detail.inputTokens) }}</el-descriptions-item>
            <el-descriptions-item label="输出 Token">{{ formatTokens(detail.outputTokens) }}</el-descriptions-item>
            <el-descriptions-item label="总 Token">{{ formatTokens(detail.totalTokens) }}</el-descriptions-item>
            <el-descriptions-item label="命中缓存 Token">
              {{ formatTokens(detail.cachedTokens) }}
              <span v-if="detail.cachedTokens != null && detail.inputTokens" class="muted">
                （占输入 {{ ((detail.cachedTokens / detail.inputTokens) * 100).toFixed(1) }}%）
              </span>
            </el-descriptions-item>
            <el-descriptions-item label="模型自报耗时" :span="2">
              {{ detail.modelReportedMs == null ? '—' : formatDuration(detail.modelReportedMs) }}
              <span v-if="detail.modelReportedMs != null" class="muted">
                （与实测大模型耗时之差 {{ formatDuration(Math.max(0, detail.modelMs - detail.modelReportedMs)) }} = 网络/排队开销）
              </span>
            </el-descriptions-item>
          </el-descriptions>

          <div class="detail-block">
            <div class="detail-block-title">问题</div>
            <div class="detail-text">{{ detail.question }}</div>
          </div>
          <div class="detail-block">
            <div class="detail-block-title">回答</div>
            <div class="detail-text">{{ detail.answer }}</div>
          </div>

          <div class="detail-block">
            <div class="detail-block-title">分段耗时时间轴</div>
            <div class="segment-timeline">
              <div v-for="seg in detail.segments" :key="seg.seq" class="timeline-row">
                <span class="timeline-name" :title="seg.name">{{ seg.name }}</span>
                <span class="timeline-track">
                  <span
                    class="timeline-bar"
                    :style="{
                      left: segmentOffsetPercent(seg) + '%',
                      width: segmentWidthPercent(seg) + '%',
                      backgroundColor: SEGMENT_KIND_META[seg.kind].color,
                    }"
                  />
                </span>
                <span class="timeline-ms">{{ formatDuration(seg.durationMs) }}</span>
              </div>
              <div v-if="detail.segments.length === 0" class="muted">暂无分段耗时记录</div>
            </div>
          </div>

          <div class="detail-block">
            <div class="detail-block-title">分段耗时明细</div>
            <el-table :data="detail.segments" size="small" style="width: 100%">
              <el-table-column prop="seq" label="序号" width="60" />
              <el-table-column label="类型" width="90">
                <template #default="{ row }: { row: AgentCallStatsSegment }">
                  <el-tag :color="SEGMENT_KIND_META[row.kind].color" style="color: #fff; border: none">
                    {{ SEGMENT_KIND_META[row.kind].label }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="name" label="名称" min-width="140" show-overflow-tooltip />
              <el-table-column prop="startTime" label="开始时间" width="150" />
              <el-table-column label="耗时" width="90" align="right">
                <template #default="{ row }: { row: AgentCallStatsSegment }">{{ formatDuration(row.durationMs) }}</template>
              </el-table-column>
              <el-table-column label="输入Token" width="90" align="right">
                <template #default="{ row }: { row: AgentCallStatsSegment }">{{ formatTokens(row.inputTokens) }}</template>
              </el-table-column>
              <el-table-column label="输出Token" width="90" align="right">
                <template #default="{ row }: { row: AgentCallStatsSegment }">{{ formatTokens(row.outputTokens) }}</template>
              </el-table-column>
              <el-table-column label="成败" width="70">
                <template #default="{ row }: { row: AgentCallStatsSegment }">
                  <el-tag :type="row.success ? 'success' : 'danger'" size="small">{{ row.success ? '成功' : '失败' }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="errorMsg" label="错误信息" min-width="140" show-overflow-tooltip>
                <template #default="{ row }: { row: AgentCallStatsSegment }">{{ row.errorMsg || '—' }}</template>
              </el-table-column>
            </el-table>
          </div>
        </template>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.filter-tip {
  margin-top: 8px;
  font-size: 12px;
  color: var(--el-text-color-placeholder);
}

.summary-row {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
  gap: 12px;
}

.stat-card {
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  padding: 14px 16px;
}

.stat-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-bottom: 6px;
}

.stat-value {
  font-size: 22px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.trend-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.trend-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.segment-bar {
  display: flex;
  align-items: center;
  height: 10px;
  width: 100%;
  border-radius: 3px;
  overflow: hidden;
  background: var(--el-fill-color-light);
}

.segment-piece {
  height: 100%;
  min-width: 2px;
}

.muted {
  color: var(--el-text-color-placeholder);
  font-size: 12px;
}

.detail-block {
  margin-top: 16px;
}

.detail-block-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--el-text-color-primary);
  margin-bottom: 8px;
}

.detail-text {
  white-space: pre-wrap;
  word-break: break-word;
  background: var(--el-fill-color-lighter);
  border-radius: 6px;
  padding: 10px 12px;
  font-size: 13px;
  color: var(--el-text-color-regular);
  max-height: 240px;
  overflow-y: auto;
}

.segment-timeline {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.timeline-row {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
}

.timeline-name {
  width: 96px;
  flex-shrink: 0;
  color: var(--el-text-color-regular);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.timeline-track {
  position: relative;
  flex: 1;
  height: 10px;
  background: var(--el-fill-color-light);
  border-radius: 3px;
}

.timeline-bar {
  position: absolute;
  top: 0;
  height: 100%;
  border-radius: 3px;
}

.timeline-ms {
  width: 64px;
  flex-shrink: 0;
  text-align: right;
  color: var(--el-text-color-secondary);
}
</style>
