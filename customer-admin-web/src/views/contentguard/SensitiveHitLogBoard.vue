<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import ContentGuardTrendChart from '@/components/ContentGuardTrendChart.vue'
import { fetchHitLogStats, pageHitLogs } from '@/api/contentGuard'
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

    <el-card class="filter-card">
      <div class="toolbar">
        <el-date-picker
          v-model="timeRange"
          type="datetimerange"
          range-separator="至"
          start-placeholder="开始时间"
          end-placeholder="结束时间"
          style="width: 380px"
        />
        <el-select v-model="query.direction" placeholder="全部方向" style="width: 140px" clearable>
          <el-option label="用户输入" value="INBOUND" />
          <el-option label="模型输出" value="OUTBOUND" />
        </el-select>
        <el-select v-model="query.action" placeholder="全部处置" style="width: 140px" clearable>
          <el-option label="拦截" value="BLOCK" />
          <el-option label="打码" value="MASK" />
          <el-option label="标记复核" value="REVIEW" />
        </el-select>
        <el-input v-model="query.keyword" placeholder="按命中词搜索" style="width: 180px" clearable @keyup.enter="handleSearch" />
        <el-input v-model="query.sessionId" placeholder="会话 ID" style="width: 180px" clearable @keyup.enter="handleSearch" />
        <el-button type="primary" @click="handleSearch">查询</el-button>
      </div>
    </el-card>

    <div class="stat-row">
      <el-card v-loading="statsLoading" class="stat-card">
        <div class="stat-label">命中总数</div>
        <div class="stat-value">{{ stats?.total ?? 0 }}</div>
      </el-card>
      <el-card v-loading="statsLoading" class="stat-card">
        <div class="stat-label">其中拦截</div>
        <div class="stat-value danger">{{ blockedCount }}</div>
      </el-card>
      <el-card v-loading="statsLoading" class="stat-card">
        <div class="stat-label">其中打码</div>
        <div class="stat-value warning">{{ maskedCount }}</div>
      </el-card>
      <el-card v-loading="statsLoading" class="stat-card">
        <div class="stat-label">用户输入命中</div>
        <div class="stat-value">{{ inboundCount }}</div>
      </el-card>
    </div>

    <div class="chart-row">
      <el-card class="trend-card">
        <div class="card-title">
          命中趋势
          <span class="granularity">（{{ stats?.trendGranularity === 'hour' ? '按小时' : '按天' }}）</span>
        </div>
        <ContentGuardTrendChart
          :points="stats?.trend ?? []"
          :granularity="stats?.trendGranularity ?? 'day'"
          :loading="statsLoading"
        />
      </el-card>

      <el-card v-loading="statsLoading" class="top-card">
        <div class="card-title">高频命中 Top 10</div>
        <div v-if="!stats?.topWords?.length" class="empty-tip">暂无数据</div>
        <div v-for="item in stats?.topWords ?? []" :key="item.label" class="top-item">
          <span class="top-label" :title="item.label">{{ item.label || '(空)' }}</span>
          <span class="top-count">{{ item.total }}</span>
        </div>
      </el-card>
    </div>

    <el-card>
      <div class="card-title">命中明细</div>
      <el-table v-loading="loading" :data="list" style="width: 100%">
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
            <el-tag v-for="word in row.words" :key="word" size="small" class="word-tag">{{ word }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="agentName" label="智能体" width="140" show-overflow-tooltip />
        <el-table-column prop="sessionId" label="会话 ID" width="160" show-overflow-tooltip />
        <el-table-column prop="snippet" label="原文片段" min-width="220" show-overflow-tooltip />
      </el-table>

      <el-pagination
        v-model:current-page="query.pageNum"
        v-model:page-size="query.pageSize"
        :total="total"
        layout="total, prev, pager, next"
        style="margin-top: 16px; justify-content: flex-end"
        @current-change="loadList"
      />
    </el-card>
  </div>
</template>

<style scoped>
.notice {
  margin-bottom: 12px;
}

.filter-card {
  margin-bottom: 12px;
}

.toolbar {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
}

.stat-row {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 12px;
  margin-bottom: 12px;
}

.stat-label {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.stat-value {
  margin-top: 8px;
  font-size: 26px;
  font-weight: 600;
}

.stat-value.danger {
  color: var(--el-color-danger);
}

.stat-value.warning {
  color: var(--el-color-warning);
}

.chart-row {
  display: grid;
  grid-template-columns: 2fr 1fr;
  gap: 12px;
  margin-bottom: 12px;
}

@media (max-width: 1100px) {
  .chart-row {
    grid-template-columns: 1fr;
  }
}

.card-title {
  font-size: 15px;
  font-weight: 600;
  margin-bottom: 12px;
}

.granularity {
  font-size: 12px;
  font-weight: 400;
  color: var(--el-text-color-secondary);
}

.top-item {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  padding: 6px 0;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.top-label {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.top-count {
  color: var(--el-text-color-secondary);
  flex-shrink: 0;
}

.empty-tip {
  color: var(--el-text-color-placeholder);
  font-size: 13px;
  padding: 24px 0;
  text-align: center;
}

.word-tag {
  margin-right: 4px;
}
</style>
