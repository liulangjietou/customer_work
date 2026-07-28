<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import type { FormInstance } from 'element-plus'
import {
  createSensitiveWord,
  deleteSensitiveWord,
  exportSensitiveWords,
  fetchSensitiveWordActions,
  fetchSensitiveWordCategories,
  importSensitiveWords,
  pageSensitiveWords,
  toggleSensitiveWord,
  updateSensitiveWord,
} from '@/api/contentGuard'
import { useCrudPage } from '@/composables/useCrudPage'
import type { SensitiveWordPageQuery, SensitiveWordSaveRequest, SensitiveWordVO } from '@/types/api'

const formRef = ref<FormInstance>()
const categories = ref<string[]>([])
const actions = ref<string[]>([])
const importVisible = ref(false)
const importText = ref('')
const importing = ref(false)

const {
  loading, list, total, query,
  dialogVisible, dialogMode, form,
  loadList, handleSearch, openCreate, openEdit, handleSubmit, handleDelete,
} = useCrudPage<SensitiveWordVO, SensitiveWordPageQuery, SensitiveWordSaveRequest>({
  page: pageSensitiveWords,
  formRef,
  create: createSensitiveWord,
  update: updateSensitiveWord,
  remove: (row) => deleteSensitiveWord(row.id),
  initQuery: () => ({ pageNum: 1, pageSize: 10, keyword: '', category: '', action: '' }),
  initForm: () => ({ word: '', category: 'CUSTOM', action: 'BLOCK', enabled: true }),
  toForm: (row) => ({ word: row.word, category: row.category, action: row.action, enabled: row.enabled }),
  deleteConfirm: (row) => `确认删除敏感词「${row.word}」？删除后客服端在下一次词表刷新时生效。`,
})

const categoryLabels: Record<string, string> = {
  POLITICS: '涉政', PORN: '涉黄', ABUSE: '辱骂', COMPETITOR: '竞品', CUSTOM: '自定义',
}

const actionMeta: Record<string, { label: string; type: 'danger' | 'warning' | 'info' }> = {
  BLOCK: { label: '拦截', type: 'danger' },
  MASK: { label: '打码', type: 'warning' },
  REVIEW: { label: '标记复核', type: 'info' },
}

const importLineCount = computed(
  () => importText.value.split('\n').filter((line) => line.trim().length > 0).length,
)

function formatTime(ms: number | null): string {
  return ms ? new Date(ms).toLocaleString('zh-CN') : '-'
}

async function handleToggle(row: SensitiveWordVO) {
  await toggleSensitiveWord(row.id, !row.enabled)
  ElMessage.success(row.enabled ? '已停用' : '已启用')
  await loadList()
}

async function handleImport() {
  const lines = importText.value.split('\n').map((line) => line.trim()).filter((line) => line.length > 0)
  if (lines.length === 0) {
    ElMessage.warning('请先粘贴要导入的词条')
    return
  }
  importing.value = true
  try {
    const count = await importSensitiveWords(lines)
    ElMessage.success(`导入完成，共处理 ${count} 条`)
    importVisible.value = false
    importText.value = ''
    await loadList()
  } finally {
    importing.value = false
  }
}

/** 导出走浏览器下载：词库动辄上千条，塞进弹窗让用户手动复制不现实。 */
async function handleExport() {
  const lines = await exportSensitiveWords()
  const blob = new Blob([lines.join('\n')], { type: 'text/plain;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `sensitive-words-${Date.now()}.csv`
  link.click()
  URL.revokeObjectURL(url)
  ElMessage.success(`已导出 ${lines.length} 条`)
}

onMounted(async () => {
  await loadList()
  categories.value = await fetchSensitiveWordCategories()
  actions.value = await fetchSensitiveWordActions()
})
</script>

<template>
  <div class="page">
    <el-alert type="info" :closable="false" show-icon class="notice">
      词库存放于客服端库，是客服链路与后台工作区共用的唯一真源。改动后由客服端轮询版本指纹自动生效
      （默认 60 秒内），无需重启任何服务。
    </el-alert>

    <el-card>
      <div class="toolbar">
        <el-input
          v-model="query.keyword"
          placeholder="按词面搜索"
          style="width: 200px"
          clearable
          @keyup.enter="handleSearch"
        />
        <el-select v-model="query.category" placeholder="全部类目" style="width: 140px" clearable>
          <el-option v-for="item in categories" :key="item" :label="categoryLabels[item] ?? item" :value="item" />
        </el-select>
        <el-select v-model="query.action" placeholder="全部动作" style="width: 140px" clearable>
          <el-option v-for="item in actions" :key="item" :label="actionMeta[item]?.label ?? item" :value="item" />
        </el-select>
        <el-select v-model="query.status" placeholder="全部状态" style="width: 120px" clearable>
          <el-option label="启用" :value="1" />
          <el-option label="停用" :value="0" />
        </el-select>
        <el-button type="primary" @click="handleSearch">搜索</el-button>
        <div class="toolbar-right">
          <el-button v-permission="'sensitive-word:add'" @click="importVisible = true">批量导入</el-button>
          <el-button @click="handleExport">导出</el-button>
          <el-button v-permission="'sensitive-word:add'" type="primary" @click="openCreate">新增敏感词</el-button>
        </div>
      </div>

      <el-table v-loading="loading" :data="list" style="width: 100%">
        <el-table-column prop="word" label="敏感词" min-width="180" show-overflow-tooltip />
        <el-table-column label="类目" width="120">
          <template #default="{ row }">{{ categoryLabels[row.category] ?? row.category }}</template>
        </el-table-column>
        <el-table-column label="处置动作" width="120">
          <template #default="{ row }">
            <el-tag :type="actionMeta[row.action]?.type ?? 'info'">
              {{ actionMeta[row.action]?.label ?? row.action }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? '启用' : '停用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="更新时间" width="180">
          <template #default="{ row }">{{ formatTime(row.updatedAtMs) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button v-permission="'sensitive-word:edit'" link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button v-permission="'sensitive-word:edit'" link type="primary" @click="handleToggle(row)">
              {{ row.enabled ? '停用' : '启用' }}
            </el-button>
            <el-button v-permission="'sensitive-word:delete'" link type="danger" @click="handleDelete(row)">删除</el-button>
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

    <el-dialog v-model="dialogVisible" :title="dialogMode === 'create' ? '新增敏感词' : '编辑敏感词'" width="480px">
      <el-form ref="formRef" :model="form" label-width="100px">
        <el-form-item label="敏感词" prop="word" :rules="[{ required: true, message: '请输入敏感词' }]">
          <el-input v-model="form.word" placeholder="维护原词即可，匹配时自动兼容全角/大小写/插入符变体" />
        </el-form-item>
        <el-form-item label="类目" prop="category" :rules="[{ required: true, message: '请选择类目' }]">
          <el-select v-model="form.category" style="width: 100%">
            <el-option v-for="item in categories" :key="item" :label="categoryLabels[item] ?? item" :value="item" />
          </el-select>
        </el-form-item>
        <el-form-item label="处置动作" prop="action" :rules="[{ required: true, message: '请选择处置动作' }]">
          <el-select v-model="form.action" style="width: 100%">
            <el-option v-for="item in actions" :key="item" :label="actionMeta[item]?.label ?? item" :value="item" />
          </el-select>
          <div class="form-hint">拦截=整条消息不发给模型并回安全话术；打码=命中片段替换为掩码后继续；标记复核=放行但记审计</div>
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="form.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleSubmit">确定</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="importVisible" title="批量导入敏感词" width="560px">
      <div class="form-hint import-hint">
        每行一条，格式 <code>词面,类目,动作</code>；类目与动作可省略（默认 CUSTOM / BLOCK）。
        同名词按新内容更新，不会报错中断。
      </div>
      <el-input
        v-model="importText"
        type="textarea"
        :rows="12"
        placeholder="示例：&#10;测试词A,CUSTOM,BLOCK&#10;竞品词B,COMPETITOR,MASK&#10;只有词面的一行"
      />
      <div class="form-hint import-count">待导入 {{ importLineCount }} 条</div>
      <template #footer>
        <el-button @click="importVisible = false">取消</el-button>
        <el-button type="primary" :loading="importing" @click="handleImport">导入</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.notice {
  margin-bottom: 12px;
}

.toolbar {
  display: flex;
  gap: 8px;
  margin-bottom: 16px;
  align-items: center;
}

.toolbar-right {
  margin-left: auto;
  display: flex;
  gap: 8px;
}

.form-hint {
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.6;
}

.import-hint {
  margin-bottom: 8px;
}

.import-count {
  margin-top: 8px;
  text-align: right;
}
</style>
