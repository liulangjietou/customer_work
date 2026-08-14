<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getDeadLetterStats,
  listDeadLetters,
  reopenDeadLetter,
  type DeadLetter,
  type DeadLetterStatusCode,
} from '@/api/ops'

// 死信队列看板：待重投 / 已放弃列表与人工重开。
//
// 重投本身由客服端的巡检器执行（按指数退避），后台只负责"看"和"把已放弃的放回队列"。
// ABANDONED 那批才是真正要人管的——重试次数已耗尽，不补就永远丢了。

const STATUS_LABELS: Record<DeadLetterStatusCode, { text: string; type: 'primary' | 'success' | 'danger' }> = {
  PENDING: { text: '待重投', type: 'primary' },
  SUCCEEDED: { text: '已成功', type: 'success' },
  ABANDONED: { text: '已放弃', type: 'danger' },
}

const loading = ref(false)
const list = ref<DeadLetter[]>([])
const stats = ref<Record<string, number>>({})
// 默认看已放弃：那批是真正需要人介入的
const status = ref<DeadLetterStatusCode>('ABANDONED')

async function loadData() {
  loading.value = true
  try {
    const [listData, statsData] = await Promise.all([listDeadLetters(status.value), getDeadLetterStats()])
    list.value = listData
    stats.value = statsData
  } finally {
    loading.value = false
  }
}

function formatTime(ms: number): string {
  return ms ? new Date(ms).toLocaleString('zh-CN', { hour12: false }) : '-'
}

function statusLabel(row: DeadLetter) {
  return STATUS_LABELS[row.status]
}

async function handleReopen(row: DeadLetter) {
  try {
    await ElMessageBox.confirm(
      `将把这条重新放回待重投队列，重试次数清零，由客服端巡检器按退避策略重投。\n\n` +
        `类型：${row.type}\n业务标识：${row.bizKey ?? '-'}\n最近失败：${row.lastError ?? '-'}`,
      '重开死信',
      { confirmButtonText: '重开', cancelButtonText: '取消', type: 'warning' },
    )
    await reopenDeadLetter(row.id)
    ElMessage.success('已放回待重投队列')
    await loadData()
  } catch (error) {
    if (error !== 'cancel') throw error
  }
}

// ---------- 详情 ----------

const detailVisible = ref(false)
const current = ref<DeadLetter | null>(null)

function openDetail(row: DeadLetter) {
  current.value = row
  detailVisible.value = true
}

onMounted(loadData)
</script>

<template>
  <div class="dead-letter-board">
    <el-card shadow="never" class="stat-card">
      <div class="stats">
        <div class="stat">
          <div class="stat-value">{{ stats.PENDING ?? 0 }}</div>
          <div class="stat-label">待重投（巡检器会自动补）</div>
        </div>
        <div class="stat">
          <div class="stat-value stat-danger">{{ stats.ABANDONED ?? 0 }}</div>
          <div class="stat-label">已放弃（重试耗尽，需人工介入）</div>
        </div>
        <div class="stat">
          <div class="stat-value stat-success">{{ stats.SUCCEEDED ?? 0 }}</div>
          <div class="stat-label">已补回（原本会丢的单）</div>
        </div>
      </div>
    </el-card>

    <el-card shadow="never">
      <div class="toolbar">
        <el-radio-group v-model="status" @change="loadData">
          <el-radio-button value="ABANDONED">已放弃</el-radio-button>
          <el-radio-button value="PENDING">待重投</el-radio-button>
          <el-radio-button value="SUCCEEDED">已成功</el-radio-button>
        </el-radio-group>
        <el-button :loading="loading" @click="loadData">刷新</el-button>
        <span class="hint">重试次数耗尽的不会被删除，留档才能捞出来手工补</span>
      </div>

      <el-table v-loading="loading" :data="list" style="width: 100%">
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusLabel(row).type">{{ statusLabel(row).text }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="type" label="类型" width="160" />
        <el-table-column prop="bizKey" label="业务标识" width="160" show-overflow-tooltip />
        <el-table-column label="重试" width="80">
          <template #default="{ row }">{{ row.attempts }} 次</template>
        </el-table-column>
        <el-table-column prop="lastError" label="最近失败原因" show-overflow-tooltip />
        <el-table-column label="发生时间" width="170">
          <template #default="{ row }">{{ formatTime(row.createdAtMs) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="140" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
            <el-button
              v-permission="'dead-letter:reopen'"
              link
              type="warning"
              :disabled="row.status !== 'ABANDONED'"
              @click="handleReopen(row)"
            >
              重开
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-empty v-if="!loading && list.length === 0" description="该状态下暂无死信" />
    </el-card>

    <el-drawer v-model="detailVisible" title="死信详情" size="560px">
      <el-descriptions v-if="current" :column="1" border>
        <el-descriptions-item label="状态">
          <el-tag :type="statusLabel(current).type">{{ statusLabel(current).text }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="类型">{{ current.type }}</el-descriptions-item>
        <el-descriptions-item label="业务标识">{{ current.bizKey ?? '-' }}</el-descriptions-item>
        <el-descriptions-item label="重试次数">{{ current.attempts }}</el-descriptions-item>
        <el-descriptions-item label="下次重投">{{ formatTime(current.nextRetryAtMs) }}</el-descriptions-item>
        <el-descriptions-item label="发生时间">{{ formatTime(current.createdAtMs) }}</el-descriptions-item>
        <el-descriptions-item label="终结时间">{{ formatTime(current.finishedAtMs) }}</el-descriptions-item>
        <el-descriptions-item label="最近失败原因">{{ current.lastError ?? '-' }}</el-descriptions-item>
        <el-descriptions-item label="重投载荷">
          <pre class="payload">{{ current.payload }}</pre>
        </el-descriptions-item>
      </el-descriptions>
    </el-drawer>
  </div>
</template>

<style scoped>
.dead-letter-board {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.stat-card :deep(.el-card__body) {
  padding: 16px 20px;
}

.stats {
  display: flex;
  gap: 56px;
  flex-wrap: wrap;
}

.stat-value {
  font-size: 28px;
  font-weight: 600;
  line-height: 1.2;
}

.stat-danger {
  color: var(--el-color-danger);
}

.stat-success {
  color: var(--el-color-success);
}

.stat-label {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  margin-top: 4px;
}

.toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}

.hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.payload {
  margin: 0;
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
