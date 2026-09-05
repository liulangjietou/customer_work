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
import CrudLoadState from '@/components/CrudLoadState.vue'
import type { KnowledgeBaseSaveRequest, KnowledgeBaseVO, PageQuery } from '@/types/api'
import KnowledgeSourceDrawer from './components/KnowledgeSourceDrawer.vue'

const testingId = ref<number | null>(null)
const formRef = ref<FormInstance>()
const knowledgeOpsVisible = ref(false)
const knowledgeOpsRow = ref<KnowledgeBaseVO | null>(null)

const {
  loading,
  loadError,
  submitting,
  deletingId,
  list,
  total,
  query,
  dialogVisible,
  dialogMode,
  form,
  loadList,
  handleSearch,
  openCreate,
  openEdit,
  handleSubmit,
  handleDelete,
} = useCrudPage<KnowledgeBaseVO, PageQuery, KnowledgeBaseSaveRequest>({
  page: pageKnowledgeBases,
  formRef,
  create: createKnowledgeBase,
  update: updateKnowledgeBase,
  remove: (row) => deleteKnowledgeBase(row.id),
  initQuery: () => ({ pageNum: 1, pageSize: 10, keyword: '' }),
  initForm: () => ({
    kbName: '',
    baseUrl: '',
    appId: '',
    apiKey: '',
    contentType: 'application/json',
    extraHeaders: '',
    topN: 5,
    scoreThreshold: 0.15,
    status: 1,
    remark: '',
  }),
  // 编辑回填时 apiKey 置空表示"留空则不修改"，与模型配置 apiKey 完全同款语义
  toForm: (row) => ({
    kbName: row.kbName,
    baseUrl: row.baseUrl,
    appId: row.appId ?? '',
    apiKey: '',
    contentType: row.contentType || 'application/json',
    extraHeaders: row.extraHeaders ?? '',
    topN: row.topN,
    scoreThreshold: row.scoreThreshold,
    status: row.status,
    remark: row.remark ?? '',
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
function validateExtraHeadersJson(
  _rule: unknown,
  value: string,
  callback: (error?: Error) => void,
) {
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
    <CrudLoadState
      :error="loadError"
      :has-stale-data="list.length > 0"
      :loading="loading"
      @retry="loadList"
    />
    <el-card>
      <div class="toolbar">
        <el-input
          v-model="query.keyword"
          placeholder="按知识库名称搜索"
          style="width: 220px"
          clearable
          @keyup.enter="handleSearch"
        />
        <el-button type="primary" @click="handleSearch">搜索</el-button>
        <div class="toolbar-actions">
          <el-button
            v-permission="'knowledge-base:add'"
            class="cw-final-action"
            type="primary"
            @click="openCreate"
            >新建知识库</el-button
          >
        </div>
      </div>

      <div v-loading="loading" class="knowledge-grid" aria-label="知识库列表">
        <article v-for="row in list" :key="row.id" class="knowledge-card">
          <header>
            <span class="knowledge-icon"
              ><el-icon><Collection /></el-icon></span
            ><el-tag :type="row.status === 1 ? 'success' : 'info'">{{
              row.status === 1 ? '已启用' : '已停用'
            }}</el-tag>
          </header>
          <h2>{{ row.kbName }}</h2>
          <p class="knowledge-remark">
            {{ row.remark || '连接企业知识，为智能体提供任务上下文。' }}
          </p>
          <dl>
            <div>
              <dt>当前版本</dt>
              <dd>
                <el-tooltip :content="`不可变版本 ID：${row.currentVersionId ?? '—'}`"
                  ><span>v{{ row.latestVersionNo ?? 0 }}</span></el-tooltip
                >
              </dd>
            </div>
            <div>
              <dt>连接状态</dt>
              <dd>
                <el-tooltip :content="row.testTime ? `测试时间：${row.testTime}` : '尚未测试'"
                  ><el-tag :type="testStatusMap[row.testStatus]?.type ?? 'info'">{{
                    testStatusMap[row.testStatus]?.label ?? '未测试'
                  }}</el-tag></el-tooltip
                >
              </dd>
            </div>
          </dl>
          <details class="knowledge-connection">
            <summary>连接配置</summary>
            <dl>
              <div>
                <dt>服务地址</dt>
                <dd>{{ row.baseUrl }}</dd>
              </div>
              <div>
                <dt>应用标识</dt>
                <dd>{{ row.appId }}</dd>
              </div>
              <div>
                <dt>访问密钥</dt>
                <dd>{{ row.apiKeyMasked }}</dd>
              </div>
              <div>
                <dt>检索条数</dt>
                <dd>{{ row.topN }}</dd>
              </div>
            </dl>
          </details>
          <footer>
            <el-button link type="primary" @click="openKnowledgeOps(row)"
              >管理知识源 <el-icon><ArrowRight /></el-icon
            ></el-button>
            <div>
              <el-button v-permission="'knowledge-base:edit'" link @click="openEdit(row)"
                >配置</el-button
              ><el-dropdown trigger="click"
                ><el-button text :aria-label="`${row.kbName}的更多操作`"
                  ><el-icon><MoreFilled /></el-icon></el-button
                ><template #dropdown
                  ><el-dropdown-menu
                    ><el-dropdown-item :disabled="testingId === row.id" @click="handleTest(row)"
                      >测试连通性</el-dropdown-item
                    ><el-dropdown-item
                      v-permission="'knowledge-base:edit'"
                      @click="handleToggleStatus(row)"
                      >{{ row.status === 1 ? '停用' : '启用' }}</el-dropdown-item
                    ><el-dropdown-item
                      v-permission="'knowledge-base:delete'"
                      :disabled="deletingId === row.id"
                      divided
                      @click="handleDelete(row)"
                      >删除知识库</el-dropdown-item
                    ></el-dropdown-menu
                  ></template
                ></el-dropdown
              >
            </div>
          </footer>
        </article>
      </div>
      <el-empty
        v-if="!loading && list.length === 0"
        description="暂无符合条件的知识库"
        :image-size="72"
      />

      <el-pagination
        v-model:current-page="query.pageNum"
        v-model:page-size="query.pageSize"
        :total="total"
        layout="total, prev, pager, next"
        class="pagination"
        @current-change="loadList"
      />
    </el-card>

    <el-dialog
      v-model="dialogVisible"
      :title="dialogMode === 'create' ? '新建知识库' : '编辑知识库'"
      width="560px"
    >
      <el-form ref="formRef" :model="form" label-width="110px">
        <el-form-item
          label="知识库名称"
          prop="kbName"
          :rules="[{ required: true, message: '请输入知识库名称' }]"
        >
          <el-input v-model="form.kbName" />
        </el-form-item>
        <el-form-item
          label="服务地址"
          prop="baseUrl"
          :rules="[{ required: true, message: '请输入服务地址' }]"
        >
          <el-input v-model="form.baseUrl" placeholder="如 https://rag.example.com" />
        </el-form-item>
        <el-form-item label="app_id" prop="appId">
          <el-input v-model="form.appId!" />
        </el-form-item>
        <el-form-item label="AppKey">
          <el-input
            v-model="form.apiKey!"
            type="password"
            show-password
            :placeholder="dialogMode === 'edit' ? '留空则不修改' : '必填'"
          />
        </el-form-item>
        <el-form-item label="Content-Type" prop="contentType">
          <el-input v-model="form.contentType!" placeholder="默认 application/json" />
        </el-form-item>
        <el-form-item
          label="自定义 Header"
          prop="extraHeaders"
          :rules="[{ validator: validateExtraHeadersJson }]"
        >
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
          <el-input-number
            v-model="form.scoreThreshold!"
            :min="0"
            :max="1"
            :step="0.01"
            :precision="4"
            style="width: 100%"
          />
          <div class="score-hint">
            该 RAG 服务 score 尺度较小（常见 0.13~0.19 区间），低于此分数的召回结果不会注入对话
          </div>
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
        <el-button
          class="cw-final-action"
          type="primary"
          :loading="submitting"
          @click="handleSubmit"
          >保存知识库</el-button
        >
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
.knowledge-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 18px;
  min-height: 1px;
}
.knowledge-card {
  padding: 20px;
  border: 1px solid var(--cw-line);
  border-radius: 10px;
  min-width: 0;
  background: var(--cw-paper);
}
.knowledge-card header,
.knowledge-card footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 10px;
}
.knowledge-icon {
  display: inline-grid;
  place-items: center;
  width: 38px;
  height: 38px;
  background: var(--cw-canvas);
  color: var(--cw-cobalt);
  border-radius: 9px;
  font-size: 20px;
}
.knowledge-card h2 {
  font-size: 16px;
  margin: 20px 0 10px;
  font-weight: 600;
  overflow-wrap: anywhere;
}
.knowledge-remark {
  color: var(--cw-text-muted);
  font-size: 12px;
  line-height: 1.7;
  min-height: 42px;
  overflow-wrap: anywhere;
}
.knowledge-card dl {
  display: grid;
  gap: 14px;
  margin: 20px 0;
}
.knowledge-card dl > div {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  font-size: 12px;
}
.knowledge-card dt {
  flex-shrink: 0;
  color: var(--cw-text-muted);
}
.knowledge-card dd {
  margin: 0;
  text-align: right;
  overflow-wrap: anywhere;
}
.knowledge-connection summary {
  font-size: 12px;
  color: var(--cw-text-muted);
  cursor: pointer;
  padding: 8px 0;
}
.knowledge-card footer {
  border-top: 1px solid var(--cw-line);
  padding-top: 12px;
  margin-top: 12px;
}
.knowledge-card footer > div {
  display: flex;
  align-items: center;
}
@media (max-width: 1300px) {
  .knowledge-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
@media (max-width: 760px) {
  .knowledge-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}

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

@media (max-width: 767px) {
  .toolbar-actions {
    margin-left: 0;
  }

  .toolbar-actions > .el-button {
    flex: 1 1 auto;
    margin-left: 0;
  }
}

.score-hint {
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
