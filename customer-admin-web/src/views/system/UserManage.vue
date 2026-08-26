<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import type { FormInstance } from 'element-plus'
import {
  createUser,
  deleteUser,
  getUserApprovalOptions,
  pageUsers,
  reviewUser,
  updateUser,
} from '@/api/user'
import { pageRoles } from '@/api/role'
import { fetchCurrentView } from '@/api/tenant'
import { useAuthStore } from '@/store/auth'
import { useCrudPage } from '@/composables/useCrudPage'
import type {
  RoleVO,
  UserApprovalRoleOption,
  UserApprovalRequest,
  UserApprovalStatus,
  UserApprovalTenantOption,
  UserPageQuery,
  UserSaveRequest,
  UserVO,
} from '@/types/api'

const auth = useAuthStore()

const roleOptions = ref<RoleVO[]>([])
const crossTenantAuthority = ref(false)
const formRef = ref<FormInstance>()
const editingApprovalStatus = ref<UserApprovalStatus>('APPROVED')
const reviewDialogVisible = ref(false)
const reviewSubmitting = ref(false)
const reviewOptionsLoading = ref(false)
const reviewTarget = ref<UserVO | null>(null)
const reviewTenantOptions = ref<UserApprovalTenantOption[]>([])
const reviewRoleOptions = ref<UserApprovalRoleOption[]>([])
const reviewForm = reactive<UserApprovalRequest>({
  decision: 'APPROVED',
  tenantId: '',
  roleIds: [],
  remark: '',
})

const {
  loading, list, total, query,
  dialogVisible, dialogMode, form,
  loadList, handleSearch, openCreate, openEdit: openEditCrud, handleSubmit, handleDelete,
} = useCrudPage<UserVO, UserPageQuery, UserSaveRequest>({
  page: pageUsers,
  formRef,
  create: createUser,
  update: async (id, f) => {
    // 编辑时密码框留空表示"不改密码"：必须传 null 而不是空字符串——后端 password 字段有
    // @Size(min=6) 校验，只对 null 跳过校验，空字符串会被当成"长度 0 的密码"直接拦截。
    const payload: UserSaveRequest = { ...f, password: f.password?.trim() ? f.password : null }
    await updateUser(id, payload)
    // 编辑的如果是当前登录用户自己，同步刷新右上角昵称缓存——否则要等重新登录才会更新，
    // 页面上会一直显示登录时缓存的旧昵称，改了跟没改一样。
    if (f.username === auth.username && f.nickname) {
      auth.updateNickname(f.nickname)
    }
  },
  remove: (row) => deleteUser(row.id),
  initQuery: () => ({
    pageNum: 1,
    pageSize: 10,
    keyword: '',
    status: undefined,
    approvalStatus: undefined,
  }),
  initForm: () => ({ username: '', password: '', nickname: '', status: 1, roleIds: [] }),
  toForm: (row) => ({
    username: row.username,
    password: '',
    nickname: row.nickname,
    status: row.status,
    roleIds: row.approvalStatus === 'APPROVED' ? row.roleIds : [],
  }),
  deleteConfirm: (row) => `确认删除用户「${row.username}」？`,
})

function openEdit(row: UserVO) {
  editingApprovalStatus.value = row.approvalStatus
  openEditCrud(row)
}

function approvalText(status: UserApprovalStatus) {
  return { PENDING: '待审核', APPROVED: '已通过', REJECTED: '已拒绝' }[status]
}

function approvalTagType(status: UserApprovalStatus): 'warning' | 'success' | 'danger' {
  if (status === 'APPROVED') {
    return 'success'
  }
  return status === 'PENDING' ? 'warning' : 'danger'
}

async function openReview(row: UserVO) {
  reviewTarget.value = row
  Object.assign(reviewForm, {
    decision: row.approvalStatus === 'REJECTED' ? 'REJECTED' : 'APPROVED',
    tenantId: row.tenantId,
    roleIds: [],
    remark: row.approvalRemark || '',
  })
  reviewDialogVisible.value = true
  await loadReviewOptions(row.tenantId)
}

async function loadReviewOptions(tenantId?: string) {
  reviewOptionsLoading.value = true
  try {
    const options = await getUserApprovalOptions(tenantId)
    reviewTenantOptions.value = options.tenants
    reviewRoleOptions.value = options.roles
    reviewForm.tenantId = options.selectedTenantId
    reviewForm.roleIds = []
  } catch {
    reviewTenantOptions.value = []
    reviewRoleOptions.value = []
  } finally {
    reviewOptionsLoading.value = false
  }
}

async function handleReviewTenantChange(tenantId: string) {
  await loadReviewOptions(tenantId)
}

async function submitReview() {
  if (!reviewTarget.value) {
    return
  }
  if (reviewForm.decision === 'APPROVED' && !reviewForm.roleIds?.length) {
    ElMessage.warning('审核通过时至少分配一个角色')
    return
  }
  if (reviewForm.decision === 'APPROVED' && !reviewForm.tenantId) {
    ElMessage.warning('审核通过时请选择归属租户')
    return
  }
  reviewSubmitting.value = true
  try {
    await reviewUser(reviewTarget.value.id, {
      decision: reviewForm.decision,
      tenantId: reviewForm.decision === 'APPROVED' ? reviewForm.tenantId : reviewTarget.value.tenantId,
      roleIds: reviewForm.decision === 'APPROVED' ? reviewForm.roleIds : [],
      remark: reviewForm.remark?.trim() || null,
    })
    ElMessage.success(reviewForm.decision === 'APPROVED'
      ? `审核通过，用户已归属 ${reviewForm.tenantId}`
      : '已拒绝该注册申请')
    reviewDialogVisible.value = false
    await loadList()
  } finally {
    reviewSubmitting.value = false
  }
}

async function loadRoleOptions() {
  try {
    const result = await pageRoles({ pageNum: 1, pageSize: 100 })
    roleOptions.value = result.list
  } catch {
    roleOptions.value = []
  }
}

onMounted(async () => {
  loadList()
  crossTenantAuthority.value = await fetchCurrentView()
    .then((view) => view.crossTenantAuthority === true)
    .catch(() => false)
  await loadRoleOptions()
})
</script>

<template>
  <div class="page">
    <el-card>
      <div class="toolbar">
        <el-input v-model="query.keyword" placeholder="按用户名搜索" style="width: 220px" clearable @keyup.enter="handleSearch" />
        <el-select v-model="query.status" placeholder="状态" style="width: 120px" clearable>
          <el-option label="启用" :value="1" />
          <el-option label="禁用" :value="0" />
        </el-select>
        <el-select v-model="query.approvalStatus" placeholder="审核状态" style="width: 130px" clearable>
          <el-option label="待审核" value="PENDING" />
          <el-option label="已通过" value="APPROVED" />
          <el-option label="已拒绝" value="REJECTED" />
        </el-select>
        <el-button type="primary" @click="handleSearch">搜索</el-button>
        <el-button v-permission="'user:add'" type="primary" @click="openCreate">新建用户</el-button>
      </div>

      <el-table v-loading="loading" :data="list" style="width: 100%">
        <el-table-column prop="username" label="用户名" />
        <el-table-column prop="tenantId" label="归属租户" min-width="120" />
        <el-table-column prop="nickname" label="昵称" />
        <el-table-column label="角色">
          <template #default="{ row }">{{ row.roleNames?.join('、') || '-' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'">{{ row.status === 1 ? '启用' : '禁用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="审核状态" width="100">
          <template #default="{ row }">
            <el-tag :type="approvalTagType(row.approvalStatus)">{{ approvalText(row.approvalStatus) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="approvalRemark" label="审核说明" min-width="140" show-overflow-tooltip />
        <el-table-column prop="lastLoginTime" label="最近登录" width="180" />
        <el-table-column prop="createTime" label="创建时间" width="180" />
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="row.approvalStatus !== 'APPROVED'"
              v-permission="'user:edit'"
              link
              type="primary"
              @click="openReview(row)"
            >
              {{ row.approvalStatus === 'PENDING' ? '审核' : '重新审核' }}
            </el-button>
            <el-button v-permission="'user:edit'" link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button v-permission="'user:delete'" link type="danger" @click="handleDelete(row)">删除</el-button>
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

    <el-dialog v-model="dialogVisible" :title="dialogMode === 'create' ? '新建用户' : '编辑用户'" width="480px">
      <el-form ref="formRef" :model="form" label-width="80px">
        <el-form-item label="用户名" prop="username" :rules="[{ required: true, message: '请输入用户名' }]">
          <el-input v-model="form.username" :disabled="dialogMode === 'edit'" />
        </el-form-item>
        <el-form-item :label="dialogMode === 'create' ? '初始密码' : '密码'">
          <el-input v-model="form.password!" type="password" show-password :placeholder="dialogMode === 'edit' ? '留空则不修改' : ''" />
        </el-form-item>
        <el-form-item label="昵称">
          <el-input v-model="form.nickname!" />
        </el-form-item>
        <el-form-item label="角色">
          <el-select
            v-model="form.roleIds"
            multiple
            style="width: 100%"
            :disabled="dialogMode === 'edit' && editingApprovalStatus !== 'APPROVED'"
            :placeholder="dialogMode === 'edit' && editingApprovalStatus !== 'APPROVED' ? '请通过审核操作分配角色' : '请选择角色'"
          >
            <el-option
              v-for="role in roleOptions"
              :key="role.id"
              :label="role.roleName"
              :value="role.id"
              :disabled="role.controlPlane && !crossTenantAuthority"
            />
          </el-select>
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

    <el-dialog v-model="reviewDialogVisible" title="注册账号审核" width="520px">
      <el-descriptions v-if="reviewTarget" :column="1" border class="review-user">
        <el-descriptions-item label="用户名">{{ reviewTarget.username }}</el-descriptions-item>
        <el-descriptions-item label="昵称">{{ reviewTarget.nickname || '-' }}</el-descriptions-item>
        <el-descriptions-item label="当前租户">{{ reviewTarget.tenantId }}</el-descriptions-item>
      </el-descriptions>
      <el-form :model="reviewForm" label-width="84px">
        <el-form-item label="审核结果">
          <el-radio-group v-model="reviewForm.decision">
            <el-radio-button value="APPROVED">通过</el-radio-button>
            <el-radio-button value="REJECTED">拒绝</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="reviewForm.decision === 'APPROVED'" label="归属租户" required>
          <el-select
            v-model="reviewForm.tenantId"
            filterable
            style="width: 100%"
            placeholder="请选择用户归属租户"
            :loading="reviewOptionsLoading"
            @change="handleReviewTenantChange"
          >
            <el-option
              v-for="tenant in reviewTenantOptions"
              :key="tenant.tenantId"
              :label="`${tenant.tenantName} (${tenant.tenantId})`"
              :value="tenant.tenantId"
            />
          </el-select>
        </el-form-item>
        <el-form-item v-if="reviewForm.decision === 'APPROVED'" label="分配角色" required>
          <el-select
            v-model="reviewForm.roleIds"
            multiple
            style="width: 100%"
            placeholder="至少选择一个目标租户角色"
            :loading="reviewOptionsLoading"
          >
            <el-option
              v-for="role in reviewRoleOptions"
              :key="role.id"
              :label="`${role.roleName} (${role.roleCode})`"
              :value="role.id"
              :disabled="role.controlPlane && !crossTenantAuthority"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="审核说明">
          <el-input
            v-model="reviewForm.remark"
            type="textarea"
            :rows="3"
            maxlength="255"
            show-word-limit
            :placeholder="reviewForm.decision === 'REJECTED' ? '建议填写拒绝原因' : '选填'"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="reviewDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="reviewSubmitting" @click="submitReview">确认审核</el-button>
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

.review-user {
  margin-bottom: 18px;
}
</style>
