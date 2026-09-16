<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, useId, watch } from 'vue'
import { useRouter } from 'vue-router'
import { Popup } from 'vant'
import type { AxiosError } from 'axios'
import { fetchRefundApprovals } from '@/api/businessProgress'
import { fetchTicketDetail } from '@/api/ticket'
import { TICKET_STATUS_TEXT, type Ticket } from '@/types/api'
import type { RefundApprovalView } from '@/types/businessProgress'
import { refundApprovalState } from '@/utils/businessProgress'

const props = defineProps<{
  open: boolean
  sessionId: string
  ticketId: string
  identityKey: string
  trigger?: HTMLElement | null
}>()
const emit = defineEmits<{ 'update:open': [value: boolean] }>()
const router = useRouter()
const headingId = useId()
const panel = ref<HTMLElement | null>(null)
const closeButton = ref<HTMLButtonElement | null>(null)
const visible = ref(false)
const ticket = ref<Ticket | null>(null)
const records = ref<RefundApprovalView[]>([])
const ticketLoading = ref(false)
const refundLoading = ref(false)
const ticketError = ref('')
const refundError = ref('')
const page = ref(1)
const total = ref(0)
const pageSize = 10
const loading = computed(() => ticketLoading.value || refundLoading.value)
const pages = computed(() => Math.max(1, Math.ceil(total.value / pageSize)))
const cards = computed(() => records.value.map((record) => ({ record, state: refundApprovalState(record) })))
let contextVersion = 0
let refundVersion = 0
let ticketVersion = 0
let disposed = false
let returnFocus: HTMLElement | null = null

/** 关闭、切会话和换身份先失效请求，再清除业务信息。 */
function clear() {
  contextVersion++
  ticketVersion++
  refundVersion++
  ticket.value = null
  records.value = []
  ticketError.value = ''
  refundError.value = ''
  ticketLoading.value = false
  refundLoading.value = false
  total.value = 0
}
function active(version: number) {
  return !disposed && props.open && visible.value && contextVersion === version
}
function errorText(error: unknown, subject: string) {
  return (error as AxiosError)?.response?.status === 404
    ? '当前会话不可访问，请返回会话列表核对。'
    : `${subject}暂时无法加载，请稍后重试。`
}
async function loadTicket() {
  const context = contextVersion
  const request = ++ticketVersion
  ticket.value = null
  ticketError.value = ''
  ticketLoading.value = true
  try {
    const result = await fetchTicketDetail(props.ticketId, { silentError: true })
    if (!active(context) || request !== ticketVersion) return
    if (result.ticket.id !== props.ticketId || result.ticket.sessionId !== props.sessionId) {
      ticketError.value = '工单与当前会话不一致，请返回会话列表核对。'
      return
    }
    ticket.value = result.ticket
  } catch (error) {
    if (active(context) && request === ticketVersion) ticketError.value = errorText(error, '工单进度')
  } finally {
    if (active(context) && request === ticketVersion) ticketLoading.value = false
  }
}
async function loadRefunds(targetPage = page.value) {
  const context = contextVersion
  const request = ++refundVersion
  records.value = []
  refundError.value = ''
  refundLoading.value = true
  page.value = targetPage
  try {
    const result = await fetchRefundApprovals(props.sessionId, targetPage, pageSize)
    if (!active(context) || request !== refundVersion) return
    total.value = result.total
    const lastPage = Math.max(1, Math.ceil(result.total / pageSize))
    if (targetPage > lastPage) {
      void loadRefunds(lastPage)
      return
    }
    records.value = result.items
  } catch (error) {
    if (active(context) && request === refundVersion) refundError.value = errorText(error, '退款进度')
  } finally {
    if (active(context) && request === refundVersion) refundLoading.value = false
  }
}
function refresh() {
  void loadTicket()
  void loadRefunds()
}
function restoreFocus() {
  void nextTick(() => {
    if (!visible.value && returnFocus?.isConnected) returnFocus.focus()
  })
}
function dismiss(restore = true) {
  clear()
  visible.value = false
  emit('update:open', false)
  if (restore) restoreFocus()
}
function openDetail(path: string, query?: Record<string, string>) {
  dismiss(false)
  void router.push(query ? { path, query } : path)
}
function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') {
    event.preventDefault()
    dismiss()
  } else if (event.key === 'Tab') {
    const buttons = panel.value?.querySelectorAll<HTMLButtonElement>('button:not(:disabled)')
    if (!buttons?.length) return
    const first = buttons[0]!
    const last = buttons[buttons.length - 1]!
    const outsidePanel = !panel.value?.contains(document.activeElement)
    if (event.shiftKey && (document.activeElement === first || outsidePanel)) {
      event.preventDefault()
      last.focus()
    } else if (!event.shiftKey && (document.activeElement === last || outsidePanel)) {
      event.preventDefault()
      first.focus()
    }
  }
}
function time(ms: number | null) {
  return ms && Number.isFinite(ms)
    ? new Date(ms).toLocaleString('zh-CN', { hour12: false })
    : '时间未记录'
}
watch(() => [props.open, props.sessionId, props.ticketId, props.identityKey] as const,
  ([open, sessionId, ticketId, identity], previous) => {
    if (previous && identity !== previous[3]) {
      dismiss(false)
      return
    }
    clear()
    page.value = 1
    visible.value = open && Boolean(sessionId && ticketId && identity)
    if (!visible.value) {
      if (previous?.[0]) restoreFocus()
      return
    }
    if (!previous?.[0]) returnFocus = props.trigger ?? document.activeElement as HTMLElement | null
    const context = contextVersion
    void nextTick(() => {
      if (!active(context)) return
      refresh()
      closeButton.value?.focus()
    })
  }, { immediate: true, flush: 'sync' })
onBeforeUnmount(() => {
  disposed = true
  clear()
})
</script>

<template>
  <Popup :show="visible" position="bottom" round teleport="body" destroy-on-close :duration="0.16"
    class="business-progress-popup" aria-modal="true" :aria-labelledby="headingId"
    @update:show="(value) => { if (!value) dismiss() }" @keydown="onKeydown">
    <section v-if="visible" ref="panel" class="progress-panel">
      <header class="progress-header">
        <div><span class="progress-eyebrow">当前会话</span><h2 :id="headingId">办理进度</h2></div>
        <button ref="closeButton" type="button" data-action="close" @click="dismiss()">关闭</button>
      </header>
      <div class="progress-body">
        <section class="progress-section" data-section="ticket" :aria-busy="ticketLoading">
          <h3>服务工单</h3>
          <p v-if="ticketLoading" class="progress-placeholder" role="status">正在核对工单…</p>
          <div v-else-if="ticketError" class="progress-error" role="alert">
            <p>{{ ticketError }}</p>
            <button type="button" data-action="retry-ticket" @click="loadTicket">重新核对</button>
          </div>
          <article v-else-if="ticket" class="ticket-summary">
            <div class="ticket-summary-top"><strong>{{ ticket.title || '服务工单' }}</strong>
              <span class="progress-badge" :data-tone="ticket.status === 'RESOLVED' ? 'complete' : 'neutral'">
                {{ TICKET_STATUS_TEXT[ticket.status] || '状态待核对' }}
              </span>
            </div>
            <p v-if="ticket.status === 'WAITING_CONFIRM'">客服已提交处理结果，请查看工单并确认是否解决。</p>
            <p v-else-if="ticket.status === 'CLOSED'">会话已结束，仍可查看已保存的办理记录。</p>
            <small>更新于 {{ time(ticket.updatedAtMs) }}</small>
            <button type="button" data-action="view-ticket" @click="openDetail(`/tickets/${encodeURIComponent(ticket.id)}`)">查看工单详情 <span aria-hidden="true">›</span></button>
          </article>
        </section>
        <section class="progress-section" data-section="refund" :aria-busy="refundLoading">
          <div class="section-title"><h3>退款办理</h3><span v-if="!refundError && !refundLoading">{{ total }} 条记录</span></div>
          <p class="progress-intro">查看本次会话的审批与执行记录。</p>
          <p v-if="refundLoading" class="progress-placeholder" role="status">正在核对退款进度…</p>
          <div v-else-if="refundError" class="progress-error" role="alert">
            <p>{{ refundError }}</p>
            <button type="button" data-action="retry-refunds" @click="loadRefunds()">重新核对</button>
          </div>
          <div v-else-if="cards.length === 0" class="progress-empty">
            <strong>{{ total === 0 ? '暂无退款审批记录' : '这一页暂无记录' }}</strong>
            <p>此处展示本次会话中已保存的退款审批，其他售后事项请查看工单或联系人工。</p>
          </div>
          <ol v-else class="progress-records">
            <li v-for="{ record, state } in cards" :key="record.id" class="refund-card">
              <div class="refund-heading"><span class="progress-badge" :data-tone="state.tone">{{ state.label }}</span>
                <strong v-if="record.amount !== null" class="refund-amount"><small>申请金额</small>¥ {{ record.amount }}</strong>
              </div>
              <p class="refund-description">{{ state.description }}</p>
              <dl><div><dt>订单</dt><dd>{{ record.orderId }}</dd></div>
                <div><dt>申请时间</dt><dd>{{ time(record.createdAtMs) }}</dd></div>
                <div v-if="record.decidedAtMs"><dt>审批时间</dt><dd>{{ time(record.decidedAtMs) }}</dd></div>
              </dl>
              <button type="button" data-action="view-order" @click="openDetail(`/orders/${encodeURIComponent(record.orderId)}`, { ticketId: props.ticketId })">查看订单 <span aria-hidden="true">›</span></button>
            </li>
          </ol>
          <nav v-if="!refundError && pages > 1" class="progress-pagination" aria-label="退款记录分页">
            <button type="button" data-action="prev-page" :disabled="refundLoading || page <= 1" @click="loadRefunds(page - 1)">上一页</button>
            <span aria-live="polite">{{ page }} / {{ pages }}</span>
            <button type="button" data-action="next-page" :disabled="refundLoading || page >= pages" @click="loadRefunds(page + 1)">下一页</button>
          </nav>
        </section>
      </div>
      <footer class="progress-footer"><button type="button" data-action="refresh" :disabled="loading" @click="refresh">{{ loading ? '正在核对…' : '刷新办理进度' }}</button></footer>
    </section>
  </Popup>
</template>

<style scoped>
.business-progress-popup {
  left: max(0px, calc((100vw - var(--cw-shell-width, 480px)) / 2));
  width: min(100%, var(--cw-shell-width, 480px));
  height: min(86dvh, 780px);
  color: var(--cw-text-primary, #18273b);
  background: var(--cw-bg, #f3f6fa);
}
.progress-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  overflow: hidden;
}
.progress-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 18px 20px 14px;
  background: var(--cw-card-bg, #fff);
  border-bottom: 1px solid var(--cw-line, #e3e7ef);
}
.progress-eyebrow {
  display: block;
  color: var(--cw-text-secondary, #626d80);
  font-size: 12px;
  margin-bottom: 4px;
}
h2 {
  margin: 0;
  font-size: 22px;
  letter-spacing: -.3px;
}
.progress-panel button {
  min-width: 44px;
  min-height: 44px;
  border: 0;
  border-radius: 10px;
  padding: 8px 12px;
  font: inherit;
  color: var(--cw-primary, #3658cb);
  background: transparent;
  cursor: pointer;
}
.progress-panel button:focus-visible {
  outline: 3px solid var(--cw-focus-ring, #9cb6ff);
  outline-offset: 2px;
}
.progress-panel button:disabled {
  opacity: .5;
  cursor: default;
}
.progress-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  overscroll-behavior: contain;
  padding: 20px 16px;
}
.progress-section + .progress-section {
  margin-top: 24px;
}
h3 {
  font-size: 15px;
  margin: 0 0 12px;
}
.section-title {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
}
.section-title span, .progress-intro {
  color: var(--cw-text-secondary, #626d80);
  font-size: 13px;
}
.progress-intro {
  margin: -4px 0 12px;
  line-height: 1.7;
}
.ticket-summary, .refund-card, .progress-empty, .progress-error {
  padding: 16px;
  border: 1px solid var(--cw-line, #e3e7ef);
  border-radius: 16px;
  background: var(--cw-card-bg, #fff);
}
.ticket-summary-top {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}
.ticket-summary-top strong {
  font-size: 16px;
  line-height: 1.6;
  overflow-wrap: anywhere;
}
.ticket-summary p, .refund-description, .progress-empty p, .progress-error p {
  font-size: 14px;
  line-height: 1.75;
  margin: 12px 0;
  overflow-wrap: anywhere;
}
.ticket-summary small {
  display: block;
  color: var(--cw-text-secondary, #626d80);
  font-size: 12px;
  line-height: 1.7;
}
.ticket-summary button, .refund-card > button {
  display: flex;
  width: 100%;
  align-items: center;
  justify-content: space-between;
  margin-top: 12px;
  border-top: 1px solid var(--cw-line, #e3e7ef);
  border-radius: 0;
  padding: 10px 0 0;
  text-align: left;
}
.progress-badge {
  display: inline-flex;
  flex-shrink: 0;
  padding: 5px 8px;
  border-radius: 7px;
  font-size: 12px;
  line-height: 1.5;
  color: #566477;
  background: #edf1f6;
}
.progress-badge[data-tone='pending'] {
  color: #855511;
  background: #fff2d8;
}
.progress-badge[data-tone='active'] {
  color: #3658cb;
  background: #edf1ff;
}
.progress-badge[data-tone='complete'] {
  color: #236954;
  background: #e5f4ed;
}
.progress-badge[data-tone='attention'] {
  color: #a13333;
  background: #fcecec;
}
.progress-records {
  list-style: none;
  padding: 0;
  margin: 0;
  display: grid;
  gap: 12px;
}
.refund-heading {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 10px;
  flex-wrap: wrap;
}
.refund-amount {
  font-size: 18px;
  font-variant-numeric: tabular-nums;
  overflow-wrap: anywhere;
}
.refund-amount small {
  display: block;
  font-size: 11px;
  font-weight: 400;
  color: var(--cw-text-secondary, #626d80);
  margin-bottom: 3px;
}
dl {
  margin: 0;
  display: grid;
  gap: 6px;
  font-size: 12px;
  line-height: 1.7;
}
dl > div {
  display: grid;
  grid-template-columns: 60px minmax(0, 1fr);
  gap: 10px;
}
dt {
  color: var(--cw-text-secondary, #626d80);
}
dd {
  margin: 0;
  text-align: right;
  overflow-wrap: anywhere;
}
.progress-placeholder {
  padding: 28px 8px;
  text-align: center;
  color: var(--cw-text-secondary, #626d80);
  font-size: 14px;
}
.progress-empty p {
  color: var(--cw-text-secondary, #626d80);
  margin-bottom: 0;
}
.progress-error {
  border-color: #ecc7c7;
}
.progress-error p {
  margin-top: 0;
}
.progress-error button {
  background: var(--cw-primary-soft, #edf1ff);
}
.progress-pagination {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-top: 12px;
  font-size: 13px;
}
.progress-footer {
  background: var(--cw-card-bg, #fff);
  border-top: 1px solid var(--cw-line, #e3e7ef);
  padding: 12px 16px calc(12px + env(safe-area-inset-bottom));
}
.progress-footer button {
  width: 100%;
  background: var(--cw-primary, #3658cb);
  color: #fff;
  font-weight: 600;
}
@media (prefers-reduced-motion: reduce) {
  .business-progress-popup {
    transition-duration: 0s !important;
  }
}
</style>
