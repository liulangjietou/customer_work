<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance } from 'element-plus'
import { createModel, deleteModel, pageModels, testModelConnectivity, updateModel } from '@/api/model'
import type { ModelSaveRequest, ModelVO, PageQuery } from '@/types/api'

const loading = ref(false)
const list = ref<ModelVO[]>([])
const total = ref(0)
const query = reactive<PageQuery>({ pageNum: 1, pageSize: 10, keyword: '' })
const testingId = ref<number | null>(null)

const dialogVisible = ref(false)
const dialogMode = ref<'create' | 'edit'>('create')
const formRef = ref<FormInstance>()
const editingId = ref<number | null>(null)
const form = reactive<ModelSaveRequest>({
  modelName: '', provider: 'openai', apiKey: '', baseUrl: '', model: '',
  isDefault: false, status: 1,
})

const testStatusMap: Record<number, { label: string; type: 'info' | 'success' | 'danger' }> = {
  0: { label: '未测试', type: 'info' },
  1: { label: '连通成功', type: 'success' },
  2: { label: '连通失败', type: 'danger' },
}

async function loadList() {
  loading.value = true
  try {
    const result = await pageModels(query)
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
  Object.assign(form, { modelName: '', provider: 'openai', apiKey: '', baseUrl: '', model: '', isDefault: false, status: 1 })
  dialogVisible.value = true
}

function openEdit(row: ModelVO) {
  dialogMode.value = 'edit'
  editingId.value = row.id
  Object.assign(form, {
    modelName: row.modelName, provider: row.provider, apiKey: '', baseUrl: row.baseUrl, model: row.model,
    isDefault: row.isDefault, status: row.status,
  })
  dialogVisible.value = true
}

async function handleSubmit() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) {
    return
  }
  if (dialogMode.value === 'create') {
    if (!form.apiKey) {
      ElMessage.warning('新建模型配置必须填写 AppKey')
      return
    }
    await createModel(form)
    ElMessage.success('新建成功')
  } else if (editingId.value) {
    await updateModel(editingId.value, form)
    ElMessage.success('保存成功')
  }
  dialogVisible.value = false
  await loadList()
}

async function handleDelete(row: ModelVO) {
  await ElMessageBox.confirm(`确认删除模型配置「${row.modelName}」？`, '提示', { type: 'warning' })
  await deleteModel(row.id)
  ElMessage.success('删除成功')
  await loadList()
}

async function handleTest(row: ModelVO) {
  testingId.value = row.id
  try {
    const result = await testModelConnectivity(row.id)
    if (result.testStatus === 1) {
      ElMessage.success('连通性测试成功')
    } else {
      ElMessage.error(result.message || '连通性测试失败')
    }
    await loadList()
  } finally {
    testingId.value = null
  }
}

onMounted(loadList)
</script>

<template>
  <div class="page">
    <el-card>
      <div class="toolbar">
        <el-input v-model="query.keyword" placeholder="按模型名称搜索" style="width: 220px" clearable @keyup.enter="handleSearch" />
        <el-button type="primary" @click="handleSearch">搜索</el-button>
        <el-button v-permission="'model:add'" type="primary" @click="openCreate">新建模型</el-button>
      </div>

      <el-table v-loading="loading" :data="list" style="width: 100%">
        <el-table-column prop="modelName" label="名称" />
        <el-table-column prop="provider" label="厂商" width="100" />
        <el-table-column prop="model" label="模型标识" />
        <el-table-column label="AppKey" width="140">
          <template #default="{ row }">{{ row.apiKeyMasked }}</template>
        </el-table-column>
        <el-table-column label="默认" width="70">
          <template #default="{ row }">
            <el-tag v-if="row.isDefault" type="warning">默认</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'">{{ row.status === 1 ? '启用' : '禁用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="连通性" width="140">
          <template #default="{ row }">
            <el-tag :type="testStatusMap[row.testStatus]?.type ?? 'info'">{{ testStatusMap[row.testStatus]?.label ?? '未测试' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" :loading="testingId === row.id" @click="handleTest(row)">测试连通性</el-button>
            <el-button v-permission="'model:edit'" link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button v-permission="'model:delete'" link type="danger" @click="handleDelete(row)">删除</el-button>
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

    <el-dialog v-model="dialogVisible" :title="dialogMode === 'create' ? '新建模型配置' : '编辑模型配置'" width="520px">
      <el-form ref="formRef" :model="form" label-width="100px">
        <el-form-item label="名称" prop="modelName" :rules="[{ required: true, message: '请输入名称' }]">
          <el-input v-model="form.modelName" />
        </el-form-item>
        <el-form-item label="厂商">
          <el-select v-model="form.provider">
            <el-option label="OpenAI 兼容" value="openai" />
          </el-select>
        </el-form-item>
        <el-form-item label="模型标识" prop="model" :rules="[{ required: true, message: '请输入模型标识，如 gpt-4o-mini' }]">
          <el-input v-model="form.model" placeholder="如 gpt-4o-mini" />
        </el-form-item>
        <el-form-item label="Base URL" prop="baseUrl" :rules="[{ required: true, message: '请输入 Base URL' }]">
          <el-input v-model="form.baseUrl" placeholder="如 https://api.openai.com/v1" />
        </el-form-item>
        <el-form-item label="AppKey">
          <el-input v-model="form.apiKey!" type="password" show-password :placeholder="dialogMode === 'edit' ? '留空则不修改' : '必填'" />
        </el-form-item>
        <el-form-item label="设为默认">
          <el-switch v-model="form.isDefault" />
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
