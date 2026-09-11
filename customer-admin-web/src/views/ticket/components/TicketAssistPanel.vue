<script setup lang="ts">
import { computed, onScopeDispose, ref, watch } from 'vue'
import { getTicketAssist } from '@/api/user-ticket'
import { useAuthStore } from '@/store/auth'
import CrudLoadState from '@/components/CrudLoadState.vue'
import {
  SENDER_TYPE_LABELS,
  type TicketAssistAdoption,
  type TicketAssistView,
} from '@/types/ticket'

const props = defineProps<{
  ticketId: string
  messageRevision: string
  disabled: boolean
  canAdopt: boolean
  adoptReason: string
}>()
const emit = defineEmits<{ adopt: [value: TicketAssistAdoption] }>()
const auth = useAuthStore()
const canRead = computed(() => !!auth.token && auth.hasPermission('user-ticket:view'))
const data = ref<TicketAssistView | null>(null)
const loading = ref(false)
const error = ref<unknown>(null)
const loadedRevision = ref('')
const sourceVisible = ref(false)
let generation = 0
const summary = computed(() => data.value?.summary)
const stale = computed(() => !!data.value && loadedRevision.value !== props.messageRevision)
const hasEvidence = computed(() => !!summary.value?.evidence.sources.length)
const canUse = computed(
  () =>
    canRead.value &&
    props.canAdopt &&
    !props.disabled &&
    !loading.value &&
    !error.value &&
    !stale.value &&
    hasEvidence.value &&
    !!summary.value?.suggestedReply.trim(),
)

/** 请求绑定当前工单、登录和消息版本；刷新失败保留旧依据并停止采用。 */
async function load() {
  if (!canRead.value || props.disabled || loading.value) return
  const version = ++generation
  const ticketId = props.ticketId
  const revision = props.messageRevision
  loading.value = true
  try {
    const result = await getTicketAssist(ticketId)
    if (version !== generation) return
    if (
      result.ticketId !== ticketId ||
      !result.summary?.evidence?.version ||
      !Array.isArray(result.summary.evidence.sources)
    )
      throw new Error('辅助记录不完整，请重新读取。')
    data.value = result
    loadedRevision.value = revision
    error.value = null
  } catch (failure) {
    if (version === generation) error.value = failure
  } finally {
    if (version === generation) loading.value = false
  }
}
function adopt() {
  if (!canUse.value || !summary.value) return
  emit('adopt', {
    ticketId: props.ticketId,
    reply: summary.value.suggestedReply,
    messageRevision: loadedRevision.value,
  })
}
function time(ms: number) {
  return new Date(ms).toLocaleString('zh-CN', { hour12: false })
}
watch(
  () => [props.ticketId, auth.token, canRead.value],
  () => {
    generation += 1
    data.value = null
    loading.value = false
    error.value = null
    sourceVisible.value = false
  },
  { immediate: true, flush: 'sync' },
)
// 先同步失效旧请求，再等待本帧全部 props 和工单加载状态更新，不能在切单中途读取。
watch(
  () => [props.ticketId, auth.token, canRead.value, props.disabled],
  () => {
    if (!props.disabled && !data.value && !error.value) void load()
  },
  { immediate: true, flush: 'post' },
)
onScopeDispose(() => {
  generation += 1
})
</script>

<template>
  <section class="ticket-assist" aria-label="坐席辅助">
    <div class="assist-heading">
      <h3>交接摘要与建议</h3>
      <el-button text :loading="loading" :disabled="disabled || !canRead" @click="load"
        >刷新辅助</el-button
      >
    </div>
    <CrudLoadState :error="error" :has-stale-data="!!data" :loading="loading" @retry="load" />
    <p v-if="!data && loading" class="muted" role="status">正在读取当前工单的原始记录…</p>
    <p v-else-if="!data && !error" class="muted">会话记录准备好后显示辅助信息。</p>
    <template v-if="summary && canRead">
      <div v-if="stale" class="stale" role="status">会话已有更新，请刷新辅助后再采用建议。</div>
      <template v-if="hasEvidence">
        <div class="assist-meta">
          <el-tag size="small" type="info">{{
            summary.fromModel ? '已有 AI 摘要' : '规则整理'
          }}</el-tag>
          <span>供坐席核对</span>
        </div>
        <p class="summary-text">{{ summary.oneLineSummary }}</p>
        <details
          v-if="summary.pendingIssues.length || summary.triedSolutions.length || summary.emotion"
          class="summary-details"
        >
          <summary>查看待核对事项</summary>
          <p v-if="summary.emotion">情绪提示：{{ summary.emotion }}，请结合原文判断。</p>
          <template v-if="summary.triedSolutions.length"
            ><h4>对话中提到的处理</h4>
            <ul>
              <li v-for="(item, index) in summary.triedSolutions" :key="index">{{ item }}</li>
            </ul></template
          >
          <template v-if="summary.pendingIssues.length"
            ><h4>尚待核对</h4>
            <ul>
              <li v-for="(item, index) in summary.pendingIssues" :key="index">{{ item }}</li>
            </ul></template
          >
        </details>
        <el-button link type="primary" class="source-link" @click="sourceVisible = true"
          >查看原始会话依据</el-button
        >
        <div class="reply-suggestion">
          <h4>建议回复 <span>待你确认</span></h4>
          <p>{{ summary.suggestedReply }}</p>
        </div>
        <el-button type="primary" plain class="adopt-button" :disabled="!canUse" @click="adopt"
          >采用为草稿</el-button
        >
        <p class="muted">
          {{ canAdopt ? '采用后可继续编辑，确认发送前不会发给客户。' : adoptReason }}
        </p>
        <details v-if="summary.suggestedNextStep" class="summary-details">
          <summary>建议核对的方向</summary>
          <p>{{ summary.suggestedNextStep }}</p>
        </details>
      </template>
      <p v-else class="muted">当前工单尚无可整理的会话记录。</p>
    </template>
    <el-dialog
      v-model="sourceVisible"
      title="原始会话依据"
      width="min(640px, calc(100vw - 24px))"
      append-to-body
      class="ticket-assist-sources"
    >
      <template v-if="summary && canRead">
        <p v-if="stale" class="stale" role="status">会话已有更新，当前展示上次整理时使用的依据。</p>
        <p class="source-note">
          {{ summary.fromModel ? '摘要生成' : '记录整理' }}于
          {{ time(summary.evidence.generatedAtMs) }}。 以下是本次使用的最近至多
          {{ summary.evidence.historyLimit }}
          条消息；原文只能证明对话中出现过，不代表业务结果已核实。
        </p>
        <p v-if="summary.evidence.truncated" class="source-note">
          较长记录只保留了本次实际使用的摘录。
        </p>
        <ol class="source-list">
          <li v-for="item in summary.evidence.sources" :key="`${item.id}:${item.messageId}`">
            <div>
              <strong>{{
                item.senderType ? SENDER_TYPE_LABELS[item.senderType] : '未知来源'
              }}</strong
              ><span>{{ time(item.createdAtMs) }}</span>
            </div>
            <p>{{ item.excerpt }}</p>
            <small v-if="item.truncated">此条展示末尾摘录</small>
          </li>
        </ol>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.ticket-assist {
  padding: 16px;
  margin: 0;
  border-bottom: 1px solid var(--cw-line);
}
.assist-heading,
.assist-meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}
.assist-heading {
  justify-content: space-between;
}
h3 {
  margin: 0;
  font-size: 15px;
}
h4 {
  font-size: 14px;
  margin: 12px 0 8px;
}
.assist-meta,
.muted,
.source-note {
  color: var(--cw-text-muted);
  font-size: 12px;
  line-height: 1.7;
}
.summary-text {
  margin: 12px 0;
  font-size: 14px;
  line-height: 1.7;
  overflow-wrap: anywhere;
}
.summary-details {
  font-size: 13px;
  line-height: 1.7;
  margin: 10px 0;
  overflow-wrap: anywhere;
}
.summary-details summary {
  cursor: pointer;
  color: var(--cw-text-muted);
  padding: 6px 0;
}
.summary-details ul {
  padding-left: 19px;
}
.source-link {
  white-space: normal;
  text-align: left;
}
.reply-suggestion {
  background: var(--cw-paper);
  border: 1px solid var(--cw-line);
  border-radius: 8px;
  padding: 12px;
  margin: 12px 0;
}
.reply-suggestion h4 {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  margin: 0 0 10px;
}
.reply-suggestion h4 span {
  color: var(--cw-text-muted);
  font-weight: 400;
  font-size: 12px;
}
.reply-suggestion p {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  font-size: 14px;
  line-height: 1.7;
  margin: 0;
}
.adopt-button {
  width: 100%;
  min-height: 40px;
}
.stale {
  padding: 10px 12px;
  margin: 10px 0;
  background: var(--el-color-warning-light-9);
  color: var(--el-color-warning-dark-2);
  font-size: 13px;
  line-height: 1.7;
  border-radius: 8px;
}
.source-list {
  list-style: none;
  padding: 0;
  margin: 18px 0 0;
}
.source-list li {
  padding: 15px 0;
  border-top: 1px solid var(--cw-line);
}
.source-list li > div {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  flex-wrap: wrap;
}
.source-list span,
.source-list small {
  color: var(--cw-text-muted);
  font-size: 12px;
}
.source-list p {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  line-height: 1.75;
}
@media (max-width: 680px) {
  .ticket-assist :deep(.el-button),
  .summary-details summary {
    min-height: 44px;
  }
}
</style>
