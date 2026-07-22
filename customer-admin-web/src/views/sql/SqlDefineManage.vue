<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
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

const datasourceOptions = ref<SqlDatasourceVO[]>([])

async function loadDatasourceOptions() {
  datasourceOptions.value = await listAllSqlDatasources()
}

// ---------- 新建/编辑（抽屉） ----------
const formRef = ref<FormInstance>()

const {
  loading, list, total, query,
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
})

async function handleCopy(row: SqlDefineVO) {
  await ElMessageBox.confirm(`确认复制 SQL 定义「${row.defineKey}」？会连同参数与列转换器一起复制一份。`, '提示', { type: 'info' })
  await copySqlDefine(row.id)
  ElMessage.success('复制成功')
  await loadList()
}

// ---------- 参数配置 ----------
const paramsDialogVisible = ref(false)
const paramsLoading = ref(false)
const paramsDefineId = ref<number | null>(null)
const paramsDefineKey = ref('')
const paramsList = ref<SqlDefineParamVO[]>([])

async function openParams(row: SqlDefineVO) {
  paramsDefineId.value = row.id
  paramsDefineKey.value = row.defineKey
  paramsDialogVisible.value = true
  await loadParams()
}

async function loadParams() {
  if (!paramsDefineId.value) return
  paramsLoading.value = true
  try {
    paramsList.value = await listSqlDefineParams(paramsDefineId.value)
  } finally {
    paramsLoading.value = false
  }
}

const paramFormVisible = ref(false)
const paramFormRef = ref<FormInstance>()
const editingParamId = ref<number | null>(null)
const paramForm = reactive<SqlDefineParamSaveRequest>({
  paramName: '', paramDesc: '', paramType: 'STRING', required: false,
  defaultValue: '', dropDown: '', isPageNum: false, isPageSize: false, sort: 0,
})

function resetParamForm() {
  Object.assign(paramForm, {
    paramName: '', paramDesc: '', paramType: 'STRING', required: false,
    defaultValue: '', dropDown: '', isPageNum: false, isPageSize: false, sort: 0,
  })
}

function openParamCreate() {
  editingParamId.value = null
  resetParamForm()
  paramFormVisible.value = true
}

function openParamEdit(row: SqlDefineParamVO) {
  editingParamId.value = row.id
  Object.assign(paramForm, {
    paramName: row.paramName, paramDesc: row.paramDesc, paramType: row.paramType, required: row.required,
    defaultValue: row.defaultValue, dropDown: row.dropDown, isPageNum: row.isPageNum, isPageSize: row.isPageSize, sort: row.sort,
  })
  paramFormVisible.value = true
}

async function handleParamSubmit() {
  const valid = await paramFormRef.value?.validate().catch(() => false)
  if (!valid || !paramsDefineId.value) {
    return
  }
  if (editingParamId.value) {
    await updateSqlDefineParam(paramsDefineId.value, editingParamId.value, paramForm)
    ElMessage.success('保存成功')
  } else {
    await createSqlDefineParam(paramsDefineId.value, paramForm)
    ElMessage.success('新增成功')
  }
  paramFormVisible.value = false
  await loadParams()
}

async function handleParamDelete(row: SqlDefineParamVO) {
  if (!paramsDefineId.value) return
  await ElMessageBox.confirm(`确认删除参数「${row.paramName}」？`, '提示', { type: 'warning' })
  await deleteSqlDefineParam(paramsDefineId.value, row.id)
  ElMessage.success('删除成功')
  await loadParams()
}

// ---------- 列转换器 ----------
const transformsDialogVisible = ref(false)
const transformsLoading = ref(false)
const transformsDefineId = ref<number | null>(null)
const transformsDefineKey = ref('')
const transformsList = ref<SqlFieldTransformVO[]>([])

async function openTransforms(row: SqlDefineVO) {
  transformsDefineId.value = row.id
  transformsDefineKey.value = row.defineKey
  transformsDialogVisible.value = true
  await loadTransforms()
}

async function loadTransforms() {
  if (!transformsDefineId.value) return
  transformsLoading.value = true
  try {
    transformsList.value = await listSqlFieldTransforms(transformsDefineId.value)
  } finally {
    transformsLoading.value = false
  }
}

const transformFormVisible = ref(false)
const transformFormRef = ref<FormInstance>()
const editingTransformId = ref<number | null>(null)
const transformForm = reactive<SqlFieldTransformSaveRequest>({
  fieldName: '', transformType: 'DATE_FORMAT', transformConfig: '',
})

function resetTransformForm() {
  Object.assign(transformForm, { fieldName: '', transformType: 'DATE_FORMAT', transformConfig: '' })
}

function openTransformCreate() {
  editingTransformId.value = null
  resetTransformForm()
  transformFormVisible.value = true
}

function openTransformEdit(row: SqlFieldTransformVO) {
  editingTransformId.value = row.id
  Object.assign(transformForm, { fieldName: row.fieldName, transformType: row.transformType, transformConfig: row.transformConfig })
  transformFormVisible.value = true
}

function transformConfigPlaceholder() {
  return transformForm.transformType === 'DATE_FORMAT' ? '如 MM-dd HH:mm:ss' : '如 {"1":"成功","0":"失败"}'
}

async function handleTransformSubmit() {
  const valid = await transformFormRef.value?.validate().catch(() => false)
  if (!valid || !transformsDefineId.value) {
    return
  }
  if (editingTransformId.value) {
    await updateSqlFieldTransform(transformsDefineId.value, editingTransformId.value, transformForm)
    ElMessage.success('保存成功')
  } else {
    await createSqlFieldTransform(transformsDefineId.value, transformForm)
    ElMessage.success('新增成功')
  }
  transformFormVisible.value = false
  await loadTransforms()
}

async function handleTransformDelete(row: SqlFieldTransformVO) {
  if (!transformsDefineId.value) return
  await ElMessageBox.confirm(`确认删除列转换器「${row.fieldName}」？`, '提示', { type: 'warning' })
  await deleteSqlFieldTransform(transformsDefineId.value, row.id)
  ElMessage.success('删除成功')
  await loadTransforms()
}

onMounted(() => {
  loadList()
  loadDatasourceOptions()
})
</script>

<template>
  <div class="page">
    <el-card>
      <div class="toolbar">
        <el-input v-model="query.keyword" placeholder="按 defineKey/描述搜索" style="width: 240px" clearable @keyup.enter="handleSearch" />
        <el-button type="primary" @click="handleSearch">搜索</el-button>
        <el-button v-permission="'sql-define:add'" type="primary" @click="openCreate">新建 SQL 定义</el-button>
      </div>

      <el-table v-loading="loading" :data="list" style="width: 100%">
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
            <el-button v-permission="'sql-define:add'" link type="primary" @click="handleCopy(row)">复制</el-button>
            <el-button v-permission="'sql-define:delete'" link type="danger" @click="handleDelete(row)">删除</el-button>
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

    <!-- 新建/编辑 SQL 定义 -->
    <el-drawer v-model="drawerVisible" :title="drawerMode === 'create' ? '新建 SQL 定义' : '编辑 SQL 定义'" size="640px">
      <el-form ref="formRef" :model="form" label-width="100px">
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
        <el-button type="primary" @click="handleSubmit">确定</el-button>
      </template>
    </el-drawer>

    <!-- 参数配置 -->
    <el-dialog v-model="paramsDialogVisible" :title="`参数配置 · ${paramsDefineKey}`" width="900px">
      <div class="toolbar">
        <el-button type="primary" @click="openParamCreate">新增参数</el-button>
      </div>
      <el-table v-loading="paramsLoading" :data="paramsList" style="width: 100%" size="small">
        <el-table-column prop="paramName" label="参数名" width="130" />
        <el-table-column prop="paramDesc" label="描述" show-overflow-tooltip />
        <el-table-column label="类型" width="90">
          <template #default="{ row }: { row: SqlDefineParamVO }">
            {{ PARAM_TYPE_OPTIONS.find((o) => o.value === row.paramType)?.label ?? row.paramType }}
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
            <el-button link type="primary" @click="openParamEdit(row)">编辑</el-button>
            <el-button link type="danger" @click="handleParamDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button @click="paramsDialogVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="paramFormVisible" :title="editingParamId ? '编辑参数' : '新增参数'" width="520px" append-to-body>
      <el-form ref="paramFormRef" :model="paramForm" label-width="90px">
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
        <el-button type="primary" @click="handleParamSubmit">确定</el-button>
      </template>
    </el-dialog>

    <!-- 列转换器 -->
    <el-dialog v-model="transformsDialogVisible" :title="`列转换器 · ${transformsDefineKey}`" width="700px">
      <div class="toolbar">
        <el-button type="primary" @click="openTransformCreate">新增转换器</el-button>
      </div>
      <el-table v-loading="transformsLoading" :data="transformsList" style="width: 100%" size="small">
        <el-table-column prop="fieldName" label="列名" width="140" />
        <el-table-column label="类型" width="110">
          <template #default="{ row }: { row: SqlFieldTransformVO }">
            {{ TRANSFORM_TYPE_OPTIONS.find((o) => o.value === row.transformType)?.label ?? row.transformType }}
          </template>
        </el-table-column>
        <el-table-column prop="transformConfig" label="配置" show-overflow-tooltip />
        <el-table-column label="操作" width="130" fixed="right">
          <template #default="{ row }: { row: SqlFieldTransformVO }">
            <el-button link type="primary" @click="openTransformEdit(row)">编辑</el-button>
            <el-button link type="danger" @click="handleTransformDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button @click="transformsDialogVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="transformFormVisible" :title="editingTransformId ? '编辑转换器' : '新增转换器'" width="480px" append-to-body>
      <el-form ref="transformFormRef" :model="transformForm" label-width="90px">
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
        <el-button type="primary" @click="handleTransformSubmit">确定</el-button>
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
  color: var(--el-text-color-secondary);
}

.sql-textarea :deep(textarea) {
  font-family: 'JetBrains Mono', 'Fira Code', 'Courier New', monospace;
  font-size: 13px;
}
</style>
