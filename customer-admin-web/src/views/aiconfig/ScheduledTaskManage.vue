<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
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
import type { AgentVO, MpPageQuery, ScheduledTaskRunVO, ScheduledTaskSaveRequest, ScheduledTaskVO, ScheduleMode } from '@/types/api'

const loading = ref(false)
const list = ref<ScheduledTaskVO[]>([])
const total = ref(0)
const query = reactive<MpPageQuery>({ current: 1, size: 10, keyword: '' })

const agentOptions = ref<AgentVO[]>([])

// 当前全局调度模式（后端在列表数据里带回）：internal=内置调度器，xxl-job=外部 XXL-JOB
const scheduleMode = ref<ScheduleMode>('internal')
const isInternalMode = computed(() => scheduleMode.value !== 'xxl-job')

async function loadList() {
  loading.value = true
  try {
    const result = await pageScheduledTasks(query)
    list.value = result.records
    total.value = result.total
    if (result.records.length > 0 && result.records[0].scheduleMode) {
      scheduleMode.value = result.records[0].scheduleMode
    }
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
  taskCode: '', taskName: '', agentId: undefined as unknown as number, prompt: '', cron: '', enabled: true, remark: '',
})

// Spring cron 为 6 位（秒 分 时 日 月 周），前端只做位数粗校验拦低级错误，合法性以后端 CronExpression 校验为准
const cronRule = {
  validator: (_rule: unknown, value: string | null | undefined, callback: (error?: Error) => void) => {
    if (!value || !value.trim()) {
      callback()
      return
    }
    if (value.trim().split(/\s+/).length !== 6) {
      callback(new Error('cron 需为 6 位（秒 分 时 日 月 周），如 0 0 9 * * ?'))
      return
    }
    callback()
  },
  trigger: 'blur',
}

function openCreate() {
  dialogMode.value = 'create'
  editingId.value = null
  Object.assign(form, { taskCode: '', taskName: '', agentId: undefined, prompt: '', cron: '', enabled: true, remark: '' })
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
    cron: row.cron,
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
  INTERNAL: '内置调度',
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
      v-if="isInternalMode"
      type="info"
      :closable="false"
      show-icon
      title="当前为内置调度模式：调度周期（cron）直接在本页配置，无需外部 XXL-JOB"
    >
      <template #default>
        <div>任务「启用」且填写了 cron 时按周期自动执行；cron 留空则仅可手动触发。</div>
        <div>「手动触发」不受调度周期影响，可随时用于验证 prompt 是否符合预期。</div>
      </template>
    </el-alert>
    <el-alert
      v-else
      type="warning"
      :closable="false"
      show-icon
      title="当前为 XXL-JOB 调度模式：调度周期与启停在公司 XXL-JOB 控制台配置，本页 cron 仅保存展示"
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
        <div class="toolbar-actions">
          <el-button v-permission="'scheduler:add'" class="cw-final-action" type="primary" @click="openCreate">新建定时任务</el-button>
        </div>
      </div>

      <el-table v-loading="loading" :data="list" class="data-table" empty-text="暂无符合条件的定时任务">
        <el-table-column prop="taskCode" label="任务编码" width="180" />
        <el-table-column prop="taskName" label="任务名称" class-name="primary-column" />
        <el-table-column label="关联智能体" width="160">
          <template #default="{ row }: { row: ScheduledTaskVO }">
            {{ row.agentName || `#${row.agentId}` }}
          </template>
        </el-table-column>
        <el-table-column label="调度周期" width="150">
          <template #default="{ row }: { row: ScheduledTaskVO }">
            <code v-if="row.cron">{{ row.cron }}</code>
            <span v-else class="cron-empty">仅手动触发</span>
          </template>
        </el-table-column>
        <el-table-column label="启用状态" width="100" align="center">
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
        class="pagination"
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
              <span v-if="agent.status !== 1" style="float: right; color: var(--el-text-color-placeholder); font-size: 12px">已停用</span>
            </el-option>
          </el-select>
        </el-form-item>
        <el-form-item label="执行指令" prop="prompt" :rules="[{ required: true, message: '请输入执行指令' }]">
          <el-input v-model="form.prompt" type="textarea" :rows="5" placeholder="每次定时执行时发给智能体的指令（prompt），与手动对话时的用户输入等价" />
        </el-form-item>
        <el-form-item label="调度周期" prop="cron" :rules="[cronRule]">
          <el-input v-model="form.cron!" placeholder="cron 表达式（6 位：秒 分 时 日 月 周），如 0 0 9 * * ?；留空则仅手动触发" />
          <div class="form-tip">
            {{ isInternalMode
              ? '内置调度模式：启用且填写 cron 后按周期自动执行，保存后立即生效'
              : 'XXL-JOB 调度模式：此处 cron 仅保存展示，实际周期以 XXL-JOB 控制台配置为准' }}
          </div>
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
        <el-button class="cw-final-action" type="primary" @click="handleSubmit">保存任务</el-button>
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
      <el-table v-loading="runsLoading" :data="runsList" style="width: 100%" empty-text="暂无执行记录">
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
        <el-table-column label="状态" width="80" align="center">
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
      <el-pagination
        v-model:current-page="runsQuery.current"
        v-model:page-size="runsQuery.size"
        :total="runsTotal"
        layout="total, prev, pager, next"
        class="pagination"
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
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 16px;
}

.toolbar-actions {
  display: flex;
  gap: 8px;
  margin-left: auto;
}

.data-table {
  min-width: 0;
  max-width: 100%;
}

:deep(.primary-column .cell) {
  color: var(--cw-text);
  font-weight: 650;
}

.cron-empty {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.form-tip {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.5;
  margin-top: 4px;
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

@media (max-width: 767px) {
  .toolbar-actions {
    margin-left: 0;
  }

  .toolbar-actions > .el-button {
    flex: 1 1 auto;
    margin-left: 0;
  }
}
</style>
