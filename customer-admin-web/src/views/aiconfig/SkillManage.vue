<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import type { FormInstance, UploadRequestOptions } from 'element-plus'
import { createSkill, deleteSkill, pageSkills, parseSkillUpload, updateSkill } from '@/api/skill'
import { useCrudPage } from '@/composables/useCrudPage'
import type { PageQuery, SkillSaveRequest, SkillVO } from '@/types/api'
import MarkdownRenderer from '@/components/MarkdownRenderer.vue'

/** 文件大小人性化展示（附属文件清单用）。 */
function formatSize(bytes: number) {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

const formRef = ref<FormInstance>()

// 上传目标选项：本地 Workspace / Nacos / SFTP，值与后端 SkillStorageTarget 枚举对齐。
const storageTargetOptions = [
  { label: '本地 Workspace', value: 'local' },
  { label: 'Nacos', value: 'nacos' },
  { label: 'SFTP', value: 'sftp' },
]

const {
  loading, list, total, query,
  dialogVisible, dialogMode, form,
  loadList, handleSearch, openCreate, openEdit, handleSubmit, handleDelete,
} = useCrudPage<SkillVO, PageQuery, SkillSaveRequest>({
  page: pageSkills,
  formRef,
  create: createSkill,
  update: updateSkill,
  remove: (row) => deleteSkill(row.id),
  initQuery: () => ({ pageNum: 1, pageSize: 10, keyword: '' }),
  // files: null = 保持后端现有附属文件不变；上传 zip 解析成功后被替换为解析出的完整文件列表
  initForm: () => ({ skillName: '', skillCode: '', content: '', description: '', status: 1, storageTargets: ['local'], files: null }),
  toForm: (row) => ({
    skillName: row.skillName, skillCode: row.skillCode, content: row.content, description: row.description, status: row.status,
    storageTargets: [...(row.storageTargets ?? [])], files: null,
  }),
  deleteConfirm: (row) => `确认删除 Skill「${row.skillName}」？`,
})

const previewVisible = ref(false)
const previewSkill = ref<SkillVO | null>(null)

function openPreview(row: SkillVO) {
  previewSkill.value = row
  previewVisible.value = true
}

/** 编辑弹窗里正在编辑的行（展示后端现有附属文件清单用）。 */
const editingRow = ref<SkillVO | null>(null)

const uploading = ref(false)

/** 表单当前生效的附属文件清单：本次上传解析出的优先，否则显示后端现有的。 */
const formFileList = computed(() => {
  if (form.files != null) return form.files.map((f) => ({ filePath: f.filePath, fileSize: f.fileSize }))
  return editingRow.value?.files ?? []
})

function openCreateWithReset() {
  editingRow.value = null
  openCreate()
}

function openEditWithRow(row: SkillVO) {
  editingRow.value = row
  openEdit(row)
}

async function handleUpload(options: UploadRequestOptions) {
  uploading.value = true
  try {
    const result = await parseSkillUpload(options.file as File)
    form.content = result.content
    // zip 解析出的附属文件随保存全量替换；.md 直传 files 为空数组，同样按"清空附属文件"处理
    form.files = result.files
    const fileTip = result.files.length > 0 ? `，含 ${result.files.length} 个附属文件` : ''
    ElMessage.success(`解析成功，已回填 SKILL.md 正文${fileTip}，确认无误后点“确定”保存`)
  } finally {
    uploading.value = false
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
        <el-button v-permission="'skill:add'" type="primary" @click="openCreateWithReset">新建 Skill</el-button>
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
        <el-table-column label="附属文件" width="100">
          <template #default="{ row }">
            <span>{{ row.files?.length ?? 0 }} 个</span>
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
            <el-button v-permission="'skill:edit'" link type="primary" @click="openEditWithRow(row)">编辑</el-button>
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
                上传 .md 或 .zip 技能包
              </el-button>
            </el-upload>
            <el-input v-model="form.content" type="textarea" :rows="10" placeholder="含 YAML frontmatter 的 SKILL.md 正文，可直接编辑或用上方按钮上传文件回填；zip 包内 references/scripts 等附属文件将一并保存" />
          </div>
        </el-form-item>
        <el-form-item v-if="formFileList.length > 0" label="附属文件">
          <div class="file-list">
            <el-tag v-for="f in formFileList" :key="f.filePath" type="info" class="file-tag">
              {{ f.filePath }}（{{ formatSize(f.fileSize) }}）
            </el-tag>
            <div class="file-tip">
              {{ form.files != null ? '本次上传解析出的文件，保存后将全量替换原有附属文件' : '当前已保存的附属文件，重新上传 zip 可整体替换' }}
            </div>
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
      <div v-if="previewSkill">
        <div class="preview-split">
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
        <div v-if="(previewSkill.files?.length ?? 0) > 0" class="preview-files">
          <div class="preview-pane-title">附属文件（{{ previewSkill.files.length }} 个）</div>
          <el-tag v-for="f in previewSkill.files" :key="f.filePath" type="info" class="file-tag">
            {{ f.filePath }}（{{ formatSize(f.fileSize) }}）
          </el-tag>
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
  height: 60vh;
}

.file-list {
  width: 100%;
}

.file-tag {
  margin: 0 6px 6px 0;
  font-family: 'JetBrains Mono', 'Fira Code', 'Courier New', monospace;
}

.file-tip {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.5;
}

.preview-files {
  margin-top: 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 4px;
  overflow: hidden;
}

.preview-files .preview-pane-title {
  margin-bottom: 8px;
}

.preview-files .file-tag {
  margin: 0 6px 8px 12px;
}

.preview-pane {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 4px;
  overflow: hidden;
}

.preview-pane-title {
  flex-shrink: 0;
  padding: 8px 12px;
  font-size: 13px;
  font-weight: 600;
  color: var(--el-text-color-regular);
  background: var(--el-fill-color-light);
  border-bottom: 1px solid var(--el-border-color-lighter);
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
  color: var(--el-text-color-primary);
}
</style>
