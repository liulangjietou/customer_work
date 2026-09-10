<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { captureMcpContract, listMcpContractHistory } from '@/api/mcp'
import type { McpChangeType, McpContractSnapshotVO, McpToolChange } from '@/types/api'

const props = defineProps<{ mcpId: number | null; mcpName: string }>()
const visible = defineModel<boolean>({ default: false })

const loading = ref(false)
const capturing = ref(false)
const snapshots = ref<McpContractSnapshotVO[]>([])
const loadError = ref('')

/**
 * 变更类型 → 中文与是否破坏性，与后端 McpChangeType 的分级一一对应。
 *
 * 类型写成 Record<McpChangeType, ...> 而不是 Record<string, ...>：漏一种类型 vue-tsc 直接报错，
 * 而不是在页面上显示一串原始英文枚举名。后端新增枚举值时，
 * McpContractDriftTest 里那条「9 种」断言会红，提醒同步这里。
 */
const CHANGE_LABELS: Record<McpChangeType, { text: string; breaking: boolean }> = {
  TOOL_REMOVED: { text: '工具下线', breaking: true },
  REQUIRED_ADDED: { text: '新增必填', breaking: true },
  FIELD_REMOVED: { text: '字段消失', breaking: true },
  FIELD_TYPE_CHANGED: { text: '类型改变', breaking: true },
  SCHEMA_TYPE_CHANGED: { text: '参数结构改变', breaking: true },
  TOOL_ADDED: { text: '新增工具', breaking: false },
  FIELD_ADDED: { text: '新增字段', breaking: false },
  REQUIRED_REMOVED: { text: '必填放宽', breaking: false },
  DESCRIPTION_CHANGED: { text: '描述修改', breaking: false },
}

function changeLabel(change: McpToolChange) {
  return CHANGE_LABELS[change.type]?.text ?? change.type
}

function changeTagType(change: McpToolChange) {
  return CHANGE_LABELS[change.type]?.breaking ? 'danger' : 'info'
}

/** el-table 的作用域插槽给的是 any，这里收窄回 McpToolChange。 */
function asChange(row: unknown) {
  return row as McpToolChange
}

function severityTagType(severity: string) {
  if (severity === 'BREAKING') return 'danger'
  if (severity === 'COMPATIBLE') return 'warning'
  return 'success'
}

function severityText(severity: string) {
  if (severity === 'BREAKING') return '破坏性变更'
  if (severity === 'COMPATIBLE') return '兼容变更'
  return '无变化'
}

async function loadHistory() {
  if (!props.mcpId) return
  loading.value = true
  loadError.value = ''
  try {
    snapshots.value = await listMcpContractHistory(props.mcpId)
  } catch (e) {
    loadError.value = e instanceof Error ? e.message : '加载失败'
  } finally {
    loading.value = false
  }
}

async function capture() {
  if (!props.mcpId) return
  capturing.value = true
  try {
    const snapshot = await captureMcpContract(props.mcpId)
    if (snapshot.driftSeverity === 'BREAKING') {
      ElMessage.warning(`检测到破坏性变更，共 ${snapshot.changes.length} 处`)
    } else if (snapshot.driftSeverity === 'COMPATIBLE') {
      ElMessage.info(`检测到兼容变更，共 ${snapshot.changes.length} 处`)
    } else {
      ElMessage.success(`契约无变化，当前 ${snapshot.toolCount} 个工具`)
    }
    await loadHistory()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '采集失败')
  } finally {
    capturing.value = false
  }
}

watch(visible, (opened) => {
  if (opened) {
    snapshots.value = []
    loadHistory()
  }
})
</script>

<template>
  <el-dialog v-model="visible" :title="`契约快照 · ${props.mcpName}`" width="760px" destroy-on-close>
    <el-alert type="info" :closable="false" show-icon class="contract-hint">
      <template #title>工具定义住在远端服务器，随时可能变</template>
      每次采集会连一遍远端并与上一条快照比对。检测到破坏性变更<b>不会停用这个 MCP</b>——
      上游演进是常态，要不要停由你决定。
    </el-alert>

    <div class="contract-actions">
      <el-button type="primary" :loading="capturing" @click="capture">采集一次</el-button>
      <el-button :loading="loading" @click="loadHistory">刷新</el-button>
    </div>

    <el-alert v-if="loadError" type="error" :closable="false" show-icon :title="loadError" />

    <el-empty v-else-if="!loading && snapshots.length === 0" description="还没有采集过契约快照" />

    <el-timeline v-else v-loading="loading">
      <el-timeline-item
        v-for="snapshot in snapshots"
        :key="snapshot.id"
        :timestamp="snapshot.capturedAt"
        placement="top"
        :type="snapshot.driftSeverity === 'BREAKING' ? 'danger' : 'primary'"
      >
        <div class="snapshot-head">
          <el-tag :type="severityTagType(snapshot.driftSeverity)" size="small">
            {{ severityText(snapshot.driftSeverity) }}
          </el-tag>
          <span class="snapshot-meta">{{ snapshot.toolCount }} 个工具</span>
          <span class="snapshot-hash" :title="snapshot.contractHash">
            {{ snapshot.contractHash.slice(0, 12) }}
          </span>
        </div>
        <el-table v-if="snapshot.changes.length" :data="snapshot.changes" size="small" class="change-table">
          <el-table-column prop="toolName" label="工具" width="180" show-overflow-tooltip />
          <el-table-column label="变更" width="130">
            <template #default="{ row }">
              <el-tag :type="changeTagType(asChange(row))" size="small" effect="plain">
                {{ changeLabel(asChange(row)) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="detail" label="细节" show-overflow-tooltip />
        </el-table>
      </el-timeline-item>
    </el-timeline>
  </el-dialog>
</template>

<style scoped>
.contract-hint {
  margin-bottom: 12px;
}

.contract-actions {
  margin-bottom: 16px;
  display: flex;
  gap: 8px;
}

.snapshot-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 6px;
}

.snapshot-meta {
  color: var(--el-text-color-regular);
  font-size: 13px;
}

.snapshot-hash {
  color: var(--el-text-color-placeholder);
  font-family: var(--el-font-family-monospace, monospace);
  font-size: 12px;
}

.change-table {
  margin-top: 4px;
}
</style>
