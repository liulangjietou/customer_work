<script setup lang="ts">
import { onMounted, ref } from 'vue'
import type { FormInstance } from 'element-plus'
import {
  createSqlDatasource,
  deleteSqlDatasource,
  pageSqlDatasources,
  testSqlDatasourceConnection,
  updateSqlDatasource,
} from '@/api/sql'
import { useCrudPage } from '@/composables/useCrudPage'
import CrudLoadState from '@/components/CrudLoadState.vue'
import type { PageQuery, SqlDatasourceSaveRequest, SqlDatasourceVO } from '@/types/api'

const testingId = ref<number | null>(null)
const formRef = ref<FormInstance>()

const {
  loading, loadError, submitting, deletingId, list, total, query,
  dialogVisible, dialogMode, form,
  loadList, handleSearch, openCreate, openEdit, handleSubmit, handleDelete,
} = useCrudPage<SqlDatasourceVO, PageQuery, SqlDatasourceSaveRequest>({
  page: pageSqlDatasources,
  formRef,
  create: createSqlDatasource,
  update: updateSqlDatasource,
  remove: (row) => deleteSqlDatasource(row.id),
  initQuery: () => ({ pageNum: 1, pageSize: 10, keyword: '' }),
  initForm: () => ({ name: '', jdbcUrl: '', username: '', password: '', enabled: true, remark: '' }),
  toForm: (row) => ({
    name: row.name, jdbcUrl: row.jdbcUrl, username: row.username, password: '',
    enabled: row.enabled, remark: row.remark,
  }),
  beforeSubmit: (mode, f) => {
    if (mode === 'create' && !f.password) {
      ElMessage.warning('新建数据源必须填写密码')
      return false
    }
    return true
  },
  deleteConfirm: (row) => `确认删除数据源「${row.name}」？若仍有 SQL 定义引用该数据源，删除会失败。`,
})

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
    <CrudLoadState :error="loadError" :has-stale-data="list.length > 0" :loading="loading" @retry="loadList" />
    <el-card>
      <div class="toolbar">
        <el-input v-model="query.keyword" placeholder="按数据源名称搜索" style="width: 220px" clearable @keyup.enter="handleSearch" />
        <el-button type="primary" @click="handleSearch">搜索</el-button>
        <div class="toolbar-actions">
          <el-button v-permission="'sql-datasource:add'" class="cw-final-action" type="primary" @click="openCreate">新建数据源</el-button>
        </div>
      </div>

      <el-table v-loading="loading" :data="list" class="data-table" empty-text="暂无符合条件的数据源">
        <el-table-column prop="name" label="名称" width="160" class-name="primary-column" />
        <el-table-column prop="username" label="用户名" width="120" />
        <el-table-column label="密码" width="120">
          <template #default="{ row }">{{ row.passwordMasked }}</template>
        </el-table-column>
        <el-table-column prop="jdbcUrl" label="JDBC URL" show-overflow-tooltip />
        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? '启用' : '禁用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="updateTime" label="更新时间" width="170" />
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" :loading="testingId === row.id" @click="handleTest(row)">测试连接</el-button>
            <el-button v-permission="'sql-datasource:edit'" link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button v-permission="'sql-datasource:delete'" link type="danger" :loading="deletingId === row.id" @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-model:current-page="query.pageNum"
        v-model:page-size="query.pageSize"
        :total="total"
        layout="total, prev, pager, next"
        class="pagination"
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
        <el-button class="cw-final-action" type="primary" :loading="submitting" @click="handleSubmit">保存数据源</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 16px;
}

.toolbar-actions {
  display: flex;
  gap: 8px;
  margin-left: auto;
}

.data-table {
  min-width: 0;
  max-width: 100%;
}

:deep(.primary-column .cell) {
  color: var(--cw-text);
  font-weight: 650;
}

@media (max-width: 767px) {
  .toolbar-actions {
    margin-left: 0;
  }

  .toolbar-actions > .el-button {
    flex: 1 1 auto;
    margin-left: 0;
  }
}
</style>
