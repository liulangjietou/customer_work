<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox, type FormInstance } from 'element-plus'
import {
  createProject,
  deleteProject,
  listProjects,
  listProjectSessions,
  removeSessionFromProject,
  updateProject,
} from '@/api/project'
import type { ProjectSaveRequest, ProjectSessionVO, ProjectVO } from '@/types/api'

const router = useRouter()

const loading = ref(false)
const list = ref<ProjectVO[]>([])
const keyword = ref('')

async function loadList() {
  loading.value = true
  try {
    list.value = await listProjects(keyword.value || undefined)
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  loadList()
}

// ---------- 新建/编辑 ----------
const dialogVisible = ref(false)
const dialogMode = ref<'create' | 'edit'>('create')
const formRef = ref<FormInstance>()
const editingId = ref<number | null>(null)
const form = reactive<ProjectSaveRequest>({ projectName: '', description: '' })

function openCreate() {
  dialogMode.value = 'create'
  editingId.value = null
  Object.assign(form, { projectName: '', description: '' })
  dialogVisible.value = true
}

function openEdit(row: ProjectVO) {
  dialogMode.value = 'edit'
  editingId.value = row.id
  Object.assign(form, { projectName: row.projectName, description: row.description })
  dialogVisible.value = true
}

async function handleSubmit() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) {
    return
  }
  if (dialogMode.value === 'create') {
    await createProject(form)
    ElMessage.success('新建成功')
  } else if (editingId.value) {
    await updateProject(editingId.value, form)
    ElMessage.success('保存成功')
  }
  dialogVisible.value = false
  await loadList()
}

async function handleDelete(row: ProjectVO) {
  await ElMessageBox.confirm(
    `确认删除项目「${row.projectName}」？项目下 ${row.sessionCount} 条会话关联会一并清除（不影响会话本身）。`,
    '提示',
    { type: 'warning' },
  )
  await deleteProject(row.id)
  ElMessage.success('删除成功')
  await loadList()
}

// ---------- 项目详情：会话列表 ----------
const detailVisible = ref(false)
const detailLoading = ref(false)
const detailProject = ref<ProjectVO | null>(null)
const detailSessions = ref<ProjectSessionVO[]>([])

async function openDetail(row: ProjectVO) {
  detailProject.value = row
  detailVisible.value = true
  await loadDetail()
}

async function loadDetail() {
  if (!detailProject.value) return
  detailLoading.value = true
  try {
    detailSessions.value = await listProjectSessions(detailProject.value.id)
  } finally {
    detailLoading.value = false
  }
}

function openSessionInWorkspace(session: ProjectSessionVO) {
  router.push({ name: 'Workspace', params: { agentCode: session.agentCode }, query: { sessionId: session.sessionId } })
}

async function handleRemoveSession(session: ProjectSessionVO) {
  if (!detailProject.value) return
  await removeSessionFromProject(detailProject.value.id, session.agentCode, session.sessionId)
  ElMessage.success('已移出项目')
  await loadDetail()
  await loadList()
}

loadList()
</script>

<template>
  <div class="page">
    <el-card>
      <div class="toolbar">
        <el-input v-model="keyword" placeholder="按项目名搜索" style="width: 220px" clearable @keyup.enter="handleSearch" />
        <el-button type="primary" @click="handleSearch">搜索</el-button>
        <el-button type="primary" @click="openCreate">新建 Project</el-button>
      </div>

      <el-table v-loading="loading" :data="list" style="width: 100%">
        <el-table-column label="项目名称">
          <template #default="{ row }">
            <el-link type="primary" :underline="false" @click="openDetail(row)">{{ row.projectName }}</el-link>
          </template>
        </el-table-column>
        <el-table-column prop="description" label="描述" show-overflow-tooltip />
        <el-table-column label="会话数" width="90">
          <template #default="{ row }">
            <el-tag size="small">{{ row.sessionCount }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="创建时间" width="180" />
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button link type="danger" @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-empty v-if="!loading && list.length === 0" description="还没有项目，点右上角新建一个" />
    </el-card>

    <el-dialog v-model="dialogVisible" :title="dialogMode === 'create' ? '新建 Project' : '编辑 Project'" width="480px">
      <el-form ref="formRef" :model="form" label-width="80px">
        <el-form-item label="名称" prop="projectName" :rules="[{ required: true, message: '请输入名称' }]">
          <el-input v-model="form.projectName" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description!" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleSubmit">确定</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="detailVisible" :title="detailProject ? `Project · ${detailProject.projectName}` : 'Project'" size="480px">
      <el-table v-loading="detailLoading" :data="detailSessions" style="width: 100%">
        <el-table-column label="会话">
          <template #default="{ row }: { row: ProjectSessionVO }">
            <template v-if="row.stale">
              <el-text type="info">会话已失效</el-text>
            </template>
            <template v-else>
              <div class="session-preview">{{ row.preview || '（空会话）' }}</div>
              <div class="session-meta">
                <el-tag size="small">{{ row.agentName }}</el-tag>
                <span>{{ row.messageCount }} 条</span>
                <span v-if="row.lastMessageTime">{{ row.lastMessageTime }}</span>
              </div>
            </template>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }: { row: ProjectSessionVO }">
            <el-button v-if="!row.stale" link type="primary" @click="openSessionInWorkspace(row)">打开</el-button>
            <el-button link type="danger" @click="handleRemoveSession(row)">移出</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!detailLoading && detailSessions.length === 0" description="还没有会话，去对话页把会话加入这个项目" />
    </el-drawer>
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  gap: 8px;
  margin-bottom: 16px;
}

.session-preview {
  font-size: 13px;
  color: #303133;
}

.session-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 4px;
  font-size: 12px;
  color: #909399;
}
</style>
