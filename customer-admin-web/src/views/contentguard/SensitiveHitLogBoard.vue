<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import ContentGuardTrendChart from '@/components/ContentGuardTrendChart.vue'
import { fetchHitLogStats, pageHitLogs } from '@/api/contentGuard'
import { summarizeSensitiveHitWords } from '@/utils/sensitiveHitWords'
import type {
  ContentGuardCountVO,
  SensitiveWordHitLogPageQuery,
  SensitiveWordHitLogVO,
  SensitiveWordHitStatsVO,
} from '@/types/api'

const loading = ref(false)
const statsLoading = ref(false)
const list = ref<SensitiveWordHitLogVO[]>([])
const total = ref(0)
const stats = ref<SensitiveWordHitStatsVO | null>(null)
/** 时间区间由 el-date-picker 双向绑定，提交前拆成 startMs/endMs 两个查询参数。 */
const timeRange = ref<[Date, Date] | null>(null)

const query = reactive<SensitiveWordHitLogPageQuery>({
  pageNum: 1,
  pageSize: 10,
  keyword: '',
  direction: '',
  action: '',
  sessionId: '',
})

const directionLabels: Record<string, string> = { INBOUND: '用户输入', OUTBOUND: '模型输出' }

const actionMeta: Record<string, { label: string; type: 'danger' | 'warning' | 'info' }> = {
  BLOCK: { label: '拦截', type: 'danger' },
  MASK: { label: '打码', type: 'warning' },
  REVIEW: { label: '标记复核', type: 'info' },
}

const blockedCount = computed(() => countOf(stats.value?.byAction, 'BLOCK'))
const maskedCount = computed(() => countOf(stats.value?.byAction, 'MASK'))
const inboundCount = computed(() => countOf(stats.value?.byDirection, 'INBOUND'))

function countOf(rows: ContentGuardCountVO[] | undefined, label: string): number {
  return rows?.find((row) => row.label === label)?.total ?? 0
}

function formatTime(ms: number | null): string {
  return ms ? new Date(ms).toLocaleString('zh-CN') : '-'
}

/** 把界面上的筛选条件转成后端参数；时间区间为空时不传，后端按全量统计。 */
function buildQuery(): SensitiveWordHitLogPageQuery {
  const range = timeRange.value
  return {
    ...query,
    startMs: range ? range[0].getTime() : undefined,
    endMs: range ? range[1].getTime() : undefined,
  }
}

async function loadList() {
  loading.value = true
  try {
    const result = await pageHitLogs(buildQuery())
    list.value = result.list
    total.value = result.total
  } finally {
    loading.value = false
  }
}

async function loadStats() {
  statsLoading.value = true
  try {
    stats.value = await fetchHitLogStats(buildQuery())
  } finally {
    statsLoading.value = false
  }
}

/** 搜索时列表与统计一起刷新——两者共用同一套条件，分开刷会出现"图表和表格对不上"。 */
async function handleSearch() {
  query.pageNum = 1
  await Promise.all([loadList(), loadStats()])
}

onMounted(handleSearch)
</script>

<template>
  <div class="page">
    <el-alert type="info" :closable="false" show-icon class="notice">
      数据来自客服端库的命中流水，仅当客服端开启
      <code>customer-work.sensitive-word.hit-log.enabled=true</code> 且 <code>store-mode=jdbc</code> 时才有记录。
      片段列保留了命中处的原文，属敏感数据，请按内部合规要求使用。
    </el-alert>

    <el-card class="filter-card" shadow="never">
      <div class="filter-controls">
        <el-date-picker
          v-model="timeRange"
          class="time-filter"
          type="datetimerange"
          range-separator="至"
          start-placeholder="开始时间"
          end-placeholder="结束时间"
        />
        <el-select v-model="query.direction" class="select-filter" placeholder="全部方向" clearable>
          <el-option label="用户输入" value="INBOUND" />
          <el-option label="模型输出" value="OUTBOUND" />
        </el-select>
        <el-select v-model="query.action" class="select-filter" placeholder="全部处置" clearable>
          <el-option label="拦截" value="BLOCK" />
          <el-option label="打码" value="MASK" />
          <el-option label="标记复核" value="REVIEW" />
        </el-select>
        <el-input v-model="query.keyword" class="text-filter" placeholder="按命中词搜索" clearable @keyup.enter="handleSearch" />
        <el-input v-model="query.sessionId" class="text-filter" placeholder="会话 ID" clearable @keyup.enter="handleSearch" />
        <el-button type="primary" @click="handleSearch">查询</el-button>
      </div>
    </el-card>

    <div class="stat-row" aria-label="敏感词命中指标">
      <div v-loading="statsLoading" class="stat-card">
        <div class="stat-label">命中总数</div>
        <div class="stat-value">{{ stats?.total ?? 0 }}</div>
      </div>
      <div v-loading="statsLoading" class="stat-card danger-stat">
        <div class="stat-label">其中拦截</div>
        <div class="stat-value danger">{{ blockedCount }}</div>
      </div>
      <div v-loading="statsLoading" class="stat-card warning-stat">
        <div class="stat-label">其中打码</div>
        <div class="stat-value warning">{{ maskedCount }}</div>
      </div>
      <div v-loading="statsLoading" class="stat-card">
        <div class="stat-label">用户输入命中</div>
        <div class="stat-value">{{ inboundCount }}</div>
      </div>
    </div>

    <div class="chart-row">
      <el-card class="trend-card" shadow="never">
        <div class="section-heading">
          <div>
            <span class="section-eyebrow">HIT TREND</span>
            <strong>命中趋势</strong>
          </div>
          <span class="granularity">{{ stats?.trendGranularity === 'hour' ? '按小时' : '按天' }}</span>
        </div>
        <ContentGuardTrendChart
          :points="stats?.trend ?? []"
          :granularity="stats?.trendGranularity ?? 'day'"
          :loading="statsLoading"
        />
      </el-card>

      <el-card v-loading="statsLoading" class="top-card" shadow="never">
        <div class="section-heading">
          <div>
            <span class="section-eyebrow">FREQUENCY EVIDENCE</span>
            <strong>高频命中 Top 10</strong>
          </div>
          <span class="evidence-count">{{ stats?.topWords?.length ?? 0 }} 项</span>
        </div>
        <div v-if="!stats?.topWords?.length" class="empty-tip">暂无数据</div>
        <div v-for="(item, index) in stats?.topWords ?? []" :key="item.label" class="top-item">
          <span class="top-rank">{{ String(index + 1).padStart(2, '0') }}</span>
          <span class="top-label" :title="item.label">{{ item.label || '(空)' }}</span>
          <span class="top-count">{{ item.total }}</span>
        </div>
      </el-card>
    </div>

    <el-card class="detail-card" shadow="never">
      <div class="section-heading detail-heading">
        <div>
          <span class="section-eyebrow">RAW EVIDENCE</span>
          <strong>命中明细</strong>
        </div>
        <span class="detail-hint">命中词仅在展示层折叠，原始统计口径保持不变。</span>
      </div>
      <div class="table-scroll">
        <el-table v-loading="loading" :data="list">
          <el-table-column label="时间" width="180">
            <template #default="{ row }">{{ formatTime(row.createdAtMs) }}</template>
          </el-table-column>
          <el-table-column label="方向" width="110">
            <template #default="{ row }">{{ directionLabels[row.direction] ?? row.direction }}</template>
          </el-table-column>
          <el-table-column label="处置" width="110">
            <template #default="{ row }">
              <el-tag :type="actionMeta[row.action]?.type ?? 'info'">
                {{ actionMeta[row.action]?.label ?? row.action }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="命中词" min-width="180">
            <template #default="{ row }">
              <el-tag
                v-for="item in summarizeSensitiveHitWords(row.words)"
                :key="item.word"
                size="small"
                class="word-tag"
              >
                {{ item.word }}<span v-if="item.extraCount > 0" class="word-extra-count">+{{ item.extraCount }}</span>
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="agentName" label="智能体" width="140" show-overflow-tooltip />
          <el-table-column prop="sessionId" label="会话 ID" width="160" show-overflow-tooltip />
          <el-table-column prop="snippet" label="原文片段" min-width="220" show-overflow-tooltip />
        </el-table>
      </div>

      <el-pagination
        v-model:current-page="query.pageNum"
        v-model:page-size="query.pageSize"
        class="pagination"
        :total="total"
        layout="total, prev, pager, next"
        @current-change="loadList"
      />
    </el-card>
  </div>
</template>

<style scoped>
.page {
  min-width: 0;
}

.notice {
  margin-bottom: 12px;
  border: 1px solid color-mix(in srgb, var(--cw-cobalt) 28%, var(--cw-line));
}

.notice :deep(.el-alert__description) {
  overflow-wrap: anywhere;
  line-height: 1.6;
}

.notice code {
  color: var(--cw-cobalt);
  font-family: "SFMono-Regular", Consolas, "Liberation Mono", monospace;
  font-size: 12px;
}

.filter-card {
  margin-bottom: 12px;
  border-color: var(--cw-line);
  background: var(--cw-paper);
}

.filter-controls {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
}

.time-filter {
  width: 380px;
}

.select-filter {
  width: 140px;
}

.text-filter {
  width: 180px;
}

.stat-row {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 12px;
}

.stat-card {
  position: relative;
  min-width: 0;
  overflow: hidden;
  padding: 17px 18px 16px;
  border: 1px solid var(--cw-line);
  border-radius: var(--cw-radius-md);
  background: var(--cw-paper);
  box-shadow: var(--cw-shadow-xs);
}

.stat-card.danger-stat::before {
  background: var(--cw-danger);
}

.stat-card.warning-stat::before {
  background: var(--cw-amber);
}

.stat-label {
  color: var(--cw-text-muted);
  font-size: 13px;
}

.stat-value {
  margin-top: 8px;
  color: var(--cw-text);
  font-size: clamp(23px, 2.3vw, 29px);
  font-variant-numeric: tabular-nums;
  font-weight: 700;
  letter-spacing: -0.025em;
}

.stat-value.danger {
  color: var(--cw-danger);
}

.stat-value.warning {
  color: var(--cw-amber);
}

.chart-row {
  display: grid;
  grid-template-columns: 2fr 1fr;
  gap: 12px;
  margin-bottom: 12px;
}

.trend-card,
.top-card,
.detail-card {
  min-width: 0;
  border-color: var(--cw-line);
  background: var(--cw-paper);
}

.section-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 12px;
}

.section-heading strong {
  display: block;
  color: var(--cw-text);
  font-size: 15px;
}

.section-eyebrow {
  display: block;
  margin-bottom: 5px;
  color: var(--cw-cobalt);
  font-size: 10px;
  font-weight: 800;
  letter-spacing: 0.14em;
}

.granularity {
  padding: 4px 8px;
  color: var(--cw-text-muted);
  border: 1px solid var(--cw-line);
  border-radius: 999px;
  background: color-mix(in srgb, var(--cw-paper) 92%, var(--cw-cobalt));
  font-size: 12px;
}

.evidence-count,
.detail-hint {
  color: var(--cw-text-muted);
  font-size: 12px;
}

.top-item {
  display: grid;
  grid-template-columns: 28px minmax(0, 1fr) auto;
  align-items: center;
  gap: 12px;
  min-height: 34px;
  border-bottom: 1px solid var(--cw-line);
}

.top-item:last-child {
  border-bottom: 0;
}

.top-rank {
  color: var(--cw-cobalt);
  font-family: "SFMono-Regular", Consolas, "Liberation Mono", monospace;
  font-size: 11px;
  font-weight: 700;
}

.top-label {
  overflow: hidden;
  color: var(--cw-text);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.top-count {
  color: var(--cw-text-muted);
  flex-shrink: 0;
  font-variant-numeric: tabular-nums;
  font-weight: 650;
}

.empty-tip {
  color: var(--cw-text-muted);
  font-size: 13px;
  padding: 24px 0;
  text-align: center;
}

.table-scroll {
  width: 100%;
  overflow-x: auto;
  overscroll-behavior-inline: contain;
}

.table-scroll :deep(.el-table) {
  min-width: 1080px;
}

.word-tag {
  margin: 2px 4px 2px 0;
}

.word-extra-count {
  margin-left: 4px;
  font-weight: 600;
}

.pagination {
  width: 100%;
  overflow-x: auto;
}

@media (max-width: 1100px) {
  .chart-row {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 767px) {
  .filter-controls {
    align-items: stretch;
    flex-direction: column;
  }

  .filter-controls :deep(.el-date-editor),
  .filter-controls :deep(.el-input),
  .filter-controls :deep(.el-select),
  .filter-controls :deep(.el-button) {
    width: 100%;
  }

  .stat-row {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .detail-heading {
    align-items: flex-start;
    flex-direction: column;
    gap: 6px;
  }
}

@media (max-width: 480px) {
  .stat-row {
    grid-template-columns: 1fr;
  }

  .stat-card {
    padding: 15px 16px;
  }
}
</style>
