<script setup lang="ts">
import { computed, onScopeDispose, ref, watch } from 'vue'
import {
  claimTicket,
  closeTicket,
  holdTicket,
  resolveTicket,
  resumeTicket,
  transferTicket,
  updateTicketCategory,
  updateTicketPriority,
} from '@/api/user-ticket'
import { getRequestErrorMessage } from '@/api/request'
import { useAuthStore } from '@/store/auth'
import {
  CATEGORY_LABELS,
  PRIORITY_LABELS,
  STATUS_LABELS,
  type TicketCategory,
  type TicketDetailVO,
  type TicketPriority,
} from '@/types/ticket'

const props = withDefaults(
  defineProps<{
    detail: TicketDetailVO
    agentId: string
    disabled: boolean
    showHeading?: boolean
  }>(),
  { showHeading: true },
)
const auth = useAuthStore()
const canEdit = computed(() => auth.hasPermission('user-ticket:edit'))
const emit = defineEmits<{ refresh: [] }>()
const acting = ref(false)
const actionError = ref('')
let generation = 0
let disposed = false
const ticket = computed(() => props.detail.ticket)
const isSelf = computed(() => ticket.value.assignee === props.agentId)
const priorityOptions = Object.entries(PRIORITY_LABELS)
const categoryOptions = Object.entries(CATEGORY_LABELS)

watch(
  () => ticket.value.id,
  () => {
    generation += 1
    acting.value = false
    actionError.value = ''
  },
)
onScopeDispose(() => {
  disposed = true
  generation += 1
})

/** 确认框始终绑定发起时的工单；切换后确认不会操作新工单，也不会替它刷新状态。 */
async function act(
  label: string,
  execute: (id: string, value: string) => Promise<void>,
  options: { prompt?: string; required?: boolean; confirm?: string } = {},
) {
  if (acting.value || props.disabled) return
  const id = ticket.value.id
  const version = generation
  const current = () => !disposed && version === generation && ticket.value.id === id
  acting.value = true
  actionError.value = ''
  try {
    let value = ''
    if (options.prompt) {
      const response = await ElMessageBox.prompt(options.prompt, `${label} · ${id}`, {
        inputType: 'textarea',
        confirmButtonText: '确认',
        cancelButtonText: '取消',
        inputValidator: (text: string) => !options.required || !!text?.trim() || '请填写必要信息',
      })
      value = response.value ?? ''
    } else if (options.confirm) {
      await ElMessageBox.confirm(options.confirm, `${label} · ${id}`, {
        confirmButtonText: '确认',
        cancelButtonText: '取消',
      })
    }
    if (!current()) return
    await execute(id, value)
    if (!current()) return
    ElMessage.success(`${label}成功`)
    emit('refresh')
  } catch (error) {
    if (current() && error !== 'cancel' && error !== 'close') {
      actionError.value = getRequestErrorMessage(error, `${label}失败，请刷新工单后核对。`)
      emit('refresh')
    }
  } finally {
    if (current()) acting.value = false
  }
}

function priority(value: TicketPriority) {
  void act('调整优先级', (id) => updateTicketPriority(id, value))
}
function category(value: TicketCategory) {
  void act('调整分类', (id) => updateTicketCategory(id, value))
}
function transfer() {
  void act('转派工单', (id, value) => transferTicket(id, { toAgent: value.trim() }), {
    prompt: '填写接收坐席的标识；留空将转回接单池。',
  })
}
function returnToPool() {
  void act('转回接单池', (id) => transferTicket(id, { toAgent: '' }), {
    confirm: '转回后将解除当前坐席分配，由队列中的坐席重新接入。',
  })
}
function formatTime(ms: number) {
  return new Date(ms).toLocaleString('zh-CN', { hour12: false })
}
</script>

<template>
  <aside class="ticket-context" aria-label="工单信息与操作">
    <div v-if="showHeading" class="context-heading">
      <h3>工单信息</h3>
      <span>当前记录</span>
    </div>
    <dl class="ticket-facts">
      <dt>客户</dt>
      <dd>{{ ticket.userId }}</dd>
      <dt>工单号</dt>
      <dd class="mono">{{ ticket.id }}</dd>
      <dt>处理坐席</dt>
      <dd>{{ ticket.assignee || '等待接入' }}<span v-if="isSelf" class="self-tag">本人</span></dd>
      <dt>服务状态</dt>
      <dd>{{ STATUS_LABELS[ticket.status] }}</dd>
      <dt>创建时间</dt>
      <dd>{{ formatTime(ticket.createdAtMs) }}</dd>
    </dl>
    <div class="context-fields">
      <label
        >优先级<el-select
          :model-value="ticket.priority"
          :disabled="disabled || acting || ticket.status === 'CLOSED'"
          v-permission="'user-ticket:edit'"
          aria-label="工单优先级"
          @change="priority"
          ><el-option
            v-for="[value, label] in priorityOptions"
            :key="value"
            :value="value"
            :label="label" /></el-select
        ><span v-if="!canEdit">{{ PRIORITY_LABELS[ticket.priority] }}</span></label
      >
      <label
        >问题分类<el-select
          :model-value="ticket.category"
          :disabled="disabled || acting || ticket.status === 'CLOSED'"
          v-permission="'user-ticket:edit'"
          aria-label="工单问题分类"
          @change="category"
          ><el-option
            v-for="[value, label] in categoryOptions"
            :key="value"
            :value="value"
            :label="label" /></el-select
        ><span v-if="!canEdit">{{ CATEGORY_LABELS[ticket.category] }}</span></label
      >
    </div>
    <section class="handoff-note" v-if="ticket.handoffReason || ticket.resolveNote">
      <h3>{{ ticket.resolveNote ? '处理结论' : '转人工原因' }}</h3>
      <p>{{ ticket.resolveNote || ticket.handoffReason }}</p>
    </section>
    <div v-if="actionError" role="alert" class="action-error">{{ actionError }}</div>
    <div class="ticket-actions">
      <el-button
        v-if="ticket.status === 'WAITING_AGENT'"
        v-permission="'user-ticket:claim'"
        type="primary"
        :disabled="disabled || acting"
        @click="act('接入工单', (id) => claimTicket(id))"
        >接入工单</el-button
      >
      <el-button
        v-if="isSelf && ticket.status === 'PROCESSING'"
        v-permission="'user-ticket:edit'"
        :disabled="disabled || acting"
        @click="
          act('挂起工单', (id) => holdTicket(id, {}), {
            confirm: '挂起后保留当前坐席，可在收到补充信息后恢复处理。',
          })
        "
        >挂起</el-button
      >
      <el-button
        v-if="isSelf && ticket.status === 'ON_HOLD'"
        v-permission="'user-ticket:edit'"
        :disabled="disabled || acting"
        @click="act('恢复处理', (id) => resumeTicket(id))"
        >恢复处理</el-button
      >
      <el-button
        v-if="isSelf && ticket.status === 'PROCESSING'"
        v-permission="'user-ticket:transfer'"
        :disabled="disabled || acting"
        @click="transfer"
        >转派</el-button
      >
      <el-button
        v-if="isSelf && ticket.status === 'ON_HOLD'"
        v-permission="'user-ticket:transfer'"
        :disabled="disabled || acting"
        @click="returnToPool"
        >转回接单池</el-button
      >
      <el-button
        v-if="isSelf && ticket.status === 'PROCESSING'"
        v-permission="'user-ticket:resolve'"
        type="primary"
        :disabled="disabled || acting"
        @click="
          act('标记处理完毕', (id, note) => resolveTicket(id, { note }), {
            prompt: '请填写处理结论，提交后等待客户确认。',
            required: true,
          })
        "
        >标记处理完毕</el-button
      >
      <el-button
        v-if="['AI_SERVING', 'RESOLVED', 'WAITING_CONFIRM'].includes(ticket.status)"
        v-permission="'user-ticket:close'"
        :disabled="disabled || acting"
        @click="
          act('关闭工单', (id, reason) => closeTicket(id, { reason }), {
            prompt: '请填写关闭原因，关闭后会话将变为只读。',
            required: true,
          })
        "
        >关闭工单</el-button
      >
    </div>
    <section class="event-section">
      <h3>
        处理轨迹 <span>{{ detail.events.length }}</span>
      </h3>
      <ol v-if="detail.events.length" class="ticket-events">
        <li v-for="event in detail.events" :key="event.id">
          <time>{{ formatTime(event.createdAtMs) }}</time>
          <strong>{{ event.toStatus ? STATUS_LABELS[event.toStatus] : '更新工单' }}</strong>
          <p>
            {{ event.actorId || '系统' }}<span v-if="event.note"> · {{ event.note }}</span>
          </p>
        </li>
      </ol>
      <p v-else class="empty-events">暂无处理轨迹</p>
    </section>
  </aside>
</template>

<style scoped>
.ticket-context {
  padding: 22px 18px;
  color: var(--cw-text);
}
.context-heading {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 22px;
}
h3 {
  font-size: 15px;
  margin: 0;
  font-weight: 650;
}
.context-heading span,
time,
.empty-events {
  color: var(--cw-text-muted);
  font-size: 12px;
}
.ticket-facts {
  display: grid;
  grid-template-columns: 68px minmax(0, 1fr);
  gap: 13px 10px;
  margin: 0 0 22px;
  font-size: 13px;
  line-height: 1.6;
}
dt {
  color: var(--cw-text-muted);
}
dd {
  margin: 0;
  overflow-wrap: anywhere;
}
.mono {
  font-family: ui-monospace, SFMono-Regular, monospace;
  font-size: 12px;
}
.self-tag {
  margin-left: 6px;
  color: var(--cw-cobalt);
  font-size: 11px;
}
.context-fields {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding-top: 18px;
  border-top: 1px solid var(--cw-line);
}
.context-fields label {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 13px;
  gap: 12px;
}
.context-fields .el-select {
  width: 154px;
}
.handoff-note {
  background: var(--cw-canvas);
  border: 1px solid var(--cw-line);
  padding: 14px;
  margin-top: 20px;
  border-radius: 10px;
}
.handoff-note p {
  margin: 8px 0 0;
  font-size: 13px;
  line-height: 1.8;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.ticket-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin: 18px 0 26px;
}
.ticket-actions .el-button {
  margin: 0;
  min-height: 36px;
}
.ticket-actions:empty {
  margin-bottom: 20px;
}
.action-error {
  color: var(--el-color-danger);
  font-size: 13px;
  margin-top: 16px;
}
.event-section {
  border-top: 1px solid var(--cw-line);
  padding-top: 20px;
}
.event-section h3 span {
  color: var(--cw-text-muted);
  font-size: 12px;
  margin-left: 6px;
}
.ticket-events {
  list-style: none;
  margin: 18px 0 0 4px;
  padding-left: 17px;
  border-left: 1px solid var(--cw-line);
}
.ticket-events li {
  position: relative;
  margin-bottom: 20px;
}
.ticket-events li::before {
  content: '';
  width: 7px;
  height: 7px;
  background: var(--cw-cobalt);
  border-radius: 50%;
  position: absolute;
  left: -21px;
  top: 5px;
}
.ticket-events time,
.ticket-events strong {
  display: block;
}
.ticket-events strong {
  font-size: 13px;
  margin-top: 5px;
}
.ticket-events p {
  margin: 4px 0 0;
  font-size: 12px;
  line-height: 1.7;
  color: var(--cw-text-muted);
  overflow-wrap: anywhere;
}
@media (max-width: 700px) {
  .ticket-actions .el-button {
    min-height: 44px;
  }
  .context-fields :deep(.el-select__wrapper) {
    min-height: 44px;
  }
}
</style>
