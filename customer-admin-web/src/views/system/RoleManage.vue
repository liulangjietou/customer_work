<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type TreeInstance } from 'element-plus'
import { createRole, deleteRole, pageRoles, updateRole } from '@/api/role'
import { permissionTree } from '@/api/permission'
import type { PageQuery, PermissionVO, RoleSaveRequest, RoleVO } from '@/types/api'

const loading = ref(false)
const list = ref<RoleVO[]>([])
const total = ref(0)
const query = reactive<PageQuery>({ pageNum: 1, pageSize: 10, keyword: '' })
const tree = ref<PermissionVO[]>([])

const dialogVisible = ref(false)
const dialogMode = ref<'create' | 'edit'>('create')
const formRef = ref<FormInstance>()
const treeRef = ref<TreeInstance>()
const editingId = ref<number | null>(null)
const form = reactive<RoleSaveRequest>({ roleName: '', roleCode: '', remark: '', status: 1, permissionIds: [] })

const treeProps = { label: 'permName', children: 'children' }

async function loadList() {
  loading.value = true
  try {
    const result = await pageRoles(query)
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
  Object.assign(form, { roleName: '', roleCode: '', remark: '', status: 1, permissionIds: [] })
  dialogVisible.value = true
  requestAnimationFrame(() => treeRef.value?.setCheckedKeys([]))
}

function openEdit(row: RoleVO) {
  dialogMode.value = 'edit'
  editingId.value = row.id
  Object.assign(form, { roleName: row.roleName, roleCode: row.roleCode, remark: row.remark, status: row.status, permissionIds: row.permissionIds })
  dialogVisible.value = true
  requestAnimationFrame(() => treeRef.value?.setCheckedKeys(row.permissionIds))
}

async function handleSubmit() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) {
    return
  }
  const checkedKeys = (treeRef.value?.getCheckedKeys() ?? []) as number[]
  const halfCheckedKeys = (treeRef.value?.getHalfCheckedKeys() ?? []) as number[]
  form.permissionIds = [...checkedKeys, ...halfCheckedKeys]

  if (dialogMode.value === 'create') {
    await createRole(form)
    ElMessage.success('新建成功')
  } else if (editingId.value) {
    await updateRole(editingId.value, form)
    ElMessage.success('保存成功')
  }
  dialogVisible.value = false
  await loadList()
}

async function handleDelete(row: RoleVO) {
  await ElMessageBox.confirm(`确认删除角色「${row.roleName}」？`, '提示', { type: 'warning' })
  await deleteRole(row.id)
  ElMessage.success('删除成功')
  await loadList()
}

onMounted(async () => {
  loadList()
  tree.value = await permissionTree().catch(() => [])
})
</script>

<template>
  <div class="page">
    <el-card>
      <div class="toolbar">
        <el-input v-model="query.keyword" placeholder="按角色名搜索" style="width: 220px" clearable @keyup.enter="handleSearch" />
        <el-button type="primary" @click="handleSearch">搜索</el-button>
        <el-button v-permission="'role:add'" type="primary" @click="openCreate">新建角色</el-button>
      </div>

      <el-table v-loading="loading" :data="list" style="width: 100%">
        <el-table-column prop="roleName" label="角色名称" />
        <el-table-column prop="roleCode" label="角色编码" />
        <el-table-column prop="remark" label="备注" show-overflow-tooltip />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'">{{ row.status === 1 ? '启用' : '禁用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="创建时间" width="180" />
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button v-permission="'role:edit'" link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button v-permission="'role:delete'" link type="danger" :disabled="row.roleCode === 'super_admin'" @click="handleDelete(row)">删除</el-button>
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

    <el-dialog v-model="dialogVisible" :title="dialogMode === 'create' ? '新建角色' : '编辑角色'" width="560px">
      <el-form ref="formRef" :model="form" label-width="80px">
        <el-form-item label="角色名称" prop="roleName" :rules="[{ required: true, message: '请输入角色名称' }]">
          <el-input v-model="form.roleName" />
        </el-form-item>
        <el-form-item label="角色编码" prop="roleCode" :rules="[{ required: true, message: '请输入角色编码' }]">
          <el-input v-model="form.roleCode" :disabled="dialogMode === 'edit'" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark!" type="textarea" />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="form.status" :active-value="1" :inactive-value="0" />
        </el-form-item>
        <el-form-item label="权限">
          <el-tree
            ref="treeRef"
            :data="tree"
            :props="treeProps"
            node-key="id"
            show-checkbox
            style="width: 100%; max-height: 320px; overflow: auto; border: 1px solid #eee; padding: 8px"
          />
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
