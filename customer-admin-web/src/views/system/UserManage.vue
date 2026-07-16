<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import type { FormInstance } from 'element-plus'
import { createUser, deleteUser, pageUsers, updateUser } from '@/api/user'
import { pageRoles } from '@/api/role'
import { useAuthStore } from '@/store/auth'
import type { PageQuery, RoleVO, UserSaveRequest, UserVO } from '@/types/api'

const auth = useAuthStore()

const loading = ref(false)
const list = ref<UserVO[]>([])
const total = ref(0)
const query = reactive<PageQuery>({ pageNum: 1, pageSize: 10, keyword: '', status: undefined })
const roleOptions = ref<RoleVO[]>([])

const dialogVisible = ref(false)
const dialogMode = ref<'create' | 'edit'>('create')
const formRef = ref<FormInstance>()
const editingId = ref<number | null>(null)
const form = reactive<UserSaveRequest>({ username: '', password: '', nickname: '', status: 1, roleIds: [] })

async function loadList() {
  loading.value = true
  try {
    const result = await pageUsers(query)
    list.value = result.list
    total.value = result.total
  } finally {
    loading.value = false
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

function handleSearch() {
  query.pageNum = 1
  loadList()
}

function openCreate() {
  dialogMode.value = 'create'
  editingId.value = null
  Object.assign(form, { username: '', password: '', nickname: '', status: 1, roleIds: [] })
  dialogVisible.value = true
}

function openEdit(row: UserVO) {
  dialogMode.value = 'edit'
  editingId.value = row.id
  Object.assign(form, { username: row.username, password: '', nickname: row.nickname, status: row.status, roleIds: row.roleIds })
  dialogVisible.value = true
}

async function handleSubmit() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) {
    return
  }
  if (dialogMode.value === 'create') {
    await createUser(form)
    ElMessage.success('新建成功')
  } else if (editingId.value) {
    // 编辑时密码框留空表示"不改密码"：必须传 null 而不是空字符串——后端 password 字段有
    // @Size(min=6) 校验，只对 null 跳过校验，空字符串会被当成"长度 0 的密码"直接拦截。
    const payload: UserSaveRequest = { ...form, password: form.password?.trim() ? form.password : null }
    await updateUser(editingId.value, payload)
    ElMessage.success('保存成功')
    // 编辑的如果是当前登录用户自己，同步刷新右上角昵称缓存——否则要等重新登录才会更新，
    // 页面上会一直显示登录时缓存的旧昵称，改了跟没改一样。
    if (form.username === auth.username && form.nickname) {
      auth.updateNickname(form.nickname)
    }
  }
  dialogVisible.value = false
  await loadList()
}

async function handleDelete(row: UserVO) {
  await ElMessageBox.confirm(`确认删除用户「${row.username}」？`, '提示', { type: 'warning' })
  await deleteUser(row.id)
  ElMessage.success('删除成功')
  await loadList()
}

onMounted(() => {
  loadList()
  loadRoleOptions()
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
        <el-button type="primary" @click="handleSearch">搜索</el-button>
        <el-button v-permission="'user:add'" type="primary" @click="openCreate">新建用户</el-button>
      </div>

      <el-table v-loading="loading" :data="list" style="width: 100%">
        <el-table-column prop="username" label="用户名" />
        <el-table-column prop="nickname" label="昵称" />
        <el-table-column label="角色">
          <template #default="{ row }">{{ row.roleNames?.join('、') || '-' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'">{{ row.status === 1 ? '启用' : '禁用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="lastLoginTime" label="最近登录" width="180" />
        <el-table-column prop="createTime" label="创建时间" width="180" />
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
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
          <el-select v-model="form.roleIds" multiple style="width: 100%">
            <el-option v-for="role in roleOptions" :key="role.id" :label="role.roleName" :value="role.id" />
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
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  gap: 8px;
  margin-bottom: 16px;
}
</style>
