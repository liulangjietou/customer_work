<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import type { FormInstance } from 'element-plus'
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
import CrudLoadState from '@/components/CrudLoadState.vue'
import { useQueryState } from '@/composables/useQueryState'
import { useCrudForm } from '@/composables/useCrudForm'
import { useRowMutation } from '@/composables/useRowMutation'
import { useAuthSubmissionScope } from '@/composables/useAuthSubmissionScope'
import { useAuthStore } from '@/store/auth'
import type { MenuChangeLogVO, MenuReorderItem, MenuSaveRequest, PermissionVO } from '@/types/api'

const TYPE_OPTIONS = [
  { label: '菜单', value: 1 },
  { label: '按钮', value: 2 },
  { label: '接口', value: 3 },
]
const TYPE_LABEL: Record<number, string> = { 1: '菜单', 2: '按钮', 3: '接口' }
const ACTION_LABEL: Record<string, string> = { CREATE: '新建', UPDATE: '编辑', DELETE: '删除', MOVE: '移动' }
const ICON_NAMES = Object.keys(ElementPlusIconsVue)

const auth = useAuthStore()
const captureSubmission = useAuthSubmissionScope()
const { data: tree, loading, error: loadError, loaded, load: loadTree } = useQueryState(menuAdminTree, () => [] as PermissionVO[])
const changes = useRowMutation('menu:edit')
const deletions = useRowMutation('menu:delete')
const publishing = computed(() => changes.isPending(1))
const reordering = computed(() => changes.isPending(0))
const dirty = ref(false)
let changeVersion = 0
function markDirty() { changeVersion += 1; dirty.value = true }
/** 写入已成功时仍提示待发布；关闭旧弹窗只隔离表单，不能抹掉同一身份已经保存的菜单变更。 */
async function persistMenu(write: () => Promise<void>) {
  const current = captureSubmission()
  await write()
  if (current()) markDirty()
}
const treeProps = { label: 'permName', children: 'children' }

// 弹窗拥有提交目标；附属图标上传使用同一个弹窗生命周期。
const formRef = ref<FormInstance>()
const iconTab = ref<'library' | 'image'>('library')
const iconSearch = ref('')
const iconUploading = ref(false)
const { form, dialogVisible, dialogMode, submitting, openCreate, openEdit: editForm,
  handleSubmit, captureDialog } = useCrudForm<PermissionVO, MenuSaveRequest>({
  formRef,
  initForm: () => ({ parentId: null, permName: '', permCode: '', type: 1, path: '', icon: '', iconType: 'library', sort: 0 }),
  toForm: node => ({ parentId: node.parentId, permName: node.permName, permCode: node.permCode,
    type: node.type, path: node.path ?? '', icon: node.icon ?? '', iconType: node.iconType ?? 'library', sort: node.sort ?? 0 }),
  create: form => persistMenu(() => createMenuNode(form)),
  update: (id, form) => persistMenu(() => updateMenuNode(id, form)),
  beforeSubmit: mode => !iconUploading.value && auth.hasPermission(mode === 'create' ? 'menu:add' : 'menu:edit'),
  onSaved: async () => { await loadTree() },
})
watch(dialogVisible, () => { iconUploading.value = false }, { flush: 'sync' })

const filteredIconNames = computed(() =>
  iconSearch.value ? ICON_NAMES.filter((n) => n.toLowerCase().includes(iconSearch.value.toLowerCase())) : ICON_NAMES,
)

function openCreateRoot() {
  openCreate()
  iconTab.value = 'library'
  iconSearch.value = ''
}

function openCreateChild(parent: PermissionVO) {
  openCreateRoot()
  form.parentId = parent.id
}

function openEdit(node: PermissionVO) {
  editForm(node)
  iconTab.value = form.iconType === 'image' ? 'image' : 'library'
  iconSearch.value = ''
}

function selectLibraryIcon(name: string) {
  if (submitting.value || iconUploading.value) return
  form.icon = name
  form.iconType = 'library'
}

async function handleIconUpload(options: UploadRequestOptions) {
  if (iconUploading.value || submitting.value) return
  const current = captureDialog()
  if (!current()) return
  iconUploading.value = true
  try {
    const url = await uploadMenuIcon(options.file as File)
    if (!current()) return
    form.icon = url
    form.iconType = 'image'
    ElMessage.success('图标上传成功')
  } catch {
    // 请求层提示失败，旧上传不能回填另一张菜单。
  } finally {
    if (current()) iconUploading.value = false
  }
}

async function handleDelete(node: PermissionVO) {
  await deletions.run(node.id, async current => {
    await ElMessageBox.confirm(`确认删除「${node.permName}」？删除前请先清空子节点。`, '提示', { type: 'warning' })
    if (current()) await deleteMenuNode(node.id)
  }, async () => {
    ElMessage.success('删除成功')
    markDirty()
    await loadTree()
  })
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
  // el-tree 已改写本地顺序；失败回读服务端，成功才标记为待发布。
  await changes.run(0, () => reorderMenuNodes(flattenForReorder(tree.value, 0)), async () => {
    ElMessage.success('顺序已调整')
    markDirty()
    await loadTree()
  }, () => { void loadTree() })
}

async function handlePublish() {
  const version = changeVersion
  await changes.run(1, publishMenu, () => {
    if (version === changeVersion) dirty.value = false
    ElMessage.success('已发布，其它在线用户的菜单将在下次轮询时自动刷新')
  })
}

// 记录与目标一起重置，后打开的抽屉不会暂显上一张菜单的历史。
const changeLogVisible = ref(false)
const changeLogPage = reactive({ pageNum: 1, pageSize: 10 })
const changeLogMenuId = ref<number | undefined>(undefined)
const changeLogMenuName = ref('')
const { data: history, loading: changeLogLoading, error: changeLogError, loaded: historyLoaded,
  load: loadChangeLog, reset: resetHistory } = useQueryState(
  () => fetchMenuChangeLog(changeLogMenuId.value, { ...changeLogPage }),
  () => ({ list: [] as MenuChangeLogVO[], total: 0 }),
)
const changeLogList = computed(() => history.value.list)
const changeLogTotal = computed(() => history.value.total)
watch(changeLogVisible, visible => { if (!visible) resetHistory() }, { flush: 'sync' })
watch([() => auth.loginGeneration, () => auth.token, () => auth.permissions.join('\0')], () => {
  dirty.value = false
  changeVersion += 1
  changeLogVisible.value = false
}, { flush: 'sync' })
watch([() => auth.loginGeneration, () => auth.token, () => auth.permissions.join('\0')], () => {
  if (auth.hasPermission('menu:view')) void loadTree()
})

function openChangeLog(node?: PermissionVO) {
  resetHistory()
  changeLogMenuId.value = node?.id
  changeLogMenuName.value = node?.permName ?? ''
  changeLogPage.pageNum = 1
  changeLogVisible.value = true
  void loadChangeLog()
}

onMounted(loadTree)
</script>

<template>
  <div class="page">
    <el-card>
      <div class="toolbar">
        <el-button :loading="loading" :disabled="reordering" @click="loadTree">刷新</el-button>
        <el-button v-permission="'menu:add'" class="cw-final-action" type="primary" @click="openCreateRoot">新增根菜单</el-button>
        <el-button v-permission="'menu:edit'" class="cw-final-action" type="primary" :disabled="!dirty || reordering" :loading="publishing" @click="handlePublish">
          发布<el-badge v-if="dirty" is-dot class="publish-badge" />
        </el-button>
        <el-button v-permission="'menu:view'" @click="openChangeLog()">变更记录</el-button>
      </div>

      <CrudLoadState :error="loadError" :has-stale-data="loaded" :loading="loading" @retry="loadTree" />
      <el-tree
        v-if="!loadError || loaded"
        v-loading="loading"
        :data="tree"
        :props="treeProps"
        node-key="id"
        :draggable="auth.hasPermission('menu:edit') && !reordering && !publishing && !loading && !loadError"
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
              <el-button v-permission="'menu:delete'" link type="danger" :loading="deletions.isPending(data.id)" @click.stop="handleDelete(data)">删除</el-button>
            </span>
          </div>
        </template>
      </el-tree>
    </el-card>

    <el-dialog v-model="dialogVisible" :title="dialogMode === 'create' ? '新建菜单节点' : '编辑菜单节点'" width="600px">
      <el-form ref="formRef" :model="form" :disabled="submitting || iconUploading" label-width="90px">
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
                  <button
                    v-for="name in filteredIconNames"
                    :key="name"
                    type="button"
                    class="icon-grid-item"
                    :disabled="submitting || iconUploading"
                    :class="{ active: form.icon === name && form.iconType === 'library' }"
                    :title="name"
                    :aria-label="`选择图标 ${name}`"
                    :aria-pressed="form.icon === name && form.iconType === 'library'"
                    @click="selectLibraryIcon(name)"
                  >
                    <el-icon><component :is="name" /></el-icon>
                  </button>
                </div>
              </el-tab-pane>
              <el-tab-pane label="上传图片" name="image">
                <el-upload :disabled="submitting || iconUploading" :show-file-list="false" :http-request="handleIconUpload" accept="image/png,image/jpeg,image/gif,image/svg+xml">
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
        <el-button class="cw-final-action" type="primary" :loading="submitting" :disabled="iconUploading" @click="handleSubmit">保存菜单</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="changeLogVisible" :title="changeLogMenuName ? `变更记录 · ${changeLogMenuName}` : '变更记录（全部）'" size="480px">
      <CrudLoadState :error="changeLogError" :has-stale-data="historyLoaded" :loading="changeLogLoading" @retry="loadChangeLog" />
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
  min-height: 38px;
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
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.node-actions {
  margin-left: auto;
  display: none;
  gap: 4px;
  white-space: nowrap;
}

.tree-node:hover .node-actions,
.tree-node:focus-within .node-actions {
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
  border: 1px solid var(--el-border-color);
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
  color: var(--el-text-color-placeholder);
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
  color: inherit;
  background: transparent;
  font: inherit;
}

.icon-grid-item:hover {
  background: var(--el-fill-color-light);
}

.icon-grid-item.active {
  border-color: var(--el-color-primary);
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}

.icon-grid-item:focus-visible {
  outline: 2px solid var(--cw-focus-ring);
  outline-offset: 1px;
}

.upload-tip {
  font-size: 12px;
  color: var(--el-text-color-secondary);
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
  color: var(--el-text-color-secondary);
  margin-bottom: 4px;
}

.snapshot-json {
  font-size: 12px;
  background: var(--el-fill-color-light);
  padding: 8px;
  border-radius: 4px;
  max-height: 200px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-all;
}

@media (max-width: 767px) {
  :deep(.el-tree-node__content) {
    height: auto;
    min-height: 38px;
    align-items: flex-start;
  }

  .tree-node {
    flex-wrap: wrap;
    gap: 6px;
    padding-block: 5px;
  }

  .node-name {
    max-width: 150px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .node-code {
    flex-basis: calc(100% - 28px);
    margin-left: 28px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .node-actions {
    display: inline-flex;
    flex-basis: 100%;
    flex-wrap: wrap;
    margin-left: 28px;
  }

  .icon-picker,
  .snapshot-pair {
    display: grid;
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
