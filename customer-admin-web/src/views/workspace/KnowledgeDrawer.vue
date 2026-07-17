<script setup lang="ts">
import { computed, onUnmounted, ref } from 'vue'
import MarkdownRenderer from '@/components/MarkdownRenderer.vue'
import {
  askKnowledge,
  buildKnowledgeIndex,
  deleteKnowledgeIndex,
  getKnowledgeIndex,
  listKnowledgeIndexes,
  searchKnowledge,
} from '@/api/knowledge'
import type { KnowledgeIndex, KnowledgeSearchHit } from '@/types/api'

const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{ (e: 'update:modelValue', value: boolean): void }>()

const visible = computed({
  get: () => props.modelValue,
  set: (v: boolean) => emit('update:modelValue', v),
})

// ---------- 构建索引 ----------
const buildForm = ref<{ indexName: string; sourcePath: string }>({ indexName: '', sourcePath: '' })
const building = ref(false)

async function handleBuild() {
  const indexName = buildForm.value.indexName.trim()
  const sourcePath = buildForm.value.sourcePath.trim()
  if (!indexName || !sourcePath) {
    ElMessage.warning('请填写索引名与源码路径')
    return
  }
  building.value = true
  try {
    await buildKnowledgeIndex({ indexName, sourcePath })
    ElMessage.success('已提交构建任务')
    buildForm.value = { indexName: '', sourcePath: '' }
    await loadIndexes()
  } catch {
    // 请求拦截器已弹错，静默兜底
  } finally {
    building.value = false
  }
}

// ---------- 索引列表 ----------
const indexes = ref<KnowledgeIndex[]>([])
const listLoading = ref(false)
// BUILDING 行的轮询定时器，key = 索引 id
const pollTimers = new Map<number, ReturnType<typeof setInterval>>()
const POLL_INTERVAL_MS = 2000

async function loadIndexes() {
  listLoading.value = true
  try {
    const list = await listKnowledgeIndexes()
    indexes.value = list ?? []
    syncPolling()
  } catch {
    // 拦截器已弹错
  } finally {
    listLoading.value = false
  }
}

/** 为所有 BUILDING 行开启轮询，非 BUILDING 行停止轮询。 */
function syncPolling() {
  const buildingIds = new Set(indexes.value.filter((i) => i.status === 'BUILDING').map((i) => i.id))
  // 停掉已完成/已消失的
  for (const [id, timer] of pollTimers) {
    if (!buildingIds.has(id)) {
      clearInterval(timer)
      pollTimers.delete(id)
    }
  }
  // 为新出现的 BUILDING 行开启轮询
  for (const id of buildingIds) {
    if (!pollTimers.has(id)) {
      const timer = setInterval(() => pollIndex(id), POLL_INTERVAL_MS)
      pollTimers.set(id, timer)
    }
  }
}

async function pollIndex(id: number) {
  try {
    const latest = await getKnowledgeIndex(id)
    const idx = indexes.value.findIndex((i) => i.id === id)
    if (idx !== -1) {
      indexes.value[idx] = latest
    }
    if (latest.status !== 'BUILDING') {
      const timer = pollTimers.get(id)
      if (timer) {
        clearInterval(timer)
        pollTimers.delete(id)
      }
    }
  } catch {
    // 轮询失败静默，等下次
  }
}

async function handleDelete(row: KnowledgeIndex) {
  try {
    await ElMessageBox.confirm(`确认删除索引「${row.indexName}」？`, '删除确认', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await deleteKnowledgeIndex(row.id)
    ElMessage.success('已删除')
    if (activeIndex.value?.id === row.id) {
      activeIndex.value = null
    }
    await loadIndexes()
  } catch {
    // 拦截器已弹错
  }
}

function statusTagType(status: KnowledgeIndex['status']): 'primary' | 'success' | 'danger' {
  if (status === 'READY') return 'success'
  if (status === 'FAILED') return 'danger'
  return 'primary'
}

// ---------- 选中索引 + 检索/问答 ----------
const activeIndex = ref<KnowledgeIndex | null>(null)

function handleSelect(row: KnowledgeIndex) {
  if (row.status !== 'READY') {
    ElMessage.warning('索引尚未就绪，无法检索/问答')
    return
  }
  activeIndex.value = row
}

const question = ref('')
const searching = ref(false)
const asking = ref(false)
const hits = ref<KnowledgeSearchHit[]>([])
const answer = ref('')
const citations = ref<KnowledgeSearchHit[]>([])

async function handleSearch() {
  if (!activeIndex.value) return
  const q = question.value.trim()
  if (!q) {
    ElMessage.warning('请输入检索内容')
    return
  }
  searching.value = true
  answer.value = ''
  citations.value = []
  try {
    hits.value = (await searchKnowledge(activeIndex.value.id, q)) ?? []
  } catch {
    // 拦截器已弹错
  } finally {
    searching.value = false
  }
}

async function handleAsk() {
  if (!activeIndex.value) return
  const q = question.value.trim()
  if (!q) {
    ElMessage.warning('请输入问题')
    return
  }
  asking.value = true
  hits.value = []
  try {
    const resp = await askKnowledge({ indexId: activeIndex.value.id, question: q })
    answer.value = resp?.answer ?? ''
    citations.value = resp?.citations ?? []
  } catch {
    // 拦截器已弹错
  } finally {
    asking.value = false
  }
}

function hitTitle(hit: KnowledgeSearchHit): string {
  return hit.symbol ? `${hit.sourcePath}#${hit.symbol}` : hit.sourcePath
}

onUnmounted(() => {
  for (const timer of pollTimers.values()) {
    clearInterval(timer)
  }
  pollTimers.clear()
})
</script>

<template>
  <el-drawer
    v-model="visible"
    title="代码知识库"
    direction="rtl"
    size="640px"
    @open="loadIndexes"
  >
    <div class="knowledge-drawer">
      <!-- 构建索引 -->
      <section class="kb-section">
        <div class="kb-section-title">构建索引</div>
        <el-form label-width="80px" @submit.prevent>
          <el-form-item label="索引名">
            <el-input v-model="buildForm.indexName" placeholder="如 customer-work-src" />
          </el-form-item>
          <el-form-item label="源码路径">
            <el-input v-model="buildForm.sourcePath" placeholder="如 ./data/admin-workspace/xxx" />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="building" @click="handleBuild">构建</el-button>
            <span class="kb-hint">源码路径需位于服务端允许的根目录下（默认 ./data/admin-workspace）</span>
          </el-form-item>
        </el-form>
      </section>

      <!-- 索引列表 -->
      <section class="kb-section">
        <div class="kb-section-header">
          <span class="kb-section-title">索引列表</span>
          <el-button link type="primary" :loading="listLoading" @click="loadIndexes">刷新</el-button>
        </div>
        <el-table :data="indexes" size="small" v-loading="listLoading" empty-text="暂无索引">
          <el-table-column prop="indexName" label="索引名" min-width="120" show-overflow-tooltip />
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag :type="statusTagType(row.status)" size="small" effect="dark">{{ row.status }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="chunkCount" label="切片" width="70" />
          <el-table-column prop="sourcePath" label="源码路径" min-width="150" show-overflow-tooltip />
          <el-table-column label="操作" width="120" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="handleSelect(row)">选择</el-button>
              <el-button link type="danger" size="small" @click="handleDelete(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </section>

      <!-- 检索 / 问答 -->
      <section v-if="activeIndex" class="kb-section">
        <div class="kb-section-title">
          检索 / 问答
          <el-tag size="small" class="kb-active-tag">当前：{{ activeIndex.indexName }}</el-tag>
        </div>
        <el-input
          v-model="question"
          type="textarea"
          :rows="2"
          placeholder="输入要检索的内容或提问"
        />
        <div class="kb-actions">
          <el-button :loading="searching" @click="handleSearch">检索</el-button>
          <el-button type="primary" :loading="asking" @click="handleAsk">提问</el-button>
        </div>

        <!-- 检索命中列表 -->
        <div v-if="hits.length > 0" class="kb-hits">
          <div v-for="(hit, hi) in hits" :key="hi" class="kb-hit">
            <div class="kb-hit-header">
              <span class="kb-hit-title" :title="hitTitle(hit)">{{ hitTitle(hit) }}</span>
              <el-tag size="small" type="info" class="kb-hit-score">score {{ hit.score.toFixed(3) }}</el-tag>
            </div>
            <pre class="kb-hit-snippet">{{ hit.snippet }}</pre>
          </div>
        </div>

        <!-- 问答结果 -->
        <div v-if="answer" class="kb-answer">
          <div class="kb-subtitle">回答</div>
          <MarkdownRenderer :text="answer" />
          <div v-if="citations.length > 0" class="kb-citations">
            <div class="kb-subtitle">引用</div>
            <div v-for="(cit, ci) in citations" :key="ci" class="kb-citation">
              <span class="kb-hit-title" :title="hitTitle(cit)">{{ hitTitle(cit) }}</span>
              <el-tag size="small" type="info" class="kb-hit-score">score {{ cit.score.toFixed(3) }}</el-tag>
            </div>
          </div>
        </div>
      </section>
      <el-empty v-else description="选择一个就绪索引后可检索/问答" :image-size="60" />
    </div>
  </el-drawer>
</template>

<style scoped>
.knowledge-drawer {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.kb-section {
  border-bottom: 1px solid var(--el-border-color-lighter);
  padding-bottom: 16px;
}

.kb-section:last-child {
  border-bottom: none;
}

.kb-section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.kb-section-title {
  font-weight: 600;
  font-size: 14px;
  color: var(--el-text-color-primary);
  margin-bottom: 8px;
}

.kb-hint {
  margin-left: 8px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.kb-active-tag {
  margin-left: 8px;
}

.kb-actions {
  display: flex;
  gap: 8px;
  margin-top: 8px;
}

.kb-hits {
  margin-top: 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.kb-hit {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  padding: 8px 10px;
  background: var(--el-fill-color-light);
}

.kb-hit-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 6px;
}

.kb-hit-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--el-text-color-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.kb-hit-score {
  flex-shrink: 0;
}

.kb-hit-snippet {
  margin: 0;
  padding: 8px;
  font-size: 12px;
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  background: var(--el-fill-color);
  border-radius: 4px;
  overflow-x: auto;
  white-space: pre-wrap;
  word-break: break-word;
}

.kb-answer {
  margin-top: 12px;
  padding: 12px;
  background: var(--el-fill-color-light);
  border-radius: 6px;
}

.kb-subtitle {
  font-size: 13px;
  font-weight: 600;
  color: var(--el-text-color-secondary);
  margin-bottom: 6px;
}

.kb-citations {
  margin-top: 12px;
}

.kb-citation {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 4px 0;
}
</style>
