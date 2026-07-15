<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type UploadRequestOptions } from 'element-plus'
import { createSkill, deleteSkill, pageSkills, parseSkillUpload, updateSkill } from '@/api/skill'
import type { PageQuery, SkillSaveRequest, SkillVO } from '@/types/api'
import MarkdownRenderer from '@/components/MarkdownRenderer.vue'

const loading = ref(false)
const list = ref<SkillVO[]>([])
const total = ref(0)
const query = reactive<PageQuery>({ pageNum: 1, pageSize: 10, keyword: '' })

const dialogVisible = ref(false)
const dialogMode = ref<'create' | 'edit'>('create')
const formRef = ref<FormInstance>()
const editingId = ref<number | null>(null)
const form = reactive<SkillSaveRequest>({ skillName: '', skillCode: '', content: '', description: '', status: 1, storageTargets: ['local'] })

// 上传目标选项：本地 Workspace / Nacos / SFTP，值与后端 SkillStorageTarget 枚举对齐。
const storageTargetOptions = [
  { label: '本地 Workspace', value: 'local' },
  { label: 'Nacos', value: 'nacos' },
  { label: 'SFTP', value: 'sftp' },
]

async function loadList() {
  loading.value = true
  try {
    const result = await pageSkills(query)
    list.value = result.list
    total.value = result.total
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  query.pageNum = 1
  loadList()
}

function openCreate() {
  dialogMode.value = 'create'
  editingId.value = null
  Object.assign(form, { skillName: '', skillCode: '', content: '', description: '', status: 1, storageTargets: ['local'] })
  dialogVisible.value = true
}

function openEdit(row: SkillVO) {
  dialogMode.value = 'edit'
  editingId.value = row.id
  Object.assign(form, {
    skillName: row.skillName, skillCode: row.skillCode, content: row.content, description: row.description, status: row.status,
    storageTargets: [...(row.storageTargets ?? [])],
  })
  dialogVisible.value = true
}

const previewVisible = ref(false)
const previewSkill = ref<SkillVO | null>(null)

function openPreview(row: SkillVO) {
  previewSkill.value = row
  previewVisible.value = true
}

async function handleSubmit() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) {
    return
  }
  if (dialogMode.value === 'create') {
    await createSkill(form)
    ElMessage.success('新建成功')
  } else if (editingId.value) {
    await updateSkill(editingId.value, form)
    ElMessage.success('保存成功')
  }
  dialogVisible.value = false
  await loadList()
}

const uploading = ref(false)

async function handleUpload(options: UploadRequestOptions) {
  uploading.value = true
  try {
    const content = await parseSkillUpload(options.file as File)
    form.content = content
    ElMessage.success('解析成功，已回填 SKILL.md 正文，确认无误后点“确定”保存')
  } finally {
    uploading.value = false
  }
}

async function handleDelete(row: SkillVO) {
  await ElMessageBox.confirm(`确认删除 Skill「${row.skillName}」？`, '提示', { type: 'warning' })
  try {
    await deleteSkill(row.id)
    ElMessage.success('删除成功')
    await loadList()
  } catch {
    // 引用校验失败的提示已由 axios 拦截器统一弹出
  }
}

onMounted(loadList)
</script>

<template>
  <div class="page">
    <el-card>
      <div class="toolbar">
        <el-input v-model="query.keyword" placeholder="按名称搜索" style="width: 220px" clearable @keyup.enter="handleSearch" />
        <el-button type="primary" @click="handleSearch">搜索</el-button>
        <el-button v-permission="'skill:add'" type="primary" @click="openCreate">新建 Skill</el-button>
      </div>

      <el-table v-loading="loading" :data="list" style="width: 100%">
        <el-table-column prop="skillName" label="名称" />
        <el-table-column prop="skillCode" label="编码" width="160" />
        <el-table-column label="存储目标" width="200">
          <template #default="{ row }">
            <el-tag v-for="t in row.storageTargets" :key="t" style="margin-right: 4px">
              {{ storageTargetOptions.find((o) => o.value === t)?.label ?? t }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="description" label="描述" show-overflow-tooltip />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'">{{ row.status === 1 ? '启用' : '禁用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="创建时间" width="180" />
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openPreview(row)">查看</el-button>
            <el-button v-permission="'skill:edit'" link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button v-permission="'skill:delete'" link type="danger" @click="handleDelete(row)">删除</el-button>
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

    <el-dialog v-model="dialogVisible" :title="dialogMode === 'create' ? '新建 Skill' : '编辑 Skill'" width="600px">
      <el-form ref="formRef" :model="form" label-width="90px">
        <el-form-item label="名称" prop="skillName" :rules="[{ required: true, message: '请输入名称' }]">
          <el-input v-model="form.skillName" />
        </el-form-item>
        <el-form-item label="编码" prop="skillCode" :rules="[{ required: true, message: '请输入编码' }]">
          <el-input v-model="form.skillCode" :disabled="dialogMode === 'edit'" placeholder="用于 SKILL.md 落盘目录名" />
        </el-form-item>
        <el-form-item label="SKILL.md" prop="content" :rules="[{ required: true, message: '请输入 SKILL.md 正文' }]">
          <div style="width: 100%">
            <el-upload
              :show-file-list="false"
              :http-request="handleUpload"
              accept=".md,.zip"
              style="margin-bottom: 8px"
            >
              <el-button :loading="uploading" size="small">
                上传 .md 或 .zip 文件回填正文
              </el-button>
            </el-upload>
            <el-input v-model="form.content" type="textarea" :rows="10" placeholder="含 YAML frontmatter 的 SKILL.md 正文，可直接编辑或用上方按钮上传文件回填" />
          </div>
        </el-form-item>
        <el-form-item
          label="上传目标"
          prop="storageTargets"
          :rules="[{ required: true, type: 'array', min: 1, message: '请至少勾选一个上传目标' }]"
        >
          <el-checkbox-group v-model="form.storageTargets">
            <el-checkbox v-for="opt in storageTargetOptions" :key="opt.value" :value="opt.value">{{ opt.label }}</el-checkbox>
          </el-checkbox-group>
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description!" type="textarea" :rows="2" />
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

    <el-dialog v-model="previewVisible" :title="previewSkill ? `查看 · ${previewSkill.skillName}` : '查看'" width="1000px" top="5vh">
      <div v-if="previewSkill" class="preview-split">
        <div class="preview-pane">
          <div class="preview-pane-title">SKILL.md 原文</div>
          <el-scrollbar class="preview-pane-body">
            <pre class="preview-raw">{{ previewSkill.content }}</pre>
          </el-scrollbar>
        </div>
        <div class="preview-pane">
          <div class="preview-pane-title">预览</div>
          <el-scrollbar class="preview-pane-body">
            <MarkdownRenderer :text="previewSkill.content" />
          </el-scrollbar>
        </div>
      </div>
      <template #footer>
        <el-button type="primary" @click="previewVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  gap: 8px;
  margin-bottom: 16px;
}

.preview-split {
  display: flex;
  gap: 16px;
  height: 65vh;
}

.preview-pane {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  border: 1px solid #ebeef5;
  border-radius: 4px;
  overflow: hidden;
}

.preview-pane-title {
  flex-shrink: 0;
  padding: 8px 12px;
  font-size: 13px;
  font-weight: 600;
  color: #606266;
  background: #f5f7fa;
  border-bottom: 1px solid #ebeef5;
}

.preview-pane-body {
  flex: 1;
  min-height: 0;
}

.preview-pane-body :deep(.markdown-body) {
  padding: 12px;
}

.preview-raw {
  margin: 0;
  padding: 12px;
  font-family: 'JetBrains Mono', 'Fira Code', 'Courier New', monospace;
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
  color: #303133;
}
</style>
