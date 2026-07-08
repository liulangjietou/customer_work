<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance } from 'element-plus'
import { createMcp, deleteMcp, pageMcps, updateMcp } from '@/api/mcp'
import type { McpSaveRequest, McpVO, PageQuery } from '@/types/api'

const loading = ref(false)
const list = ref<McpVO[]>([])
const total = ref(0)
const query = reactive<PageQuery>({ pageNum: 1, pageSize: 10, keyword: '' })

const dialogVisible = ref(false)
const dialogMode = ref<'create' | 'edit'>('create')
const formRef = ref<FormInstance>()
const editingId = ref<number | null>(null)
const form = reactive<McpSaveRequest>({ mcpName: '', mcpType: 'sse', config: '', description: '', status: 1 })

const configPlaceholder = computed(() =>
  form.mcpType === 'stdio'
    ? '{"command": "python", "args": ["-m", "mcp_server_git"]}'
    : '{"url": "https://mcp.example.com/sse"}',
)

function validateConfigJson(_rule: unknown, value: string, callback: (error?: Error) => void) {
  try {
    JSON.parse(value)
    callback()
  } catch {
    callback(new Error('config 必须是合法 JSON'))
  }
}

async function loadList() {
  loading.value = true
  try {
    const result = await pageMcps(query)
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
  Object.assign(form, { mcpName: '', mcpType: 'sse', config: '', description: '', status: 1 })
  dialogVisible.value = true
}

function openEdit(row: McpVO) {
  dialogMode.value = 'edit'
  editingId.value = row.id
  Object.assign(form, { mcpName: row.mcpName, mcpType: row.mcpType, config: row.config, description: row.description, status: row.status })
  dialogVisible.value = true
}

async function handleSubmit() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) {
    return
  }
  if (dialogMode.value === 'create') {
    await createMcp(form)
    ElMessage.success('新建成功')
  } else if (editingId.value) {
    await updateMcp(editingId.value, form)
    ElMessage.success('保存成功')
  }
  dialogVisible.value = false
  await loadList()
}

async function handleDelete(row: McpVO) {
  await ElMessageBox.confirm(`确认删除 MCP「${row.mcpName}」？`, '提示', { type: 'warning' })
  try {
    await deleteMcp(row.id)
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
        <el-button v-permission="'mcp:add'" type="primary" @click="openCreate">新建 MCP</el-button>
      </div>

      <el-table v-loading="loading" :data="list" style="width: 100%">
        <el-table-column prop="mcpName" label="名称" />
        <el-table-column prop="mcpType" label="类型" width="100" />
        <el-table-column prop="description" label="描述" show-overflow-tooltip />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'">{{ row.status === 1 ? '启用' : '禁用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="创建时间" width="180" />
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button v-permission="'mcp:edit'" link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button v-permission="'mcp:delete'" link type="danger" @click="handleDelete(row)">删除</el-button>
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

    <el-dialog v-model="dialogVisible" :title="dialogMode === 'create' ? '新建 MCP' : '编辑 MCP'" width="520px">
      <el-form ref="formRef" :model="form" label-width="90px">
        <el-form-item label="名称" prop="mcpName" :rules="[{ required: true, message: '请输入名称' }]">
          <el-input v-model="form.mcpName" />
        </el-form-item>
        <el-form-item label="类型" prop="mcpType">
          <el-radio-group v-model="form.mcpType">
            <el-radio value="sse">sse</el-radio>
            <el-radio value="stdio">stdio</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="连接配置" prop="config" :rules="[{ required: true, message: '请输入 config' }, { validator: validateConfigJson }]">
          <el-input v-model="form.config" type="textarea" :rows="4" :placeholder="configPlaceholder" />
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
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  gap: 8px;
  margin-bottom: 16px;
}
</style>
