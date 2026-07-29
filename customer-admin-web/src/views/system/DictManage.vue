<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import type { FormInstance, FormRules } from 'element-plus'
import {
  createDictItem,
  createDictType,
  deleteDictItem,
  deleteDictType,
  listDictItems,
  listDictTypes,
  updateDictItem,
  updateDictType,
  type DictItemSaveRequest,
  type DictItemVO,
  type DictTypeSaveRequest,
  type DictTypeVO,
} from '@/api/dict'

// ---------- 类型列表（左侧） ----------

const typeLoading = ref(false)
const types = ref<DictTypeVO[]>([])
const typeKeyword = ref('')
const activeType = ref<DictTypeVO | null>(null)

function filteredTypes(): DictTypeVO[] {
  const kw = typeKeyword.value.trim().toLowerCase()
  if (!kw) return types.value
  return types.value.filter(
    (t) => t.dictType.toLowerCase().includes(kw) || t.typeName.toLowerCase().includes(kw),
  )
}

async function loadTypes(keepActive = true) {
  typeLoading.value = true
  try {
    types.value = await listDictTypes()
    if (keepActive && activeType.value) {
      activeType.value = types.value.find((t) => t.id === activeType.value?.id) ?? types.value[0] ?? null
    } else {
      activeType.value = types.value[0] ?? null
    }
    await loadItems()
  } finally {
    typeLoading.value = false
  }
}

async function selectType(row: DictTypeVO) {
  activeType.value = row
  await loadItems()
}

// ---------- 类型编辑弹窗 ----------

const typeDialogVisible = ref(false)
const typeDialogMode = ref<'create' | 'edit'>('create')
const typeFormRef = ref<FormInstance>()
const editingTypeId = ref<number | null>(null)
const typeForm = reactive<DictTypeSaveRequest>({ dictType: '', typeName: '', remark: '', enabled: true })

const typeRules: FormRules = {
  dictType: [
    { required: true, message: '请输入类型编码', trigger: 'blur' },
    { pattern: /^[a-z][a-z0-9_]{1,63}$/, message: '小写字母开头的小写字母/数字/下划线，2-64 位', trigger: 'blur' },
  ],
  typeName: [{ required: true, message: '请输入类型名称', trigger: 'blur' }],
}

function openCreateType() {
  typeDialogMode.value = 'create'
  editingTypeId.value = null
  Object.assign(typeForm, { dictType: '', typeName: '', remark: '', enabled: true })
  typeDialogVisible.value = true
}

function openEditType(row: DictTypeVO) {
  typeDialogMode.value = 'edit'
  editingTypeId.value = row.id
  Object.assign(typeForm, {
    dictType: row.dictType, typeName: row.typeName, remark: row.remark ?? '', enabled: row.enabled,
  })
  typeDialogVisible.value = true
}

async function submitType() {
  await typeFormRef.value?.validate()
  if (typeDialogMode.value === 'create') {
    await createDictType({ ...typeForm })
    ElMessage.success('类型已创建')
  } else if (editingTypeId.value != null) {
    await updateDictType(editingTypeId.value, { ...typeForm })
    ElMessage.success('类型已更新')
  }
  typeDialogVisible.value = false
  await loadTypes()
}

async function handleDeleteType(row: DictTypeVO) {
  await ElMessageBox.confirm(
    `确认删除字典类型「${row.typeName}」（${row.dictType}）？该类型下仍有字典项时将被拒绝。`,
    '删除确认', { type: 'warning' },
  )
  await deleteDictType(row.id)
  ElMessage.success('类型已删除')
  if (activeType.value?.id === row.id) {
    activeType.value = null
  }
  await loadTypes(false)
}

// ---------- 字典项（右侧） ----------

const itemLoading = ref(false)
const items = ref<DictItemVO[]>([])

async function loadItems() {
  if (!activeType.value) {
    items.value = []
    return
  }
  itemLoading.value = true
  try {
    items.value = await listDictItems(activeType.value.dictType)
  } finally {
    itemLoading.value = false
  }
}

// ---------- 字典项编辑弹窗 ----------

const itemDialogVisible = ref(false)
const itemDialogMode = ref<'create' | 'edit'>('create')
const itemFormRef = ref<FormInstance>()
const editingItemId = ref<number | null>(null)
const itemForm = reactive<DictItemSaveRequest>({ itemKey: '', itemLabel: '', sort: 0, enabled: true, remark: '' })

const itemRules: FormRules = {
  itemKey: [{ required: true, message: '请输入字典项键（业务值）', trigger: 'blur' }],
  itemLabel: [{ required: true, message: '请输入字典项标签（展示文案）', trigger: 'blur' }],
}

function openCreateItem() {
  itemDialogMode.value = 'create'
  editingItemId.value = null
  const nextSort = items.value.length > 0 ? Math.max(...items.value.map((i) => i.sort)) + 1 : 1
  Object.assign(itemForm, { itemKey: '', itemLabel: '', sort: nextSort, enabled: true, remark: '' })
  itemDialogVisible.value = true
}

function openEditItem(row: DictItemVO) {
  itemDialogMode.value = 'edit'
  editingItemId.value = row.id
  Object.assign(itemForm, {
    itemKey: row.itemKey, itemLabel: row.itemLabel, sort: row.sort, enabled: row.enabled, remark: row.remark ?? '',
  })
  itemDialogVisible.value = true
}

async function submitItem() {
  await itemFormRef.value?.validate()
  if (itemDialogMode.value === 'create') {
    if (!activeType.value) return
    await createDictItem(activeType.value.dictType, { ...itemForm })
    ElMessage.success('字典项已创建')
  } else if (editingItemId.value != null) {
    await updateDictItem(editingItemId.value, { ...itemForm })
    ElMessage.success('字典项已更新')
  }
  itemDialogVisible.value = false
  await loadItems()
  await refreshTypeCounts()
}

async function toggleItem(row: DictItemVO) {
  await updateDictItem(row.id, {
    itemKey: row.itemKey, itemLabel: row.itemLabel, sort: row.sort, enabled: !row.enabled, remark: row.remark,
  })
  ElMessage.success(row.enabled ? '已停用' : '已启用')
  await loadItems()
}

async function handleDeleteItem(row: DictItemVO) {
  await ElMessageBox.confirm(`确认删除字典项「${row.itemLabel}」（${row.itemKey}）？`, '删除确认', { type: 'warning' })
  await deleteDictItem(row.id)
  ElMessage.success('字典项已删除')
  await loadItems()
  await refreshTypeCounts()
}

/** 项增删后仅刷新左侧计数，不打断当前选中态。 */
async function refreshTypeCounts() {
  types.value = await listDictTypes()
  if (activeType.value) {
    activeType.value = types.value.find((t) => t.id === activeType.value?.id) ?? activeType.value
  }
}

function formatTime(ms: number | null): string {
  return ms ? new Date(ms).toLocaleString('zh-CN') : '-'
}

onMounted(loadTypes)
</script>

<template>
  <div class="page">
    <el-alert type="info" :closable="false" show-icon class="notice">
      字典解决"就几条枚举数据、不值当建表"的场景：新增一类下拉/标签数据时在此配置即可，业务页面经
      <code>useDict('类型编码')</code> 读取。数据存客服端库（cw_dict_type / cw_dict_item），客服端
      <code>customer-work.dict.store-mode=jdbc</code> 时读同一份数据。
    </el-alert>

    <div class="layout">
      <!-- 左：字典类型 -->
      <el-card class="type-panel">
        <template #header>
          <div class="panel-header">
            <span>字典类型</span>
            <el-button v-permission="'dict:add'" type="primary" size="small" @click="openCreateType">新增类型</el-button>
          </div>
        </template>
        <el-input v-model="typeKeyword" placeholder="按编码或名称过滤" clearable class="type-filter" />
        <el-table
          v-loading="typeLoading"
          :data="filteredTypes()"
          highlight-current-row
          :row-class-name="({ row }) => (row.id === activeType?.id ? 'active-row' : '')"
          @row-click="selectType"
        >
          <el-table-column label="类型" min-width="140">
            <template #default="{ row }">
              <div class="type-cell">
                <span class="type-name">{{ row.typeName }}</span>
                <span class="type-code">{{ row.dictType }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="项数" width="60" align="center">
            <template #default="{ row }">{{ row.itemCount }}</template>
          </el-table-column>
          <el-table-column label="状态" width="70" align="center">
            <template #default="{ row }">
              <el-tag :type="row.enabled ? 'success' : 'info'" size="small">{{ row.enabled ? '启用' : '停用' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="110" align="center">
            <template #default="{ row }">
              <el-button v-permission="'dict:edit'" link type="primary" size="small" @click.stop="openEditType(row)">编辑</el-button>
              <el-button v-permission="'dict:delete'" link type="danger" size="small" @click.stop="handleDeleteType(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-card>

      <!-- 右：字典项 -->
      <el-card class="item-panel">
        <template #header>
          <div class="panel-header">
            <span>
              字典项
              <template v-if="activeType">
                · {{ activeType.typeName }}（<code>{{ activeType.dictType }}</code>）
              </template>
            </span>
            <el-button
              v-permission="'dict:add'"
              type="primary"
              size="small"
              :disabled="!activeType"
              @click="openCreateItem"
            >新增字典项</el-button>
          </div>
        </template>
        <el-empty v-if="!activeType" description="左侧选择一个字典类型" />
        <el-table v-else v-loading="itemLoading" :data="items">
          <el-table-column prop="sort" label="排序" width="70" align="center" />
          <el-table-column prop="itemKey" label="键（业务值）" min-width="140" show-overflow-tooltip />
          <el-table-column prop="itemLabel" label="标签（展示文案）" min-width="140" show-overflow-tooltip />
          <el-table-column label="状态" width="80" align="center">
            <template #default="{ row }">
              <el-tag :type="row.enabled ? 'success' : 'info'" size="small">{{ row.enabled ? '启用' : '停用' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="remark" label="备注" min-width="120" show-overflow-tooltip />
          <el-table-column label="更新时间" width="170">
            <template #default="{ row }">{{ formatTime(row.updatedAtMs) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="160" fixed="right">
            <template #default="{ row }">
              <el-button v-permission="'dict:edit'" link type="primary" @click="openEditItem(row)">编辑</el-button>
              <el-button v-permission="'dict:edit'" link type="primary" @click="toggleItem(row)">
                {{ row.enabled ? '停用' : '启用' }}
              </el-button>
              <el-button v-permission="'dict:delete'" link type="danger" @click="handleDeleteItem(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-card>
    </div>

    <!-- 类型弹窗 -->
    <el-dialog
      v-model="typeDialogVisible"
      :title="typeDialogMode === 'create' ? '新增字典类型' : '编辑字典类型'"
      width="480px"
    >
      <el-form ref="typeFormRef" :model="typeForm" :rules="typeRules" label-width="90px">
        <el-form-item label="类型编码" prop="dictType">
          <el-input v-model="typeForm.dictType" :disabled="typeDialogMode === 'edit'" placeholder="如 order_status" />
        </el-form-item>
        <el-form-item label="类型名称" prop="typeName">
          <el-input v-model="typeForm.typeName" placeholder="如 订单状态" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="typeForm.remark" type="textarea" :rows="2" maxlength="255" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="typeForm.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="typeDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submitType">确定</el-button>
      </template>
    </el-dialog>

    <!-- 字典项弹窗 -->
    <el-dialog
      v-model="itemDialogVisible"
      :title="itemDialogMode === 'create' ? '新增字典项' : '编辑字典项'"
      width="480px"
    >
      <el-form ref="itemFormRef" :model="itemForm" :rules="itemRules" label-width="110px">
        <el-form-item label="键（业务值）" prop="itemKey">
          <el-input v-model="itemForm.itemKey" placeholder="参与业务匹配的值" />
        </el-form-item>
        <el-form-item label="标签（文案）" prop="itemLabel">
          <el-input v-model="itemForm.itemLabel" placeholder="下拉/标签展示的文案" />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="itemForm.sort" :min="0" :max="9999" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="itemForm.enabled" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="itemForm.remark" type="textarea" :rows="2" maxlength="255" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="itemDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submitItem">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.notice {
  margin-bottom: 12px;
}

.layout {
  display: flex;
  gap: 12px;
  align-items: flex-start;
}

.type-panel {
  width: 420px;
  flex-shrink: 0;
}

.item-panel {
  flex: 1;
  min-width: 0;
}

.panel-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.type-filter {
  margin-bottom: 8px;
}

.type-cell {
  display: flex;
  flex-direction: column;
  cursor: pointer;
}

.type-name {
  font-weight: 500;
}

.type-code {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

:deep(.active-row) {
  background: var(--el-color-primary-light-9);
}
</style>
