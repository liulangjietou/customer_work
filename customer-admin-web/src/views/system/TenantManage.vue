<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import {
  changeTenantStatus,
  createTenant,
  deleteTenant,
  pageTenants,
  updateTenant,
  type TenantSaveRequest,
  type TenantVO,
} from '@/api/tenant'

// 租户管理：仅具备控制面角色及相应权限点的用户可用。保留租户 default 在后端受保护，
// 前端同步禁用相应按钮，避免用户点了才收到报错。

const STATUS_LABELS: Record<string, { text: string; type: 'success' | 'warning' | 'info' }> = {
  ACTIVE: { text: '正常', type: 'success' },
  SUSPENDED: { text: '已冻结', type: 'warning' },
  TERMINATED: { text: '已退租', type: 'info' },
}

const loading = ref(false)
const list = ref<TenantVO[]>([])
const total = ref(0)
const query = reactive({ pageNum: 1, pageSize: 10, keyword: '', tenantStatus: '' })

async function loadList() {
  loading.value = true
  try {
    const data = await pageTenants(query)
    list.value = data.list
    total.value = data.total
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  query.pageNum = 1
  return loadList()
}

// ---------- 新建 / 编辑 ----------

const dialogVisible = ref(false)
const dialogMode = ref<'create' | 'edit'>('create')
const formRef = ref<FormInstance>()
const form = reactive<TenantSaveRequest>({
  tenantCode: '',
  tenantName: '',
  contactName: '',
  contactPhone: '',
  contactEmail: '',
  remark: '',
  expireTime: null,
})

const rules: FormRules = {
  tenantCode: [
    { required: true, message: '请输入租户编码', trigger: 'blur' },
    {
      pattern: /^[a-zA-Z0-9][a-zA-Z0-9-_]*$/,
      message: '只能包含字母、数字、连字符和下划线，且以字母或数字开头',
      trigger: 'blur',
    },
  ],
  tenantName: [{ required: true, message: '请输入租户名称', trigger: 'blur' }],
  contactEmail: [{ type: 'email', message: '邮箱格式不正确', trigger: 'blur' }],
}

function openCreate() {
  dialogMode.value = 'create'
  Object.assign(form, {
    id: undefined,
    tenantCode: '',
    tenantName: '',
    contactName: '',
    contactPhone: '',
    contactEmail: '',
    remark: '',
    expireTime: null,
  })
  dialogVisible.value = true
}

function openEdit(row: TenantVO) {
  dialogMode.value = 'edit'
  Object.assign(form, {
    id: row.id,
    tenantCode: row.tenantCode,
    tenantName: row.tenantName,
    contactName: row.contactName ?? '',
    contactPhone: row.contactPhone ?? '',
    contactEmail: row.contactEmail ?? '',
    remark: row.remark ?? '',
    expireTime: row.expireTime,
  })
  dialogVisible.value = true
}

async function submit() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  if (dialogMode.value === 'create') {
    await createTenant(form)
    ElMessage.success('租户已创建，已自动初始化租户管理员角色')
  } else {
    await updateTenant(form)
    ElMessage.success('租户已更新')
  }
  dialogVisible.value = false
  await loadList()
}

// ---------- 生命周期 ----------

async function handleStatusChange(row: TenantVO, target: string) {
  const action = target === 'ACTIVE' ? '恢复' : target === 'SUSPENDED' ? '冻结' : '退租'
  const extra =
    target === 'ACTIVE'
      ? ''
      : '该租户下的所有账号将立即无法登录，业务数据保留不受影响。'
  await ElMessageBox.confirm(`确认${action}租户「${row.tenantName}」？${extra}`, `${action}确认`, {
    type: 'warning',
  })
  await changeTenantStatus(row.id, target)
  ElMessage.success(`租户已${action}`)
  await loadList()
}

async function handleDelete(row: TenantVO) {
  await ElMessageBox.confirm(
    `确认删除租户「${row.tenantName}」？删除后该租户无法登录，但其业务数据仍保留在库中。`,
    '删除确认',
    { type: 'warning' },
  )
  await deleteTenant(row.id)
  ElMessage.success('租户已删除')
  await loadList()
}

onMounted(loadList)
</script>

<template>
  <div class="page">
    <el-card>
      <div class="toolbar">
        <el-input
          v-model="query.keyword"
          placeholder="按租户编码或名称搜索"
          style="width: 240px"
          clearable
          @keyup.enter="handleSearch"
        />
        <el-select v-model="query.tenantStatus" placeholder="全部状态" clearable style="width: 140px">
          <el-option label="正常" value="ACTIVE" />
          <el-option label="已冻结" value="SUSPENDED" />
          <el-option label="已退租" value="TERMINATED" />
        </el-select>
        <el-button type="primary" @click="handleSearch">搜索</el-button>
        <div class="toolbar-actions">
          <el-button v-permission="'tenant:add'" class="cw-final-action" type="primary" @click="openCreate">新建租户</el-button>
        </div>
      </div>

      <el-table v-loading="loading" :data="list" class="data-table" empty-text="暂无符合条件的租户">
        <el-table-column prop="tenantCode" label="租户编码" width="160">
          <template #default="{ row }">
            {{ row.tenantCode }}
            <el-tag v-if="row.reserved" size="small" type="info" style="margin-left: 6px">系统保留</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="tenantName" label="租户名称" class-name="primary-column" />
        <el-table-column label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="STATUS_LABELS[row.status]?.type ?? 'info'">
              {{ STATUS_LABELS[row.status]?.text ?? row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="contactName" label="联系人" width="120" />
        <el-table-column prop="contactPhone" label="联系电话" width="140" />
        <el-table-column prop="expireTime" label="到期时间" width="180">
          <template #default="{ row }">{{ row.expireTime ?? '不限期' }}</template>
        </el-table-column>
        <el-table-column prop="createTime" label="创建时间" width="180" />
        <el-table-column label="操作" width="240" fixed="right">
          <template #default="{ row }">
            <el-button v-permission="'tenant:edit'" link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button
              v-if="row.status === 'ACTIVE'"
              v-permission="'tenant:edit'"
              link
              type="warning"
              :disabled="row.reserved"
              @click="handleStatusChange(row, 'SUSPENDED')"
            >
              冻结
            </el-button>
            <el-button
              v-else
              v-permission="'tenant:edit'"
              link
              type="success"
              :disabled="row.reserved"
              @click="handleStatusChange(row, 'ACTIVE')"
            >
              恢复
            </el-button>
            <el-button
              v-permission="'tenant:edit'"
              link
              type="info"
              :disabled="row.reserved || row.status === 'TERMINATED'"
              @click="handleStatusChange(row, 'TERMINATED')"
            >
              退租
            </el-button>
            <el-button
              v-permission="'tenant:delete'"
              link
              type="danger"
              :disabled="row.reserved"
              @click="handleDelete(row)"
            >
              删除
            </el-button>
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

    <el-dialog v-model="dialogVisible" :title="dialogMode === 'create' ? '新建租户' : '编辑租户'" width="560px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="租户编码" prop="tenantCode">
          <el-input v-model="form.tenantCode" :disabled="dialogMode === 'edit'" placeholder="如 acme" />
          <div v-if="dialogMode === 'create'" class="form-tip">
            创建后不可修改：它会写进该租户所有业务数据的归属标识。
          </div>
        </el-form-item>
        <el-form-item label="租户名称" prop="tenantName">
          <el-input v-model="form.tenantName" />
        </el-form-item>
        <el-form-item label="联系人">
          <el-input v-model="form.contactName!" />
        </el-form-item>
        <el-form-item label="联系电话">
          <el-input v-model="form.contactPhone!" />
        </el-form-item>
        <el-form-item label="联系邮箱" prop="contactEmail">
          <el-input v-model="form.contactEmail!" />
        </el-form-item>
        <el-form-item label="到期时间">
          <el-date-picker
            v-model="form.expireTime"
            type="datetime"
            placeholder="留空表示不限期"
            value-format="YYYY-MM-DDTHH:mm:ss"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark!" type="textarea" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button class="cw-final-action" type="primary" @click="submit">保存租户</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
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

.form-tip {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.6;
}
</style>
