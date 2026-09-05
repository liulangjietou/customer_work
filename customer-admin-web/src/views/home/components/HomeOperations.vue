<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useMenuStore } from '@/store/menu'
import { buildNavigationCommands, buildNavigationSections } from '@/layouts/navigationModel'
import { getAgentCallStatsSummary, getAgentCallStatsTrend } from '@/api/agentCallStats'
import { getSloAlertSummary, type SloAlertSummary } from '@/api/slo'
import type { AgentCallStatsSummary, AgentCallStatsTrendPoint } from '@/types/api'
import AgentCallTrendChart from '@/components/AgentCallTrendChart.vue'

defineProps<{ agentCount: number }>()
const emit = defineEmits<{ navigate: [path: string] }>()
const menu = useMenuStore()
const paths = computed(
  () =>
    new Set(buildNavigationCommands(buildNavigationSections(menu.tree)).map((entry) => entry.path)),
)
const canViewStats = computed(() => paths.value.has('/system/agent-call-stats'))
const canViewSlo = computed(() => paths.value.has('/system/slo'))
const summary = ref<AgentCallStatsSummary | null>(null)
const trend = ref<AgentCallStatsTrendPoint[]>([])
const alerts = ref<SloAlertSummary | null>(null)
const loading = ref(false)
const statsFailed = ref(false)
const alertsFailed = ref(false)
let requestVersion = 0

function localDate(date: Date) {
  const pad = (value: number) => String(value).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

/** 首页只查询已授权的统计入口，权限变化或页面卸载后丢弃旧响应。 */
async function load() {
  const version = ++requestVersion
  summary.value = null
  trend.value = []
  alerts.value = null
  statsFailed.value = false
  alertsFailed.value = false
  loading.value = true
  const today = new Date()
  const firstDay = new Date(today)
  firstDay.setDate(today.getDate() - 6)
  const query = {
    startTime: `${localDate(firstDay)} 00:00:00`,
    endTime: `${localDate(today)} 23:59:59`,
  }
  const [statsResult, alertsResult] = await Promise.allSettled([
    canViewStats.value
      ? Promise.all([getAgentCallStatsSummary(query), getAgentCallStatsTrend(query, 'day')])
      : Promise.resolve(null),
    canViewSlo.value ? getSloAlertSummary() : Promise.resolve(null),
  ])
  if (version !== requestVersion) return
  if (statsResult.status === 'fulfilled' && statsResult.value)
    [summary.value, trend.value] = statsResult.value
  statsFailed.value = statsResult.status === 'rejected'
  if (alertsResult.status === 'fulfilled') alerts.value = alertsResult.value
  alertsFailed.value = alertsResult.status === 'rejected'
  loading.value = false
}
watch([canViewStats, canViewSlo], load, { immediate: true })
onBeforeUnmount(() => {
  requestVersion += 1
})
const number = (value: number | undefined) =>
  value === undefined ? '—' : new Intl.NumberFormat('zh-CN').format(value)
</script>

<template>
  <section class="home-operations" aria-label="运行概况" :aria-busy="loading">
    <div class="metric-grid">
      <article class="metric-card">
        <span>可用智能体</span><strong>{{ agentCount }}</strong
        ><small>当前账号可进入</small>
      </article>
      <article class="metric-card">
        <span>近 7 日调用</span><strong>{{ number(summary?.totalCalls) }}</strong
        ><small>{{ canViewStats ? '当前数据视角 · 全部渠道' : '未开通调用统计权限' }}</small>
      </article>
      <article class="metric-card">
        <span>平均响应耗时</span
        ><strong
          >{{ summary?.totalCalls ? (summary.avgDurationMs / 1000).toFixed(1) : '—'
          }}<em v-if="summary?.totalCalls">s</em></strong
        ><small>近 7 日完整调用平均值</small>
      </article>
      <article class="metric-card">
        <span>Token 消耗</span><strong>{{ number(summary?.totalTokens) }}</strong
        ><small>近 7 日累计用量</small>
      </article>
    </div>
    <div class="operations-grid">
      <article class="operations-card">
        <header>
          <h2>运行趋势</h2>
          <el-button
            v-if="canViewStats"
            link
            type="primary"
            @click="emit('navigate', '/system/agent-call-stats')"
            >查看统计 <el-icon><ArrowRight /></el-icon
          ></el-button>
        </header>
        <div v-if="statsFailed" class="operation-empty" role="status">
          <p>运行统计暂时无法加载</p>
          <el-button @click="load">重新加载</el-button>
        </div>
        <AgentCallTrendChart
          v-else-if="canViewStats && (loading || trend.length)"
          :points="trend"
          granularity="day"
          :loading="loading"
          compact
        />
        <div v-else-if="canViewStats" class="operation-empty">
          <el-icon><DataLine /></el-icon>
          <p>近 7 日暂无调用记录</p>
          <small>智能体完成任务后，调用数据会显示在这里。</small>
        </div>
        <div v-else class="operation-empty">
          <el-icon><DataLine /></el-icon>
          <p>尚未开通调用统计权限</p>
          <small>获得权限后，可查看智能体调用量与耗时趋势。</small>
        </div>
      </article>
      <article class="operations-card attention-card">
        <header>
          <h2>需要关注</h2>
          <el-icon><Bell /></el-icon>
        </header>
        <div v-if="alertsFailed" class="operation-empty" role="status">
          <p>告警状态暂时无法加载</p>
          <el-button @click="load">重新加载</el-button>
        </div>
        <template v-else-if="alerts">
          <button class="attention-row" @click="emit('navigate', '/system/slo')">
            <span>待确认告警<small>服务质量告警</small></span
            ><strong :class="{ 'has-alerts': alerts.openCount > 0 }">{{ alerts.openCount }}</strong
            ><el-icon><ArrowRight /></el-icon>
          </button>
          <button class="attention-row" @click="emit('navigate', '/system/slo')">
            <span>跟进中告警<small>已确认，等待恢复</small></span
            ><strong>{{ alerts.acknowledgedCount }}</strong
            ><el-icon><ArrowRight /></el-icon>
          </button>
          <p class="attention-note">告警为当前状态，趋势为近 7 日数据。</p>
        </template>
        <div v-else class="operation-empty">
          <el-icon><Bell /></el-icon>
          <p>{{ loading && canViewSlo ? '正在加载告警' : '尚未开通服务质量权限' }}</p>
        </div>
      </article>
    </div>
  </section>
</template>

<style scoped>
.metric-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 16px;
}
.metric-card {
  display: flex;
  flex-direction: column;
  gap: 12px;
  background: var(--cw-paper);
  padding: 20px;
  border: 1px solid var(--cw-line);
  border-radius: 10px;
}
.metric-card > span {
  font-size: 12px;
  color: var(--cw-text-muted);
}
.metric-card > strong {
  font-size: clamp(24px, 2.3vw, 32px);
  font-weight: 600;
  line-height: 1.1;
  letter-spacing: -1px;
  font-variant-numeric: tabular-nums;
  overflow-wrap: anywhere;
}
.metric-card em {
  font-size: 14px;
  color: var(--cw-text-muted);
  font-style: normal;
  margin-left: 5px;
}
small,
.attention-note {
  color: var(--cw-text-muted);
  font-size: 12px;
  line-height: 1.6;
}
.operations-grid {
  display: grid;
  grid-template-columns: minmax(0, 2fr) minmax(240px, 1fr);
  gap: 20px;
  margin-top: 20px;
}
.operations-card {
  min-width: 0;
  background: var(--cw-paper);
  border: 1px solid var(--cw-line);
  border-radius: 10px;
  padding: 20px;
}
header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 24px;
  margin-bottom: 20px;
}
h2 {
  margin: 0;
  font-size: 15px;
  font-weight: 650;
}
.operation-empty {
  min-height: 250px;
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
  text-align: center;
  color: var(--cw-text-muted);
  font-size: 13px;
}
.operation-empty > .el-icon {
  font-size: 26px;
}
.attention-row {
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
  padding: 20px 0;
  border: 0;
  border-bottom: 1px solid var(--cw-line);
  background: transparent;
  font: inherit;
  font-size: 13px;
  text-align: left;
  color: var(--cw-text);
  cursor: pointer;
}
.attention-row span {
  display: grid;
  gap: 7px;
}
.attention-row strong {
  margin-left: auto;
  font-size: 22px;
  font-weight: 550;
  font-variant-numeric: tabular-nums;
}
.attention-row:hover {
  color: var(--cw-cobalt);
}
.has-alerts {
  color: var(--cw-danger);
}
.attention-note {
  margin: 20px 0 0;
}
@media (max-width: 1100px) {
  .metric-grid {
    gap: 10px;
  }
  .metric-card {
    padding: 16px;
  }
  .operations-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
@media (max-width: 600px) {
  .metric-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .metric-card > strong {
    font-size: 25px;
  }
}
</style>
