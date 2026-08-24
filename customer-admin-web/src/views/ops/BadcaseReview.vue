<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import ImprovementClosurePanel from '@/components/ImprovementClosurePanel.vue'
import {
  adoptAsEvalCase,
  adoptAsKnowledge,
  ignoreBadcase,
  pageBadcases,
  type AdoptEvalCaseRequest,
  type AdoptKnowledgeRequest,
  type Badcase,
  type BadcaseSourceCode,
  type BadcaseStatusCode,
} from '@/api/badcase'

// badcase 回流：待筛队列 → 转知识库 / 转评测用例 / 忽略。
//
// 两个出口刻意并列而不是二选一：补知识让下次能答对（治本），加评测用例让下次答错能立刻被发现（防复发）。
// 一条值得处理的 badcase 通常两件事都该做，所以抽屉里两张表单同时可用、各自独立提交。

const SOURCE_LABELS: Record<BadcaseSourceCode, { text: string; type: 'danger' | 'warning'; hint: string }> = {
  NEGATIVE_FEEDBACK: {
    text: '用户点踩',
    type: 'danger',
    hint: '主观但真实的不满——哪怕回复在规则上挑不出毛病',
  },
  QUALITY_FAILURE: {
    text: '质检不过',
    type: 'warning',
    hint: '客观但机械的规则命中——可能只是话术不合规范，用户其实满意',
  },
}

const STATUS_LABELS: Record<BadcaseStatusCode, { text: string; type: 'info' | 'success' | 'primary' }> = {
  PENDING: { text: '待筛选', type: 'primary' },
  RESOLVED: { text: '已处理', type: 'success' },
  IGNORED: { text: '已忽略', type: 'info' },
}

// el-table 的插槽参数是 any，直接在模板里索引 Record 会丢类型；
// 收口成两个入参带类型的辅助函数，模板保持简洁的同时不牺牲类型检查
function sourceLabel(row: Badcase) {
  return SOURCE_LABELS[row.source]
}

function statusLabel(row: Badcase) {
  return STATUS_LABELS[row.status]
}

const loading = ref(false)
const list = ref<Badcase[]>([])
const total = ref(0)
const query = reactive({
  status: 'PENDING' as BadcaseStatusCode | undefined,
  source: undefined as BadcaseSourceCode | undefined,
  pageNum: 1,
  pageSize: 20,
})

async function loadList() {
  loading.value = true
  try {
    const data = await pageBadcases(query)
    list.value = data.list
    total.value = data.total
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  query.pageNum = 1
  return loadList()
}

function formatTime(ms: number): string {
  return ms ? new Date(ms).toLocaleString('zh-CN', { hour12: false }) : '-'
}

// ---------- 详情与回流 ----------

const drawerVisible = ref(false)
const current = ref<Badcase | null>(null)
const submitting = ref(false)

const knowledgeFormRef = ref<FormInstance>()
const knowledgeForm = reactive<AdoptKnowledgeRequest>({ title: '', content: '', keyword: '' })
const knowledgeRules: FormRules = {
  title: [{ required: true, message: '请填写条目标题', trigger: 'blur' }],
  content: [{ required: true, message: '请填写条目内容', trigger: 'blur' }],
  keyword: [{ required: true, message: '请填写命中关键词', trigger: 'blur' }],
}

const evalFormRef = ref<FormInstance>()
const evalForm = reactive<AdoptEvalCaseRequest>({
  caseId: '',
  evalType: 'INTENT',
  expected: '',
  category: '',
})
const evalRules: FormRules = {
  caseId: [{ required: true, message: '请填写用例编号', trigger: 'blur' }],
}

/** 期望值的填写说明随评测类型变化——两类评测的"期望"含义完全不同。 */
const expectedHint = computed(() =>
  evalForm.evalType === 'INTENT'
    ? '期望意图（refund/order/complaint/consult）；留空表示期望规则快车道不命中、应交 LLM'
    : '期望回复要点，供 Judge 打分时参考',
)

function openDrawer(row: Badcase) {
  current.value = row
  drawerVisible.value = true
  // 每次打开都重置：上一条的填写内容留在表单里，极易误提交到这一条上
  knowledgeFormRef.value?.resetFields()
  evalFormRef.value?.resetFields()
  knowledgeForm.title = ''
  knowledgeForm.content = ''
  knowledgeForm.keyword = ''
  evalForm.caseId = `bc-${row.id.slice(0, 8)}`
  evalForm.evalType = 'INTENT'
  evalForm.expected = ''
  evalForm.category = ''
}

async function submitKnowledge() {
  if (!current.value || !knowledgeFormRef.value) return
  await knowledgeFormRef.value.validate()
  submitting.value = true
  try {
    current.value = await adoptAsKnowledge(current.value.id, { ...knowledgeForm })
    ElMessage.success('已补进知识库，下次遇到同类问题就能答上来了')
    await loadList()
  } finally {
    submitting.value = false
  }
}

async function submitEvalCase() {
  if (!current.value || !evalFormRef.value) return
  await evalFormRef.value.validate()
  submitting.value = true
  try {
    current.value = await adoptAsEvalCase(current.value.id, { ...evalForm })
    ElMessage.success('已加入评测集，下次再答错会被评测立刻发现')
    await loadList()
  } finally {
    submitting.value = false
  }
}

async function handleIgnore(row: Badcase) {
  try {
    const { value: reason } = await ElMessageBox.prompt(
      '忽略后仍保留记录，只是不再出现在待筛队列里。',
      '忽略这条 badcase',
      {
        confirmButtonText: '忽略',
        cancelButtonText: '取消',
        inputPlaceholder: '原因（可选），如"用户误触""质检误报"',
      },
    )
    await ignoreBadcase(row.id, reason || undefined)
    ElMessage.success('已忽略')
    drawerVisible.value = false
    await loadList()
  } catch (error) {
    if (error !== 'cancel') {
      throw error
    }
  }
}

onMounted(loadList)
</script>

<template>
  <div class="badcase-review">
    <el-card shadow="never">
      <div class="toolbar">
        <el-select v-model="query.status" placeholder="全部状态" clearable style="width: 130px">
          <el-option label="待筛选" value="PENDING" />
          <el-option label="已处理" value="RESOLVED" />
          <el-option label="已忽略" value="IGNORED" />
        </el-select>
        <el-select v-model="query.source" placeholder="全部来源" clearable style="width: 140px">
          <el-option label="用户点踩" value="NEGATIVE_FEEDBACK" />
          <el-option label="质检不过" value="QUALITY_FAILURE" />
        </el-select>
        <el-button type="primary" @click="handleSearch">搜索</el-button>
        <span class="hint">
          筛选后可一键转知识库（治本）或转评测用例（防复发），两者不冲突
        </span>
      </div>

      <el-table v-loading="loading" :data="list" style="width: 100%">
        <el-table-column label="发生时间" width="170">
          <template #default="{ row }">{{ formatTime(row.createdAtMs) }}</template>
        </el-table-column>
        <el-table-column label="来源" width="100">
          <template #default="{ row }">
            <el-tag :type="sourceLabel(row).type">{{ sourceLabel(row).text }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="用户问" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.userInput">{{ row.userInput }}</span>
            <span v-else class="muted">（未开聊天留痕，无上下文）</span>
          </template>
        </el-table-column>
        <el-table-column label="AI 答" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.agentReply">{{ row.agentReply }}</span>
            <span v-else class="muted">-</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusLabel(row).type">{{ statusLabel(row).text }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="已回流" width="150">
          <template #default="{ row }">
            <el-tag v-if="row.adoptedKnowledgeId" size="small" type="success" effect="plain">知识库</el-tag>
            <el-tag v-if="row.adoptedEvalCaseId" size="small" type="success" effect="plain">评测用例</el-tag>
            <span v-if="!row.adoptedKnowledgeId && !row.adoptedEvalCaseId" class="muted">-</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDrawer(row)">筛选</el-button>
            <el-button
              v-permission="'badcase:adopt'"
              link
              type="info"
              :disabled="row.status === 'RESOLVED'"
              @click="handleIgnore(row)"
            >
              忽略
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-model:current-page="query.pageNum"
        v-model:page-size="query.pageSize"
        :total="total"
        :page-sizes="[10, 20, 50]"
        layout="total, sizes, prev, pager, next"
        class="pagination"
        @current-change="loadList"
        @size-change="handleSearch"
      />
    </el-card>

    <el-drawer v-model="drawerVisible" title="badcase 筛选与回流" size="640px">
      <div v-if="current">
        <el-descriptions :column="1" border>
          <el-descriptions-item label="来源">
            <el-tag :type="SOURCE_LABELS[current.source].type">
              {{ SOURCE_LABELS[current.source].text }}
            </el-tag>
            <span class="hint source-hint">{{ SOURCE_LABELS[current.source].hint }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="用户问">
            {{ current.userInput || '（未开聊天留痕，无上下文）' }}
          </el-descriptions-item>
          <el-descriptions-item label="AI 答">{{ current.agentReply || '-' }}</el-descriptions-item>
          <el-descriptions-item label="原始信号">{{ current.detail || '-' }}</el-descriptions-item>
          <el-descriptions-item label="会话">{{ current.sessionId || '-' }}</el-descriptions-item>
        </el-descriptions>

        <el-alert
          v-if="current.status === 'RESOLVED'"
          class="section"
          type="success"
          show-icon
          :closable="false"
          :title="`已由 ${current.handledBy || '—'} 于 ${formatTime(current.handledAtMs)} 处理`"
        />

        <div class="section">
          <div class="section-title">
            转知识库条目
            <span class="hint">治本：补上答错的那块知识</span>
          </div>
          <el-alert
            v-if="current.adoptedKnowledgeId"
            type="info"
            show-icon
            :closable="false"
            :title="`已补进知识库（条目 #${current.adoptedKnowledgeId}），不可重复采纳`"
          />
          <el-form
            v-else
            ref="knowledgeFormRef"
            :model="knowledgeForm"
            :rules="knowledgeRules"
            label-width="88px"
          >
            <el-form-item label="条目标题" prop="title">
              <el-input v-model="knowledgeForm.title" placeholder="如：发票开具规则" />
            </el-form-item>
            <el-form-item label="条目内容" prop="content">
              <el-input
                v-model="knowledgeForm.content"
                type="textarea"
                :rows="3"
                placeholder="这次该怎么答才对——写成知识的样子，不要照抄聊天记录"
              />
            </el-form-item>
            <el-form-item label="关键词" prop="keyword">
              <el-input v-model="knowledgeForm.keyword" placeholder="逗号分隔，决定这条知识能否被检索到" />
            </el-form-item>
            <el-form-item>
              <el-button
                v-permission="'badcase:adopt'"
                type="primary"
                :loading="submitting"
                @click="submitKnowledge"
              >
                补进知识库
              </el-button>
            </el-form-item>
          </el-form>
        </div>

        <div class="section">
          <div class="section-title">
            转评测用例
            <span class="hint">防复发：下次再答错会被评测立刻发现</span>
          </div>
          <el-alert
            v-if="current.adoptedEvalCaseId"
            type="info"
            show-icon
            :closable="false"
            :title="`已加入评测集（用例 ${current.adoptedEvalCaseId}），不可重复采纳`"
          />
          <el-alert
            v-else-if="!current.userInput"
            type="warning"
            show-icon
            :closable="false"
            title="缺少用户输入，无法转成评测用例"
            description="登记时聊天留痕不可用。开启 chat-log.store-mode=jdbc 后新产生的 badcase 才会带上下文。"
          />
          <el-form v-else ref="evalFormRef" :model="evalForm" :rules="evalRules" label-width="88px">
            <el-form-item label="用例编号" prop="caseId">
              <el-input v-model="evalForm.caseId" placeholder="同类型内唯一" />
            </el-form-item>
            <el-form-item label="评测类型">
              <el-radio-group v-model="evalForm.evalType">
                <el-radio-button value="INTENT">意图路由</el-radio-button>
                <el-radio-button value="QUALITY">回复质量</el-radio-button>
              </el-radio-group>
            </el-form-item>
            <el-form-item label="期望">
              <el-input v-model="evalForm.expected" :placeholder="expectedHint" />
            </el-form-item>
            <el-form-item label="归类">
              <el-input v-model="evalForm.category" placeholder="如：模糊-多意图" />
            </el-form-item>
            <el-form-item>
              <el-button
                v-permission="'badcase:adopt'"
                type="primary"
                :loading="submitting"
                @click="submitEvalCase"
              >
                加入评测集
              </el-button>
              <span class="hint">用户输入直接取自本条 badcase，不改写</span>
            </el-form-item>
          </el-form>
        </div>

        <div v-permission="'improvement:manage'" class="section">
          <div class="section-title">
            上线效果闭环
            <span class="hint">责任、SLA、精确制品、复评、可靠发布与同类问题复发观察</span>
          </div>
          <ImprovementClosurePanel source-type="BADCASE" :source-key="current.id" />
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
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

.source-hint {
  margin-left: 8px;
}

.muted {
  color: var(--el-text-color-placeholder);
}

.section {
  margin-top: 20px;
}

.section-title {
  font-weight: 600;
  margin-bottom: 10px;
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.pagination {
  margin-top: 12px;
  justify-content: flex-end;
}
</style>
