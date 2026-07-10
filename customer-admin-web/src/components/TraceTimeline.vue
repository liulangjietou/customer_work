<script setup lang="ts">
import { ref } from 'vue'
import { CircleCheck, Connection, Cpu, MagicStick, Tools, VideoPause, VideoPlay } from '@element-plus/icons-vue'

/** 与后端 ChatNodeKind 一一对应（小写下划线形式，见 SSE event 名 node:<kind>）。 */
export interface TraceNode {
  kind: string
  text: string
}

defineProps<{ nodes: TraceNode[]; active: boolean }>()

// 默认展开，不折叠（可手动收起）——跟旧版 ThinkingBlock 默认折叠正相反。
const expanded = ref(true)

const NODE_META: Record<string, { label: string; icon: unknown; type: string }> = {
  thinking_start: { label: '开始思考', icon: VideoPlay, type: 'primary' },
  thinking: { label: '思考中', icon: Cpu, type: 'info' },
  thinking_end: { label: '结束思考', icon: VideoPause, type: 'primary' },
  model_call: { label: '调用大模型', icon: Cpu, type: 'warning' },
  tool_skill: { label: '调用 Skill', icon: MagicStick, type: 'success' },
  tool_mcp: { label: '调用 MCP', icon: Connection, type: 'success' },
  tool_builtin: { label: '调用工具', icon: Tools, type: 'success' },
  tool_result: { label: '工具返回', icon: CircleCheck, type: 'success' },
}

function metaOf(kind: string) {
  return NODE_META[kind] ?? { label: kind, icon: Cpu, type: 'info' }
}
</script>

<template>
  <div class="trace-timeline">
    <div class="trace-header" @click="expanded = !expanded">
      <el-icon class="chevron" :class="{ expanded }"><ArrowRight /></el-icon>
      <span>{{ active ? '思考中…' : '思考过程' }}</span>
    </div>
    <el-timeline v-show="expanded" class="trace-body">
      <el-timeline-item
        v-for="(node, idx) in nodes"
        :key="idx"
        :type="(metaOf(node.kind).type as any)"
        :icon="metaOf(node.kind).icon"
        size="normal"
      >
        <div class="trace-label">{{ metaOf(node.kind).label }}</div>
        <div v-if="node.text" class="trace-text">{{ node.text }}</div>
      </el-timeline-item>
    </el-timeline>
  </div>
</template>

<style scoped>
.trace-timeline {
  margin-bottom: 6px;
  font-size: 12px;
  color: #909399;
}

.trace-header {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  cursor: pointer;
  user-select: none;
  margin-bottom: 4px;
}

.chevron {
  transition: transform 0.15s;
  font-size: 12px;
}

.chevron.expanded {
  transform: rotate(90deg);
}

.trace-body {
  padding: 4px 10px 0 4px;
}

.trace-body :deep(.el-timeline-item__tail) {
  border-left: 2px solid #e4e7ed;
}

.trace-body :deep(.el-timeline-item__node) {
  width: 18px;
  height: 18px;
  left: -3px;
}

.trace-body :deep(.el-timeline-item__wrapper) {
  padding-left: 20px;
}

.trace-label {
  font-size: 12px;
  font-weight: 600;
  color: #606266;
}

.trace-text {
  margin-top: 2px;
  padding: 6px 8px;
  background: #fafafa;
  border-left: 2px solid #dcdfe6;
  white-space: pre-wrap;
  word-break: break-word;
  color: #666;
}
</style>
