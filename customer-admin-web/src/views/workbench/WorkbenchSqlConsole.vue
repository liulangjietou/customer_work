<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import {
  executeAdhocSql,
  exportAdhocSql,
  listAdhocDatabases,
  listAdhocTables,
  listAllSqlDatasources,
} from '@/api/sql'
import type { SqlDatasourceVO, SqlQueryResultVO } from '@/types/api'
import { format, type SqlLanguage } from 'sql-formatter'

const datasources = ref<SqlDatasourceVO[]>([])
const datasourceId = ref<number>()
const sql = ref('')
const executing = ref(false)
const exporting = ref(false)
const result = ref<SqlQueryResultVO | null>(null)
const sqlInputRef = ref<{ textarea?: HTMLTextAreaElement } | null>(null)

// ===== 左侧库树（库列表 + 点库懒加载表）=====
interface DbNode {
  name: string
  expanded: boolean
  loading: boolean
  loaded: boolean
  tables: string[]
}
const databases = ref<DbNode[]>([])
const treeLoading = ref(false)
const dbFilter = ref('')

const filteredDatabases = computed(() => {
  const kw = dbFilter.value.trim().toLowerCase()
  return kw ? databases.value.filter((d) => d.name.toLowerCase().includes(kw)) : databases.value
})

async function loadDatasources() {
  datasources.value = await listAllSqlDatasources()
}

async function loadDatabases() {
  databases.value = []
  if (!datasourceId.value) {
    return
  }
  treeLoading.value = true
  try {
    const names = await listAdhocDatabases(datasourceId.value)
    databases.value = names.map((name) => ({ name, expanded: false, loading: false, loaded: false, tables: [] }))
  } finally {
    treeLoading.value = false
  }
}

async function toggleDb(db: DbNode) {
  db.expanded = !db.expanded
  if (db.expanded && !db.loaded && datasourceId.value) {
    db.loading = true
    try {
      db.tables = await listAdhocTables(datasourceId.value, db.name)
      db.loaded = true
    } finally {
      db.loading = false
    }
  }
}

/** 点表名 → 在 SQL 编辑器光标处插入全限定表名（反引号包裹，adhoc 无 USE 需带库名）。 */
function insertTable(dbName: string, table: string) {
  insertAtCursor('`' + dbName + '`.`' + table + '`')
}

function insertAtCursor(text: string) {
  const ta = sqlInputRef.value?.textarea
  if (!ta) {
    sql.value += text
    return
  }
  const start = ta.selectionStart ?? sql.value.length
  const end = ta.selectionEnd ?? sql.value.length
  sql.value = sql.value.slice(0, start) + text + sql.value.slice(end)
  nextTick(() => {
    ta.focus()
    const pos = start + text.length
    ta.setSelectionRange(pos, pos)
  })
}

// ===== SQL 格式化 =====
// 纯前端做（sql-formatter）：这条能力只此一处、没有智能体版本，不存在两端实现漂移的风险；
// 而 Java 侧没有质量相当的格式化库，硬写一个只会做得更差。

/** jdbcUrl 里的驱动名 → sql-formatter 方言。取不到时回退 MySQL（本项目数据源以 MySQL 为主）。 */
const SQL_DIALECTS: Record<string, SqlLanguage> = {
  mysql: 'mysql',
  mariadb: 'mariadb',
  postgresql: 'postgresql',
  sqlite: 'sqlite',
  sqlserver: 'transactsql',
  oracle: 'plsql',
}

const currentDialect = computed<SqlLanguage>(() => {
  const url = datasources.value.find((item) => item.id === datasourceId.value)?.jdbcUrl ?? ''
  // jdbc:mysql://host:3306/db → 取 jdbc: 与下一个冒号之间的驱动名
  const driver = /^jdbc:([a-z0-9]+):/i.exec(url)?.[1]?.toLowerCase()
  return (driver && SQL_DIALECTS[driver]) || 'mysql'
})

function formatSql() {
  if (!sql.value.trim()) {
    ElMessage.warning('请先输入 SQL')
    return
  }
  try {
    sql.value = format(sql.value, {
      language: currentDialect.value,
      tabWidth: 2,
      keywordCase: 'upper',
      linesBetweenQueries: 1,
    })
  } catch (e) {
    // 语法不完整时 sql-formatter 会抛错，保持原文不动并提示，避免把用户写了一半的 SQL 弄乱
    ElMessage.warning(`SQL 无法解析，未做格式化：${e instanceof Error ? e.message : String(e)}`)
  }
}

// ===== 查询 / 导出 =====
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

function onKeydown(e: KeyboardEvent) {
  if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') {
    e.preventDefault()
    runQuery()
  }
}

// 切换数据源：清空结果并重载库树
watch(datasourceId, () => {
  result.value = null
  dbFilter.value = ''
  loadDatabases()
})

onMounted(loadDatasources)
</script>

<template>
  <div class="page">
    <el-card body-style="padding: 0">
      <div class="console-body">
        <!-- 左侧：数据源 + 库树 -->
        <aside class="sidebar">
          <el-select
            v-model="datasourceId"
            placeholder="选择数据源"
            filterable
            style="width: 100%"
          >
            <el-option v-for="ds in datasources" :key="ds.id" :label="ds.name" :value="ds.id" />
          </el-select>

          <el-input
            v-model="dbFilter"
            placeholder="过滤数据库"
            clearable
            size="small"
            style="margin: 8px 0"
          />

          <div v-loading="treeLoading" class="db-tree">
            <el-empty v-if="!datasourceId" :image-size="60" description="请先选择数据源" />
            <template v-else>
              <div v-for="db in filteredDatabases" :key="db.name" class="db-block">
                <div class="db-node" @click="toggleDb(db)">
                  <span class="toggle">{{ db.expanded ? '▾' : '▸' }}</span>
                  <span class="db-name" :title="db.name">{{ db.name }}</span>
                </div>
                <div v-if="db.expanded" class="table-list">
                  <div v-if="db.loading" class="hint">加载中…</div>
                  <div v-else-if="db.tables.length === 0" class="hint">（无表）</div>
                  <div
                    v-for="t in db.tables"
                    v-else
                    :key="t"
                    class="table-node"
                    :title="`点击插入 ${db.name}.${t}`"
                    @click="insertTable(db.name, t)"
                  >
                    {{ t }}
                  </div>
                </div>
              </div>
              <el-empty v-if="filteredDatabases.length === 0" :image-size="60" description="无匹配数据库" />
            </template>
          </div>
        </aside>

        <!-- 右侧：SQL 编辑 + 结果 -->
        <section class="main">
          <el-alert
            type="warning"
            :closable="false"
            show-icon
            title="仅支持只读 SELECT / WITH 查询，禁止多语句；结果最多返回 2000 行、单条超时 30 秒。每次执行都会记入操作日志。"
            style="margin-bottom: 12px"
          />

          <div class="toolbar">
            <el-button type="primary" :loading="executing" @click="runQuery">执行（Ctrl+Enter）</el-button>
            <el-button :disabled="!sql.trim()" @click="formatSql">格式化 SQL</el-button>
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
            ref="sqlInputRef"
            v-model="sql"
            type="textarea"
            :rows="8"
            placeholder="输入只读 SQL，例如：SELECT * FROM `db`.`t_user` LIMIT 100（点左侧表名可插入）"
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
            max-height="460"
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
        </section>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.console-body {
  display: flex;
  min-height: 560px;
}
.sidebar {
  width: 260px;
  flex-shrink: 0;
  border-right: 1px solid var(--el-border-color-lighter);
  padding: 12px;
  display: flex;
  flex-direction: column;
}
.db-tree {
  flex: 1;
  overflow: auto;
  min-height: 200px;
}
.db-block {
  font-size: 13px;
}
.db-node {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 4px 2px;
  cursor: pointer;
  border-radius: 4px;
}
.db-node:hover {
  background: var(--el-fill-color-light);
}
.toggle {
  width: 12px;
  color: var(--el-text-color-secondary);
  flex-shrink: 0;
}
.db-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.table-list {
  padding-left: 18px;
}
.table-node {
  padding: 3px 6px;
  cursor: pointer;
  color: var(--el-text-color-regular);
  border-radius: 4px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.table-node:hover {
  background: var(--el-fill-color-light);
  color: var(--el-color-primary);
}
.hint {
  padding: 3px 6px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.main {
  flex: 1;
  padding: 12px;
  min-width: 0;
}
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
