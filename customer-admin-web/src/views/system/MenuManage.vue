<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance } from 'element-plus'
import type { AllowDropFunction } from 'element-plus/es/components/tree/src/tree.type'
import type { UploadRequestOptions } from 'element-plus/es/components/upload/src/upload'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'
import {
  createMenuNode,
  deleteMenuNode,
  fetchMenuChangeLog,
  menuAdminTree,
  publishMenu,
  reorderMenuNodes,
  updateMenuNode,
  uploadMenuIcon,
} from '@/api/menu-admin'
import type { MenuChangeLogVO, MenuReorderItem, MenuSaveRequest, PermissionVO } from '@/types/api'

const TYPE_OPTIONS = [
  { label: '菜单', value: 1 },
  { label: '按钮', value: 2 },
  { label: '接口', value: 3 },
]
const TYPE_LABEL: Record<number, string> = { 1: '菜单', 2: '按钮', 3: '接口' }
const ACTION_LABEL: Record<string, string> = { CREATE: '新建', UPDATE: '编辑', DELETE: '删除', MOVE: '移动' }
const ICON_NAMES = Object.keys(ElementPlusIconsVue)

const loading = ref(false)
const tree = ref<PermissionVO[]>([])
/** 有未发布的改动（增删改/拖拽都会置位），点"发布"广播给其它在线用户后清零，只是本地 UI 提示，不影响任何实际行为。 */
const dirty = ref(false)
const treeProps = { label: 'permName', children: 'children' }

async function loadTree() {
  loading.value = true
  try {
    tree.value = await menuAdminTree()
  } finally {
    loading.value = false
  }
}

// ---------- 新建/编辑弹窗 ----------
const dialogVisible = ref(false)
const dialogMode = ref<'create' | 'edit'>('create')
const formRef = ref<FormInstance>()
const editingId = ref<number | null>(null)
const iconTab = ref<'library' | 'image'>('library')
const iconSearch = ref('')
const form = reactive<MenuSaveRequest>({
  parentId: null,
  permName: '',
  permCode: '',
  type: 1,
  path: '',
  icon: '',
  iconType: 'library',
  sort: 0,
})

const filteredIconNames = computed(() =>
  iconSearch.value ? ICON_NAMES.filter((n) => n.toLowerCase().includes(iconSearch.value.toLowerCase())) : ICON_NAMES,
)

function resetForm(parentId: number | null) {
  Object.assign(form, { parentId, permName: '', permCode: '', type: 1, path: '', icon: '', iconType: 'library', sort: 0 })
  iconTab.value = 'library'
  iconSearch.value = ''
}

function openCreateRoot() {
  dialogMode.value = 'create'
  editingId.value = null
  resetForm(null)
  dialogVisible.value = true
}

function openCreateChild(parent: PermissionVO) {
  dialogMode.value = 'create'
  editingId.value = null
  resetForm(parent.id)
  dialogVisible.value = true
}

function openEdit(node: PermissionVO) {
  dialogMode.value = 'edit'
  editingId.value = node.id
  Object.assign(form, {
    parentId: node.parentId,
    permName: node.permName,
    permCode: node.permCode,
    type: node.type,
    path: node.path ?? '',
    icon: node.icon ?? '',
    iconType: node.iconType ?? 'library',
    sort: node.sort ?? 0,
  })
  iconTab.value = form.iconType === 'image' ? 'image' : 'library'
  iconSearch.value = ''
  dialogVisible.value = true
}

function selectLibraryIcon(name: string) {
  form.icon = name
  form.iconType = 'library'
}

async function handleIconUpload(options: UploadRequestOptions) {
  try {
    const url = await uploadMenuIcon(options.file as File)
    form.icon = url
    form.iconType = 'image'
    ElMessage.success('图标上传成功')
  } catch {
    // 全局 axios 拦截器已经弹过错误提示，这里不用重复弹
  }
}

async function handleSubmit() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) {
    return
  }
  if (dialogMode.value === 'create') {
    await createMenuNode(form)
    ElMessage.success('新建成功')
  } else if (editingId.value) {
    await updateMenuNode(editingId.value, form)
    ElMessage.success('保存成功')
  }
  dirty.value = true
  dialogVisible.value = false
  await loadTree()
}

async function handleDelete(node: PermissionVO) {
  await ElMessageBox.confirm(`确认删除「${node.permName}」？删除前请先清空子节点。`, '提示', { type: 'warning' })
  await deleteMenuNode(node.id)
  ElMessage.success('删除成功')
  dirty.value = true
  await loadTree()
}

// ---------- 拖拽排序 ----------
// 只有"菜单"类型节点能当父节点；按钮/接口天然是叶子，不允许往里面塞子节点
const allowDrop: AllowDropFunction = (_draggingNode, dropNode, type) => {
  if (type === 'inner') {
    return (dropNode.data as PermissionVO).type === 1
  }
  return true
}

function flattenForReorder(nodes: PermissionVO[], parentId: number): MenuReorderItem[] {
  const items: MenuReorderItem[] = []
  nodes.forEach((node, index) => {
    items.push({ id: node.id, parentId, sort: index + 1 })
    if (node.children?.length) {
      items.push(...flattenForReorder(node.children, node.id))
    }
  })
  return items
}

async function handleNodeDrop() {
  // el-tree 拖拽后已经把新顺序写回了 tree.value（受控 :data），这里整树摊平重新计算 parentId/sort
  // 一次性提交，不做增量 diff——菜单树节点量小，简单可靠比抠性能更重要。
  await reorderMenuNodes(flattenForReorder(tree.value, 0))
  ElMessage.success('顺序已调整')
  dirty.value = true
  await loadTree()
}

// ---------- 发布 ----------
async function handlePublish() {
  await publishMenu()
  dirty.value = false
  ElMessage.success('已发布，其它在线用户的菜单将在下次轮询时自动刷新')
}

// ---------- 变更记录 ----------
const changeLogVisible = ref(false)
const changeLogLoading = ref(false)
const changeLogList = ref<MenuChangeLogVO[]>([])
const changeLogTotal = ref(0)
const changeLogPage = reactive({ pageNum: 1, pageSize: 10 })
const changeLogMenuId = ref<number | undefined>(undefined)
const changeLogMenuName = ref('')

async function loadChangeLog() {
  changeLogLoading.value = true
  try {
    const result = await fetchMenuChangeLog(changeLogMenuId.value, changeLogPage)
    changeLogList.value = result.list
    changeLogTotal.value = result.total
  } finally {
    changeLogLoading.value = false
  }
}

function openChangeLog(node?: PermissionVO) {
  changeLogMenuId.value = node?.id
  changeLogMenuName.value = node?.permName ?? ''
  changeLogPage.pageNum = 1
  changeLogVisible.value = true
  loadChangeLog()
}

onMounted(loadTree)
</script>

<template>
  <div class="page">
    <el-card>
      <div class="toolbar">
        <el-button v-permission="'menu:add'" type="primary" @click="openCreateRoot">新增根菜单</el-button>
        <el-button v-permission="'menu:edit'" type="success" :disabled="!dirty" @click="handlePublish">
          发布<el-badge v-if="dirty" is-dot class="publish-badge" />
        </el-button>
        <el-button v-permission="'menu:view'" @click="openChangeLog()">变更记录</el-button>
      </div>

      <el-tree
        v-loading="loading"
        :data="tree"
        :props="treeProps"
        node-key="id"
        draggable
        default-expand-all
        :allow-drop="allowDrop"
        @node-drop="handleNodeDrop"
      >
        <template #default="{ data }">
          <div class="tree-node">
            <span class="node-icon">
              <img v-if="data.iconType === 'image' && data.icon" :src="data.icon" alt="" />
              <el-icon v-else><component :is="data.icon || 'Document'" /></el-icon>
            </span>
            <span class="node-name">{{ data.permName }}</span>
            <el-tag size="small" :type="data.type === 1 ? 'primary' : data.type === 2 ? 'success' : 'info'">
              {{ TYPE_LABEL[data.type] }}
            </el-tag>
            <span class="node-code">{{ data.permCode }}</span>
            <span class="node-actions">
              <el-button v-permission="'menu:add'" link type="primary" @click.stop="openCreateChild(data)">新增子节点</el-button>
              <el-button v-permission="'menu:edit'" link type="primary" @click.stop="openEdit(data)">编辑</el-button>
              <el-button v-permission="'menu:view'" link @click.stop="openChangeLog(data)">历史</el-button>
              <el-button v-permission="'menu:delete'" link type="danger" @click.stop="handleDelete(data)">删除</el-button>
            </span>
          </div>
        </template>
      </el-tree>
    </el-card>

    <el-dialog v-model="dialogVisible" :title="dialogMode === 'create' ? '新建菜单节点' : '编辑菜单节点'" width="600px">
      <el-form ref="formRef" :model="form" label-width="90px">
        <el-form-item label="名称" prop="permName" :rules="[{ required: true, message: '请输入名称' }]">
          <el-input v-model="form.permName" />
        </el-form-item>
        <el-form-item label="权限标识" prop="permCode" :rules="[{ required: true, message: '请输入权限标识' }]">
          <el-input v-model="form.permCode" :disabled="dialogMode === 'edit'" placeholder="如 menu:view" />
        </el-form-item>
        <el-form-item label="类型" prop="type" :rules="[{ required: true, message: '请选择类型' }]">
          <el-radio-group v-model="form.type">
            <el-radio v-for="opt in TYPE_OPTIONS" :key="opt.value" :value="opt.value">{{ opt.label }}</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="路由/路径">
          <el-input v-model="form.path!" placeholder="菜单类型填前端路由，如 /system/menu；SQL 报表菜单填 /sql/query?defineKey=xxx" />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="form.sort!" :min="0" />
        </el-form-item>
        <el-form-item v-if="form.type === 1" label="图标">
          <div class="icon-picker">
            <div class="icon-preview">
              <img v-if="form.iconType === 'image' && form.icon" :src="form.icon" alt="" />
              <el-icon v-else-if="form.icon" :size="24"><component :is="form.icon" /></el-icon>
              <span v-else class="icon-preview-empty">未选择</span>
            </div>
            <el-tabs v-model="iconTab" class="icon-tabs">
              <el-tab-pane label="图标库" name="library">
                <el-input v-model="iconSearch" placeholder="搜索图标名" clearable style="margin-bottom: 8px" />
                <div class="icon-grid">
                  <div
                    v-for="name in filteredIconNames"
                    :key="name"
                    class="icon-grid-item"
                    :class="{ active: form.icon === name && form.iconType === 'library' }"
                    :title="name"
                    @click="selectLibraryIcon(name)"
                  >
                    <el-icon><component :is="name" /></el-icon>
                  </div>
                </div>
              </el-tab-pane>
              <el-tab-pane label="上传图片" name="image">
                <el-upload :show-file-list="false" :http-request="handleIconUpload" accept="image/png,image/jpeg,image/gif,image/svg+xml">
                  <el-button>选择图片上传</el-button>
                  <template #tip>
                    <div class="upload-tip">支持 png/jpg/jpeg/gif/svg，不超过 1MB</div>
                  </template>
                </el-upload>
              </el-tab-pane>
            </el-tabs>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleSubmit">确定</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="changeLogVisible" :title="changeLogMenuName ? `变更记录 · ${changeLogMenuName}` : '变更记录（全部）'" size="480px">
      <el-table v-loading="changeLogLoading" :data="changeLogList" style="width: 100%">
        <el-table-column label="操作" width="70">
          <template #default="{ row }">{{ ACTION_LABEL[row.action] || row.action }}</template>
        </el-table-column>
        <el-table-column prop="operatorName" label="操作人" width="100" />
        <el-table-column prop="createTime" label="时间" width="160" />
      </el-table>
      <el-collapse class="change-log-detail">
        <el-collapse-item v-for="row in changeLogList" :key="row.id" :title="`#${row.id} 变更前后详情`" :name="row.id">
          <div class="snapshot-pair">
            <div>
              <div class="snapshot-label">变更前</div>
              <pre class="snapshot-json">{{ row.beforeSnapshot ? JSON.stringify(JSON.parse(row.beforeSnapshot), null, 2) : '（无）' }}</pre>
            </div>
            <div>
              <div class="snapshot-label">变更后</div>
              <pre class="snapshot-json">{{ row.afterSnapshot ? JSON.stringify(JSON.parse(row.afterSnapshot), null, 2) : '（无）' }}</pre>
            </div>
          </div>
        </el-collapse-item>
      </el-collapse>
      <el-pagination
        v-model:current-page="changeLogPage.pageNum"
        v-model:page-size="changeLogPage.pageSize"
        :total="changeLogTotal"
        layout="total, prev, pager, next"
        style="margin-top: 16px; justify-content: flex-end"
        @current-change="loadChangeLog"
      />
    </el-drawer>
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  gap: 8px;
  margin-bottom: 16px;
  align-items: center;
}

.publish-badge {
  margin-left: 4px;
}

.tree-node {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding-right: 8px;
}

.node-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
}

.node-icon img {
  width: 18px;
  height: 18px;
  object-fit: contain;
}

.node-name {
  font-weight: 500;
}

.node-code {
  color: #909399;
  font-size: 12px;
}

.node-actions {
  margin-left: auto;
  display: none;
  gap: 4px;
  white-space: nowrap;
}

.tree-node:hover .node-actions {
  display: inline-flex;
}

.icon-picker {
  display: flex;
  gap: 12px;
  width: 100%;
}

.icon-preview {
  flex: none;
  width: 48px;
  height: 48px;
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.icon-preview img {
  width: 32px;
  height: 32px;
  object-fit: contain;
}

.icon-preview-empty {
  font-size: 12px;
  color: #c0c4cc;
}

.icon-tabs {
  flex: 1;
  min-width: 0;
}

.icon-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(32px, 1fr));
  gap: 6px;
  max-height: 220px;
  overflow-y: auto;
}

.icon-grid-item {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 32px;
  border-radius: 4px;
  cursor: pointer;
  border: 1px solid transparent;
}

.icon-grid-item:hover {
  background: #f5f7fa;
}

.icon-grid-item.active {
  border-color: #409eff;
  color: #409eff;
  background: #ecf5ff;
}

.upload-tip {
  font-size: 12px;
  color: #909399;
  margin-top: 4px;
}

.change-log-detail {
  margin-top: 16px;
}

.snapshot-pair {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
}

.snapshot-label {
  font-size: 12px;
  color: #909399;
  margin-bottom: 4px;
}

.snapshot-json {
  font-size: 12px;
  background: #f5f7fa;
  padding: 8px;
  border-radius: 4px;
  max-height: 200px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
