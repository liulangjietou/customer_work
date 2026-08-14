<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { getCsatSummary, listCsatSurveys, type CsatSummary, type CsatSurvey } from '@/api/ops'

// CSAT 看板：会话级满意度。
//
// 与消息级点赞/点踩衡量的是完全不同的东西：那个看单句质量，这个看"这次服务整体解决了没有"。
// 一次会话可能每句都答得像样但问题始终没解决——那会拿到一堆 UP 和一个 2 分。

const DAY_MS = 24 * 60 * 60 * 1000

/** 回收率警戒线：低于此值时那个满意度分数只代表愿意评价的一小撮人，不该当真。 */
const RESPONSE_RATE_WARN = 0.3

const loading = ref(false)
const summary = ref<CsatSummary | null>(null)
const surveys = ref<CsatSurvey[]>([])
const query = reactive({ scopeId: 'default', days: 7 })

async function loadData() {
  loading.value = true
  try {
    const end = Date.now()
    const start = end - query.days * DAY_MS
    const params = { scopeId: query.scopeId, windowStartMs: start, windowEndMs: end }
    const [summaryData, listData] = await Promise.all([
      getCsatSummary(params),
      listCsatSurveys(params),
    ])
    summary.value = summaryData
    surveys.value = listData
  } finally {
    loading.value = false
  }
}

// 两个 formatter 都容忍 undefined：后端漏个字段不该让整张页面白掉。
// 这不是假想——CsatSummary 的派生指标一度没标 @JsonProperty，JSON 里压根没有这几个键，
// 模板里直接 .toFixed() 就在 undefined 上抛错，Vue 渲染中断、loading 停在原地转圈，
// 表面看像"接口没返回"，实际接口早就 200 了。
function formatPercent(value: number | undefined | null): string {
  return typeof value === 'number' ? `${(value * 100).toFixed(1)}%` : '-'
}

function formatScore(value: number | undefined | null): string {
  return typeof value === 'number' ? value.toFixed(2) : '-'
}

function formatTime(ms: number): string {
  return ms ? new Date(ms).toLocaleString('zh-CN', { hour12: false }) : '-'
}

/** 回收率过低时 CSAT 不可尽信——样本偏向特别满意与特别不满的两头。 */
const responseRateTooLow = computed(
  () => !!summary.value && summary.value.invited > 0 && summary.value.responseRate < RESPONSE_RATE_WARN,
)

/** 只看已评价的，且低分优先——低分留言才是能拿来改进的东西。 */
const answeredSurveys = computed(() =>
  surveys.value.filter((s) => s.answered).sort((a, b) => (a.score ?? 0) - (b.score ?? 0)),
)

function scoreTagType(score: number | null): 'success' | 'warning' | 'danger' | 'info' {
  if (score === null) return 'info'
  if (score >= 4) return 'success'
  if (score === 3) return 'warning'
  return 'danger'
}

onMounted(loadData)
</script>

<template>
  <div class="csat-board">
    <el-card shadow="never">
      <div class="toolbar">
        <el-input v-model="query.scopeId" placeholder="分区键" style="width: 160px" />
        <el-select v-model="query.days" style="width: 130px">
          <el-option label="最近 7 天" :value="7" />
          <el-option label="最近 30 天" :value="30" />
          <el-option label="最近 90 天" :value="90" />
        </el-select>
        <el-button type="primary" :loading="loading" @click="loadData">查询</el-button>
        <span class="hint">CSAT 是按周看的指标，按天看噪声太大</span>
      </div>

      <div v-loading="loading" class="stats">
        <div class="stat">
          <div class="stat-value stat-primary">{{ formatPercent(summary?.csat) }}</div>
          <div class="stat-label">CSAT（4 分及以上占回收数）</div>
        </div>
        <div class="stat">
          <div class="stat-value" :class="{ 'stat-warn': responseRateTooLow }">
            {{ formatPercent(summary?.responseRate) }}
          </div>
          <div class="stat-label">回收率（{{ summary?.answered ?? 0 }} / {{ summary?.invited ?? 0 }}）</div>
        </div>
        <div class="stat">
          <div class="stat-value">{{ formatScore(summary?.averageScore) }}</div>
          <div class="stat-label">平均分（辅助看，主指标是 CSAT）</div>
        </div>
      </div>

      <el-alert
        v-if="responseRateTooLow"
        class="section"
        type="warning"
        show-icon
        :closable="false"
        title="回收率偏低，上面的 CSAT 不可尽信"
        description="愿意主动评价的往往是特别满意或特别不满的两头，中间的沉默大多数不在样本里。
          此时这个分数更像是「两极用户的比例」而非整体满意度。"
      />
    </el-card>

    <el-card shadow="never">
      <div class="section-title">
        评价明细（低分优先）
        <span class="hint">低分留言才是能拿来改进的东西</span>
      </div>
      <el-table v-loading="loading" :data="answeredSurveys" style="width: 100%">
        <el-table-column label="评分" width="90">
          <template #default="{ row }">
            <el-tag :type="scoreTagType(row.score)">{{ row.score }} 分</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="sessionId" label="会话" width="240" show-overflow-tooltip />
        <el-table-column prop="comment" label="用户留言" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.comment">{{ row.comment }}</span>
            <span v-else class="muted">（未填写）</span>
          </template>
        </el-table-column>
        <el-table-column label="评价时间" width="180">
          <template #default="{ row }">{{ formatTime(row.submittedAtMs) }}</template>
        </el-table-column>
      </el-table>

      <el-empty
        v-if="!loading && answeredSurveys.length === 0"
        description="该窗口内还没有用户评价（会话结束时会自动发出邀请）"
      />
    </el-card>
  </div>
</template>

<style scoped>
.csat-board {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
  flex-wrap: wrap;
}

.stats {
  display: flex;
  gap: 56px;
  flex-wrap: wrap;
}

.stat-value {
  font-size: 30px;
  font-weight: 600;
  line-height: 1.2;
}

.stat-primary {
  color: var(--el-color-primary);
}

.stat-warn {
  color: var(--el-color-warning);
}

.stat-label {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  margin-top: 4px;
}

.section {
  margin-top: 16px;
}

.section-title {
  font-weight: 600;
  margin-bottom: 12px;
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  font-weight: 400;
}

.muted {
  color: var(--el-text-color-placeholder);
}
</style>
