<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import {
  getBusinessOutcomeSummary,
  listBusinessOutcomeSessions,
  type BusinessOutcomeSession,
  type BusinessOutcomeSummary,
} from '@/api/businessOutcome'

const DAY_MS = 24 * 60 * 60 * 1000
const loading = ref(false)
const summary = ref<BusinessOutcomeSummary | null>(null)
const sessions = ref<BusinessOutcomeSession[]>([])
const total = ref(0)
const query = reactive({ days: 7, agentCode: '', page: 1, size: 20 })

async function loadData(resetPage = false) {
  if (resetPage) query.page = 1
  loading.value = true
  try {
    const toMs = Date.now()
    const fromMs = toMs - query.days * DAY_MS
    const agentCode = query.agentCode.trim() || undefined
    const params = { fromMs, toMs, agentCode }
    const [summaryData, sessionData] = await Promise.all([
      getBusinessOutcomeSummary(params),
      listBusinessOutcomeSessions({ ...params, page: query.page, size: query.size }),
    ])
    summary.value = summaryData
    sessions.value = sessionData.records
    total.value = sessionData.total
  } finally {
    loading.value = false
  }
}

function percent(value: number | null | undefined) {
  return typeof value === 'number' ? `${(value * 100).toFixed(2)}%` : '-'
}

function valueOrDash(value: number | null | undefined, digits = 0) {
  return typeof value === 'number' ? value.toLocaleString('zh-CN', {
    minimumFractionDigits: digits,
    maximumFractionDigits: digits,
  }) : '-'
}

function formatCost(value: number | null | undefined, currency?: string | null) {
  return typeof value === 'number' ? `${currency || ''} ${value.toFixed(8)}`.trim() : '-'
}

function formatTime(ms: number) {
  return ms ? new Date(ms).toLocaleString('zh-CN', { hour12: false }) : '-'
}

function availabilityType(status?: string) {
  if (status === 'COMPLETE') return 'success'
  if (status === 'PARTIAL') return 'warning'
  return 'info'
}

onMounted(() => loadData())
</script>

<template>
  <div class="outcome-board">
    <el-card class="filter-card" shadow="never">
      <div class="filter-head">
        <div>
          <h2>业务结果与成本</h2>
          <p>基于客服端真实调用、冻结价目、转人工和 CSAT 事实；代理指标与成本完整性均显式标注。</p>
        </div>
        <div class="filter-controls">
          <el-input v-model="query.agentCode" class="agent-filter" clearable placeholder="Agent 编码（可选）" />
          <el-select v-model="query.days" class="day-filter">
            <el-option label="最近 1 天" :value="1" />
            <el-option label="最近 7 天" :value="7" />
            <el-option label="最近 30 天" :value="30" />
            <el-option label="最近 90 天" :value="90" />
          </el-select>
          <el-button type="primary" :loading="loading" @click="loadData(true)">查询</el-button>
        </div>
      </div>
    </el-card>

    <el-alert
      class="definition-alert"
      type="warning"
      :closable="false"
      show-icon
      title="自动解决是代理指标，不是用户确认的解决率"
      :description="summary?.definitions.autoResolvedProxy || '技术调用全部成功且没有转人工事实，不能证明用户问题确已解决。'"
    />

    <div v-loading="loading" class="metric-grid" aria-label="业务结果指标">
      <div class="metric"><strong>{{ valueOrDash(summary?.totalSessions) }}</strong><span>观测会话</span></div>
      <div class="metric"><strong>{{ percent(summary?.successfulSessionRate) }}</strong><span>技术成功会话</span></div>
      <div class="metric primary"><strong>{{ percent(summary?.autoResolvedProxyRate) }}</strong><span>自动解决代理率</span></div>
      <div class="metric"><strong>{{ percent(summary?.handoffRate) }}</strong><span>转人工率</span></div>
      <div class="metric"><strong>{{ valueOrDash(summary?.averageCsat, 2) }}</strong><span>平均 CSAT（1-5）</span></div>
      <div class="metric"><strong>{{ percent(summary?.csatSatisfiedRate) }}</strong><span>满意率（≥4）</span></div>
      <div class="metric"><strong>{{ valueOrDash(summary?.totalTokens) }}</strong><span>已上报 Token</span></div>
      <div class="metric"><strong>{{ formatCost(summary?.totalCost, summary?.costCurrency) }}</strong><span>已结算模型成本</span></div>
      <div class="metric primary"><strong>{{ formatCost(summary?.costPerAutoResolvedSession, summary?.costCurrency) }}</strong><span>单次自动解决代理成本</span></div>
    </div>

    <el-card v-loading="loading" class="evidence-card" shadow="never">
      <div class="section-heading">
        <div>
          <span class="section-eyebrow">DATA AVAILABILITY</span>
          <strong>归因可用性证据</strong>
        </div>
        <span class="section-hint">先确认事实是否完整，再解释成本效率。</span>
      </div>
      <div class="availability-grid">
        <div class="availability-item">
          <div class="availability-title">
            <span>Token 事实</span>
            <el-tag :type="availabilityType(summary?.tokenAvailability.status)">
              {{ summary?.tokenAvailability.status || 'UNAVAILABLE' }}
            </el-tag>
          </div>
          <p>{{ summary?.tokenAvailability.reason || '暂无 Token 可用性说明' }}</p>
        </div>
        <div class="availability-item">
          <div class="availability-title">
            <span>成本事实</span>
            <el-tag :type="availabilityType(summary?.costAvailability.status)">
              {{ summary?.costAvailability.status || 'UNAVAILABLE' }}
            </el-tag>
          </div>
          <p>{{ summary?.costAvailability.reason || '暂无成本可用性说明' }}</p>
        </div>
        <div class="availability-item">
          <div class="availability-title">
            <span>单次解决成本</span>
            <el-tag :type="availabilityType(summary?.costPerAutoResolvedAvailability.status)">
              {{ summary?.costPerAutoResolvedAvailability.status || 'UNAVAILABLE' }}
            </el-tag>
          </div>
          <p>{{ summary?.costPerAutoResolvedAvailability.reason || '暂无单次解决成本说明' }}</p>
        </div>
      </div>
    </el-card>

    <el-card class="detail-card" shadow="never">
      <template #header>
        <div class="section-heading compact">
          <div>
            <span class="section-eyebrow">SESSION EVIDENCE</span>
            <strong>会话结果下钻</strong>
          </div>
          <span class="section-hint">每行都能回到组成汇总指标的 session 事实</span>
        </div>
      </template>
      <div class="table-scroll">
        <el-table v-loading="loading" :data="sessions" stripe>
          <el-table-column prop="sessionId" label="Session" min-width="210" show-overflow-tooltip />
          <el-table-column prop="agentCodes" label="Agent" min-width="150" show-overflow-tooltip />
          <el-table-column label="最后调用" width="180">
            <template #default="{ row }">{{ formatTime(row.lastCallAtMs) }}</template>
          </el-table-column>
          <el-table-column prop="callCount" label="调用数" width="80" />
          <el-table-column label="技术成功" width="100">
            <template #default="{ row }"><el-tag :type="row.successful ? 'success' : 'danger'">{{ row.successful ? '是' : '否' }}</el-tag></template>
          </el-table-column>
          <el-table-column label="转人工" width="90">
            <template #default="{ row }"><el-tag :type="row.handedOff ? 'warning' : 'info'">{{ row.handedOff ? '是' : '否' }}</el-tag></template>
          </el-table-column>
          <el-table-column label="自动解决代理" width="130">
            <template #default="{ row }"><el-tag :type="row.autoResolvedProxy ? 'success' : 'info'">{{ row.autoResolvedProxy ? '是' : '否' }}</el-tag></template>
          </el-table-column>
          <el-table-column label="Token" width="120">
            <template #default="{ row }">{{ valueOrDash(row.totalTokens) }}</template>
          </el-table-column>
          <el-table-column label="模型成本" width="160">
            <template #default="{ row }">
              <el-tooltip :content="row.costAvailability.reason">
                <span>{{ formatCost(row.modelCost, row.costCurrency) }}</span>
              </el-tooltip>
            </template>
          </el-table-column>
          <el-table-column label="CSAT" width="80">
            <template #default="{ row }">{{ row.csatScore ?? '-' }}</template>
          </el-table-column>
        </el-table>
      </div>
      <el-pagination
        v-model:current-page="query.page"
        v-model:page-size="query.size"
        class="pagination"
        layout="total, sizes, prev, pager, next"
        :page-sizes="[10, 20, 50, 100]"
        :total="total"
        @current-change="loadData()"
        @size-change="loadData(true)"
      />
    </el-card>
  </div>
</template>

<style scoped>
.outcome-board {
  display: flex;
  flex-direction: column;
  gap: 14px;
  min-width: 0;
}

.filter-card,
.evidence-card,
.detail-card {
  border-color: var(--cw-line);
  background: var(--cw-paper);
}

.filter-head,
.filter-controls,
.section-heading,
.availability-title {
  display: flex;
  align-items: center;
}

.filter-head {
  justify-content: space-between;
  gap: 24px;
}

.filter-head h2 {
  margin: 0 0 6px;
  color: var(--cw-text);
  font-size: 20px;
  line-height: 1.25;
}

.filter-head p {
  max-width: 760px;
  margin: 0;
  color: var(--cw-text-muted);
  line-height: 1.55;
}

.filter-controls {
  flex: 0 0 auto;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 10px;
}

.agent-filter {
  width: 190px;
}

.day-filter {
  width: 130px;
}

.definition-alert {
  border: 1px solid color-mix(in srgb, var(--cw-amber) 35%, var(--cw-line));
}

.metric-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.metric {
  min-width: 0;
  padding: 17px 18px 16px;
  border: 1px solid var(--cw-line);
  border-radius: var(--cw-radius-md);
  background: var(--cw-paper);
  box-shadow: var(--cw-shadow-xs);
}

.metric strong {
  display: block;
  overflow: hidden;
  color: var(--cw-text);
  font-size: clamp(21px, 2vw, 27px);
  line-height: 1.2;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.metric span {
  display: block;
  margin-top: 8px;
  color: var(--cw-text-muted);
  font-size: 12px;
}

.metric.primary strong {
  color: var(--cw-cobalt);
}

.section-heading {
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 14px;
}

.section-heading.compact {
  margin-bottom: 0;
}

.section-heading strong {
  display: block;
  color: var(--cw-text);
  font-size: 16px;
}

.section-eyebrow {
  display: block;
  margin-bottom: 5px;
  color: var(--cw-cobalt);
  font-size: 10px;
  font-weight: 800;
  letter-spacing: 0.14em;
}

.section-hint {
  color: var(--cw-text-muted);
  font-size: 12px;
  font-weight: 400;
  text-align: right;
}

.availability-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
}

.availability-item {
  min-width: 0;
  padding: 14px;
  border: 1px solid var(--cw-line);
  border-radius: var(--cw-radius-md);
  background: color-mix(in srgb, var(--cw-paper) 94%, var(--cw-cobalt));
}

.availability-title {
  justify-content: space-between;
  gap: 12px;
  color: var(--cw-text);
  font-weight: 650;
}

.availability-item p {
  margin: 10px 0 0;
  color: var(--cw-text-muted);
  font-size: 12px;
  line-height: 1.55;
}

.table-scroll {
  width: 100%;
  overflow-x: auto;
  overscroll-behavior-inline: contain;
}

.table-scroll :deep(.el-table) {
  min-width: 1240px;
}

.pagination {
  width: 100%;
  overflow-x: auto;
}

@media (max-width: 1100px) {
  .filter-head {
    align-items: flex-start;
    flex-direction: column;
    gap: 16px;
  }

  .filter-controls {
    justify-content: flex-start;
    width: 100%;
  }

  .availability-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 767px) {
  .outcome-board {
    gap: 12px;
  }

  .filter-controls {
    align-items: stretch;
    flex-direction: column;
  }

  .filter-controls :deep(.el-input),
  .filter-controls :deep(.el-select),
  .filter-controls :deep(.el-button) {
    width: 100%;
  }

  .metric-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .section-heading {
    align-items: flex-start;
    flex-direction: column;
    gap: 7px;
  }

  .section-hint {
    text-align: left;
  }
}

@media (max-width: 480px) {
  .metric-grid {
    grid-template-columns: 1fr;
  }

  .metric {
    padding: 15px 16px;
  }
}
</style>
