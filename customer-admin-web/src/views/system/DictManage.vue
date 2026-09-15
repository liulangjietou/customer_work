<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import CrudLoadState from '@/components/CrudLoadState.vue'
import { useCrudForm } from '@/composables/useCrudForm'
import { useQueryState } from '@/composables/useQueryState'
import { useRowMutation } from '@/composables/useRowMutation'
import { useAuthStore } from '@/store/auth'
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

const auth = useAuthStore()
const typeKeyword = ref('')
const selectedTypeId = ref<number | null>(null)
const { data: types, loading: typeLoading, error: typeError, loaded: typesLoaded, load: queryTypes } = useQueryState(listDictTypes, () => [] as DictTypeVO[])
const activeType = computed(() => types.value.find(row => row.id === selectedTypeId.value) ?? null)
const { data: items, loading: itemLoading, error: itemError, loaded: itemsLoaded, load: queryItems, reset: resetItems } = useQueryState(
  () => activeType.value ? listDictItems(activeType.value.dictType) : Promise.resolve([]), () => [] as DictItemVO[],
)

function filteredTypes(): DictTypeVO[] {
  const keyword = typeKeyword.value.trim().toLowerCase()
  return types.value.filter(row => !keyword || row.dictType.toLowerCase().includes(keyword) || row.typeName.toLowerCase().includes(keyword))
}

async function loadItems() { await queryItems() }
async function loadTypes(keepActive = true) {
  const accepted = await queryTypes()
  if (!accepted) return
  if (!keepActive || !accepted.some(row => row.id === selectedTypeId.value)) selectedTypeId.value = accepted[0]?.id ?? null
  await loadItems()
}
async function selectType(row: DictTypeVO) {
  selectedTypeId.value = row.id
  await loadItems()
}

const typeFormRef = ref<FormInstance>()
const { form: typeForm, dialogVisible: typeDialogVisible, dialogMode: typeDialogMode,
  submitting: typeSubmitting, openCreate: openCreateType, openEdit: openEditType, handleSubmit: submitType } = useCrudForm<DictTypeVO, DictTypeSaveRequest>({
  formRef: typeFormRef,
  initForm: () => ({ dictType: '', typeName: '', remark: '', enabled: true }),
  toForm: row => ({ dictType: row.dictType, typeName: row.typeName, remark: row.remark ?? '', enabled: row.enabled }),
  create: createDictType, update: updateDictType,
  beforeSubmit: mode => auth.hasPermission(mode === 'create' ? 'dict:add' : 'dict:edit'),
  messages: { created: '类型已创建', updated: '类型已更新' },
  onSaved: loadTypes,
})
const typeRules: FormRules = {
  dictType: [
    { required: true, message: '请输入类型编码', trigger: 'blur' },
    { pattern: /^[a-z][a-z0-9_]{1,63}$/, message: '小写字母开头的小写字母/数字/下划线，2-64 位', trigger: 'blur' },
  ],
  typeName: [{ required: true, message: '请输入类型名称', trigger: 'blur' }],
}

// 创建字典项的父类型随弹窗捕获；切换类型会关闭旧表单，不能把旧输入提交到新类型。
type ItemForm = DictItemSaveRequest & { dictType: string }
const itemFormRef = ref<FormInstance>()
const { form: itemForm, dialogVisible: itemDialogVisible, dialogMode: itemDialogMode,
  submitting: itemSubmitting, openCreate: createItemForm, openEdit: openEditItem, handleSubmit: submitItem } = useCrudForm<DictItemVO, ItemForm>({
  formRef: itemFormRef,
  initForm: () => ({ dictType: '', itemKey: '', itemLabel: '', sort: 0, enabled: true, remark: '' }),
  toForm: row => ({ dictType: row.dictType, itemKey: row.itemKey, itemLabel: row.itemLabel, sort: row.sort, enabled: row.enabled, remark: row.remark ?? '' }),
  create: ({ dictType, ...payload }) => createDictItem(dictType, payload),
  update: (id, { dictType: _dictType, ...payload }) => updateDictItem(id, payload),
  beforeSubmit: mode => !!activeType.value && auth.hasPermission(mode === 'create' ? 'dict:add' : 'dict:edit'),
  messages: { created: '字典项已创建', updated: '字典项已更新' },
  onSaved: loadTypes,
})
const itemRules: FormRules = {
  itemKey: [{ required: true, message: '请输入字典项键（业务值）', trigger: 'blur' }],
  itemLabel: [{ required: true, message: '请输入字典项标签（展示文案）', trigger: 'blur' }],
}
function openCreateItem() {
  if (!activeType.value) return
  createItemForm()
  itemForm.dictType = activeType.value.dictType
  itemForm.sort = items.value.length ? Math.max(...items.value.map(item => item.sort)) + 1 : 1
}
watch(() => activeType.value?.dictType, () => {
  resetItems()
  itemDialogVisible.value = false
}, { flush: 'sync' })
watch([() => auth.loginGeneration, () => auth.token, () => auth.permissions.join('\0')], () => {
  selectedTypeId.value = null
  typeKeyword.value = ''
}, { flush: 'sync' })

const typeDeletes = useRowMutation('dict:delete')
const itemDeletes = useRowMutation('dict:delete')
const itemChanges = useRowMutation('dict:edit')
async function handleDeleteType(row: DictTypeVO) {
  await typeDeletes.run(row.id, async current => {
    await ElMessageBox.confirm(`确认删除字典类型「${row.typeName}」（${row.dictType}）？该类型下仍有字典项时将被拒绝。`, '删除确认', { type: 'warning' })
    if (current()) await deleteDictType(row.id)
  }, async () => { ElMessage.success('类型已删除'); await loadTypes() })
}
async function toggleItem(row: DictItemVO) {
  const enabled = !row.enabled
  await itemChanges.run(row.id, () => updateDictItem(row.id, {
    itemKey: row.itemKey, itemLabel: row.itemLabel, sort: row.sort, enabled, remark: row.remark,
  }), async () => { ElMessage.success(enabled ? '已启用' : '已停用'); await loadItems() })
}
async function handleDeleteItem(row: DictItemVO) {
  await itemDeletes.run(row.id, async current => {
    await ElMessageBox.confirm(`确认删除字典项「${row.itemLabel}」（${row.itemKey}）？`, '删除确认', { type: 'warning' })
    if (current()) await deleteDictItem(row.id)
  }, async () => { ElMessage.success('字典项已删除'); await loadTypes() })
}

function formatTime(ms: number | null): string {
  return ms ? new Date(ms).toLocaleString('zh-CN') : '-'
}

onMounted(loadTypes)
</script>

<template>
  <div class="page">
    <el-alert type="info" :closable="false" show-icon class="notice">
      统一维护业务下拉选项和标签。先选择字典类型，再维护其中的选项；停用类型或选项后，相关业务页面将不再提供该选项。
    </el-alert>

    <div class="layout">
      <!-- 左：字典类型 -->
      <el-card class="type-panel">
        <template #header>
          <div class="panel-header">
            <span>字典类型</span>
            <el-button :loading="typeLoading" size="small" @click="loadTypes()">刷新</el-button>
            <el-button v-permission="'dict:add'" class="cw-final-action" type="primary" size="small" @click="openCreateType">新增类型</el-button>
          </div>
        </template>
        <el-input v-model="typeKeyword" placeholder="按编码或名称过滤" clearable class="type-filter" />
        <CrudLoadState :error="typeError" :has-stale-data="typesLoaded" :loading="typeLoading" @retry="loadTypes" />
        <el-table
          v-if="!typeError || typesLoaded"
          v-loading="typeLoading"
          :data="filteredTypes()"
          highlight-current-row
          :row-class-name="({ row }: { row: DictTypeVO }) => (row.id === activeType?.id ? 'active-row' : '')"
          @row-click="selectType"
        >
          <el-table-column label="类型" min-width="140">
            <template #default="{ row }">
              <button
                type="button"
                class="type-cell"
                :aria-label="`选择字典类型 ${row.typeName}`"
                :aria-pressed="row.id === activeType?.id"
                @click.stop="selectType(row)"
              >
                <span class="type-name">{{ row.typeName }}</span>
                <span class="type-code">{{ row.dictType }}</span>
              </button>
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
          <el-table-column v-if="auth.hasPermission('dict:edit') || auth.hasPermission('dict:delete')" label="操作" width="110" align="center">
            <template #default="{ row }">
              <el-button v-permission="'dict:edit'" link type="primary" size="small" @click.stop="openEditType(row)">编辑</el-button>
              <el-button v-permission="'dict:delete'" link type="danger" size="small" :loading="typeDeletes.isPending(row.id)" @click.stop="handleDeleteType(row)">删除</el-button>
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
        <CrudLoadState :error="itemError" :has-stale-data="itemsLoaded" :loading="itemLoading" @retry="loadItems" />
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
          <el-table-column v-if="auth.hasPermission('dict:edit') || auth.hasPermission('dict:delete')" label="操作" width="160" fixed="right">
            <template #default="{ row }">
              <el-button v-permission="'dict:edit'" link type="primary" @click="openEditItem(row)">编辑</el-button>
              <el-button v-permission="'dict:edit'" link type="primary" :loading="itemChanges.isPending(row.id)" @click="toggleItem(row)">
                {{ row.enabled ? '停用' : '启用' }}
              </el-button>
              <el-button v-permission="'dict:delete'" link type="danger" :loading="itemDeletes.isPending(row.id)" @click="handleDeleteItem(row)">删除</el-button>
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
      <el-form ref="typeFormRef" :model="typeForm" :disabled="typeSubmitting" :rules="typeRules" label-width="90px">
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
        <el-button class="cw-final-action" type="primary" :loading="typeSubmitting" @click="submitType">保存类型</el-button>
      </template>
    </el-dialog>

    <!-- 字典项弹窗 -->
    <el-dialog
      v-model="itemDialogVisible"
      :title="itemDialogMode === 'create' ? '新增字典项' : '编辑字典项'"
      width="480px"
    >
      <el-form ref="itemFormRef" :model="itemForm" :disabled="itemSubmitting" :rules="itemRules" label-width="110px">
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
        <el-button class="cw-final-action" type="primary" :loading="itemSubmitting" @click="submitItem">保存字典项</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.notice {
  margin-bottom: 12px;
}

.layout {
  display: grid;
  grid-template-columns: minmax(300px, 420px) minmax(0, 1fr);
  gap: 12px;
  align-items: flex-start;
}

.type-panel {
  width: auto;
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
  width: 100%;
  padding: 4px 2px;
  color: inherit;
  text-align: left;
  border: 0;
  border-radius: var(--cw-radius-sm);
  background: transparent;
  cursor: pointer;
  font: inherit;
}

.type-cell:hover {
  background: var(--el-fill-color-light);
}

.type-cell:focus-visible {
  outline: 2px solid var(--cw-focus-ring);
  outline-offset: 1px;
}

.type-name {
  font-weight: 500;
}

.type-code {
  color: var(--cw-text-muted);
  font-size: 12px;
}

:deep(.active-row) {
  background: color-mix(in srgb, var(--cw-cobalt) 9%, var(--cw-paper));
}

@media (max-width: 767px) {
  .layout {
    grid-template-columns: minmax(0, 1fr);
  }

  .type-panel,
  .item-panel {
    width: 100%;
  }
}
</style>
