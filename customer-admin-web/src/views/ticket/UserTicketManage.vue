<script setup lang="ts">
import { computed, onMounted, onScopeDispose, ref, watch } from 'vue'
import { usePagedList } from '@/composables/usePagedList'
import CrudLoadState from '@/components/CrudLoadState.vue'
import { getTicketWsCredential, pageTickets } from '@/api/user-ticket'
import { getRequestErrorMessage } from '@/api/request'
import { useAuthStore } from '@/store/auth'
import { useTicketReplies } from '@/store/ticketReplies'
import { WsClient } from '@/utils/ws'
import TicketChatPanel from './components/TicketChatPanel.vue'
import PageContextHeader from '@/layouts/PageContextHeader.vue'
import {
  CATEGORY_LABELS,
  PRIORITY_LABELS,
  STATUS_LABELS,
  STATUS_TAG_TYPE,
  type TicketPageQuery,
  type TicketVO,
} from '@/types/ticket'

const auth = useAuthStore()
const replies = useTicketReplies()
const { loading, loadError, list, total, query, loadList, handleSearch } = usePagedList<
  TicketVO,
  TicketPageQuery
>({
  initQuery: () => ({ status: '', category: '', priority: '', pageNum: 1, pageSize: 10 }),
  page: async (query) => {
    const result = await pageTickets(query)
    return { list: result.items, total: result.total }
  },
})
const ws = new WsClient()
const activeTicketId = ref<string | null>(null)
const agentId = ref('')
const ready = ref(false)
const connected = ref(false)
const credentialError = ref('')
const connecting = ref(false)
const keyword = ref('')
const scope = computed(() => {
  if (agentId.value && query.assignee === agentId.value) return 'mine'
  return query.status === 'WAITING_AGENT' ? 'waiting' : 'all'
})
const visibleTickets = computed(() =>
  list.value.filter(
    (ticket) =>
      !keyword.value.trim() ||
      `${ticket.title} ${ticket.id} ${ticket.userId}`
        .toLowerCase()
        .includes(keyword.value.trim().toLowerCase()),
  ),
)
let disposed = false
let credentialRequest = 0
const identity = ref('')
let renewalTimer: ReturnType<typeof setTimeout> | undefined
let refreshTimer: ReturnType<typeof setTimeout> | undefined

function scheduleRefresh() {
  if (refreshTimer) clearTimeout(refreshTimer)
  refreshTimer = setTimeout(() => {
    if (!disposed) void loadList()
  }, 150)
}
const subscriptions = [
  ws.on('open', () => {
    connected.value = true
    void loadList()
  }),
  ws.on('close', () => {
    connected.value = false
  }),
  ws.on('unauthorized', () => {
    connected.value = false
    credentialError.value = '实时凭证已失效，请重新连接后核对记录。'
    ws.close()
  }),
  ws.on('ticket_new', scheduleRefresh),
  ws.on('ticket_event', scheduleRefresh),
]

async function setupWs() {
  const request = ++credentialRequest
  const token = auth.token
  connecting.value = true
  clearTimeout(renewalTimer)
  try {
    const credential = await getTicketWsCredential()
    if (disposed || request !== credentialRequest || token !== auth.token) return
    if (!credential.agentId || !credential.tenantId || !credential.token || !credential.wsUrl) {
      throw new Error('坐席身份信息不完整，请重新连接。')
    }
    if (!Number.isFinite(credential.expiresAtMs) || credential.expiresAtMs <= Date.now()) {
      throw new Error('实时凭证已过期，请重新连接。')
    }
    const nextIdentity = JSON.stringify([token, credential.tenantId, credential.agentId])
    if (identity.value && identity.value !== nextIdentity) activeTicketId.value = null
    identity.value = nextIdentity
    replies.bindIdentity(credential.tenantId, credential.agentId)
    const wasMine = scope.value === 'mine'
    agentId.value = credential.agentId
    if (wasMine) query.assignee = credential.agentId
    ready.value = true
    credentialError.value = ''
    const url = new URL(credential.wsUrl, window.location.href)
    url.searchParams.set('token', credential.token)
    connected.value = false
    ws.connect(url.toString())
    renewalTimer = setTimeout(
      () => {
        if (!disposed) void setupWs()
      },
      Math.max(1000, credential.expiresAtMs - Date.now() - 30000),
    )
  } catch (error) {
    if (disposed || request !== credentialRequest || token !== auth.token) return
    credentialError.value = getRequestErrorMessage(error, '实时连接暂时不可用，请重新连接。')
  } finally {
    if (!disposed && request === credentialRequest) connecting.value = false
  }
}
function selectScope(value: string) {
  query.assignee = value === 'mine' ? agentId.value : undefined
  query.status = value === 'waiting' ? 'WAITING_AGENT' : ''
  void handleSearch()
}
function openChat(id: string) {
  activeTicketId.value = id
}
function formatTime(ms: number) {
  return new Date(ms).toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  })
}
watch(
  () => auth.token,
  () => {
    credentialRequest += 1
    ready.value = false
    connected.value = false
    activeTicketId.value = null
    ws.close()
    if (auth.token) void setupWs()
  },
)
onMounted(() => {
  void loadList()
  void setupWs()
})
onScopeDispose(() => {
  disposed = true
  credentialRequest += 1
  clearTimeout(renewalTimer)
  clearTimeout(refreshTimer)
  subscriptions.forEach((unsubscribe) => unsubscribe())
  ws.close()
})
</script>

<template>
  <div class="service-desk page" :class="{ 'has-active-ticket': activeTicketId }">
    <header class="desk-header">
      <PageContextHeader />
      <div class="desk-connection">
        <span class="connection-dot" :class="{ connected }"></span
        ><span>{{ connected ? '实时在线' : '实时连接暂未建立' }}</span
        ><el-button :loading="connecting" text @click="setupWs">重新连接</el-button>
      </div>
    </header>
    <div v-if="credentialError" class="credential-error" role="status">{{ credentialError }}</div>
    <div class="desk-grid">
      <aside class="ticket-queue" aria-label="工单队列">
        <div class="queue-header">
          <div class="queue-title">
            <h2>服务会话</h2>
            <span>{{ loading && !list.length ? '加载中' : `共 ${total} 条` }}</span
            ><el-button text aria-label="刷新" :loading="loading" @click="loadList"
              ><el-icon><Refresh /></el-icon
            ></el-button>
          </div>
          <div class="queue-scopes" aria-label="工单范围">
            <button :aria-pressed="scope === 'all'" @click="selectScope('all')">全部</button
            ><button :aria-pressed="scope === 'waiting'" @click="selectScope('waiting')">
              待接入</button
            ><button
              :aria-pressed="scope === 'mine'"
              :disabled="!agentId"
              @click="selectScope('mine')"
            >
              我负责
            </button>
          </div>
          <el-input
            v-model="keyword"
            placeholder="在本页搜索标题、客户或工单"
            clearable
            aria-label="搜索本页工单"
            ><template #prefix
              ><el-icon><Search /></el-icon></template
          ></el-input>
          <div class="queue-filters">
            <el-select
              v-model="query.status"
              placeholder="所有状态"
              clearable
              aria-label="工单状态筛选"
              @change="handleSearch"
              ><el-option
                v-for="(label, value) in STATUS_LABELS"
                :key="value"
                :value="value"
                :label="label" /></el-select
            ><el-select
              v-model="query.priority"
              placeholder="优先级"
              clearable
              aria-label="工单优先级筛选"
              @change="handleSearch"
              ><el-option
                v-for="(label, value) in PRIORITY_LABELS"
                :key="value"
                :value="value"
                :label="label"
            /></el-select>
          </div>
          <el-select
            v-model="query.category"
            placeholder="所有问题分类"
            clearable
            aria-label="工单分类筛选"
            @change="handleSearch"
            ><el-option
              v-for="(label, value) in CATEGORY_LABELS"
              :key="value"
              :value="value"
              :label="label"
          /></el-select>
        </div>
        <CrudLoadState
          :error="loadError"
          :has-stale-data="list.length > 0"
          :loading="loading"
          @retry="loadList"
        />
        <div class="queue-list" :aria-busy="loading">
          <article
            v-for="ticket in visibleTickets"
            :key="ticket.id"
            class="queue-ticket"
            :class="{ selected: activeTicketId === ticket.id }"
          >
            <button
              class="queue-entry"
              :aria-label="`打开工单：${ticket.title || ticket.id}`"
              :aria-pressed="activeTicketId === ticket.id"
              @click="openChat(ticket.id)"
            >
              <div class="queue-row">
                <span class="queue-customer">{{ ticket.userId }}</span
                ><el-tag :type="STATUS_TAG_TYPE[ticket.status]" size="small">{{
                  STATUS_LABELS[ticket.status]
                }}</el-tag>
              </div>
              <strong>{{ ticket.title || '尚未填写标题' }}</strong>
              <p>
                {{ CATEGORY_LABELS[ticket.category] }} ·
                {{ PRIORITY_LABELS[ticket.priority] }}优先级
              </p>
              <p class="queue-note">
                {{
                  ticket.handoffReason ||
                  ticket.resolveNote ||
                  `处理坐席：${ticket.assignee || '待接入'}`
                }}
              </p>
            </button>
            <div class="queue-footer">
              <time>{{ formatTime(ticket.updatedAtMs) }}</time
              ><span v-if="ready && replies.state(ticket.id).content" class="draft-mark">草稿</span
              ><el-button link type="primary" @click="openChat(ticket.id)">查看</el-button>
            </div>
          </article>
          <div v-if="!loading && !visibleTickets.length" class="queue-empty">
            <el-icon><ChatDotRound /></el-icon>
            <p>
              {{
                loadError ? '暂时无法加载工单' : keyword ? '本页没有匹配工单' : '暂无符合条件的工单'
              }}
            </p>
            <span>{{ keyword ? '可更换关键词或切换分页' : '新会话进入后会更新此处' }}</span>
          </div>
        </div>
        <div class="queue-pagination">
          <el-pagination
            v-model:current-page="query.pageNum"
            :page-size="query.pageSize"
            :total="total"
            layout="prev, pager, next"
            :pager-count="5"
            small
            @current-change="loadList"
          />
          <p>切换工单保留草稿和阅读位置</p>
        </div>
      </aside>
      <TicketChatPanel
        :key="identity"
        :ticket-id="activeTicketId"
        :ws="ws"
        :agent-id="agentId"
        :ready="ready"
        :connected="connected"
        @back="activeTicketId = null"
        @refresh="loadList"
      />
    </div>
  </div>
</template>

<style scoped>
.service-desk {
  display: flex;
  flex-direction: column;
  gap: 18px;
  min-width: 0;
  color: var(--cw-text);
  height: 100%;
  min-height: 0;
}
.desk-header :deep(.cw-page-context) {
  margin: 0;
  padding: 0;
}
.desk-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex: none;
}
.desk-eyebrow {
  font-size: 11px;
  color: var(--cw-text-muted);
  letter-spacing: 0.06em;
}
.desk-header h1 {
  margin: 4px 0 0;
  font-size: 24px;
  font-weight: 650;
  line-height: 1.3;
}
.desk-connection {
  display: flex;
  align-items: center;
  gap: 7px;
  color: var(--cw-text-muted);
  font-size: 12px;
}
.connection-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--el-color-warning);
}
.connection-dot.connected {
  background: var(--el-color-success);
}
.desk-grid {
  display: grid;
  grid-template-columns: 272px minmax(0, 1fr);
  min-height: 0;
  flex: 1;
  gap: 14px;
}
.ticket-queue {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  background: var(--cw-paper);
  border: 1px solid var(--cw-line);
  border-radius: 12px;
  overflow: hidden;
}
.queue-header {
  padding: 16px 14px;
  border-bottom: 1px solid var(--cw-line);
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.queue-title {
  display: flex;
  align-items: center;
  gap: 10px;
}
.queue-title h2 {
  font-size: 15px;
  margin: 0;
}
.queue-title > span {
  font-size: 11px;
  color: var(--cw-text-muted);
}
.queue-title .el-button {
  margin-left: auto;
  padding: 6px;
}
.queue-scopes {
  display: flex;
  gap: 3px;
  border-radius: 8px;
  padding: 3px;
  background: var(--cw-canvas);
}
.queue-scopes button {
  flex: 1;
  padding: 7px 3px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--cw-text-muted);
  font-size: 12px;
  cursor: pointer;
}
.queue-scopes button[aria-pressed='true'] {
  color: var(--cw-cobalt);
  background: var(--cw-paper);
  box-shadow: 0 1px 4px #152b4510;
  font-weight: 650;
}
.queue-scopes button:disabled {
  opacity: 0.5;
  cursor: default;
}
.queue-filters {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 8px;
}
.queue-list {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  overscroll-behavior: contain;
}
.queue-ticket {
  border-bottom: 1px solid var(--cw-line);
  border-left: 3px solid transparent;
}
.queue-ticket.selected {
  background: color-mix(in srgb, var(--cw-cobalt) 6%, var(--cw-paper));
  border-left-color: var(--cw-cobalt);
}
.queue-entry {
  width: 100%;
  text-align: left;
  border: 0;
  background: transparent;
  color: var(--cw-text);
  padding: 16px 13px 0;
  cursor: pointer;
}
.queue-entry:hover {
  background: color-mix(in srgb, var(--cw-cobalt) 3%, transparent);
}
.queue-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
}
.queue-customer {
  font-size: 12px;
  color: var(--cw-text-muted);
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.queue-entry strong {
  display: block;
  font-size: 14px;
  line-height: 1.6;
  margin: 10px 0 7px;
  overflow-wrap: anywhere;
}
.queue-entry p {
  font-size: 12px;
  color: var(--cw-text-muted);
  line-height: 1.6;
  margin: 4px 0;
}
.queue-note {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.queue-footer {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 4px 13px 12px;
}
.queue-footer time {
  font-size: 11px;
  color: var(--cw-text-muted);
}
.queue-footer .el-button {
  margin-left: auto;
  font-size: 12px;
}
.draft-mark {
  color: var(--el-color-warning-dark-2);
  font-size: 11px;
}
.queue-pagination {
  padding: 14px 8px 10px;
  border-top: 1px solid var(--cw-line);
  display: flex;
  align-items: center;
  flex-direction: column;
  gap: 8px;
}
.queue-pagination p {
  font-size: 11px;
  margin: 0;
  color: var(--cw-text-muted);
}
.queue-empty {
  padding: 46px 20px;
  text-align: center;
  color: var(--cw-text-muted);
  font-size: 13px;
}
.queue-empty .el-icon {
  font-size: 26px;
}
.queue-empty span {
  font-size: 12px;
}
.credential-error {
  color: var(--el-color-warning-dark-2);
  font-size: 13px;
}
:deep(.crud-load-state) {
  margin: 8px;
  padding: 10px;
  font-size: 12px;
}
@media (max-width: 1100px) {
  .desk-grid {
    grid-template-columns: 248px minmax(0, 1fr);
    gap: 12px;
  }
}
@media (max-width: 700px) {
  .service-desk {
    height: 100%;
    min-height: 0;
    gap: 12px;
  }
  .desk-header h1 {
    font-size: 20px;
  }
  .desk-header :deep(.cw-page-context p) {
    display: none;
  }
  .desk-header :deep(.cw-page-context h1) {
    font-size: 20px;
  }
  .desk-connection > span:not(.connection-dot) {
    display: none;
  }
  .desk-connection .el-button {
    min-height: 44px;
  }
  .desk-grid {
    grid-template-columns: minmax(0, 1fr);
  }
  .ticket-workspace {
    display: none;
  }
  .has-active-ticket .ticket-queue {
    display: none;
  }
  .has-active-ticket :deep(.ticket-workspace) {
    display: grid;
  }
  .queue-scopes button {
    min-height: 44px;
  }
  .queue-header :deep(.el-input__wrapper),
  .queue-header :deep(.el-select__wrapper) {
    min-height: 44px;
  }
  .queue-footer .el-button {
    min-height: 44px;
  }
}
</style>
