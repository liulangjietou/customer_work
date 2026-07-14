<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance } from 'element-plus'
import {
  createSqlDatasource,
  deleteSqlDatasource,
  pageSqlDatasources,
  testSqlDatasourceConnection,
  updateSqlDatasource,
} from '@/api/sql'
import type { PageQuery, SqlDatasourceSaveRequest, SqlDatasourceVO } from '@/types/api'

const loading = ref(false)
const list = ref<SqlDatasourceVO[]>([])
const total = ref(0)
const query = reactive<PageQuery>({ pageNum: 1, pageSize: 10, keyword: '' })
const testingId = ref<number | null>(null)

async function loadList() {
  loading.value = true
  try {
    const result = await pageSqlDatasources(query)
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

// ---------- 新建/编辑 ----------
const dialogVisible = ref(false)
const dialogMode = ref<'create' | 'edit'>('create')
const formRef = ref<FormInstance>()
const editingId = ref<number | null>(null)
const form = reactive<SqlDatasourceSaveRequest>({
  name: '', jdbcUrl: '', username: '', password: '', enabled: true, remark: '',
})

function resetForm() {
  Object.assign(form, { name: '', jdbcUrl: '', username: '', password: '', enabled: true, remark: '' })
}

function openCreate() {
  dialogMode.value = 'create'
  editingId.value = null
  resetForm()
  dialogVisible.value = true
}

function openEdit(row: SqlDatasourceVO) {
  dialogMode.value = 'edit'
  editingId.value = row.id
  Object.assign(form, {
    name: row.name, jdbcUrl: row.jdbcUrl, username: row.username, password: '',
    enabled: row.enabled, remark: row.remark,
  })
  dialogVisible.value = true
}

async function handleSubmit() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) {
    return
  }
  if (dialogMode.value === 'create') {
    if (!form.password) {
      ElMessage.warning('新建数据源必须填写密码')
      return
    }
    await createSqlDatasource(form)
    ElMessage.success('新建成功')
  } else if (editingId.value) {
    await updateSqlDatasource(editingId.value, form)
    ElMessage.success('保存成功')
  }
  dialogVisible.value = false
  await loadList()
}

async function handleDelete(row: SqlDatasourceVO) {
  await ElMessageBox.confirm(
    `确认删除数据源「${row.name}」？若仍有 SQL 定义引用该数据源，删除会失败。`,
    '提示',
    { type: 'warning' },
  )
  await deleteSqlDatasource(row.id)
  ElMessage.success('删除成功')
  await loadList()
}

async function handleTest(row: SqlDatasourceVO) {
  testingId.value = row.id
  try {
    await testSqlDatasourceConnection(row.id)
    ElMessage.success('连通性测试成功')
  } catch {
    // 连接失败的错误提示已由 request.ts 拦截器统一弹出（Result.code!=0 时），这里不用重复弹
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
        <el-input v-model="query.keyword" placeholder="按数据源名称搜索" style="width: 220px" clearable @keyup.enter="handleSearch" />
        <el-button type="primary" @click="handleSearch">搜索</el-button>
        <el-button v-permission="'sql-datasource:add'" type="primary" @click="openCreate">新建数据源</el-button>
      </div>

      <el-table v-loading="loading" :data="list" style="width: 100%">
        <el-table-column prop="name" label="名称" width="160" />
        <el-table-column prop="username" label="用户名" width="120" />
        <el-table-column label="密码" width="120">
          <template #default="{ row }">{{ row.passwordMasked }}</template>
        </el-table-column>
        <el-table-column prop="jdbcUrl" label="JDBC URL" show-overflow-tooltip />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? '启用' : '禁用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="updateTime" label="更新时间" width="170" />
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" :loading="testingId === row.id" @click="handleTest(row)">测试连接</el-button>
            <el-button v-permission="'sql-datasource:edit'" link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button v-permission="'sql-datasource:delete'" link type="danger" @click="handleDelete(row)">删除</el-button>
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

    <el-dialog v-model="dialogVisible" :title="dialogMode === 'create' ? '新建数据源' : '编辑数据源'" width="560px">
      <el-form ref="formRef" :model="form" label-width="100px">
        <el-form-item label="名称" prop="name" :rules="[{ required: true, message: '请输入名称' }]">
          <el-input v-model="form.name" />
        </el-form-item>
        <el-form-item label="JDBC URL" prop="jdbcUrl" :rules="[{ required: true, message: '请输入 JDBC URL' }]">
          <el-input v-model="form.jdbcUrl" placeholder="如 jdbc:mysql://host:3306/db?useUnicode=true" />
        </el-form-item>
        <el-form-item label="用户名" prop="username" :rules="[{ required: true, message: '请输入用户名' }]">
          <el-input v-model="form.username" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="form.password!" type="password" show-password :placeholder="dialogMode === 'edit' ? '留空则不修改' : '必填'" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark!" type="textarea" :rows="2" />
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
