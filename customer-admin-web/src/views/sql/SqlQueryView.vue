<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { executeSqlQuery, exportSqlQuery, fetchSqlQueryMeta } from '@/api/sql'
import type { SqlQueryMetaParam, SqlQueryMetaVO, SqlQueryResultVO } from '@/types/api'

const DEFAULT_PAGE_SIZE = 30

const route = useRoute()

const metaLoading = ref(false)
const meta = ref<SqlQueryMetaVO | null>(null)
const loadError = ref<string | null>(null)

const executing = ref(false)
const exporting = ref(false)
const result = ref<SqlQueryResultVO | null>(null)

const paramValues = reactive<Record<string, unknown>>({})
const pageNum = ref(1)
const pageSize = ref(DEFAULT_PAGE_SIZE)

/** 排除分页标记参数后的表单参数，按 sort 升序渲染。 */
const formParams = computed(() =>
  (meta.value?.params ?? []).filter((p) => !p.isPageNum && !p.isPageSize).sort((a, b) => a.sort - b.sort),
)
const pageNumParam = computed(() => meta.value?.params.find((p) => p.isPageNum))
const pageSizeParam = computed(() => meta.value?.params.find((p) => p.isPageSize))

function fieldKind(p: SqlQueryMetaParam): 'select' | 'date' | 'number' | 'input' {
  if (p.dropDown) return 'select'
  if (p.paramType === 'DATETIME') return 'date'
  if (p.paramType === 'INTEGER') return 'number'
  return 'input'
}

function coerceDefault(p: SqlQueryMetaParam): unknown {
  if (p.defaultValue === null || p.defaultValue === '') {
    return p.paramType === 'INTEGER' ? undefined : ''
  }
  return p.paramType === 'INTEGER' ? Number(p.defaultValue) : p.defaultValue
}

function resetParamValues() {
  Object.keys(paramValues).forEach((key) => delete paramValues[key])
  for (const p of formParams.value) {
    paramValues[p.paramName] = coerceDefault(p)
  }
  pageNum.value = 1
  pageSize.value = pageSizeParam.value?.defaultValue ? Number(pageSizeParam.value.defaultValue) : DEFAULT_PAGE_SIZE
}

function validateRequiredParams(): boolean {
  for (const p of formParams.value) {
    if (!p.required) continue
    const value = paramValues[p.paramName]
    if (value === undefined || value === null || value === '') {
      ElMessage.warning(`请填写「${p.paramDesc || p.paramName}」`)
      return false
    }
  }
  return true
}

function buildParams(): Record<string, unknown> {
  const params: Record<string, unknown> = { ...paramValues }
  if (pageNumParam.value) {
    params[pageNumParam.value.paramName] = pageNum.value
  }
  if (pageSizeParam.value) {
    params[pageSizeParam.value.paramName] = pageSize.value
  }
  return params
}

async function runQuery() {
  if (!meta.value) return
  executing.value = true
  try {
    result.value = await executeSqlQuery({ defineKey: meta.value.defineKey, params: buildParams() })
  } finally {
    executing.value = false
  }
}

function handleSearch() {
  if (!validateRequiredParams()) return
  pageNum.value = 1
  runQuery()
}

function handleReset() {
  resetParamValues()
  result.value = null
}

function handlePageChange(newPage: number) {
  pageNum.value = newPage
  runQuery()
}

function handlePageSizeChange(newSize: number) {
  pageSize.value = newSize
  pageNum.value = 1
  runQuery()
}

function handlePrevPage() {
  if (pageNum.value <= 1) return
  pageNum.value -= 1
  runQuery()
}

function handleNextPage() {
  pageNum.value += 1
  runQuery()
}

async function handleExport() {
  if (!meta.value || !validateRequiredParams()) return
  exporting.value = true
  try {
    await exportSqlQuery({ defineKey: meta.value.defineKey, params: buildParams() })
  } finally {
    exporting.value = false
  }
}

async function load() {
  const defineKey = route.query.defineKey as string | undefined
  meta.value = null
  result.value = null
  loadError.value = null
  if (!defineKey) {
    return
  }
  metaLoading.value = true
  try {
    meta.value = await fetchSqlQueryMeta(defineKey)
    resetParamValues()
    if (meta.value.autoLoad) {
      await runQuery()
    }
  } catch (e) {
    loadError.value = (e as { message?: string })?.message || '加载查询配置失败'
  } finally {
    metaLoading.value = false
  }
}

watch(() => route.query.defineKey, load, { immediate: true })
</script>

<template>
  <div class="page">
    <el-empty v-if="!route.query.defineKey" description="未指定报表（缺少 defineKey），请从菜单进入具体报表" />

    <template v-else>
      <el-card v-loading="metaLoading">
        <el-alert v-if="loadError" type="error" :closable="false" :title="loadError" show-icon style="margin-bottom: 16px" />

        <template v-if="meta">
          <div class="page-header">
            <h3>{{ meta.sqlDescribe || meta.defineKey }}</h3>
          </div>

          <el-form :model="paramValues" inline class="param-form">
            <el-form-item v-for="p in formParams" :key="p.paramName" :label="p.paramDesc || p.paramName">
              <el-select
                v-if="fieldKind(p) === 'select'"
                :model-value="paramValues[p.paramName] as string | undefined"
                clearable
                style="width: 180px"
                :placeholder="p.required ? '必填' : '不限'"
                @update:model-value="(v: unknown) => (paramValues[p.paramName] = v)"
              >
                <el-option v-for="(label, value) in p.dropDown ?? {}" :key="value" :label="label" :value="value" />
              </el-select>
              <el-date-picker
                v-else-if="fieldKind(p) === 'date'"
                :model-value="paramValues[p.paramName] as string | undefined"
                type="datetime"
                value-format="YYYY-MM-DD HH:mm:ss"
                :placeholder="p.required ? '必填' : '不限'"
                @update:model-value="(v: unknown) => (paramValues[p.paramName] = v)"
              />
              <el-input-number
                v-else-if="fieldKind(p) === 'number'"
                :model-value="paramValues[p.paramName] as number | undefined"
                :placeholder="p.required ? '必填' : '不限'"
                style="width: 160px"
                @update:model-value="(v: unknown) => (paramValues[p.paramName] = v)"
              />
              <el-input
                v-else
                :model-value="paramValues[p.paramName] as string | undefined"
                :placeholder="p.required ? '必填' : '不限'"
                style="width: 200px"
                clearable
                @update:model-value="(v: unknown) => (paramValues[p.paramName] = v)"
              />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="executing" @click="handleSearch">查询</el-button>
              <el-button @click="handleReset">重置</el-button>
              <el-button v-permission="'sql-query:export'" :loading="exporting" @click="handleExport">导出</el-button>
            </el-form-item>
          </el-form>

          <div v-if="result" class="result-meta">耗时 {{ result.useMillis }} ms，共 {{ result.rows.length }} 行</div>

          <el-table v-loading="executing" :data="result?.rows ?? []" style="width: 100%" border>
            <el-table-column
              v-for="col in result?.columns ?? []"
              :key="col"
              :prop="col"
              :label="col"
              show-overflow-tooltip
              min-width="120"
            />
          </el-table>
          <el-empty v-if="result && result.rows.length === 0" description="没有查询到数据" />

          <div v-if="result" class="pagination-bar">
            <el-pagination
              v-if="result.total >= 0"
              v-model:current-page="pageNum"
              v-model:page-size="pageSize"
              :total="result.total"
              :page-sizes="[10, 20, 30, 50, 100]"
              layout="total, sizes, prev, pager, next"
              @current-change="handlePageChange"
              @size-change="handlePageSizeChange"
            />
            <div v-else class="simple-pager">
              <el-button :disabled="pageNum <= 1" @click="handlePrevPage">上一页</el-button>
              <span class="simple-pager-current">第 {{ pageNum }} 页</span>
              <el-button :disabled="result.rows.length < pageSize" @click="handleNextPage">下一页</el-button>
            </div>
          </div>
        </template>
      </el-card>
    </template>
  </div>
</template>

<style scoped>
.page-header {
  margin-bottom: 12px;
}

.page-header h3 {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
}

.param-form {
  margin-bottom: 8px;
}

.result-meta {
  margin-bottom: 8px;
  font-size: 12px;
  color: #909399;
}

.pagination-bar {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
}

.simple-pager {
  display: flex;
  align-items: center;
  gap: 12px;
}

.simple-pager-current {
  color: #606266;
  font-size: 13px;
}
</style>
