<script setup lang="ts">
import { computed, onScopeDispose, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/store/auth'
import { getRequestErrorMessage } from '@/api/request'
import CrudLoadState from '@/components/CrudLoadState.vue'
import KnowledgeGapReviewDrawer from './KnowledgeGapReviewDrawer.vue'
import { GAP_CATEGORY_LABELS, GAP_VIEW_OPTIONS } from './knowledgeGapPresentation'
import type { KnowledgeGapView } from '@/api/ops'
import type { FormInstance, FormRules } from 'element-plus'
import ImprovementClosurePanel from '@/components/ImprovementClosurePanel.vue'
import {
  fillKnowledgeGap,
  listKnowledgeGaps,
  type FillKnowledgeGapRequest,
  type KnowledgeGap,
} from '@/api/ops'

const auth = useAuthStore()
const loading = ref(false)
const hasLoaded = ref(false)
const loadError = ref<unknown>(null)
const list = ref<KnowledgeGap[]>([])
const search = ref('')
const view = ref<KnowledgeGapView>('WORK')
const reviewHash = ref<string | null>(null)
const visibleList = computed(() =>
  list.value.filter(
    (gap) =>
      !search.value.trim() ||
      gap.question.toLocaleLowerCase().includes(search.value.trim().toLocaleLowerCase()),
  ),
)
const closureVisible = ref(false)
const closureGap = ref<KnowledgeGap | null>(null)
const totalMisses = computed(() => list.value.reduce((sum, item) => sum + item.missCount, 0))
const urgentGapCount = computed(() => list.value.filter((item) => item.missCount >= 10).length)
const recurringGapCount = computed(() => list.value.filter((item) => item.missCount >= 3).length)
let listRequest = 0
let editorGeneration = 0

/** 当前租户由已认证入口解析；刷新失败保留旧结果并明确标记。 */
async function loadList() {
  if (!auth.hasPermission('knowledge-gap:view')) return
  const requestId = ++listRequest
  loading.value = true
  try {
    const result = await listKnowledgeGaps(undefined, 50, view.value)
    if (requestId !== listRequest) return
    list.value = result
    hasLoaded.value = true
    loadError.value = null
  } catch (error) {
    if (requestId === listRequest) loadError.value = error
  } finally {
    if (requestId === listRequest) loading.value = false
  }
}

function formatTime(ms: number): string {
  return ms ? new Date(ms).toLocaleString('zh-CN', { hour12: false }) : '-'
}

/** 色阶只表达出现频次，处理优先级由人工复核确定。 */
function missTagType(count: number): 'danger' | 'warning' | 'info' {
  if (count >= 10) return 'danger'
  if (count >= 3) return 'warning'
  return 'info'
}

// ---------- 一键补知识 ----------

const dialogVisible = ref(false)
const submitting = ref(false)
const fillError = ref('')
const currentGap = ref<KnowledgeGap | null>(null)
const formRef = ref<FormInstance>()
const form = reactive<FillKnowledgeGapRequest>({
  questionHash: '',
  title: '',
  content: '',
  keyword: '',
})

const rules: FormRules = {
  title: [{ required: true, message: '请填写条目标题', trigger: 'blur' }],
  content: [{ required: true, message: '请填写条目内容', trigger: 'blur' }],
  keyword: [{ required: true, message: '请填写命中关键词', trigger: 'blur' }],
}

function changeView() {
  listRequest += 1
  list.value = []
  hasLoaded.value = false
  loadError.value = null
  void loadList()
}

function openReview(row: KnowledgeGap) {
  if (!auth.hasPermission('knowledge-gap:view') || loading.value || loadError.value) return
  reviewHash.value = row.questionHash
}

function openFill(row: KnowledgeGap) {
  if (!auth.hasPermission('knowledge-gap:fill') || loading.value || loadError.value) return
  editorGeneration += 1
  fillError.value = ''
  submitting.value = false
  currentGap.value = row
  form.questionHash = row.questionHash
  form.title = ''
  form.content = ''
  // 关键词预填原问题，运营在此基础上改比从空白写快
  form.keyword = row.question
  dialogVisible.value = true
  formRef.value?.clearValidate()
}

function openClosure(row: KnowledgeGap) {
  if (!auth.hasPermission('improvement:manage') || loading.value || loadError.value) return
  closureGap.value = row
  closureVisible.value = true
}

async function submitFill() {
  if (!formRef.value || submitting.value || !auth.hasPermission('knowledge-gap:fill')) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  const generation = editorGeneration
  submitting.value = true
  fillError.value = ''
  try {
    const knowledgeId = await fillKnowledgeGap({ ...form })
    if (generation !== editorGeneration) return
    ElMessage.success(`已写入 FAQ（条目 #${knowledgeId}），检索效果待验证`)
    dialogVisible.value = false
  } catch (error) {
    if (generation === editorGeneration) {
      fillError.value = getRequestErrorMessage(error, '写入结果尚未核实，请核对记录后再操作')
    }
  } finally {
    if (generation === editorGeneration) submitting.value = false
  }
}

watch(
  () => auth.token,
  () => {
    listRequest += 1
    editorGeneration += 1
    list.value = []
    hasLoaded.value = false
    loadError.value = null
    closureVisible.value = false
    closureGap.value = null
    reviewHash.value = null
    dialogVisible.value = false
    if (auth.token) void loadList()
  },
  { immediate: true, flush: 'sync' },
)
onScopeDispose(() => {
  listRequest += 1
  editorGeneration += 1
})
</script>

<template>
  <div class="knowledge-gap-board">
    <el-alert
      type="info"
      show-icon
      :closable="false"
      title="先核对原始问题，再决定处理方式"
      description="未命中表示本次没有检索到资料，不代表一定缺少知识。实时查询、工具异常和流程问题需要分别核对；低频问题也可进入人工复核。"
    />

    <el-card shadow="never" class="filter-card">
      <div class="toolbar">
        <el-input
          v-model="search"
          aria-label="搜索当前问题"
          placeholder="搜索当前问题"
          clearable
          class="gap-search"
        />
        <el-select v-model="view" aria-label="问题处理视图" class="gap-view" @change="changeView">
          <el-option
            v-for="option in GAP_VIEW_OPTIONS"
            :key="option.value"
            :label="option.label"
            :value="option.value"
          />
        </el-select>
        <span class="scope-note">当前租户 · 当前筛选前 50 条</span>
        <el-button type="primary" :loading="loading" @click="loadList">刷新</el-button>
      </div>
    </el-card>

    <CrudLoadState
      :error="loadError"
      :has-stale-data="hasLoaded"
      :loading="loading"
      @retry="loadList"
    />

    <div class="summary-row" :aria-busy="loading">
      <div class="stat">
        <strong>{{ hasLoaded ? list.length : '—' }}</strong>
        <span>当前问题数</span>
      </div>
      <div class="stat">
        <strong>{{ hasLoaded ? totalMisses : '—' }}</strong>
        <span>这些问题的未命中次数</span>
      </div>
      <div class="stat stat-danger">
        <strong>{{ hasLoaded ? urgentGapCount : '—' }}</strong>
        <span>高频信号（≥10 次）</span>
      </div>
      <div class="stat stat-warning">
        <strong>{{ hasLoaded ? recurringGapCount : '—' }}</strong>
        <span>反复出现（≥3 次）</span>
      </div>
    </div>

    <el-card shadow="never" class="list-card">
      <div class="section-heading">
        <strong>未命中证据明细</strong>
        <span>保留原始检索问题与频次，处理后通过治理闭环验证效果</span>
      </div>
      <el-table
        v-loading="loading"
        :data="visibleList"
        :empty-text="
          loading
            ? '正在读取…'
            : loadError
              ? '数据暂不可用'
              : search
                ? '没有匹配的问题'
                : '暂无未命中记录'
        "
        style="width: 100%"
      >
        <el-table-column label="未命中" width="100">
          <template #default="{ row }">
            <el-tag :type="missTagType(row.missCount)">{{ row.missCount }} 次</el-tag>
          </template>
        </el-table-column>
        <el-table-column
          prop="question"
          label="原始检索问题"
          min-width="220"
          show-overflow-tooltip
        />
        <el-table-column label="处理分类" width="170"
          ><template #default="{ row }">
            <el-tag :type="row.classification?.origin === 'MANUAL' ? 'success' : 'info'">{{
              GAP_CATEGORY_LABELS[
                (row.classification?.category as keyof typeof GAP_CATEGORY_LABELS) ?? 'PENDING'
              ]
            }}</el-tag>
            <span v-if="row.classification?.priority === 'HIGH'" class="high-priority"
              >高优先级</span
            >
            <small v-else-if="row.classification?.origin === 'RULE'" class="classification-origin"
              >规则建议</small
            >
          </template></el-table-column
        >
        <el-table-column label="首次出现" width="170">
          <template #default="{ row }">{{ formatTime(row.firstSeenAtMs) }}</template>
        </el-table-column>
        <el-table-column label="最近出现" width="170">
          <template #default="{ row }">{{ formatTime(row.lastSeenAtMs) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="250" fixed="right">
          <template #default="{ row }">
            <el-button
              link
              type="primary"
              :disabled="loading || !!loadError"
              @click="openReview(row)"
              >问题详情</el-button
            >
            <el-button
              v-permission="'knowledge-gap:fill'"
              link
              type="primary"
              :disabled="loading || !!loadError"
              @click="openFill(row)"
            >
              补充知识
            </el-button>
            <el-button
              v-permission="'improvement:manage'"
              link
              type="success"
              :disabled="loading || !!loadError"
              @click="openClosure(row)"
            >
              治理闭环
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <KnowledgeGapReviewDrawer
      v-if="reviewHash"
      :question-hash="reviewHash"
      @close="reviewHash = null"
      @saved="loadList"
    />

    <el-dialog
      v-model="dialogVisible"
      title="补充 FAQ 条目"
      width="620px"
      :close-on-click-modal="!submitting"
      :close-on-press-escape="!submitting"
      :show-close="!submitting"
    >
      <el-alert
        v-if="currentGap"
        class="origin"
        type="warning"
        show-icon
        :closable="false"
        :title="`该问题已记录 ${currentGap.missCount} 次未命中：${currentGap.question}`"
      />
      <el-alert
        type="warning"
        :closable="false"
        show-icon
        class="origin"
        title="保存会直接写入线上 FAQ"
        description="此入口沿用直接写入流程，尚未经过候选评测。写入后仍需验证检索效果，治理闭环不会自动标记完成。"
      />
      <el-alert v-if="fillError" :title="fillError" type="error" :closable="false" class="origin" />
      <el-form ref="formRef" :model="form" :rules="rules" label-width="88px">
        <el-form-item label="条目标题" prop="title">
          <el-input v-model="form.title" placeholder="如：货到付款支持范围" />
        </el-form-item>
        <el-form-item label="条目内容" prop="content">
          <el-input
            v-model="form.content"
            type="textarea"
            :rows="4"
            placeholder="这个问题该怎么答——写成知识的样子，不要照抄用户的口语化提问"
          />
        </el-form-item>
        <el-form-item label="关键词" prop="keyword">
          <el-input v-model="form.keyword" placeholder="逗号分隔，决定这条知识能否被检索到" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="submitting" @click="dialogVisible = false">取消</el-button>
        <el-button class="cw-final-action" type="primary" :loading="submitting" @click="submitFill"
          >确认写入线上 FAQ</el-button
        >
      </template>
    </el-dialog>

    <el-drawer
      v-model="closureVisible"
      title="知识缺口治理闭环"
      size="min(760px, 100vw)"
      destroy-on-close
    >
      <blockquote v-if="closureGap" class="gap-source-quote">
        <span>原始检索问题</span>
        <p>{{ closureGap.question }}</p>
      </blockquote>
      <ImprovementClosurePanel
        v-if="closureVisible && closureGap"
        source-type="KNOWLEDGE_GAP"
        :source-key="closureGap.questionHash"
      />
    </el-drawer>
  </div>
</template>

<style scoped>
.knowledge-gap-board {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 0;
  flex-wrap: wrap;
}

.gap-view {
  width: 180px;
}
.high-priority,
.classification-origin {
  display: block;
  font-size: 12px;
  margin-top: 5px;
  color: var(--cw-text-muted);
}
.high-priority {
  color: var(--cw-danger);
}

.gap-search {
  flex: 1 1 220px;
  max-width: 380px;
}

.scope-note {
  color: var(--cw-text-muted);
  font-size: 13px;
}

.filter-card .toolbar {
  padding: 0;
  border: 0;
  background: transparent;
  box-shadow: none;
}

.gap-source-quote {
  margin: 0 0 16px;
  border-left: 3px solid var(--el-color-primary);
  padding: 8px 12px;
  background: var(--cw-paper);
  overflow-wrap: anywhere;
}

.gap-source-quote span {
  color: var(--cw-text-muted);
  font-size: 12px;
}

.gap-source-quote p {
  margin: 6px 0 0;
  font-size: 16px;
  line-height: 1.6;
}

.origin {
  margin-bottom: 16px;
}

.summary-row {
  display: grid;
  grid-template-columns: repeat(4, minmax(150px, 1fr));
  gap: 12px;
}

.stat {
  min-height: 88px;
  padding: 15px 16px 14px;
  border: 1px solid var(--cw-line);
  border-radius: var(--cw-radius-md);
  background: var(--cw-paper);
  box-shadow: var(--cw-shadow-xs);
}

.stat strong {
  display: block;
  color: var(--cw-text);
  font-size: 25px;
  font-weight: 720;
  font-variant-numeric: tabular-nums;
  line-height: 1.2;
}

.stat span {
  display: block;
  margin-top: 7px;
  color: var(--cw-text-muted);
  font-size: 12px;
}

.stat-danger strong {
  color: var(--cw-danger);
}

.stat-warning strong {
  color: var(--cw-amber);
}

.section-heading {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 12px;
}

.section-heading strong {
  color: var(--cw-text);
  font-size: 14px;
  font-weight: 700;
}

.section-heading span {
  color: var(--cw-text-muted);
  font-size: 12px;
  text-align: right;
}

@media (max-width: 1023px) {
  .summary-row {
    grid-template-columns: repeat(2, minmax(150px, 1fr));
  }
}

@media (max-width: 767px) {
  .summary-row {
    grid-template-columns: 1fr 1fr;
  }

  .section-heading {
    align-items: flex-start;
    flex-direction: column;
  }

  .section-heading span {
    text-align: left;
  }

  .knowledge-gap-board :deep(.el-dialog .el-form-item__label) {
    width: 100% !important;
    justify-content: flex-start;
  }

  .knowledge-gap-board :deep(.el-dialog .el-form-item__content) {
    margin-left: 0 !important;
  }
}

@media (max-width: 480px) {
  .toolbar .el-button,
  .knowledge-gap-board :deep(.el-table .el-button) {
    min-height: 44px;
  }

  .summary-row {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
