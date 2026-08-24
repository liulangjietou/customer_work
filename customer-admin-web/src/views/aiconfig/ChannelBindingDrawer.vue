<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance } from 'element-plus'
import {
  createChannelBinding,
  deleteChannelBinding,
  getRuntimePublishGate,
  listChannelBindings,
  overrideRuntimePublishGate,
  republishChannelBinding,
  retryRuntimePublishGate,
  updateChannelBinding,
} from '@/api/channel-binding'
import { pageAgents } from '@/api/agent'
import type {
  AgentVO,
  ChannelBindingSaveRequest,
  ChannelBindingVO,
  EvalGateStatus,
  RuntimePublishGateVO,
  RuntimePublishStatus,
} from '@/types/api'

// 抽屉可见性通过 v-model 与父页（AgentManage）联动，父页负责鉴权入口按钮。
const visible = defineModel<boolean>({ required: true })

// 智能体下拉复用「智能体管理」的分页接口，无独立全量接口时拉大页兜底。
const AGENT_OPTION_PAGE_SIZE = 200

const loading = ref(false)
const list = ref<ChannelBindingVO[]>([])
const agentOptions = ref<AgentVO[]>([])
// 记录正在重新发布的渠道编码，逐行控制按钮 loading。
const republishingCode = ref<string | null>(null)
type StatusMeta = { text: string; type: 'info' | 'warning' | 'success' | 'danger' }

const publishStatusMeta: Record<RuntimePublishStatus, StatusMeta> = {
  PENDING: { text: '待发布', type: 'info' },
  PROCESSING: { text: '发布中', type: 'warning' },
  BLOCKED: { text: '门禁阻断', type: 'danger' },
  PUBLISHED: { text: '待回执', type: 'warning' },
  PARTIAL: { text: '部分生效', type: 'warning' },
  APPLIED: { text: '已生效', type: 'success' },
  FAILED: { text: '失败', type: 'danger' },
}

const gateStatusMeta: Record<EvalGateStatus, StatusMeta> = {
  NOT_REQUIRED: { text: '无需门禁', type: 'info' },
  PENDING: { text: '待评估', type: 'warning' },
  PASSED: { text: '已通过', type: 'success' },
  BLOCKED: { text: '已阻断', type: 'danger' },
  OVERRIDDEN: { text: '人工豁免', type: 'warning' },
}

const versionLabels: Array<{ key: keyof NonNullable<RuntimePublishGateVO['candidateVersions']>; label: string }> = [
  { key: 'datasetVersion', label: '评测集版本' },
  { key: 'datasetFingerprint', label: '评测集指纹' },
  { key: 'modelVersion', label: '模型版本' },
  { key: 'promptVersion', label: '提示词版本' },
  { key: 'agentVersion', label: '智能体版本' },
  { key: 'knowledgeBaseVersion', label: '知识库版本' },
  { key: 'toolVersion', label: '工具版本' },
  { key: 'judgeVersion', label: 'Judge 版本' },
  { key: 'rubricVersion', label: '评分规则版本' },
]

const dialogVisible = ref(false)
const dialogMode = ref<'create' | 'edit'>('create')
const formRef = ref<FormInstance>()
const editingId = ref<number | null>(null)
const form = reactive<ChannelBindingSaveRequest>({
  channelCode: '',
  agentId: undefined as unknown as number,
  status: 1,
})

const gateVisible = ref(false)
const gateLoading = ref(false)
const gateAction = ref<'retry' | 'override' | null>(null)
const gateDetail = ref<RuntimePublishGateVO | null>(null)

function formatTime(timestamp?: number) {
  return timestamp ? new Date(timestamp).toLocaleString('zh-CN', { hour12: false }) : '-'
}

function publishMeta(status?: RuntimePublishStatus): StatusMeta {
  return status ? publishStatusMeta[status] : { text: '未发布', type: 'info' }
}

function gateMeta(status?: EvalGateStatus): StatusMeta {
  return status ? gateStatusMeta[status] : { text: '未评估', type: 'info' }
}

async function loadList() {
  loading.value = true
  try {
    list.value = await listChannelBindings()
  } finally {
    loading.value = false
  }
}

async function loadAgentOptions() {
  const result = await pageAgents({ pageNum: 1, pageSize: AGENT_OPTION_PAGE_SIZE })
  // 仅展示启用状态的智能体供绑定，停用的不出现在下拉里。
  agentOptions.value = result.list.filter((a) => a.status === 1)
}

// 抽屉打开时才拉取数据，关闭不预加载，避免父页无谓请求。
watch(visible, (open) => {
  if (open) {
    loadList()
    loadAgentOptions()
  }
})

function openCreate() {
  dialogMode.value = 'create'
  editingId.value = null
  Object.assign(form, { channelCode: '', agentId: undefined, status: 1 })
  dialogVisible.value = true
}

function openEdit(row: ChannelBindingVO) {
  dialogMode.value = 'edit'
  editingId.value = row.id
  Object.assign(form, { channelCode: row.channelCode, agentId: row.agentId, status: row.status })
  dialogVisible.value = true
}

async function handleSubmit() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) {
    return
  }
  if (dialogMode.value === 'create') {
    await createChannelBinding(form)
    ElMessage.success('新建成功')
  } else if (editingId.value != null) {
    await updateChannelBinding(editingId.value, form)
    ElMessage.success('保存成功')
  }
  dialogVisible.value = false
  await loadList()
}

async function handleDelete(row: ChannelBindingVO) {
  await ElMessageBox.confirm(`确认删除渠道「${row.channelCode}」的绑定？`, '提示', { type: 'warning' })
  await deleteChannelBinding(row.id)
  ElMessage.success('删除成功')
  await loadList()
}

async function handleRepublish(row: ChannelBindingVO) {
  republishingCode.value = row.channelCode
  try {
    const taskId = await republishChannelBinding(row.channelCode)
    ElMessage.success(`已进入可靠发布队列，任务：${taskId}`)
    await loadList()
  } finally {
    // 失败时业务错误码由 request 拦截器统一弹提示，这里只负责收尾 loading。
    republishingCode.value = null
  }
}

async function loadGate(taskId: string) {
  gateLoading.value = true
  try {
    gateDetail.value = await getRuntimePublishGate(taskId)
  } finally {
    gateLoading.value = false
  }
}

async function openGate(row: ChannelBindingVO) {
  if (!row.publishTaskId) {
    ElMessage.info('该绑定尚无可靠发布任务')
    return
  }
  gateDetail.value = null
  gateVisible.value = true
  await loadGate(row.publishTaskId)
}

async function refreshGate() {
  if (!gateDetail.value?.taskId) return
  await Promise.all([loadGate(gateDetail.value.taskId), loadList()])
}

async function handleGateRetry() {
  const taskId = gateDetail.value?.taskId
  if (!taskId) return
  gateAction.value = 'retry'
  try {
    await retryRuntimePublishGate(taskId)
    ElMessage.success('已重新进入评测门禁，发布将在通过后自动继续')
    await refreshGate()
  } finally {
    gateAction.value = null
  }
}

async function handleGateOverride() {
  const taskId = gateDetail.value?.taskId
  if (!taskId) return
  try {
    const { value } = await ElMessageBox.prompt(
      '豁免会跳过本任务的评测阻断并继续发布，理由将与候选哈希一同永久审计。',
      '紧急豁免发布门禁',
      {
        confirmButtonText: '确认豁免并继续发布',
        cancelButtonText: '取消',
        inputType: 'textarea',
        inputPlaceholder: '请说明事故背景、风险判断和回滚预案（必填，最多 500 字）',
        inputValidator: (input) => {
          const reason = input.trim()
          if (!reason) return '豁免理由不能为空'
          return reason.length <= 500 || '豁免理由不能超过 500 字'
        },
      },
    )
    gateAction.value = 'override'
    await overrideRuntimePublishGate(taskId, value.trim())
    ElMessage.success('门禁已豁免，发布任务将自动继续')
    await refreshGate()
  } catch (error) {
    if (error !== 'cancel') throw error
  } finally {
    gateAction.value = null
  }
}
</script>

<template>
  <el-drawer v-model="visible" title="渠道绑定" size="980px">
    <div class="toolbar">
      <el-button v-permission="'agent:edit'" type="primary" @click="openCreate">新建绑定</el-button>
    </div>

    <el-table v-loading="loading" :data="list" style="width: 100%">
      <el-table-column prop="channelCode" label="渠道编码" min-width="160" />
      <el-table-column prop="agentName" label="绑定智能体" min-width="140" />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'info'">{{ row.status === 1 ? '启用' : '停用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="配置发布" min-width="150">
        <template #default="{ row }">
          <div class="publish-cell">
            <el-tag :type="publishMeta(row.publishStatus).type">
              {{ publishMeta(row.publishStatus).text }}
            </el-tag>
            <span v-if="row.publishLastError" class="publish-error">{{ row.publishLastError }}</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="评测门禁" min-width="115">
        <template #default="{ row }">
          <el-tag :type="gateMeta(row.publishGateStatus).type">
            {{ gateMeta(row.publishGateStatus).text }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="发布修订" min-width="150" show-overflow-tooltip>
        <template #default="{ row }">
          <span class="mono">{{ row.publishRevision || '-' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="290" fixed="right">
        <template #default="{ row }">
          <el-button v-permission="'agent:edit'" link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button
            v-if="row.publishTaskId"
            v-permission="'eval:view'"
            link
            type="primary"
            @click="openGate(row)"
          >
            门禁详情
          </el-button>
          <el-button
            v-permission="'agent:edit'"
            link
            type="primary"
            :loading="republishingCode === row.channelCode"
            @click="handleRepublish(row)"
          >
            重新发布
          </el-button>
          <el-button v-permission="'agent:edit'" link type="danger" @click="handleDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" :title="dialogMode === 'create' ? '新建绑定' : '编辑绑定'" width="480px" append-to-body>
      <el-form ref="formRef" :model="form" label-width="90px">
        <el-form-item label="渠道编码" prop="channelCode" :rules="[{ required: true, message: '请输入渠道编码' }]">
          <el-input v-model="form.channelCode" :disabled="dialogMode === 'edit'" placeholder="如 wechat / web / app" />
        </el-form-item>
        <el-form-item label="智能体" prop="agentId" :rules="[{ required: true, message: '请选择智能体' }]">
          <el-select v-model="form.agentId" style="width: 100%" placeholder="仅展示启用状态的智能体">
            <el-option v-for="a in agentOptions" :key="a.id" :label="a.agentName" :value="a.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="form.status" :active-value="1" :inactive-value="0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleSubmit">确定</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="gateVisible" title="发布门禁详情" width="860px" append-to-body destroy-on-close>
      <div v-loading="gateLoading">
        <template v-if="gateDetail">
          <el-descriptions :column="2" border size="small">
            <el-descriptions-item label="发布任务" :span="2">
              <span class="mono">{{ gateDetail.taskId }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="发布状态">
              <el-tag :type="publishMeta(gateDetail.publishStatus).type">
                {{ publishMeta(gateDetail.publishStatus).text }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="门禁状态">
              <el-tag :type="gateMeta(gateDetail.gateStatus).type">
                {{ gateMeta(gateDetail.gateStatus).text }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="候选内容哈希" :span="2">
              <span class="mono">{{ gateDetail.candidateContentHash || '-' }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="评估时间">{{ formatTime(gateDetail.evaluatedAtMs) }}</el-descriptions-item>
            <el-descriptions-item label="豁免审计 ID">{{ gateDetail.overrideId || '-' }}</el-descriptions-item>
            <el-descriptions-item label="评测运行" :span="2">
              <span v-if="gateDetail.evalRunIds.length === 0">-</span>
              <el-space v-else wrap>
                <el-tag v-for="runId in gateDetail.evalRunIds" :key="runId" type="info" effect="plain">
                  {{ runId }}
                </el-tag>
              </el-space>
            </el-descriptions-item>
          </el-descriptions>

          <h4>候选版本绑定</h4>
          <el-descriptions :column="1" border size="small">
            <el-descriptions-item v-for="item in versionLabels" :key="item.key" :label="item.label">
              <span class="mono">{{ gateDetail.candidateVersions?.[item.key] || '-' }}</span>
            </el-descriptions-item>
          </el-descriptions>

          <h4>门禁判定</h4>
          <el-empty v-if="!gateDetail.decision || gateDetail.decision.checks.length === 0" description="暂无门禁检查项" />
          <el-table v-else :data="gateDetail.decision.checks" border>
            <el-table-column label="评测类型" width="100">
              <template #default="{ row }">{{ row.evalType === 'INTENT' ? '意图' : '质量' }}</template>
            </el-table-column>
            <el-table-column prop="runId" label="当前运行" min-width="130" show-overflow-tooltip />
            <el-table-column prop="baselineRunId" label="基线运行" min-width="130" show-overflow-tooltip />
            <el-table-column label="结果" width="85">
              <template #default="{ row }">
                <el-tag :type="row.passed ? 'success' : 'danger'">{{ row.passed ? '通过' : '阻断' }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="原因与提示" min-width="260">
              <template #default="{ row }">
                <ul v-if="row.failures.length || row.notices.length" class="gate-reasons">
                  <li v-for="reason in row.failures" :key="`failure-${reason}`" class="gate-failure">{{ reason }}</li>
                  <li v-for="notice in row.notices" :key="`notice-${notice}`">{{ notice }}</li>
                </ul>
                <span v-else>-</span>
              </template>
            </el-table-column>
          </el-table>
        </template>
      </div>
      <template #footer>
        <el-button :loading="gateLoading" @click="refreshGate">刷新</el-button>
        <el-button
          v-if="gateDetail?.gateStatus === 'BLOCKED'"
          v-permission="'eval:run'"
          :loading="gateAction === 'retry'"
          type="primary"
          @click="handleGateRetry"
        >
          重新评估
        </el-button>
        <el-button
          v-if="gateDetail?.gateStatus === 'BLOCKED'"
          v-permission="'eval:gate-override'"
          :loading="gateAction === 'override'"
          type="danger"
          plain
          @click="handleGateOverride"
        >
          紧急豁免并继续发布
        </el-button>
      </template>
    </el-dialog>
  </el-drawer>
</template>

<style scoped>
.toolbar {
  display: flex;
  gap: 8px;
  margin-bottom: 16px;
}

.publish-cell {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 4px;
}

.publish-error {
  display: -webkit-box;
  overflow: hidden;
  color: var(--el-color-danger);
  font-size: 12px;
  line-height: 16px;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.mono {
  overflow-wrap: anywhere;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 12px;
}

h4 {
  margin: 18px 0 10px;
}

.gate-reasons {
  margin: 0;
  padding-left: 18px;
}

.gate-failure {
  color: var(--el-color-danger);
}
</style>
