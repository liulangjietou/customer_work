<script setup lang="ts">
import { onMounted, onUnmounted, reactive, ref } from 'vue'
import { ElMessage, ElNotification } from 'element-plus'
import { claimTicket, getTicketWsCredential, pageTickets } from '@/api/user-ticket'
import { WsClient } from '@/utils/ws'
import TicketChatPanel from './components/TicketChatPanel.vue'
import {
  CATEGORY_LABELS,
  PRIORITY_LABELS,
  PRIORITY_TAG_TYPE,
  STATUS_LABELS,
  STATUS_TAG_TYPE,
  type TicketCategory,
  type TicketPageQuery,
  type TicketPriority,
  type TicketStatus,
  type TicketVO,
  type WsTicketEventFrameData,
  type WsTicketNewFrameData,
} from '@/types/ticket'

const loading = ref(false)
const list = ref<TicketVO[]>([])
const total = ref(0)
const query = reactive<TicketPageQuery>({ status: '', category: '', priority: '', pageNum: 1, pageSize: 10 })

const statusOptions = Object.entries(STATUS_LABELS) as [TicketStatus, string][]
const priorityOptions = Object.entries(PRIORITY_LABELS) as [TicketPriority, string][]
const categoryOptions = Object.entries(CATEGORY_LABELS) as [TicketCategory, string][]

async function loadList() {
  loading.value = true
  try {
    const result = await pageTickets(query)
    list.value = result.items
    total.value = result.total
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  query.pageNum = 1
  loadList()
}

function handlePageChange() {
  loadList()
}

// ---------- WS：坐席工作台单例连接，进池提醒 + 行状态实时更新 ----------
const ws = new WsClient()
const agentId = ref('')

async function setupWs() {
  try {
    const credential = await getTicketWsCredential()
    agentId.value = credential.agentId
    ws.connect(`${credential.wsUrl}?token=${encodeURIComponent(credential.token)}`)
  } catch (error) {
    ElMessage.error('工单实时连接建立失败：' + (error instanceof Error ? error.message : String(error)))
    return
  }
  ws.on('ticket_new', (data) => {
    const frame = data as WsTicketNewFrameData
    ElNotification({
      title: '新工单进入接单池',
      message: `${frame.title}（${CATEGORY_LABELS[frame.category]} · ${PRIORITY_LABELS[frame.priority]}）`,
      type: 'info',
    })
    // 新工单只有在当前筛选条件命中时才值得插入列表，简单起见直接整体刷新第一页，
    // 避免手工拼装 TicketVO（WS 帧字段不全，凑不出完整行数据）。
    if (query.pageNum === 1) {
      loadList()
    }
  })
  ws.on('ticket_event', (data) => {
    const frame = data as WsTicketEventFrameData
    const row = list.value.find((t) => t.id === frame.ticketId)
    if (row && frame.toStatus) {
      row.status = frame.toStatus
      row.updatedAtMs = frame.ts
    }
  })
}

// ---------- 接入 / 查看 ----------
const chatVisible = ref(false)
const activeTicketId = ref<string | null>(null)

async function handleClaim(row: TicketVO) {
  try {
    await claimTicket(row.id)
    ElMessage.success('已接入')
    await loadList()
    openChat(row.id)
  } catch (error) {
    ElMessage.error('接入失败：' + (error instanceof Error ? error.message : String(error)))
  }
}

function openChat(id: string) {
  activeTicketId.value = id
  chatVisible.value = true
}

function handleChatVisibleChange(visible: boolean) {
  chatVisible.value = visible
  if (!visible) {
    activeTicketId.value = null
  }
}

function handleChatRefresh() {
  loadList()
}

function formatTime(ms: number) {
  return new Date(ms).toLocaleString()
}

onMounted(() => {
  loadList()
  setupWs()
})

onUnmounted(() => {
  ws.close()
})
</script>

<template>
  <div class="page">
    <el-card>
      <div class="toolbar">
        <el-select v-model="query.status" placeholder="状态" clearable style="width: 160px" @change="handleSearch">
          <el-option v-for="[value, label] in statusOptions" :key="value" :value="value" :label="label" />
        </el-select>
        <el-select v-model="query.priority" placeholder="优先级" clearable style="width: 140px" @change="handleSearch">
          <el-option v-for="[value, label] in priorityOptions" :key="value" :value="value" :label="label" />
        </el-select>
        <el-select v-model="query.category" placeholder="分类" clearable style="width: 140px" @change="handleSearch">
          <el-option v-for="[value, label] in categoryOptions" :key="value" :value="value" :label="label" />
        </el-select>
        <el-button type="primary" @click="handleSearch">刷新</el-button>
      </div>

      <el-table v-loading="loading" :data="list" style="width: 100%">
        <el-table-column prop="id" label="工单号" width="90" />
        <el-table-column prop="title" label="标题" show-overflow-tooltip />
        <el-table-column prop="userId" label="用户" width="140" show-overflow-tooltip />
        <el-table-column label="状态" width="120">
          <template #default="{ row }: { row: TicketVO }">
            <el-tag :type="STATUS_TAG_TYPE[row.status]" size="small">{{ STATUS_LABELS[row.status] }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="优先级" width="90">
          <template #default="{ row }: { row: TicketVO }">
            <el-tag :type="PRIORITY_TAG_TYPE[row.priority]" size="small">{{ PRIORITY_LABELS[row.priority] }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="分类" width="90">
          <template #default="{ row }: { row: TicketVO }">{{ CATEGORY_LABELS[row.category] }}</template>
        </el-table-column>
        <el-table-column label="当前坐席" width="120">
          <template #default="{ row }: { row: TicketVO }">{{ row.assignee || '-' }}</template>
        </el-table-column>
        <el-table-column label="更新时间" width="170">
          <template #default="{ row }: { row: TicketVO }">{{ formatTime(row.updatedAtMs) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }: { row: TicketVO }">
            <el-button
              v-if="row.status === 'WAITING_AGENT'"
              v-permission="'user-ticket:claim'"
              link
              type="primary"
              @click="handleClaim(row)"
            >
              接入
            </el-button>
            <el-button v-else link type="primary" @click="openChat(row.id)">查看</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-model:current-page="query.pageNum"
        v-model:page-size="query.pageSize"
        :total="total"
        layout="total, prev, pager, next"
        style="margin-top: 16px; justify-content: flex-end"
        @current-change="handlePageChange"
      />
    </el-card>

    <TicketChatPanel
      :visible="chatVisible"
      :ticket-id="activeTicketId"
      :ws="ws"
      :agent-id="agentId"
      @update:visible="handleChatVisibleChange"
      @refresh="handleChatRefresh"
    />
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  gap: 8px;
  margin-bottom: 16px;
}
</style>
