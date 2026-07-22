<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { executeAdhocSql, exportAdhocSql, listAllSqlDatasources } from '@/api/sql'
import type { SqlDatasourceVO, SqlQueryResultVO } from '@/types/api'

const datasources = ref<SqlDatasourceVO[]>([])
const datasourceId = ref<number>()
const sql = ref('')
const executing = ref(false)
const exporting = ref(false)
const result = ref<SqlQueryResultVO | null>(null)

async function loadDatasources() {
  datasources.value = await listAllSqlDatasources()
}

function validate(): boolean {
  if (!datasourceId.value) {
    ElMessage.warning('请选择数据源')
    return false
  }
  if (!sql.value.trim()) {
    ElMessage.warning('请输入 SQL')
    return false
  }
  return true
}

async function runQuery() {
  if (!validate()) {
    return
  }
  executing.value = true
  try {
    result.value = await executeAdhocSql({ datasourceId: datasourceId.value!, sql: sql.value })
  } finally {
    executing.value = false
  }
}

async function handleExport() {
  if (!validate()) {
    return
  }
  exporting.value = true
  try {
    await exportAdhocSql({ datasourceId: datasourceId.value!, sql: sql.value })
  } finally {
    exporting.value = false
  }
}

/** Ctrl/Cmd + Enter 执行查询。 */
function onKeydown(e: KeyboardEvent) {
  if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') {
    e.preventDefault()
    runQuery()
  }
}

onMounted(loadDatasources)
</script>

<template>
  <div class="page">
    <el-card>
      <el-alert
        type="warning"
        :closable="false"
        show-icon
        title="仅支持只读 SELECT / WITH 查询，禁止多语句；结果最多返回 2000 行、单条超时 30 秒。每次执行都会记入操作日志。"
        style="margin-bottom: 12px"
      />

      <div class="toolbar">
        <el-select
          v-model="datasourceId"
          placeholder="选择数据源"
          filterable
          style="width: 260px"
        >
          <el-option v-for="ds in datasources" :key="ds.id" :label="ds.name" :value="ds.id" />
        </el-select>
        <el-button type="primary" :loading="executing" @click="runQuery">执行（Ctrl+Enter）</el-button>
        <el-button
          v-permission="'sql-console:export'"
          :loading="exporting"
          :disabled="!result || result.rows.length === 0"
          @click="handleExport"
        >
          导出 Excel
        </el-button>
      </div>

      <el-input
        v-model="sql"
        type="textarea"
        :rows="8"
        placeholder="输入只读 SQL，例如：SELECT * FROM t_user WHERE create_time > '2026-01-01' LIMIT 100"
        class="sql-input"
        @keydown="onKeydown"
      />

      <div v-if="result" class="result-meta">
        耗时 {{ result.useMillis }} ms，返回 {{ result.rows.length }} 行<span v-if="result.rows.length >= 2000">（已达 2000 行上限，可能被截断）</span>
      </div>

      <el-table
        v-if="result"
        v-loading="executing"
        :data="result.rows"
        style="width: 100%; margin-top: 8px"
        border
        max-height="520"
      >
        <el-table-column
          v-for="col in result.columns"
          :key="col"
          :prop="col"
          :label="col"
          show-overflow-tooltip
          min-width="140"
        />
      </el-table>
      <el-empty v-if="result && result.rows.length === 0" description="没有查询到数据" />
    </el-card>
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}
.sql-input :deep(textarea) {
  font-family: var(--el-font-family-mono, monospace);
  font-size: 13px;
}
.result-meta {
  margin-top: 12px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
