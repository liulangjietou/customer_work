<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import type { FormInstance } from 'element-plus'
import { fetchSystemTools, updateSystemTool } from '@/api/system-tool'
import type { PageQuery, SystemToolSaveRequest, SystemToolVO } from '@/types/api'

const loading = ref(false)
const list = ref<SystemToolVO[]>([])
const total = ref(0)
const query = reactive<PageQuery>({ pageNum: 1, pageSize: 10, keyword: '' })

const dialogVisible = ref(false)
const formRef = ref<FormInstance>()
const editingId = ref<number | null>(null)
const form = reactive<SystemToolSaveRequest>({
  toolName: '', description: '', enabled: 1, remark: '',
})

async function loadList() {
  loading.value = true
  try {
    const result = await fetchSystemTools(query)
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

function openEdit(row: SystemToolVO) {
  editingId.value = row.id
  Object.assign(form, {
    toolName: row.toolName, description: row.description ?? '', enabled: row.enabled, remark: row.remark ?? '',
  })
  dialogVisible.value = true
}

async function handleSubmit() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid || !editingId.value) {
    return
  }
  await updateSystemTool(editingId.value, form)
  ElMessage.success('保存成功')
  dialogVisible.value = false
  await loadList()
}

// 开关直接调 PUT 局部更新：把当前行其余字段一并回传，只改 enabled。
async function handleToggleEnabled(row: SystemToolVO) {
  try {
    await updateSystemTool(row.id, {
      toolName: row.toolName, description: row.description, enabled: row.enabled, remark: row.remark,
    })
    ElMessage.success(row.enabled === 1 ? '已启用' : '已停用')
  } catch {
    // 失败时回滚开关状态
    row.enabled = row.enabled === 1 ? 0 : 1
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
      </div>

      <el-table v-loading="loading" :data="list" style="width: 100%">
        <el-table-column prop="toolCode" label="编码" width="160" />
        <el-table-column prop="toolName" label="名称" width="180" />
        <el-table-column prop="description" label="描述" show-overflow-tooltip />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-switch
              v-model="row.enabled"
              v-permission="'system-tool:edit'"
              :active-value="1"
              :inactive-value="0"
              @change="handleToggleEnabled(row)"
            />
          </template>
        </el-table-column>
        <el-table-column prop="updateTime" label="更新时间" width="180" />
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button v-permission="'system-tool:edit'" link type="primary" @click="openEdit(row)">编辑</el-button>
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

    <el-dialog v-model="dialogVisible" title="编辑系统工具" width="520px">
      <el-form ref="formRef" :model="form" label-width="100px">
        <el-form-item label="名称" prop="toolName" :rules="[{ required: true, message: '请输入名称' }]">
          <el-input v-model="form.toolName" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description!" type="textarea" :rows="3" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark!" />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="form.enabled!" :active-value="1" :inactive-value="0" />
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
