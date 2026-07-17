<script setup lang="ts">
import { onMounted, ref } from 'vue'
import type { FormInstance } from 'element-plus'
import { createModel, deleteModel, pageModels, testModelConnectivity, updateModel } from '@/api/model'
import { useCrudPage } from '@/composables/useCrudPage'
import type { ModelSaveRequest, ModelVO, PageQuery } from '@/types/api'

const testingId = ref<number | null>(null)
const formRef = ref<FormInstance>()

const {
  loading, list, total, query,
  dialogVisible, dialogMode, form,
  loadList, handleSearch, openCreate, openEdit, handleSubmit, handleDelete,
} = useCrudPage<ModelVO, PageQuery, ModelSaveRequest>({
  page: pageModels,
  formRef,
  create: createModel,
  update: updateModel,
  remove: (row) => deleteModel(row.id),
  initQuery: () => ({ pageNum: 1, pageSize: 10, keyword: '' }),
  initForm: () => ({
    modelName: '', provider: 'openai', apiKey: '', baseUrl: '', model: '',
    isDefault: false, status: 1,
  }),
  // 编辑回填时 apiKey 置空表示"留空则不修改"
  toForm: (row) => ({
    modelName: row.modelName, provider: row.provider, apiKey: '', baseUrl: row.baseUrl, model: row.model,
    isDefault: row.isDefault, status: row.status,
  }),
  beforeSubmit: (mode, f) => {
    if (mode === 'create' && !f.apiKey) {
      ElMessage.warning('新建模型配置必须填写 AppKey')
      return false
    }
    return true
  },
  deleteConfirm: (row) => `确认删除模型配置「${row.modelName}」？`,
})

const testStatusMap: Record<number, { label: string; type: 'info' | 'success' | 'danger' }> = {
  0: { label: '未测试', type: 'info' },
  1: { label: '连通成功', type: 'success' },
  2: { label: '连通失败', type: 'danger' },
}

// 厂商预设：默认 Base URL 与常用模型占位（与后端 ModelProvider 枚举默认值语义一致，用户可改）
interface ProviderPreset {
  value: string
  label: string
  defaultBaseUrl: string
  modelPlaceholder: string
}
const providerPresets: ProviderPreset[] = [
  { value: 'openai', label: 'OpenAI 兼容', defaultBaseUrl: 'https://api.openai.com/v1', modelPlaceholder: '如 gpt-4o-mini' },
  { value: 'dashscope', label: '百炼 DashScope（通义千问）', defaultBaseUrl: 'https://dashscope.aliyuncs.com', modelPlaceholder: '如 qwen-max' },
  { value: 'anthropic', label: 'Anthropic Claude', defaultBaseUrl: 'https://api.anthropic.com', modelPlaceholder: '如 claude-3-5-sonnet-latest' },
  { value: 'gemini', label: 'Google Gemini', defaultBaseUrl: 'https://generativelanguage.googleapis.com', modelPlaceholder: '如 gemini-2.0-flash' },
]

function presetOf(provider: string | null | undefined): ProviderPreset {
  return providerPresets.find((p) => p.value === provider) ?? providerPresets[0]
}

// 切换厂商时预填默认 Base URL：仅当当前 Base URL 为空或等于其它厂商默认值时覆盖，避免冲掉用户自定义值
function handleProviderChange(provider: string) {
  const preset = presetOf(provider)
  const isDefaultOrEmpty = !form.baseUrl
    || providerPresets.some((p) => p.defaultBaseUrl === form.baseUrl)
  if (isDefaultOrEmpty) {
    form.baseUrl = preset.defaultBaseUrl
  }
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
          <el-select v-model="form.provider" @change="handleProviderChange">
            <el-option v-for="p in providerPresets" :key="p.value" :label="p.label" :value="p.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="模型标识" prop="model" :rules="[{ required: true, message: '请输入模型标识' }]">
          <el-input v-model="form.model" :placeholder="presetOf(form.provider).modelPlaceholder" />
        </el-form-item>
        <el-form-item label="Base URL" prop="baseUrl" :rules="[{ required: true, message: '请输入 Base URL' }]">
          <el-input v-model="form.baseUrl" :placeholder="presetOf(form.provider).defaultBaseUrl" />
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
