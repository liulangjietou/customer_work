<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import type { FormInstance } from 'element-plus'
import {
  createKnowledgeSource,
  deleteKnowledgeSource,
  fetchKnowledgeBaseVersions,
  fetchKnowledgeDocumentLineage,
  fetchKnowledgeSources,
  fetchKnowledgeSyncRuns,
  syncKnowledgeSource,
  updateKnowledgeSource,
} from '@/api/knowledgeBase'
import type {
  KnowledgeBaseVersionVO,
  KnowledgeBaseVO,
  KnowledgeDocumentRevisionVO,
  KnowledgeSourceSaveRequest,
  KnowledgeSourceVO,
  KnowledgeSyncRequest,
  KnowledgeSyncRunVO,
} from '@/types/api'

const props = defineProps<{
  modelValue: boolean
  knowledgeBase: KnowledgeBaseVO | null
}>()

const emit = defineEmits<{
  (event: 'update:modelValue', value: boolean): void
  (event: 'changed'): void
}>()

const loading = ref(false)
const sources = ref<KnowledgeSourceVO[]>([])
const versions = ref<KnowledgeBaseVersionVO[]>([])
const sourceDialogVisible = ref(false)
const sourceDialogMode = ref<'create' | 'edit'>('create')
const editingSourceId = ref<number | null>(null)
const sourceFormRef = ref<FormInstance>()
const syncDialogVisible = ref(false)
const syncing = ref(false)
const syncSource = ref<KnowledgeSourceVO | null>(null)
const syncPayload = ref('')
const runsVisible = ref(false)
const runsLoading = ref(false)
const runs = ref<KnowledgeSyncRunVO[]>([])
const lineageVisible = ref(false)
const lineageLoading = ref(false)
const lineageExternalId = ref('')
const lineage = ref<KnowledgeDocumentRevisionVO[]>([])

interface SourceFormState {
  sourceCode: string
  sourceName: string
  status: number
  freshnessSlaMinutes: number
  qualityThreshold: number
  aclMode: 'PUBLIC' | 'RESTRICTED'
  allowedSubjectTypes: Array<'USER' | 'ADMIN_USER' | 'IP' | 'API_KEY'>
  allowedSubjectIdsText: string
  allowedChannelsText: string
}

const sourceForm = reactive<SourceFormState>({
  sourceCode: '', sourceName: '', status: 1, freshnessSlaMinutes: 1440,
  qualityThreshold: 0.8, aclMode: 'PUBLIC', allowedSubjectTypes: [],
  allowedSubjectIdsText: '', allowedChannelsText: '',
})

const freshnessLabel: Record<string, string> = {
  NEVER_SYNCED: '未同步', FRESH: '新鲜', STALE: '已过期', FAILED: '最近失败',
}
const freshnessType: Record<string, 'info' | 'success' | 'warning' | 'danger'> = {
  NEVER_SYNCED: 'info', FRESH: 'success', STALE: 'warning', FAILED: 'danger',
}
const qualityLabel: Record<string, string> = {
  UNKNOWN: '未评估', PASSED: '通过', FAILED: '未通过',
}
const syncType: Record<string, 'info' | 'success' | 'danger' | 'warning'> = {
  PROCESSING: 'info', SUCCEEDED: 'success', FAILED: 'danger', QUALITY_FAILED: 'warning',
}

watch(() => props.modelValue, (visible) => {
  if (visible && props.knowledgeBase) loadAll()
})

async function loadAll() {
  if (!props.knowledgeBase) return
  loading.value = true
  try {
    const [sourceRows, versionRows] = await Promise.all([
      fetchKnowledgeSources(props.knowledgeBase.id),
      fetchKnowledgeBaseVersions(props.knowledgeBase.id),
    ])
    sources.value = sourceRows
    versions.value = versionRows
  } finally {
    loading.value = false
  }
}

function close() {
  emit('update:modelValue', false)
}

function resetSourceForm() {
  Object.assign(sourceForm, {
    sourceCode: '', sourceName: '', status: 1, freshnessSlaMinutes: 1440,
    qualityThreshold: 0.8, aclMode: 'PUBLIC', allowedSubjectTypes: [],
    allowedSubjectIdsText: '', allowedChannelsText: '',
  })
}

function openCreateSource() {
  sourceDialogMode.value = 'create'
  editingSourceId.value = null
  resetSourceForm()
  sourceDialogVisible.value = true
}

function openEditSource(source: KnowledgeSourceVO) {
  sourceDialogMode.value = 'edit'
  editingSourceId.value = source.id
  Object.assign(sourceForm, {
    sourceCode: source.sourceCode,
    sourceName: source.sourceName,
    status: source.status,
    freshnessSlaMinutes: source.freshnessSlaMinutes,
    qualityThreshold: source.qualityThreshold,
    aclMode: source.defaultAcl.mode,
    allowedSubjectTypes: [...source.defaultAcl.allowedSubjectTypes],
    allowedSubjectIdsText: source.defaultAcl.allowedSubjectIds.join(','),
    allowedChannelsText: source.defaultAcl.allowedChannels.join(','),
  })
  sourceDialogVisible.value = true
}

function splitValues(raw: string) {
  return [...new Set(raw.split(',').map((value) => value.trim()).filter(Boolean))]
}

function sourceRequest(): KnowledgeSourceSaveRequest {
  return {
    sourceCode: sourceForm.sourceCode,
    sourceName: sourceForm.sourceName,
    sourceType: 'PUSH',
    status: sourceForm.status,
    freshnessSlaMinutes: sourceForm.freshnessSlaMinutes,
    qualityThreshold: sourceForm.qualityThreshold,
    defaultAcl: {
      mode: sourceForm.aclMode,
      allowedSubjectTypes: sourceForm.aclMode === 'RESTRICTED' ? sourceForm.allowedSubjectTypes : [],
      allowedSubjectIds: sourceForm.aclMode === 'RESTRICTED'
        ? splitValues(sourceForm.allowedSubjectIdsText) : [],
      allowedChannels: sourceForm.aclMode === 'RESTRICTED'
        ? splitValues(sourceForm.allowedChannelsText) : [],
    },
  }
}

function restrictedAclValid() {
  return sourceForm.aclMode !== 'RESTRICTED'
    || sourceForm.allowedSubjectTypes.length > 0
    || splitValues(sourceForm.allowedSubjectIdsText).length > 0
    || splitValues(sourceForm.allowedChannelsText).length > 0
}

async function saveSource() {
  if (!props.knowledgeBase) return
  const valid = await sourceFormRef.value?.validate().catch(() => false)
  if (!valid) return
  if (!restrictedAclValid()) {
    ElMessage.warning('受限 ACL 至少配置一个主体类型、主体 ID 或渠道')
    return
  }
  if (sourceDialogMode.value === 'create') {
    await createKnowledgeSource(props.knowledgeBase.id, sourceRequest())
    ElMessage.success('文档源已创建')
  } else if (editingSourceId.value != null) {
    await updateKnowledgeSource(props.knowledgeBase.id, editingSourceId.value, sourceRequest())
    ElMessage.success('文档源已更新')
  }
  sourceDialogVisible.value = false
  await loadAll()
}

async function removeSource(source: KnowledgeSourceVO) {
  if (!props.knowledgeBase) return
  await ElMessageBox.confirm(`确认删除文档源「${source.sourceName}」？`, '提示', { type: 'warning' })
  await deleteKnowledgeSource(props.knowledgeBase.id, source.id)
  ElMessage.success('文档源已删除')
  await loadAll()
}

function openSync(source: KnowledgeSourceVO) {
  syncSource.value = source
  syncPayload.value = JSON.stringify({
    requestId: `manual-${Date.now()}`,
    expectedCheckpoint: source.currentCheckpoint,
    checkpoint: '',
    fullSnapshot: false,
    expectedDocumentCount: null,
    documents: [],
  } satisfies KnowledgeSyncRequest, null, 2)
  syncDialogVisible.value = true
}

async function submitSync() {
  if (!props.knowledgeBase || !syncSource.value) return
  let payload: KnowledgeSyncRequest
  try {
    payload = JSON.parse(syncPayload.value) as KnowledgeSyncRequest
  } catch {
    ElMessage.error('同步请求必须是合法 JSON')
    return
  }
  if (!payload.requestId || !payload.checkpoint || !Array.isArray(payload.documents)) {
    ElMessage.error('requestId、checkpoint、documents 为必填字段')
    return
  }
  syncing.value = true
  try {
    const result = await syncKnowledgeSource(props.knowledgeBase.id, syncSource.value.id, payload)
    ElMessage.success(`同步已提交：${result.status}${result.knowledgeBaseVersionId ? `，版本 #${result.knowledgeBaseVersionId}` : ''}`)
    syncDialogVisible.value = false
    await loadAll()
    emit('changed')
  } finally {
    syncing.value = false
  }
}

async function openRuns(source: KnowledgeSourceVO) {
  if (!props.knowledgeBase) return
  runsVisible.value = true
  runsLoading.value = true
  try {
    runs.value = await fetchKnowledgeSyncRuns(props.knowledgeBase.id, source.id)
  } finally {
    runsLoading.value = false
  }
}

async function openLineage(source: KnowledgeSourceVO) {
  if (!props.knowledgeBase) return
  try {
    const result = await ElMessageBox.prompt('输入上游文档 externalId', '查询文档 lineage', {
      confirmButtonText: '查询', cancelButtonText: '取消', inputPattern: /\S+/,
      inputErrorMessage: 'externalId 不能为空',
    })
    lineageExternalId.value = result.value
    lineageVisible.value = true
    lineageLoading.value = true
    try {
      lineage.value = await fetchKnowledgeDocumentLineage(
        props.knowledgeBase.id, source.id, result.value,
      )
    } finally {
      lineageLoading.value = false
    }
  } catch (action) {
    if (action !== 'cancel' && action !== 'close') throw action
  }
}

function shortHash(hash: string | null) {
  return hash ? `${hash.slice(0, 12)}…` : '-'
}
</script>

<template>
  <el-drawer :model-value="modelValue" size="86%" destroy-on-close @close="close">
    <template #header>
      <div>
        <strong>KnowledgeOps · {{ knowledgeBase?.kbName }}</strong>
        <div class="header-meta">
          当前版本 v{{ knowledgeBase?.latestVersionNo ?? 0 }} / #{{ knowledgeBase?.currentVersionId ?? '-' }}
        </div>
      </div>
    </template>

    <div v-loading="loading">
      <div class="section-title">
        <span>文档源</span>
        <el-button v-permission="'knowledge-base:edit'" class="cw-final-action" type="primary" @click="openCreateSource">新增文档源</el-button>
      </div>
      <el-table :data="sources" border>
        <el-table-column label="文档源" min-width="180">
          <template #default="{ row }">
            <div>{{ row.sourceName }}</div>
            <code>{{ row.sourceCode }}</code>
          </template>
        </el-table-column>
        <el-table-column label="checkpoint" min-width="150" show-overflow-tooltip>
          <template #default="{ row }"><code>{{ row.currentCheckpoint ?? '-' }}</code></template>
        </el-table-column>
        <el-table-column label="新鲜度" width="110">
          <template #default="{ row }">
            <el-tooltip :content="row.freshnessDeadline ? `SLA 截止：${row.freshnessDeadline}` : '尚未成功同步'">
              <el-tag :type="freshnessType[row.freshnessStatus] ?? 'info'">
                {{ freshnessLabel[row.freshnessStatus] ?? row.freshnessStatus }}
              </el-tag>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="质量" width="130">
          <template #default="{ row }">
            <el-tag :type="row.qualityStatus === 'PASSED' ? 'success' : row.qualityStatus === 'FAILED' ? 'danger' : 'info'">
              {{ qualityLabel[row.qualityStatus] }} {{ row.qualityScore == null ? '' : Number(row.qualityScore).toFixed(4) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="activeDocumentCount" label="有效文档" width="90" />
        <el-table-column label="最近同步" min-width="180">
          <template #default="{ row }">
            <el-tag v-if="row.lastSyncStatus" :type="syncType[row.lastSyncStatus] ?? 'info'">{{ row.lastSyncStatus }}</el-tag>
            <span v-else>-</span>
            <div class="subtle">{{ row.lastSyncAt ?? '' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="80">
          <template #default="{ row }"><el-tag :type="row.status === 1 ? 'success' : 'info'">{{ row.status === 1 ? '启用' : '停用' }}</el-tag></template>
        </el-table-column>
        <el-table-column label="操作" width="280" fixed="right">
          <template #default="{ row }">
            <el-button v-permission="'knowledge-base:source-sync'" link type="primary" @click="openSync(row)">同步</el-button>
            <el-button link type="primary" @click="openRuns(row)">运行记录</el-button>
            <el-button link type="primary" @click="openLineage(row)">lineage</el-button>
            <el-button v-permission="'knowledge-base:edit'" link type="primary" @click="openEditSource(row)">编辑</el-button>
            <el-button v-permission="'knowledge-base:delete'" link type="danger" @click="removeSource(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="sources.length === 0" description="暂无托管文档源；该知识库仍可按冻结版本访问外部 RAG" />

      <div class="section-title versions-title"><span>不可变版本</span></div>
      <el-table :data="versions" border max-height="320">
        <el-table-column prop="versionNo" label="版本" width="80">
          <template #default="{ row }">v{{ row.versionNo }}</template>
        </el-table-column>
        <el-table-column prop="id" label="版本 ID" width="100" />
        <el-table-column prop="checkpoint" label="checkpoint" min-width="150" show-overflow-tooltip />
        <el-table-column prop="documentCount" label="文档数" width="90" />
        <el-table-column label="质量" width="120">
          <template #default="{ row }">{{ row.qualityStatus }} / {{ Number(row.qualityScore).toFixed(4) }}</template>
        </el-table-column>
        <el-table-column label="snapshotHash" width="150">
          <template #default="{ row }"><el-tooltip :content="row.snapshotHash"><code>{{ shortHash(row.snapshotHash) }}</code></el-tooltip></template>
        </el-table-column>
        <el-table-column prop="changeNote" label="变更说明" min-width="180" />
        <el-table-column prop="createTime" label="创建时间" width="180" />
      </el-table>
    </div>

    <el-dialog v-model="sourceDialogVisible" :title="sourceDialogMode === 'create' ? '新增文档源' : '编辑文档源'" width="620px" append-to-body>
      <el-form ref="sourceFormRef" :model="sourceForm" label-width="130px">
        <el-form-item label="sourceCode" prop="sourceCode" :rules="[{ required: true, message: '请输入 sourceCode' }, { pattern: /^[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}$/, message: '格式不合法' }]">
          <el-input v-model="sourceForm.sourceCode" :disabled="sourceDialogMode === 'edit'" />
        </el-form-item>
        <el-form-item label="名称" prop="sourceName" :rules="[{ required: true, message: '请输入名称' }]">
          <el-input v-model="sourceForm.sourceName" maxlength="128" />
        </el-form-item>
        <el-form-item label="新鲜度 SLA">
          <el-input-number v-model="sourceForm.freshnessSlaMinutes" :min="1" :max="525600" /> 分钟
        </el-form-item>
        <el-form-item label="质量门槛">
          <el-input-number v-model="sourceForm.qualityThreshold" :min="0" :max="1" :step="0.05" :precision="4" />
        </el-form-item>
        <el-form-item label="默认 ACL">
          <el-radio-group v-model="sourceForm.aclMode"><el-radio value="PUBLIC">公开</el-radio><el-radio value="RESTRICTED">受限</el-radio></el-radio-group>
        </el-form-item>
        <template v-if="sourceForm.aclMode === 'RESTRICTED'">
          <el-form-item label="主体类型">
            <el-checkbox-group v-model="sourceForm.allowedSubjectTypes">
              <el-checkbox v-for="type in ['USER', 'ADMIN_USER', 'IP', 'API_KEY']" :key="type" :value="type">{{ type }}</el-checkbox>
            </el-checkbox-group>
          </el-form-item>
          <el-form-item label="主体 ID"><el-input v-model="sourceForm.allowedSubjectIdsText" placeholder="逗号分隔；为空表示不限制此维度" /></el-form-item>
          <el-form-item label="渠道"><el-input v-model="sourceForm.allowedChannelsText" placeholder="如 user-http,user-ws；逗号分隔" /></el-form-item>
        </template>
        <el-form-item label="状态"><el-switch v-model="sourceForm.status" :active-value="1" :inactive-value="0" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="sourceDialogVisible = false">取消</el-button><el-button class="cw-final-action" type="primary" @click="saveSource">保存文档源</el-button></template>
    </el-dialog>

    <el-dialog v-model="syncDialogVisible" title="提交文档同步批次" width="820px" append-to-body>
      <el-alert type="info" :closable="false" show-icon>
        checkpoint 采用 CAS：expectedCheckpoint 必须等于服务端当前值，成功后才推进。全量快照只传 UPSERT，缺失文档自动生成 DELETE 修订。
      </el-alert>
      <el-input v-model="syncPayload" type="textarea" :rows="20" class="json-editor" />
      <template #footer><el-button @click="syncDialogVisible = false">取消</el-button><el-button class="cw-final-action" type="primary" :loading="syncing" @click="submitSync">提交同步</el-button></template>
    </el-dialog>

    <el-dialog v-model="runsVisible" title="同步运行记录（最近 100 次）" width="1100px" append-to-body>
      <el-table v-loading="runsLoading" :data="runs" border>
        <el-table-column prop="requestId" label="requestId" min-width="160" show-overflow-tooltip />
        <el-table-column prop="syncMode" label="模式" width="100" />
        <el-table-column label="状态" width="130"><template #default="{ row }"><el-tag :type="syncType[row.status] ?? 'info'">{{ row.status }}</el-tag></template></el-table-column>
        <el-table-column label="变更" width="180"><template #default="{ row }">+{{ row.upsertedCount ?? '-' }} / -{{ row.deletedCount ?? '-' }} / ={{ row.unchangedCount ?? '-' }}</template></el-table-column>
        <el-table-column prop="qualityScore" label="质量" width="90" />
        <el-table-column prop="knowledgeBaseVersionId" label="版本 ID" width="100" />
        <el-table-column prop="errorMessage" label="错误" min-width="180" show-overflow-tooltip />
        <el-table-column prop="startedAt" label="开始时间" width="180" />
      </el-table>
    </el-dialog>

    <el-dialog v-model="lineageVisible" :title="`文档 lineage · ${lineageExternalId}`" width="1100px" append-to-body>
      <el-table v-loading="lineageLoading" :data="lineage" border>
        <el-table-column prop="id" label="修订 ID" width="90" />
        <el-table-column prop="parentRevisionId" label="父修订" width="90" />
        <el-table-column prop="operation" label="操作" width="90" />
        <el-table-column prop="sourceVersion" label="上游版本" width="120" />
        <el-table-column prop="title" label="标题" min-width="160" show-overflow-tooltip />
        <el-table-column prop="aclMode" label="ACL" width="110" />
        <el-table-column label="contentHash" width="150"><template #default="{ row }"><el-tooltip :content="row.contentHash ?? ''"><code>{{ shortHash(row.contentHash) }}</code></el-tooltip></template></el-table-column>
        <el-table-column prop="createTime" label="创建时间" width="180" />
      </el-table>
    </el-dialog>
  </el-drawer>
</template>

<style scoped>
.header-meta, .subtle { margin-top: 4px; color: var(--el-text-color-secondary); font-size: 12px; }
.section-title { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; font-size: 16px; font-weight: 600; }
.versions-title { margin-top: 28px; }
code { font-family: 'JetBrains Mono', 'Fira Code', monospace; font-size: 12px; }
.json-editor { margin-top: 12px; font-family: 'JetBrains Mono', 'Fira Code', monospace; }
</style>
