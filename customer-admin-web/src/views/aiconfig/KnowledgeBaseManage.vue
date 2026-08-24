<script setup lang="ts">
import { onMounted, ref } from 'vue'
import type { FormInstance } from 'element-plus'
import {
  createKnowledgeBase,
  deleteKnowledgeBase,
  pageKnowledgeBases,
  testKnowledgeBaseConnectivity,
  updateKnowledgeBase,
  updateKnowledgeBaseStatus,
} from '@/api/knowledgeBase'
import { useCrudPage } from '@/composables/useCrudPage'
import type { KnowledgeBaseSaveRequest, KnowledgeBaseVO, PageQuery } from '@/types/api'
import KnowledgeSourceDrawer from './components/KnowledgeSourceDrawer.vue'

const testingId = ref<number | null>(null)
// 保存会触发后端同步实测连通性（可达数秒），单独维护提交中状态给保存按钮加 loading，
// 不进 useCrudPage（该动作是本页特有的耗时语义，composable 只收敛通用 CRUD 状态机）。
const submitting = ref(false)
const formRef = ref<FormInstance>()
const knowledgeOpsVisible = ref(false)
const knowledgeOpsRow = ref<KnowledgeBaseVO | null>(null)

const {
  loading, list, total, query,
  dialogVisible, dialogMode, form,
  loadList, handleSearch, openCreate, openEdit, handleSubmit, handleDelete,
} = useCrudPage<KnowledgeBaseVO, PageQuery, KnowledgeBaseSaveRequest>({
  page: pageKnowledgeBases,
  formRef,
  create: createKnowledgeBase,
  update: updateKnowledgeBase,
  remove: (row) => deleteKnowledgeBase(row.id),
  initQuery: () => ({ pageNum: 1, pageSize: 10, keyword: '' }),
  initForm: () => ({
    kbName: '', baseUrl: '', appId: '', apiKey: '', contentType: 'application/json',
    extraHeaders: '', topN: 5, scoreThreshold: 0.15, status: 1, remark: '',
  }),
  // 编辑回填时 apiKey 置空表示"留空则不修改"，与模型配置 apiKey 完全同款语义
  toForm: (row) => ({
    kbName: row.kbName, baseUrl: row.baseUrl, appId: row.appId ?? '', apiKey: '',
    contentType: row.contentType || 'application/json', extraHeaders: row.extraHeaders ?? '',
    topN: row.topN, scoreThreshold: row.scoreThreshold, status: row.status, remark: row.remark ?? '',
  }),
  beforeSubmit: (mode, f) => {
    if (mode === 'create' && !f.apiKey) {
      ElMessage.warning('新建知识库必须填写 AppKey')
      return false
    }
    return true
  },
  deleteConfirm: (row) => `确认删除知识库「${row.kbName}」？`,
})

const testStatusMap: Record<number, { label: string; type: 'info' | 'success' | 'danger' }> = {
  0: { label: '未测试', type: 'info' },
  1: { label: '连通成功', type: 'success' },
  2: { label: '连通失败', type: 'danger' },
}

/** 自定义 Header 是 JSON 字符串，默认为空；非空时必须是合法 JSON，否则阻止提交。 */
function validateExtraHeadersJson(_rule: unknown, value: string, callback: (error?: Error) => void) {
  if (!value) {
    callback()
    return
  }
  try {
    JSON.parse(value)
    callback()
  } catch {
    callback(new Error('自定义 Header 必须是合法 JSON'))
  }
}

async function handleSubmitWithLoading() {
  submitting.value = true
  try {
    await handleSubmit()
  } finally {
    submitting.value = false
  }
}

async function handleTest(row: KnowledgeBaseVO) {
  testingId.value = row.id
  try {
    const result = await testKnowledgeBaseConnectivity(row.id)
    if (result.testStatus === 1) {
      ElMessage.success(`连通性测试成功，召回 ${result.hitCount ?? 0} 条`)
    } else {
      ElMessage.error(result.message || '连通性测试失败')
    }
    await loadList()
  } finally {
    testingId.value = null
  }
}

// 状态开关直接调专用状态接口，不走整条更新（与"启用/停用"作为独立操作的语义一致）
async function handleToggleStatus(row: KnowledgeBaseVO) {
  const nextStatus = row.status === 1 ? 0 : 1
  await updateKnowledgeBaseStatus(row.id, nextStatus)
  ElMessage.success(nextStatus === 1 ? '已启用' : '已停用')
  await loadList()
}

function openKnowledgeOps(row: KnowledgeBaseVO) {
  knowledgeOpsRow.value = row
  knowledgeOpsVisible.value = true
}

onMounted(loadList)
</script>

<template>
  <div class="page">
    <el-card>
      <div class="toolbar">
        <el-input v-model="query.keyword" placeholder="按知识库名称搜索" style="width: 220px" clearable @keyup.enter="handleSearch" />
        <el-button type="primary" @click="handleSearch">搜索</el-button>
        <el-button v-permission="'knowledge-base:add'" type="primary" @click="openCreate">新建知识库</el-button>
      </div>

      <el-table v-loading="loading" :data="list" style="width: 100%">
        <el-table-column prop="kbName" label="知识库名称" />
        <el-table-column prop="baseUrl" label="服务地址" show-overflow-tooltip />
        <el-table-column prop="appId" label="app_id" width="140" />
        <el-table-column label="appkey" width="140">
          <template #default="{ row }">{{ row.apiKeyMasked }}</template>
        </el-table-column>
        <el-table-column prop="topN" label="top_n" width="80" />
        <el-table-column label="版本" width="100">
          <template #default="{ row }">
            <el-tooltip :content="`不可变版本 ID：${row.currentVersionId ?? '-'}`">
              <el-tag type="primary">v{{ row.latestVersionNo ?? 0 }}</el-tag>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'">{{ row.status === 1 ? '启用' : '停用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="连通性" width="140">
          <template #default="{ row }">
            <el-tooltip :content="row.testTime ? `测试时间：${row.testTime}` : '尚未测试'" placement="top">
              <el-tag :type="testStatusMap[row.testStatus]?.type ?? 'info'">{{ testStatusMap[row.testStatus]?.label ?? '未测试' }}</el-tag>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column prop="remark" label="备注" show-overflow-tooltip />
        <el-table-column label="操作" width="350" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openKnowledgeOps(row)">KnowledgeOps</el-button>
            <el-button link type="primary" :loading="testingId === row.id" @click="handleTest(row)">测试连通性</el-button>
            <el-button v-permission="'knowledge-base:edit'" link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button v-permission="'knowledge-base:edit'" link type="primary" @click="handleToggleStatus(row)">
              {{ row.status === 1 ? '停用' : '启用' }}
            </el-button>
            <el-button v-permission="'knowledge-base:delete'" link type="danger" @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-model:current-page="query.pageNum"
        v-model:page-size="query.pageSize"
        :total="total"
        layout="total, prev, pager, next"
        style="margin-top: 16px; justify-content: flex-end"
        @current-change="loadList"
      />
    </el-card>

    <el-dialog v-model="dialogVisible" :title="dialogMode === 'create' ? '新建知识库' : '编辑知识库'" width="560px">
      <el-form ref="formRef" :model="form" label-width="110px">
        <el-form-item label="知识库名称" prop="kbName" :rules="[{ required: true, message: '请输入知识库名称' }]">
          <el-input v-model="form.kbName" />
        </el-form-item>
        <el-form-item label="服务地址" prop="baseUrl" :rules="[{ required: true, message: '请输入服务地址' }]">
          <el-input v-model="form.baseUrl" placeholder="如 https://rag.example.com" />
        </el-form-item>
        <el-form-item label="app_id" prop="appId">
          <el-input v-model="form.appId!" />
        </el-form-item>
        <el-form-item label="AppKey">
          <el-input v-model="form.apiKey!" type="password" show-password :placeholder="dialogMode === 'edit' ? '留空则不修改' : '必填'" />
        </el-form-item>
        <el-form-item label="Content-Type" prop="contentType">
          <el-input v-model="form.contentType!" placeholder="默认 application/json" />
        </el-form-item>
        <el-form-item label="自定义 Header" prop="extraHeaders" :rules="[{ validator: validateExtraHeadersJson }]">
          <el-input
            v-model="form.extraHeaders!"
            type="textarea"
            :rows="3"
            placeholder='JSON 格式，如 {"X-Tenant": "abc"}，可留空'
          />
        </el-form-item>
        <el-form-item label="top_n" prop="topN">
          <el-input-number v-model="form.topN!" :min="1" :max="50" style="width: 100%" />
        </el-form-item>
        <el-form-item label="score 阈值" prop="scoreThreshold">
          <el-input-number v-model="form.scoreThreshold!" :min="0" :max="1" :step="0.01" :precision="4" style="width: 100%" />
          <div class="score-hint">该 RAG 服务 score 尺度较小（常见 0.13~0.19 区间），低于此分数的召回结果不会注入对话</div>
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="form.status" :active-value="1" :inactive-value="0" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark!" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleSubmitWithLoading">确定</el-button>
      </template>
    </el-dialog>

    <KnowledgeSourceDrawer
      v-model="knowledgeOpsVisible"
      :knowledge-base="knowledgeOpsRow"
      @changed="loadList"
    />
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  gap: 8px;
  margin-bottom: 16px;
}

.score-hint {
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
