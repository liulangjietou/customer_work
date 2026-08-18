<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import type { FormInstance, TreeInstance } from 'element-plus'
import { createRole, deleteRole, pageRoles, updateRole } from '@/api/role'
import { permissionTree } from '@/api/permission'
import { fetchCurrentView } from '@/api/tenant'
import { useCrudPage } from '@/composables/useCrudPage'
import type { DataScope, PageQuery, PermissionVO, RoleSaveRequest, RoleVO } from '@/types/api'

// 数据范围选项。ALL 只对平台运营方开放——租户管理员能在自己租户里建角色，
// 若让他选 ALL 就等于自己给自己开跨租户的口子（后端 RoleService 另有校验，这里只是不误导）。
const DATA_SCOPE_OPTIONS: Array<{ value: DataScope; label: string; hint: string; platformOnly?: boolean }> = [
  { value: 'ALL', label: '全部数据', hint: '可查看全部租户的数据，平台运营方专用', platformOnly: true },
  { value: 'TENANT', label: '本租户全部', hint: '本租户内所有成员的数据都可见' },
  { value: 'SELF', label: '仅本人', hint: '只能看到自己创建的项目、会话、附件、工作台账号等个人数据' },
]

const DATA_SCOPE_LABELS: Record<DataScope, string> = {
  ALL: '全部数据',
  TENANT: '本租户全部',
  SELF: '仅本人',
}

const platformOperator = ref(false)
const scopeOptions = computed(() =>
  DATA_SCOPE_OPTIONS.filter((option) => !option.platformOnly || platformOperator.value))

const tree = ref<PermissionVO[]>([])
const formRef = ref<FormInstance>()
const treeRef = ref<TreeInstance>()

const treeProps = { label: 'permName', children: 'children' }

const {
  loading, list, total, query,
  dialogVisible, dialogMode, form,
  loadList, handleSearch,
  openCreate: openCreateBase, openEdit: openEditBase,
  handleSubmit, handleDelete,
} = useCrudPage<RoleVO, PageQuery, RoleSaveRequest>({
  page: pageRoles,
  formRef,
  create: createRole,
  update: updateRole,
  remove: (row) => deleteRole(row.id),
  initQuery: () => ({ pageNum: 1, pageSize: 10, keyword: '' }),
  initForm: () => ({ roleName: '', roleCode: '', remark: '', status: 1, dataScope: 'SELF' as DataScope, permissionIds: [] }),
  toForm: (row) => ({ roleName: row.roleName, roleCode: row.roleCode, remark: row.remark, status: row.status, dataScope: row.dataScope ?? 'SELF', permissionIds: row.permissionIds }),
  // 提交前把权限树的选中态（含半选的父节点）收集进表单，与原 handleSubmit 里的顺序一致
  beforeSubmit: (_mode, f) => {
    const checkedKeys = (treeRef.value?.getCheckedKeys() ?? []) as number[]
    const halfCheckedKeys = (treeRef.value?.getHalfCheckedKeys() ?? []) as number[]
    f.permissionIds = [...checkedKeys, ...halfCheckedKeys]
    return true
  },
  deleteConfirm: (row) => `确认删除角色「${row.roleName}」？`,
})

// 弹窗打开后权限树的勾选状态需要单独同步（新建清空/编辑回填），composable 不感知树组件，留在页面包一层
function openCreate() {
  openCreateBase()
  requestAnimationFrame(() => treeRef.value?.setCheckedKeys([]))
}

function openEdit(row: RoleVO) {
  openEditBase(row)
  requestAnimationFrame(() => treeRef.value?.setCheckedKeys(row.permissionIds))
}

onMounted(async () => {
  loadList()
  tree.value = await permissionTree().catch(() => [])
  // 拿不到就当作非平台运营方：多显示一个越权选项的代价，比少显示一个大得多
  platformOperator.value = await fetchCurrentView()
    .then((view) => view.platformOperator === true)
    .catch(() => false)
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
        <el-table-column label="数据范围" width="130">
          <template #default="{ row }">
            <el-tag :type="row.dataScope === 'SELF' ? 'info' : row.dataScope === 'ALL' ? 'danger' : 'warning'" disable-transitions>
              {{ DATA_SCOPE_LABELS[row.dataScope as DataScope] ?? row.dataScope }}
            </el-tag>
          </template>
        </el-table-column>
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
        <el-form-item label="数据范围">
          <el-select v-model="form.dataScope" style="width: 100%">
            <el-option v-for="option in scopeOptions" :key="option.value" :label="option.label" :value="option.value">
              <span>{{ option.label }}</span>
              <span style="float: right; color: var(--el-text-color-secondary); font-size: 12px">{{ option.hint }}</span>
            </el-option>
          </el-select>
          <div class="form-tip">范围在每次请求时实时判定，保存后对该角色下的用户立即生效，无需重新登录。</div>
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

.form-tip {
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.5;
}
</style>
