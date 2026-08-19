<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import {
  fillKnowledgeGap,
  listKnowledgeGaps,
  type FillKnowledgeGapRequest,
  type KnowledgeGap,
} from '@/api/ops'

// 知识盲区看板：哪些问题反复查不到知识。
//
// 这份数据本来唾手可得（检索未命中时记一笔），此前没人记，于是补知识全靠拍脑袋——
// 而拍出来的往往是运营自己关心的，不是用户实际在问的。

const loading = ref(false)
const list = ref<KnowledgeGap[]>([])
// 分区键 = 租户码（未开多租户时统一落 default），与 CSAT 看板同一口径
const scopeId = ref('default')

async function loadList() {
  loading.value = true
  try {
    list.value = await listKnowledgeGaps(scopeId.value)
  } finally {
    loading.value = false
  }
}

function formatTime(ms: number): string {
  return ms ? new Date(ms).toLocaleString('zh-CN', { hour12: false }) : '-'
}

/** 未命中次数越多越该优先补，用色阶让排行一眼可见。 */
function missTagType(count: number): 'danger' | 'warning' | 'info' {
  if (count >= 10) return 'danger'
  if (count >= 3) return 'warning'
  return 'info'
}

// ---------- 一键补知识 ----------

const dialogVisible = ref(false)
const submitting = ref(false)
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

function openFill(row: KnowledgeGap) {
  currentGap.value = row
  form.questionHash = row.questionHash
  form.title = ''
  form.content = ''
  // 关键词预填原问题，运营在此基础上改比从空白写快
  form.keyword = row.question
  dialogVisible.value = true
  formRef.value?.clearValidate()
}

async function submitFill() {
  if (!formRef.value) return
  await formRef.value.validate()
  submitting.value = true
  try {
    const knowledgeId = await fillKnowledgeGap({ ...form })
    ElMessage.success(`已补进知识库（条目 #${knowledgeId}），下次问到就能答上来了`)
    dialogVisible.value = false
    await loadList()
  } finally {
    submitting.value = false
  }
}

onMounted(loadList)
</script>

<template>
  <div class="knowledge-gap-board">
    <el-alert
      type="info"
      show-icon
      :closable="false"
      title="按未命中次数排序——越靠前的越该优先补"
      description="只出现过一次的问法没有补知识的价值；反复被问却查不到的，才是知识库真正的缺口。
        补进去的内容会直接影响线上回答，请写成知识的样子，不要照抄用户的口语化提问。"
    />

    <el-card shadow="never">
      <div class="toolbar">
        <el-input v-model="scopeId" placeholder="租户码" style="width: 180px" />
        <el-button type="primary" :loading="loading" @click="loadList">查询</el-button>
      </div>

      <el-table v-loading="loading" :data="list" style="width: 100%">
        <el-table-column label="未命中" width="100">
          <template #default="{ row }">
            <el-tag :type="missTagType(row.missCount)">{{ row.missCount }} 次</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="question" label="查不到的问题" show-overflow-tooltip />
        <el-table-column label="首次出现" width="170">
          <template #default="{ row }">{{ formatTime(row.firstSeenAtMs) }}</template>
        </el-table-column>
        <el-table-column label="最近出现" width="170">
          <template #default="{ row }">{{ formatTime(row.lastSeenAtMs) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button v-permission="'knowledge-gap:fill'" link type="primary" @click="openFill(row)">
              补充知识
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-empty
        v-if="!loading && list.length === 0"
        description="暂无盲区记录（需开启 knowledge-gap.store-mode=jdbc）"
      />
    </el-card>

    <el-dialog v-model="dialogVisible" title="补充知识库条目" width="620px">
      <el-alert
        v-if="currentGap"
        class="origin"
        type="warning"
        show-icon
        :closable="false"
        :title="`用户问过 ${currentGap.missCount} 次但查不到：${currentGap.question}`"
      />
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
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitFill">补进知识库</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.knowledge-gap-board {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}

.origin {
  margin-bottom: 16px;
}
</style>
