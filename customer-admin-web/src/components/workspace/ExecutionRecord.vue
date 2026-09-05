<script setup lang="ts">
import { computed } from 'vue'
import TraceTimeline from '@/components/TraceTimeline.vue'
import MessageAttachments from '@/components/attachment/MessageAttachments.vue'
import { summarizeTrace } from '@/utils/traceTimeline'
import type { ChatMessage } from '@/store/chatConversations'

const props = defineProps<{
  agentCode: string
  assistantName: string
  message?: ChatMessage
  active: boolean
  request?: ChatMessage
}>()
const summary = computed(() => summarizeTrace(props.message?.nodes ?? []))
</script>

<template>
  <div class="execution-record">
    <div class="record-agent">
      <span>当前智能体</span><strong>{{ assistantName }}</strong>
    </div>
    <template v-if="message?.nodes.length">
      <dl class="record-metrics">
        <div>
          <dt>执行步骤</dt>
          <dd>{{ summary.stepCount }}</dd>
        </div>
        <div>
          <dt>工具调用</dt>
          <dd>{{ summary.toolCount }}</dd>
        </div>
      </dl>
      <p class="record-note">本次会话收到的执行记录</p>
      <TraceTimeline :nodes="message.nodes" :active="active" :failed="message.failed" />
    </template>
    <div v-else class="record-empty">
      <el-icon><Document /></el-icon>
      <p>{{ message ? '该消息没有可展示的执行记录' : '发送任务后查看执行过程' }}</p>
      <small>这里展示本轮任务的步骤与工具调用。</small>
    </div>
    <section v-if="request?.attachments?.length" class="record-attachments">
      <h3>本轮附件</h3>
      <MessageAttachments :agent-code="agentCode" :attachments="request.attachments" />
    </section>
  </div>
</template>

<style scoped>
.execution-record {
  padding: 18px;
  font-size: 13px;
  overflow-wrap: anywhere;
}
.record-agent {
  display: grid;
  gap: 8px;
  padding-bottom: 20px;
}
.record-agent span,
dt,
.record-note,
small {
  color: var(--cw-text-muted);
  font-size: 12px;
}
.record-agent strong {
  font-weight: 550;
}
.record-metrics {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
  margin: 0 0 18px;
}
.record-metrics > div {
  background: var(--cw-canvas);
  border: 1px solid var(--cw-line);
  border-radius: 7px;
  padding: 12px;
}
dd {
  margin: 8px 0 0;
  font-size: 22px;
  font-variant-numeric: tabular-nums;
}
.record-note {
  margin: 0 0 14px;
}
.record-empty {
  display: grid;
  justify-items: center;
  gap: 8px;
  padding: 60px 0;
  text-align: center;
  color: var(--cw-text-muted);
}
.record-empty .el-icon {
  font-size: 26px;
}
.record-empty p {
  margin: 6px 0;
}
h3 {
  font-size: 13px;
  font-weight: 550;
}
</style>
