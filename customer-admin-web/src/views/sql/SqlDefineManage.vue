<script setup lang="ts">
import { computed, onMounted, onScopeDispose, reactive, ref, watch } from 'vue'
import type { FormInstance } from 'element-plus'
import {
  createSqlDefine,
  createSqlDefineParam,
  createSqlFieldTransform,
  deleteSqlDefine,
  deleteSqlDefineParam,
  deleteSqlFieldTransform,
  copySqlDefine,
  listAllSqlDatasources,
  listSqlDefineParams,
  listSqlFieldTransforms,
  pageSqlDefines,
  updateSqlDefine,
  updateSqlDefineParam,
  updateSqlFieldTransform,
} from '@/api/sql'
import { useCrudPage } from '@/composables/useCrudPage'
import { useQueryState } from '@/composables/useQueryState'
import { useRowMutation } from '@/composables/useRowMutation'
import { useAuthStore } from '@/store/auth'
import CrudLoadState from '@/components/CrudLoadState.vue'
import type {
  PageQuery,
  SqlDatasourceVO,
  SqlDefineParamSaveRequest,
  SqlDefineParamVO,
  SqlDefineSaveRequest,
  SqlDefineVO,
  SqlFieldTransformSaveRequest,
  SqlFieldTransformVO,
  SqlParamType,
  SqlTransformType,
} from '@/types/api'

const PARAM_TYPE_OPTIONS: { label: string; value: SqlParamType }[] = [
  { label: '字符串', value: 'STRING' },
  { label: '整数', value: 'INTEGER' },
  { label: '日期时间', value: 'DATETIME' },
]
const TRANSFORM_TYPE_OPTIONS: { label: string; value: SqlTransformType }[] = [
  { label: '日期格式化', value: 'DATE_FORMAT' },
  { label: '值映射', value: 'VALUE_MAP' },
]
/** DATETIME 参数常用日期格式预设（下拉可手输其他合法 Java 格式串）。 */
const DATE_FORMAT_OPTIONS = ['yyyy-MM-dd HH:mm:ss', 'yyyy-MM-dd']

const auth = useAuthStore()
const children = useRowMutation<string>('sql-define:edit')
const copies = useRowMutation<number>('sql-define:add')
const { data: datasourceOptions, loading: datasourceLoading, error: datasourceError, loaded: datasourcesLoaded, load: loadDatasourceOptions } =
  useQueryState<SqlDatasourceVO[]>(listAllSqlDatasources, () => [])
const datasourceBlocked = computed(() => datasourceLoading.value || !!datasourceError.value || !datasourcesLoaded.value)

// ---------- 新建/编辑（抽屉） ----------
const formRef = ref<FormInstance>()

const {
  loading, loadError, submitting, deletingId, list, total, query,
  dialogVisible: drawerVisible, dialogMode: drawerMode, form,
  loadList, handleSearch, openCreate, openEdit, handleSubmit, handleDelete,
} = useCrudPage<SqlDefineVO, PageQuery, SqlDefineSaveRequest>({
  page: pageSqlDefines,
  formRef,
  create: createSqlDefine,
  update: updateSqlDefine,
  remove: (row) => deleteSqlDefine(row.id),
  initQuery: () => ({ pageNum: 1, pageSize: 10, keyword: '' }),
  initForm: () => ({
    defineKey: '', datasourceId: undefined as unknown as number, sqlDescribe: '',
    querySql: '', countSql: '', autoLoad: false, enabled: true, remark: '',
  }),
  toForm: (row) => ({
    defineKey: row.defineKey, datasourceId: row.datasourceId, sqlDescribe: row.sqlDescribe,
    querySql: row.querySql, countSql: row.countSql, autoLoad: row.autoLoad, enabled: row.enabled, remark: row.remark,
  }),
  deleteConfirm: (row) => `确认删除 SQL 定义「${row.defineKey}」？关联的参数与列转换器会一并删除。`,
  beforeSubmit: () => !datasourceBlocked.value,
})

async function handleCopy(row: SqlDefineVO) {
  let completed = false
  await copies.run(row.id, async isCurrent => {
    await ElMessageBox.confirm(`确认复制 SQL 定义「${row.defineKey}」？会连同参数与列转换器一起复制一份。`, '提示', { type: 'info' })
    if (!isCurrent()) return
    await copySqlDefine(row.id)
    completed = true
  }, async () => {
    if (!completed) return
    ElMessage.success('复制成功')
    await loadList()
  })
}

// ---------- 参数配置 ----------
const paramsDialogVisible = ref(false)
const paramsDefineId = ref<number | null>(null)
const paramsDefineKey = ref('')
let paramsGeneration = 0
const { data: paramsList, loading: paramsLoading, error: paramsError, load: loadParams, reset: resetParams } =
  useQueryState<SqlDefineParamVO[]>(() => paramsDefineId.value == null ? Promise.resolve([]) : listSqlDefineParams(paramsDefineId.value), () => [])

async function openParams(row: SqlDefineVO) {
  paramsGeneration += 1
  resetParams()
  paramFormVisible.value = false
  paramsDefineId.value = row.id
  paramsDefineKey.value = row.defineKey
  paramsDialogVisible.value = true
  await loadParams()
}

const paramFormVisible = ref(false)
const paramFormRef = ref<FormInstance>()
const editingParamId = ref<number | null>(null)
const paramFormGeneration = ref(0)
const paramSubmitting = computed(() => children.isPending(`param-form:${paramFormGeneration.value}`))
const paramForm = reactive<SqlDefineParamSaveRequest>({
  paramName: '', paramDesc: '', paramType: 'STRING', dateFormat: '', required: false,
  defaultValue: '', dropDown: '', isPageNum: false, isPageSize: false, sort: 0,
})

function resetParamForm() {
  Object.assign(paramForm, {
    paramName: '', paramDesc: '', paramType: 'STRING', dateFormat: '', required: false,
    defaultValue: '', dropDown: '', isPageNum: false, isPageSize: false, sort: 0,
  })
}

function openParamCreate() {
  paramFormGeneration.value += 1
  editingParamId.value = null
  resetParamForm()
  paramFormVisible.value = true
}

function openParamEdit(row: SqlDefineParamVO) {
  paramFormGeneration.value += 1
  editingParamId.value = row.id
  Object.assign(paramForm, {
    paramName: row.paramName, paramDesc: row.paramDesc, paramType: row.paramType, dateFormat: row.dateFormat ?? '', required: row.required,
    defaultValue: row.defaultValue, dropDown: row.dropDown, isPageNum: row.isPageNum, isPageSize: row.isPageSize, sort: row.sort,
  })
  paramFormVisible.value = true
}

async function handleParamSubmit() {
  const parentId = paramsDefineId.value
  const editingId = editingParamId.value
  const generation = paramFormGeneration.value
  if (parentId == null || paramsLoading.value || paramsError.value) return
  const payload = { ...paramForm, dateFormat: paramForm.paramType === 'DATETIME' ? paramForm.dateFormat : '' }
  const isCurrentForm = () => paramsDialogVisible.value && paramFormVisible.value
    && paramsDefineId.value === parentId && paramFormGeneration.value === generation
  let completed = false
  await children.run(`param-form:${generation}`, async isCurrent => {
    const valid = await paramFormRef.value?.validate().catch(() => false)
    if (!valid || !isCurrent() || !isCurrentForm()) return
    if (editingId != null) {
      await updateSqlDefineParam(parentId, editingId, payload)
    } else {
      await createSqlDefineParam(parentId, payload)
    }
    completed = true
  }, async () => {
    if (!completed || !isCurrentForm()) return
    ElMessage.success(editingId == null ? '新增成功' : '保存成功')
    paramFormVisible.value = false
    await loadParams()
  })
}

async function handleParamDelete(row: SqlDefineParamVO) {
  const parentId = paramsDefineId.value
  const generation = paramsGeneration
  if (parentId == null) return
  const isCurrentParent = () => paramsDialogVisible.value && parentId === paramsDefineId.value && generation === paramsGeneration
  let completed = false
  await children.run(`param-delete:${parentId}:${row.id}`, async isCurrent => {
    await ElMessageBox.confirm(`确认删除参数「${row.paramName}」？`, '提示', { type: 'warning' })
    if (!isCurrent() || !isCurrentParent()) return
    await deleteSqlDefineParam(parentId, row.id)
    completed = true
  }, async () => {
    if (!completed || !isCurrentParent()) return
    ElMessage.success('删除成功')
    await loadParams()
  })
}

// ---------- 列转换器 ----------
const transformsDialogVisible = ref(false)
const transformsDefineId = ref<number | null>(null)
const transformsDefineKey = ref('')
let transformsGeneration = 0
const { data: transformsList, loading: transformsLoading, error: transformsError, load: loadTransforms, reset: resetTransforms } =
  useQueryState<SqlFieldTransformVO[]>(() => transformsDefineId.value == null ? Promise.resolve([]) : listSqlFieldTransforms(transformsDefineId.value), () => [])

async function openTransforms(row: SqlDefineVO) {
  transformsGeneration += 1
  resetTransforms()
  transformFormVisible.value = false
  transformsDefineId.value = row.id
  transformsDefineKey.value = row.defineKey
  transformsDialogVisible.value = true
  await loadTransforms()
}

const transformFormVisible = ref(false)
const transformFormRef = ref<FormInstance>()
const editingTransformId = ref<number | null>(null)
const transformFormGeneration = ref(0)
const transformSubmitting = computed(() => children.isPending(`transform-form:${transformFormGeneration.value}`))
const transformForm = reactive<SqlFieldTransformSaveRequest>({
  fieldName: '', transformType: 'DATE_FORMAT', transformConfig: '',
})

function resetTransformForm() {
  Object.assign(transformForm, { fieldName: '', transformType: 'DATE_FORMAT', transformConfig: '' })
}

function openTransformCreate() {
  transformFormGeneration.value += 1
  editingTransformId.value = null
  resetTransformForm()
  transformFormVisible.value = true
}

function openTransformEdit(row: SqlFieldTransformVO) {
  transformFormGeneration.value += 1
  editingTransformId.value = row.id
  Object.assign(transformForm, { fieldName: row.fieldName, transformType: row.transformType, transformConfig: row.transformConfig })
  transformFormVisible.value = true
}

function transformConfigPlaceholder() {
  return transformForm.transformType === 'DATE_FORMAT' ? '如 MM-dd HH:mm:ss' : '如 {"1":"成功","0":"失败"}'
}

async function handleTransformSubmit() {
  const parentId = transformsDefineId.value
  const editingId = editingTransformId.value
  const generation = transformFormGeneration.value
  if (parentId == null || transformsLoading.value || transformsError.value) return
  const payload = { ...transformForm }
  const isCurrentForm = () => transformsDialogVisible.value && transformFormVisible.value
    && transformsDefineId.value === parentId && transformFormGeneration.value === generation
  let completed = false
  await children.run(`transform-form:${generation}`, async isCurrent => {
    const valid = await transformFormRef.value?.validate().catch(() => false)
    if (!valid || !isCurrent() || !isCurrentForm()) return
    if (editingId != null) {
      await updateSqlFieldTransform(parentId, editingId, payload)
    } else {
      await createSqlFieldTransform(parentId, payload)
    }
    completed = true
  }, async () => {
    if (!completed || !isCurrentForm()) return
    ElMessage.success(editingId == null ? '新增成功' : '保存成功')
    transformFormVisible.value = false
    await loadTransforms()
  })
}

async function handleTransformDelete(row: SqlFieldTransformVO) {
  const parentId = transformsDefineId.value
  const generation = transformsGeneration
  if (parentId == null) return
  const isCurrentParent = () => transformsDialogVisible.value && parentId === transformsDefineId.value && generation === transformsGeneration
  let completed = false
  await children.run(`transform-delete:${parentId}:${row.id}`, async isCurrent => {
    await ElMessageBox.confirm(`确认删除列转换器「${row.fieldName}」？`, '提示', { type: 'warning' })
    if (!isCurrent() || !isCurrentParent()) return
    await deleteSqlFieldTransform(parentId, row.id)
    completed = true
  }, async () => {
    if (!completed || !isCurrentParent()) return
    ElMessage.success('删除成功')
    await loadTransforms()
  })
}

watch(paramFormVisible, visible => { if (!visible) paramFormGeneration.value += 1 }, { flush: 'sync' })
watch(transformFormVisible, visible => { if (!visible) transformFormGeneration.value += 1 }, { flush: 'sync' })
watch(paramsDialogVisible, visible => {
  if (!visible) {
    paramsGeneration += 1
    resetParams()
    paramFormVisible.value = false
  }
}, { flush: 'sync' })
watch(transformsDialogVisible, visible => {
  if (!visible) {
    transformsGeneration += 1
    resetTransforms()
    transformFormVisible.value = false
  }
}, { flush: 'sync' })
function resetChildren() {
  paramsGeneration += 1
  transformsGeneration += 1
  paramsDialogVisible.value = transformsDialogVisible.value = false
  paramFormVisible.value = transformFormVisible.value = false
  paramsDefineId.value = transformsDefineId.value = null
  resetParams()
  resetTransforms()
}
watch([() => auth.token, () => auth.loginGeneration, () => auth.permissions.join('\0')], () => {
  resetChildren()
  if (auth.isLoggedIn && auth.isApproved && auth.hasPermission('sql-define:view')) void loadDatasourceOptions()
}, { immediate: true })
onScopeDispose(resetChildren)
onMounted(loadList)
</script>

<template>
  <div class="page">
    <CrudLoadState :error="loadError" :has-stale-data="list.length > 0" :loading="loading" @retry="loadList" />
    <el-card>
      <div class="toolbar">
        <el-input v-model="query.keyword" placeholder="按 defineKey/描述搜索" style="width: 240px" clearable @keyup.enter="handleSearch" />
        <el-button type="primary" @click="handleSearch">搜索</el-button>
        <el-button v-permission="'sql-define:add'" class="cw-final-action" type="primary" @click="openCreate">新建 SQL 定义</el-button>
      </div>

      <el-table v-if="!loadError || list.length > 0" v-loading="loading" :data="list" style="width: 100%" empty-text="暂无符合条件的 SQL 定义">
        <el-table-column prop="defineKey" label="defineKey" width="180" />
        <el-table-column prop="sqlDescribe" label="描述" show-overflow-tooltip />
        <el-table-column prop="datasourceName" label="数据源" width="140" />
        <el-table-column label="启用" width="80">
          <template #default="{ row }">
            <el-tag :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? '是' : '否' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="自动加载" width="90">
          <template #default="{ row }">
            <el-tag v-if="row.autoLoad" type="warning">是</el-tag>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column prop="updateTime" label="更新时间" width="170" />
        <el-table-column label="操作" width="340" fixed="right">
          <template #default="{ row }: { row: SqlDefineVO }">
            <el-button link type="primary" @click="openParams(row)">参数配置</el-button>
            <el-button link type="primary" @click="openTransforms(row)">列转换器</el-button>
            <el-button v-permission="'sql-define:edit'" link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button v-permission="'sql-define:add'" link type="primary" :loading="copies.isPending(row.id)" :disabled="copies.isPending(row.id)" @click="handleCopy(row)">复制</el-button>
            <el-button v-permission="'sql-define:delete'" link type="danger" :loading="deletingId === row.id" @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-if="!loadError || list.length > 0"
        v-model:current-page="query.pageNum"
        v-model:page-size="query.pageSize"
        :total="total"
        layout="total, prev, pager, next"
        style="margin-top: 16px; justify-content: flex-end"
        @current-change="loadList"
      />
    </el-card>

    <!-- 新建/编辑 SQL 定义 -->
    <el-drawer v-model="drawerVisible" :title="drawerMode === 'create' ? '新建 SQL 定义' : '编辑 SQL 定义'" size="640px">
      <CrudLoadState :error="datasourceError" :has-stale-data="datasourcesLoaded" :loading="datasourceLoading" @retry="loadDatasourceOptions" />
      <el-form ref="formRef" :model="form" :disabled="submitting" label-width="100px">
        <el-form-item label="defineKey" prop="defineKey" :rules="[{ required: true, message: '请输入 defineKey' }]">
          <el-input v-model="form.defineKey" :disabled="drawerMode === 'edit'" placeholder="唯一标识，报表菜单靠它关联，如 order_daily_stat" />
        </el-form-item>
        <el-form-item label="数据源" prop="datasourceId" :rules="[{ required: true, message: '请选择数据源' }]">
          <el-select v-model="form.datasourceId" placeholder="请选择数据源" style="width: 100%">
            <el-option v-for="ds in datasourceOptions" :key="ds.id" :label="ds.name" :value="ds.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.sqlDescribe!" placeholder="展示在查询页页头，说明这个报表是查什么的" />
        </el-form-item>
        <el-form-item label="查询 SQL" prop="querySql" :rules="[{ required: true, message: '请输入查询 SQL' }]">
          <el-input
            v-model="form.querySql"
            type="textarea"
            :rows="8"
            class="sql-textarea"
            placeholder="只允许只读 SELECT/WITH 单语句；参数用 :paramName 命名参数占位，如 SELECT * FROM t WHERE id = :id"
          />
        </el-form-item>
        <el-form-item label="总数 SQL">
          <el-input
            v-model="form.countSql!"
            type="textarea"
            :rows="4"
            class="sql-textarea"
            placeholder="可空——为空则查询页只有上一页/下一页、没有总数；有则用于分页条显示总条数"
          />
        </el-form-item>
        <el-form-item label="自动加载">
          <el-switch v-model="form.autoLoad" />
          <span class="form-tip">打开查询页时自动执行一次查询，不用等用户点“查询”</span>
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark!" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="drawerVisible = false">取消</el-button>
        <el-button class="cw-final-action" type="primary" :loading="submitting" :disabled="datasourceBlocked || submitting" @click="handleSubmit">保存定义</el-button>
      </template>
    </el-drawer>

    <!-- 参数配置 -->
    <el-dialog v-model="paramsDialogVisible" :title="`参数配置 · ${paramsDefineKey}`" width="900px">
      <CrudLoadState :error="paramsError" :has-stale-data="paramsList.length > 0" :loading="paramsLoading" @retry="loadParams" />
      <div class="toolbar">
        <el-button class="cw-final-action" type="primary" v-permission="'sql-define:edit'" :disabled="paramsLoading || !!paramsError" @click="openParamCreate">新增参数</el-button>
      </div>
      <el-table v-if="!paramsError || paramsList.length > 0" v-loading="paramsLoading" :data="paramsList" style="width: 100%" size="small">
        <el-table-column prop="paramName" label="参数名" width="130" />
        <el-table-column prop="paramDesc" label="描述" show-overflow-tooltip />
        <el-table-column label="类型" width="150">
          <template #default="{ row }: { row: SqlDefineParamVO }">
            {{ PARAM_TYPE_OPTIONS.find((o) => o.value === row.paramType)?.label ?? row.paramType }}
            <div v-if="row.dateFormat" class="param-date-format">{{ row.dateFormat }}</div>
          </template>
        </el-table-column>
        <el-table-column label="必填" width="70">
          <template #default="{ row }: { row: SqlDefineParamVO }">{{ row.required ? '是' : '否' }}</template>
        </el-table-column>
        <el-table-column prop="defaultValue" label="默认值" width="130" show-overflow-tooltip />
        <el-table-column label="分页标记" width="100">
          <template #default="{ row }: { row: SqlDefineParamVO }">
            <el-tag v-if="row.isPageNum" size="small" type="warning">页码</el-tag>
            <el-tag v-else-if="row.isPageSize" size="small" type="warning">页大小</el-tag>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column prop="sort" label="排序" width="70" />
        <el-table-column label="操作" width="130" fixed="right">
          <template #default="{ row }: { row: SqlDefineParamVO }">
            <el-button link type="primary" v-permission="'sql-define:edit'" @click="openParamEdit(row)">编辑</el-button>
            <el-button link type="danger" v-permission="'sql-define:edit'" :loading="children.isPending(`param-delete:${paramsDefineId}:${row.id}`)" :disabled="children.isPending(`param-delete:${paramsDefineId}:${row.id}`)" @click="handleParamDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button @click="paramsDialogVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="paramFormVisible" :title="editingParamId ? '编辑参数' : '新增参数'" width="520px" append-to-body>
      <el-form ref="paramFormRef" :model="paramForm" :disabled="paramSubmitting" label-width="90px">
        <el-form-item label="参数名" prop="paramName" :rules="[{ required: true, message: '请输入参数名' }]">
          <el-input v-model="paramForm.paramName" :disabled="!!editingParamId" placeholder="对应 SQL 里的 :paramName" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="paramForm.paramDesc!" placeholder="展示在查询页表单标签上" />
        </el-form-item>
        <el-form-item label="类型" prop="paramType" :rules="[{ required: true, message: '请选择类型' }]">
          <el-select v-model="paramForm.paramType" style="width: 100%">
            <el-option v-for="opt in PARAM_TYPE_OPTIONS" :key="opt.value" :label="opt.label" :value="opt.value" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="paramForm.paramType === 'DATETIME'" label="日期格式">
          <el-select
            v-model="paramForm.dateFormat!"
            filterable
            allow-create
            default-first-option
            clearable
            style="width: 100%"
            placeholder="默认 yyyy-MM-dd HH:mm:ss，可手输其他格式"
          >
            <el-option v-for="fmt in DATE_FORMAT_OPTIONS" :key="fmt" :label="fmt" :value="fmt" />
          </el-select>
        </el-form-item>
        <el-form-item label="必填">
          <el-switch v-model="paramForm.required" />
        </el-form-item>
        <el-form-item label="默认值">
          <el-input v-model="paramForm.defaultValue!" placeholder="支持表达式，如 ${now}、${now-14d}" />
        </el-form-item>
        <el-form-item label="下拉选项">
          <el-input v-model="paramForm.dropDown!" type="textarea" :rows="2" placeholder='可空；JSON 对象，如 {"1":"启用","0":"禁用"}' />
        </el-form-item>
        <el-form-item label="分页页码">
          <el-switch v-model="paramForm.isPageNum" />
        </el-form-item>
        <el-form-item label="分页页大小">
          <el-switch v-model="paramForm.isPageSize" />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="paramForm.sort!" :min="0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="paramFormVisible = false">取消</el-button>
        <el-button class="cw-final-action" type="primary" :loading="paramSubmitting" :disabled="paramSubmitting || paramsLoading || !!paramsError" @click="handleParamSubmit">保存参数</el-button>
      </template>
    </el-dialog>

    <!-- 列转换器 -->
    <el-dialog v-model="transformsDialogVisible" :title="`列转换器 · ${transformsDefineKey}`" width="700px">
      <CrudLoadState :error="transformsError" :has-stale-data="transformsList.length > 0" :loading="transformsLoading" @retry="loadTransforms" />
      <div class="toolbar">
        <el-button class="cw-final-action" type="primary" v-permission="'sql-define:edit'" :disabled="transformsLoading || !!transformsError" @click="openTransformCreate">新增转换器</el-button>
      </div>
      <el-table v-if="!transformsError || transformsList.length > 0" v-loading="transformsLoading" :data="transformsList" style="width: 100%" size="small">
        <el-table-column prop="fieldName" label="列名" width="140" />
        <el-table-column label="类型" width="110">
          <template #default="{ row }: { row: SqlFieldTransformVO }">
            {{ TRANSFORM_TYPE_OPTIONS.find((o) => o.value === row.transformType)?.label ?? row.transformType }}
          </template>
        </el-table-column>
        <el-table-column prop="transformConfig" label="配置" show-overflow-tooltip />
        <el-table-column label="操作" width="130" fixed="right">
          <template #default="{ row }: { row: SqlFieldTransformVO }">
            <el-button link type="primary" v-permission="'sql-define:edit'" @click="openTransformEdit(row)">编辑</el-button>
            <el-button link type="danger" v-permission="'sql-define:edit'" :loading="children.isPending(`transform-delete:${transformsDefineId}:${row.id}`)" :disabled="children.isPending(`transform-delete:${transformsDefineId}:${row.id}`)" @click="handleTransformDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button @click="transformsDialogVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="transformFormVisible" :title="editingTransformId ? '编辑转换器' : '新增转换器'" width="480px" append-to-body>
      <el-form ref="transformFormRef" :model="transformForm" :disabled="transformSubmitting" label-width="90px">
        <el-form-item label="列名" prop="fieldName" :rules="[{ required: true, message: '请输入列名' }]">
          <el-input v-model="transformForm.fieldName" :disabled="!!editingTransformId" placeholder="匹配查询结果集里的列名" />
        </el-form-item>
        <el-form-item label="类型" prop="transformType" :rules="[{ required: true, message: '请选择类型' }]">
          <el-select v-model="transformForm.transformType" style="width: 100%">
            <el-option v-for="opt in TRANSFORM_TYPE_OPTIONS" :key="opt.value" :label="opt.label" :value="opt.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="配置" prop="transformConfig" :rules="[{ required: true, message: '请输入配置' }]">
          <el-input v-model="transformForm.transformConfig" :placeholder="transformConfigPlaceholder()" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="transformFormVisible = false">取消</el-button>
        <el-button class="cw-final-action" type="primary" :loading="transformSubmitting" :disabled="transformSubmitting || transformsLoading || !!transformsError" @click="handleTransformSubmit">保存转换器</el-button>
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
  margin-left: 12px;
  font-size: 12px;
  color: var(--cw-text-muted);
}

.param-date-format {
  font-size: 12px;
  color: var(--cw-text-muted);
  line-height: 1.2;
}

.sql-textarea :deep(textarea) {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 13px;
}

@media (max-width: 767px) {
  .form-tip {
    display: block;
    margin: 6px 0 0;
    line-height: 1.5;
  }
}
</style>
