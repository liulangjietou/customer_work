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
    <el-card shadow="never">
      <template #header>
        <div class="header">
          <div>
            <h2>业务结果与成本</h2>
            <p>基于客服端真实调用、冻结价目、转人工和 CSAT 事实；代理指标与成本完整性均显式标注。</p>
          </div>
          <div class="toolbar">
            <el-input v-model="query.agentCode" clearable placeholder="Agent 编码（可选）" style="width: 190px" />
            <el-select v-model="query.days" style="width: 130px">
              <el-option label="最近 1 天" :value="1" />
              <el-option label="最近 7 天" :value="7" />
              <el-option label="最近 30 天" :value="30" />
              <el-option label="最近 90 天" :value="90" />
            </el-select>
            <el-button type="primary" :loading="loading" @click="loadData(true)">查询</el-button>
          </div>
        </div>
      </template>

      <el-alert
        type="warning"
        :closable="false"
        show-icon
        title="自动解决是代理指标，不是用户确认的解决率"
        :description="summary?.definitions.autoResolvedProxy || '技术调用全部成功且没有转人工事实，不能证明用户问题确已解决。'"
      />

      <div v-loading="loading" class="metric-grid">
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

      <div class="availability-row">
        <el-tag :type="availabilityType(summary?.tokenAvailability.status)">
          Token {{ summary?.tokenAvailability.status || 'UNAVAILABLE' }}
        </el-tag>
        <span>{{ summary?.tokenAvailability.reason }}</span>
        <el-tag type="info">Cost {{ summary?.costAvailability.status || 'UNAVAILABLE' }}</el-tag>
        <span>{{ summary?.costAvailability.reason }}</span>
        <el-tag :type="availabilityType(summary?.costPerAutoResolvedAvailability.status)">
          Unit Cost {{ summary?.costPerAutoResolvedAvailability.status || 'UNAVAILABLE' }}
        </el-tag>
        <span>{{ summary?.costPerAutoResolvedAvailability.reason }}</span>
      </div>
    </el-card>

    <el-card shadow="never">
      <template #header>
        <div>
          <strong>会话结果下钻</strong>
          <span class="hint">每行都能回到组成汇总指标的 session 事实</span>
        </div>
      </template>
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
.outcome-board { display: flex; flex-direction: column; gap: 12px; }
.header { display: flex; align-items: center; justify-content: space-between; gap: 20px; flex-wrap: wrap; }
.header h2 { margin: 0 0 6px; font-size: 20px; }
.header p { margin: 0; color: var(--el-text-color-secondary); }
.toolbar { display: flex; align-items: center; gap: 10px; }
.metric-grid { display: grid; grid-template-columns: repeat(4, minmax(140px, 1fr)); gap: 12px; margin-top: 18px; }
.metric { padding: 16px; border: 1px solid var(--el-border-color-lighter); border-radius: 8px; background: var(--el-fill-color-lighter); }
.metric strong { display: block; font-size: 25px; line-height: 1.2; }
.metric span { display: block; margin-top: 7px; color: var(--el-text-color-secondary); font-size: 12px; }
.metric.primary strong { color: var(--el-color-primary); }
.metric.unavailable strong { color: var(--el-text-color-placeholder); }
.availability-row { display: grid; grid-template-columns: auto minmax(160px, 1fr) auto minmax(260px, 2fr); align-items: center; gap: 10px; margin-top: 16px; color: var(--el-text-color-secondary); font-size: 12px; }
.hint { margin-left: 10px; color: var(--el-text-color-secondary); font-size: 12px; font-weight: 400; }
.pagination { justify-content: flex-end; margin-top: 16px; }
@media (max-width: 1000px) { .metric-grid { grid-template-columns: repeat(2, minmax(140px, 1fr)); } .availability-row { grid-template-columns: auto 1fr; } }
</style>
