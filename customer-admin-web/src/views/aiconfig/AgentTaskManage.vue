<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { cancelAgentTask, getAgentTask, listAgentTaskStatuses, pageAgentTasks } from '@/api/agent-task'
import { usePagedList } from '@/composables/usePagedList'
import { useQueryState } from '@/composables/useQueryState'
import { useRowMutation } from '@/composables/useRowMutation'
import { useAuthStore } from '@/store/auth'
import CrudLoadState from '@/components/CrudLoadState.vue'
import type { AgentTaskStatus, AgentTaskVO } from '@/types/api'

/** 非终态任务的列表自动刷新间隔：任务是分钟级的长活儿，5 秒足够跟上进度又不至于打爆接口。 */
const AUTO_REFRESH_MS = 5000
const auth = useAuthStore()

const { loading, loadError, list, total, query, loadList: loadListBase } = usePagedList({
  page: async ({ pageNum, pageSize, ...filters }) => {
    const result = await pageAgentTasks({ current: pageNum, size: pageSize, ...filters })
    return { list: result.records, total: result.total }
  },
  initQuery: () => ({ pageNum: 1, pageSize: 10, keyword: '', status: '', agentCode: '' }),
})
const statusOptions = ref<string[]>([])
const silentRefreshing = ref(false)

const detailVisible = ref(false)
const detailTarget = ref<string | null>(null)
const { data: detail, loading: detailLoading, error: detailError, load: loadDetail, reset: resetDetail } =
  useQueryState<AgentTaskVO | null>(() => getAgentTask(detailTarget.value!), () => null)

let refreshTimer: ReturnType<typeof setInterval> | null = null

/** 终态不会再变，只有存在非终态任务时才值得轮询。 */
function hasRunningTask() {
  return list.value.some(item => item.status === 'PENDING' || item.status === 'RUNNING')
}

async function loadList(silent = false) {
  silentRefreshing.value = silent
  await loadListBase()
}

async function loadStatuses() {
  statusOptions.value = await listAgentTaskStatuses()
}

function handleSearch() {
  query.pageNum = 1
  return loadList()
}

function handleReset() {
  query.keyword = ''
  query.status = ''
  query.agentCode = ''
  handleSearch()
}

async function openDetail(row: AgentTaskVO) {
  resetDetail()
  detailTarget.value = row.taskId
  detailVisible.value = true
  await loadDetail()
}
watch(detailVisible, visible => {
  if (!visible) { resetDetail(); detailTarget.value = null }
}, { flush: 'sync' })
watch([() => auth.loginGeneration, () => auth.token, () => auth.permissions.join('\0')], () => {
  detailVisible.value = false
})

const cancellation = useRowMutation<string>('agent-task:cancel')
async function handleCancel(row: AgentTaskVO) {
  await cancellation.run(row.taskId, async isCurrent => {
    await ElMessageBox.confirm(
      '取消后任务会尽快中断，已经产生的部分结果不会保留。确认取消？',
      '取消任务', { type: 'warning' },
    )
    if (isCurrent()) await cancelAgentTask(row.taskId)
  }, async () => {
    ElMessage.success('已请求取消')
    await loadList()
  })
}

function statusTagType(status: AgentTaskStatus) {
  switch (status) {
    case 'COMPLETED':
      return 'success'
    case 'FAILED':
      return 'danger'
    case 'CANCELLED':
      return 'info'
    case 'RUNNING':
      return 'warning'
    default:
      return 'primary'
  }
}

function isTerminal(status: AgentTaskStatus) {
  return status === 'COMPLETED' || status === 'FAILED' || status === 'CANCELLED'
}

function formatCost(costMs?: number) {
  if (costMs === undefined || costMs === null) {
    return '-'
  }
  return costMs < 1000 ? `${costMs} ms` : `${(costMs / 1000).toFixed(1)} s`
}

onMounted(() => {
  loadStatuses()
  loadList()
  // 静默刷新：不打开 loading 遮罩，免得列表每 5 秒闪一次
  refreshTimer = setInterval(() => {
    if (hasRunningTask() && !detailVisible.value && !loading.value) {
      loadList(true)
    }
  }, AUTO_REFRESH_MS)
})

onBeforeUnmount(() => {
  if (refreshTimer) {
    clearInterval(refreshTimer)
    refreshTimer = null
  }
})
</script>

<template>
  <div class="page">
    <CrudLoadState :error="loadError" :has-stale-data="list.length > 0" :loading="loading" @retry="loadList()" />
    <el-alert type="info" :closable="false" show-icon title="关于后台任务">
      <template #default>
        <div>
          智能体在对话中把耗时长的活儿派给子智能体异步执行（<code>agent_spawn</code> 后台模式）时，会在这里留下一条记录。
          提交后可以直接关掉页面，回来查看结果。
        </div>
        <div>任务由智能体自行派发，本页只做查看与取消，不提供手工新建。</div>
      </template>
    </el-alert>

    <el-card>
      <div class="toolbar">
        <el-input
          v-model="query.keyword"
          placeholder="按任务ID/子智能体/会话ID搜索"
          style="width: 260px"
          clearable
          @keyup.enter="handleSearch"
        />
        <el-select v-model="query.status" placeholder="全部状态" clearable style="width: 150px">
          <el-option v-for="item in statusOptions" :key="item" :label="item" :value="item" />
        </el-select>
        <el-input v-model="query.agentCode" placeholder="父智能体编码" style="width: 180px" clearable />
        <el-button type="primary" @click="handleSearch">搜索</el-button>
        <el-button @click="handleReset">重置</el-button>
      </div>

      <el-table v-if="!loadError || list.length > 0" v-loading="loading && !silentRefreshing" :data="list" class="data-table" empty-text="暂无符合条件的后台任务">
        <el-table-column prop="taskId" label="任务ID" width="180" show-overflow-tooltip class-name="primary-column" />
        <el-table-column prop="subAgentId" label="子智能体" width="150" show-overflow-tooltip />
        <el-table-column prop="parentAgentCode" label="父智能体" width="150" show-overflow-tooltip />
        <el-table-column label="状态" width="110" align="center">
          <template #default="{ row }: { row: AgentTaskVO }">
            <el-tag :type="statusTagType(row.status)">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="结果 / 错误" min-width="240" show-overflow-tooltip>
          <template #default="{ row }: { row: AgentTaskVO }">
            <span v-if="row.errorMessage" class="error-text">{{ row.errorMessage }}</span>
            <span v-else-if="row.result">
              {{ row.result }}<span v-if="row.resultTruncated" class="muted">…</span>
            </span>
            <span v-else class="muted">-</span>
          </template>
        </el-table-column>
        <el-table-column label="耗时" width="100">
          <template #default="{ row }: { row: AgentTaskVO }">{{ formatCost(row.costMs) }}</template>
        </el-table-column>
        <el-table-column prop="createdAt" label="创建时间" width="170" />
        <el-table-column label="操作" width="140" fixed="right">
          <template #default="{ row }: { row: AgentTaskVO }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
            <el-button
              v-permission="'agent-task:cancel'"
              link
              type="danger"
              :disabled="isTerminal(row.status) || cancellation.isPending(row.taskId)"
              :loading="cancellation.isPending(row.taskId)"
              @click="handleCancel(row)"
            >
              取消
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-if="!loadError || list.length > 0"
        v-model:current-page="query.pageNum"
        v-model:page-size="query.pageSize"
        :total="total"
        :page-sizes="[10, 20, 50]"
        layout="total, sizes, prev, pager, next"
        class="pagination"
        @current-change="loadList()"
        @size-change="handleSearch"
      />
    </el-card>

    <el-drawer v-model="detailVisible" title="任务详情" size="46%">
      <CrudLoadState :error="detailError" :has-stale-data="!!detail" :loading="detailLoading" @retry="loadDetail" />
      <div v-loading="detailLoading">
        <el-descriptions v-if="detail" :column="1" border>
          <el-descriptions-item label="任务ID">{{ detail.taskId }}</el-descriptions-item>
          <el-descriptions-item label="子智能体">{{ detail.subAgentId || '-' }}</el-descriptions-item>
          <el-descriptions-item label="父智能体">{{ detail.parentAgentCode || '-' }}</el-descriptions-item>
          <el-descriptions-item label="父会话ID">{{ detail.parentSessionId || '-' }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="statusTagType(detail.status)">{{ detail.status }}</el-tag>
            <span v-if="detail.cancelRequested" class="muted"> （已请求取消）</span>
          </el-descriptions-item>
          <el-descriptions-item label="创建时间">{{ detail.createdAt || '-' }}</el-descriptions-item>
          <el-descriptions-item label="开始时间">{{ detail.startedAt || '-' }}</el-descriptions-item>
          <el-descriptions-item label="结束时间">{{ detail.finishedAt || '-' }}</el-descriptions-item>
          <el-descriptions-item label="耗时">{{ formatCost(detail.costMs) }}</el-descriptions-item>
          <el-descriptions-item v-if="detail.errorMessage" label="错误信息">
            <pre class="detail-text error-text">{{ detail.errorMessage }}</pre>
          </el-descriptions-item>
          <el-descriptions-item label="结果">
            <pre v-if="detail.result" class="detail-text">{{ detail.result }}</pre>
            <span v-else class="muted">-</span>
          </el-descriptions-item>
        </el-descriptions>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}

.data-table {
  min-width: 0;
  max-width: 100%;
}

:deep(.primary-column .cell) {
  color: var(--cw-text);
  font-weight: 650;
}

.muted {
  color: var(--el-text-color-secondary);
}

.error-text {
  color: var(--el-color-danger);
}

.detail-text {
  white-space: pre-wrap;
  word-break: break-word;
  margin: 0;
  font-family: inherit;
}
</style>
