<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import type { FormInstance } from 'element-plus'
import {
  createScheduledTask,
  deleteScheduledTask,
  disableScheduledTask,
  enableScheduledTask,
  pageScheduledTaskRuns,
  pageScheduledTasks,
  triggerScheduledTask,
  updateScheduledTask,
} from '@/api/scheduled-task'
import { pageAgents } from '@/api/agent'
import type { AgentVO, MpPageQuery, ScheduledTaskRunVO, ScheduledTaskSaveRequest, ScheduledTaskVO } from '@/types/api'

const loading = ref(false)
const list = ref<ScheduledTaskVO[]>([])
const total = ref(0)
const query = reactive<MpPageQuery>({ current: 1, size: 10, keyword: '' })

const agentOptions = ref<AgentVO[]>([])

async function loadList() {
  loading.value = true
  try {
    const result = await pageScheduledTasks(query)
    list.value = result.records
    total.value = result.total
  } finally {
    loading.value = false
  }
}

async function loadAgentOptions() {
  const result = await pageAgents({ pageNum: 1, pageSize: 100 })
  agentOptions.value = result.list
}

function handleSearch() {
  query.current = 1
  loadList()
}

function handlePageChange() {
  loadList()
}

// ---------- 新建/编辑 ----------
const dialogVisible = ref(false)
const dialogMode = ref<'create' | 'edit'>('create')
const formRef = ref<FormInstance>()
const editingId = ref<number | null>(null)
const form = reactive<ScheduledTaskSaveRequest>({
  taskCode: '', taskName: '', agentId: undefined as unknown as number, prompt: '', enabled: true, remark: '',
})

function openCreate() {
  dialogMode.value = 'create'
  editingId.value = null
  Object.assign(form, { taskCode: '', taskName: '', agentId: undefined, prompt: '', enabled: true, remark: '' })
  dialogVisible.value = true
}

function openEdit(row: ScheduledTaskVO) {
  dialogMode.value = 'edit'
  editingId.value = row.id
  Object.assign(form, {
    taskCode: row.taskCode,
    taskName: row.taskName,
    agentId: row.agentId,
    prompt: row.prompt,
    enabled: row.enabled,
    remark: row.remark,
  })
  dialogVisible.value = true
}

async function handleSubmit() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) {
    return
  }
  if (dialogMode.value === 'edit' && editingId.value) {
    await updateScheduledTask(editingId.value, form)
    ElMessage.success('保存成功')
  } else {
    await createScheduledTask(form)
    ElMessage.success('新建成功')
  }
  dialogVisible.value = false
  await loadList()
}

async function handleDelete(row: ScheduledTaskVO) {
  await ElMessageBox.confirm(`确认删除定时任务「${row.taskName}」？`, '提示', { type: 'warning' })
  await deleteScheduledTask(row.id)
  ElMessage.success('删除成功')
  await loadList()
}

async function handleToggleEnabled(row: ScheduledTaskVO, value: boolean) {
  try {
    if (value) {
      await enableScheduledTask(row.id)
      ElMessage.success('已启用')
    } else {
      await disableScheduledTask(row.id)
      ElMessage.success('已停用')
    }
    row.enabled = value
  } catch {
    // 开关状态与后端不一致时，重新拉取兜底，避免界面显示与实际状态错位
    await loadList()
  }
}

// ---------- 手动触发 ----------
const triggeringId = ref<number | null>(null)
const triggerResultVisible = ref(false)
const triggerResult = ref<ScheduledTaskRunVO | null>(null)

async function handleTrigger(row: ScheduledTaskVO) {
  await ElMessageBox.confirm(
    `确认手动触发任务「${row.taskName}」？该操作会同步调用智能体执行，可能耗时较长（分钟级），请耐心等待。`,
    '手动触发',
    { type: 'warning' },
  )
  triggeringId.value = row.id
  try {
    triggerResult.value = await triggerScheduledTask(row.id)
    triggerResultVisible.value = true
  } catch {
    ElMessage.error('触发失败，请查看执行历史或联系管理员排查')
  } finally {
    triggeringId.value = null
  }
}

// ---------- 执行历史 ----------
const runsVisible = ref(false)
const runsLoading = ref(false)
const runsTaskName = ref('')
const runsTaskId = ref<number | null>(null)
const runsQuery = reactive<MpPageQuery>({ current: 1, size: 10 })
const runsList = ref<ScheduledTaskRunVO[]>([])
const runsTotal = ref(0)

const triggerTypeLabel: Record<ScheduledTaskRunVO['triggerType'], string> = {
  XXL_JOB: 'XXL-JOB',
  MANUAL: '手动触发',
}

async function openRuns(row: ScheduledTaskVO) {
  runsTaskId.value = row.id
  runsTaskName.value = row.taskName
  runsQuery.current = 1
  runsVisible.value = true
  await loadRuns()
}

async function loadRuns() {
  if (!runsTaskId.value) return
  runsLoading.value = true
  try {
    const result = await pageScheduledTaskRuns(runsTaskId.value, runsQuery)
    runsList.value = result.records
    runsTotal.value = result.total
  } finally {
    runsLoading.value = false
  }
}

const outputVisible = ref(false)
const outputRun = ref<ScheduledTaskRunVO | null>(null)

function openOutput(row: ScheduledTaskRunVO) {
  outputRun.value = row
  outputVisible.value = true
}

onMounted(() => {
  loadList()
  loadAgentOptions()
})
</script>

<template>
  <div class="page">
    <el-alert
      type="warning"
      :closable="false"
      show-icon
      title="调度周期（cron）与启停在公司 XXL-JOB 控制台配置"
      style="margin-bottom: 16px"
    >
      <template #default>
        <div>执行器 JobHandler 填 <code>agentScheduledTask</code>，执行器参数填本页对应任务的「任务编码」。</div>
        <div>本页「手动触发」不经过 XXL-JOB，仅用于本地验证 prompt 是否符合预期，不会影响线上调度周期。</div>
      </template>
    </el-alert>

    <el-card>
      <div class="toolbar">
        <el-input v-model="query.keyword" placeholder="按任务编码/名称搜索" style="width: 240px" clearable @keyup.enter="handleSearch" />
        <el-button type="primary" @click="handleSearch">搜索</el-button>
        <el-button v-permission="'scheduler:add'" type="primary" @click="openCreate">新建定时任务</el-button>
      </div>

      <el-table v-loading="loading" :data="list" style="width: 100%">
        <el-table-column prop="taskCode" label="任务编码" width="180" />
        <el-table-column prop="taskName" label="任务名称" />
        <el-table-column label="关联智能体" width="160">
          <template #default="{ row }: { row: ScheduledTaskVO }">
            {{ row.agentName || `#${row.agentId}` }}
          </template>
        </el-table-column>
        <el-table-column label="启用状态" width="100">
          <template #default="{ row }: { row: ScheduledTaskVO }">
            <el-switch
              v-permission="'scheduler:edit'"
              :model-value="row.enabled"
              @change="(value: string | number | boolean) => handleToggleEnabled(row, value as boolean)"
            />
          </template>
        </el-table-column>
        <el-table-column prop="remark" label="备注" show-overflow-tooltip />
        <el-table-column prop="updateTime" label="更新时间" width="170" />
        <el-table-column label="操作" width="260" fixed="right">
          <template #default="{ row }: { row: ScheduledTaskVO }">
            <el-button v-permission="'scheduler:edit'" link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button
              v-permission="'scheduler:trigger'"
              link
              type="primary"
              :loading="triggeringId === row.id"
              @click="handleTrigger(row)"
            >
              手动触发
            </el-button>
            <el-button link type="primary" @click="openRuns(row)">执行历史</el-button>
            <el-button v-permission="'scheduler:delete'" link type="danger" @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-model:current-page="query.current"
        v-model:page-size="query.size"
        :total="total"
        layout="total, prev, pager, next"
        style="margin-top: 16px; justify-content: flex-end"
        @current-change="handlePageChange"
      />
    </el-card>

    <el-dialog v-model="dialogVisible" :title="dialogMode === 'edit' ? '编辑定时任务' : '新建定时任务'" width="560px">
      <el-form ref="formRef" :model="form" label-width="90px">
        <el-form-item label="任务编码" prop="taskCode" :rules="[{ required: true, message: '请输入任务编码' }]">
          <el-input v-model="form.taskCode" :disabled="dialogMode === 'edit'" placeholder="XXL-JOB 执行器参数填这个值，建议英文+下划线" />
        </el-form-item>
        <el-form-item label="任务名称" prop="taskName" :rules="[{ required: true, message: '请输入任务名称' }]">
          <el-input v-model="form.taskName" />
        </el-form-item>
        <el-form-item label="关联智能体" prop="agentId" :rules="[{ required: true, message: '请选择智能体' }]">
          <el-select v-model="form.agentId" placeholder="请选择智能体" style="width: 100%">
            <el-option
              v-for="agent in agentOptions"
              :key="agent.id"
              :label="agent.agentName"
              :value="agent.id"
              :disabled="agent.status !== 1"
            >
              <span>{{ agent.agentName }}</span>
              <span v-if="agent.status !== 1" style="float: right; color: #c0c4cc; font-size: 12px">已停用</span>
            </el-option>
          </el-select>
        </el-form-item>
        <el-form-item label="执行指令" prop="prompt" :rules="[{ required: true, message: '请输入执行指令' }]">
          <el-input v-model="form.prompt" type="textarea" :rows="5" placeholder="每次定时执行时发给智能体的指令（prompt），与手动对话时的用户输入等价" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark!" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleSubmit">确定</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="triggerResultVisible" title="手动触发结果" width="600px">
      <template v-if="triggerResult">
        <el-descriptions :column="1" border>
          <el-descriptions-item label="状态">
            <el-tag :type="triggerResult.status === 'SUCCESS' ? 'success' : 'danger'">
              {{ triggerResult.status === 'SUCCESS' ? '成功' : '失败' }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="耗时">{{ triggerResult.costMs != null ? `${triggerResult.costMs} ms` : '-' }}</el-descriptions-item>
          <el-descriptions-item v-if="triggerResult.status === 'SUCCESS'" label="输出">
            <pre class="result-pre">{{ triggerResult.output || '(空)' }}</pre>
          </el-descriptions-item>
          <el-descriptions-item v-else label="错误信息">
            <pre class="result-pre">{{ triggerResult.errorMessage || '(空)' }}</pre>
          </el-descriptions-item>
        </el-descriptions>
      </template>
      <template #footer>
        <el-button type="primary" @click="triggerResultVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="runsVisible" :title="`执行历史 · ${runsTaskName}`" size="640px">
      <el-table v-loading="runsLoading" :data="runsList" style="width: 100%">
        <el-table-column label="触发方式" width="100">
          <template #default="{ row }: { row: ScheduledTaskRunVO }">
            <el-tag :type="row.triggerType === 'MANUAL' ? 'warning' : 'info'" size="small">
              {{ triggerTypeLabel[row.triggerType] }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="startTime" label="开始时间" width="170" />
        <el-table-column label="耗时" width="90">
          <template #default="{ row }: { row: ScheduledTaskRunVO }">
            {{ row.costMs != null ? `${row.costMs} ms` : '-' }}
          </template>
        </el-table-column>
        <el-table-column label="状态" width="80">
          <template #default="{ row }: { row: ScheduledTaskRunVO }">
            <el-tag :type="row.status === 'SUCCESS' ? 'success' : 'danger'" size="small">
              {{ row.status === 'SUCCESS' ? '成功' : '失败' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }: { row: ScheduledTaskRunVO }">
            <el-button link type="primary" @click="openOutput(row)">查看输出</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!runsLoading && runsList.length === 0" description="还没有执行记录" />
      <el-pagination
        v-model:current-page="runsQuery.current"
        v-model:page-size="runsQuery.size"
        :total="runsTotal"
        layout="total, prev, pager, next"
        style="margin-top: 16px; justify-content: flex-end"
        @current-change="loadRuns"
      />
    </el-drawer>

    <el-dialog v-model="outputVisible" title="执行输出" width="600px">
      <template v-if="outputRun">
        <pre class="result-pre">{{ outputRun.status === 'SUCCESS' ? (outputRun.output || '(空)') : (outputRun.errorMessage || '(空)') }}</pre>
      </template>
      <template #footer>
        <el-button type="primary" @click="outputVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  gap: 8px;
  margin-bottom: 16px;
}

.result-pre {
  margin: 0;
  padding: 12px;
  background: var(--el-fill-color-light);
  border-radius: 4px;
  font-family: 'JetBrains Mono', 'Fira Code', 'Courier New', monospace;
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 50vh;
  overflow-y: auto;
}
</style>
