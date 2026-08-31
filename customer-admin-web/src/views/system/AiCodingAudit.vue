<script setup lang="ts">
import { onMounted } from 'vue'
import { pageAiCodingAudit } from '@/api/aiCodingAudit'
import { useCrudPage } from '@/composables/useCrudPage'
import CrudLoadState from '@/components/CrudLoadState.vue'
import type { AiCodingAuditLog, AiCodingAuditQuery } from '@/types/api'

/** 操作类型 -> 中文展示与标签色（与后端 AiCodingOperation 枚举一一对应）。 */
const OPERATION_META: Record<string, { label: string; tag: 'primary' | 'success' | 'info' | 'warning' }> = {
  CHAT_STREAM: { label: '对话编码', tag: 'primary' },
  FILE_SAVE: { label: '保存文件', tag: 'success' },
  GIT_DIFF_SUMMARY: { label: 'Diff 摘要', tag: 'info' },
  COMMIT_MESSAGE: { label: 'Commit Message', tag: 'info' },
  PR_DESCRIPTION: { label: 'PR 描述', tag: 'info' },
}

// 只读审计日志：无新建/编辑/删除能力，只接列表半套
const {
  loading, loadError, list, total, query,
  loadList, handleSearch,
} = useCrudPage<AiCodingAuditLog, AiCodingAuditQuery, Record<string, never>>({
  page: pageAiCodingAudit,
  initQuery: () => ({ pageNum: 1, pageSize: 10, keyword: '', operation: '', sessionId: '' }),
  initForm: () => ({}),
})

/** changed_files 列是 JSON 数组字符串，解析失败时原样返回单元素兜底展示。 */
function parseChangedFiles(raw: string | null): string[] {
  if (!raw) return []
  try {
    const parsed = JSON.parse(raw)
    return Array.isArray(parsed) ? parsed : [raw]
  } catch {
    return [raw]
  }
}

function operationLabel(op: string) {
  return OPERATION_META[op]?.label ?? op
}

function operationTag(op: string) {
  return OPERATION_META[op]?.tag ?? 'info'
}

onMounted(loadList)
</script>

<template>
  <div class="page">
    <CrudLoadState :error="loadError" :has-stale-data="list.length > 0" :loading="loading" @retry="loadList" />
    <el-card>
      <div class="toolbar">
        <el-input v-model="query.keyword" placeholder="按操作人搜索" style="width: 180px" clearable @keyup.enter="handleSearch" />
        <el-select v-model="query.operation" placeholder="操作类型" style="width: 170px" clearable>
          <el-option v-for="(meta, op) in OPERATION_META" :key="op" :label="meta.label" :value="op" />
        </el-select>
        <el-input v-model="query.sessionId" placeholder="会话ID" style="width: 200px" clearable @keyup.enter="handleSearch" />
        <el-button type="primary" @click="handleSearch">搜索</el-button>
      </div>

      <el-table v-loading="loading" :data="list" class="data-table" empty-text="暂无 AI 编码审计记录">
        <el-table-column prop="createTime" label="时间" width="170" />
        <el-table-column prop="username" label="操作人" width="100" />
        <el-table-column label="操作类型" width="140">
          <template #default="{ row }">
            <el-tag :type="operationTag(row.operation)">{{ operationLabel(row.operation) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="agentCode" label="智能体" width="110" class-name="primary-column" />
        <el-table-column prop="sessionId" label="会话ID" width="150" show-overflow-tooltip />
        <el-table-column label="变更文件" min-width="160">
          <template #default="{ row }">
            <el-tooltip v-if="parseChangedFiles(row.changedFiles).length" placement="top">
              <template #content>
                <div v-for="file in parseChangedFiles(row.changedFiles)" :key="file">{{ file }}</div>
              </template>
              <span class="changed-files">{{ parseChangedFiles(row.changedFiles).length }} 个文件</span>
            </el-tooltip>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="Token" width="110" align="right">
          <template #default="{ row }">
            <el-tooltip v-if="row.totalTokens != null" placement="top"
              :content="`输入 ${row.inputTokens ?? '-'} / 输出 ${row.outputTokens ?? '-'}`">
              <span>{{ row.totalTokens }}</span>
            </el-tooltip>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="耗时" width="100" align="right">
          <template #default="{ row }">
            <span v-if="row.durationMs != null">{{ (row.durationMs / 1000).toFixed(1) }}s</span>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="结果" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="row.result === 1 ? 'success' : 'danger'">{{ row.result === 1 ? '成功' : '失败' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="errorCode" label="错误码" width="180" show-overflow-tooltip />
      </el-table>

      <el-pagination
        v-model:current-page="query.pageNum"
        v-model:page-size="query.pageSize"
        :total="total"
        layout="total, prev, pager, next"
        class="pagination"
        @current-change="loadList"
      />
    </el-card>
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 16px;
}
.data-table {
  min-width: 0;
  max-width: 100%;
}
:deep(.primary-column .cell) {
  color: var(--cw-text);
  font-weight: 650;
}
.changed-files {
  color: var(--el-color-primary);
  cursor: default;
}
.muted {
  color: var(--el-text-color-placeholder);
}
</style>
