import { computed, onScopeDispose, ref, watch, type Ref } from 'vue'
import { getTicketDetail, getTicketMessages } from '@/api/user-ticket'
import { getRequestErrorMessage } from '@/api/request'
import { useTicketReplies } from '@/store/ticketReplies'
import type { WsClient } from '@/utils/ws'
import type {
  TicketDetailVO,
  TicketMessageVO,
  WsChatFrameData,
  WsTicketEventFrameData,
} from '@/types/ticket'

const PAGE_SIZE = 30

/** 当前工单的服务端快照；切单和卸载均使旧请求失效，恢复时补齐多页缺口后再合并。 */
export function useTicketConversation(
  ticketId: Ref<string | null>,
  ready: Ref<boolean>,
  ws: WsClient,
) {
  const replies = useTicketReplies()
  const detail = ref<TicketDetailVO | null>(null)
  const history = ref<TicketMessageVO[]>([])
  const loading = ref(false)
  const historyLoading = ref(false)
  const detailError = ref('')
  const historyError = ref('')
  const hasMore = ref(false)
  let generation = 0
  let detailRequest = 0
  let historyRequest = 0
  let disposed = false
  let previousId: string | null = null
  const snapshots = new Map<string, { rows: TicketMessageVO[]; more: boolean }>()
  const active = (id: string, version: number) =>
    !disposed && ready.value && ticketId.value === id && generation === version

  function merge(rows: TicketMessageVO[]) {
    const merged = new Map(history.value.map((message) => [message.messageId, message]))
    for (const message of rows) {
      if (message.ticketId === ticketId.value && message.messageId)
        merged.set(message.messageId, message)
    }
    history.value = [...merged.values()].sort(
      (left, right) => left.createdAtMs - right.createdAtMs || left.id - right.id,
    )
  }

  async function loadDetail() {
    const id = ticketId.value
    if (!id || !ready.value) return
    const version = generation
    const request = ++detailRequest
    loading.value = true
    try {
      const result = await getTicketDetail(id)
      if (!active(id, version) || request !== detailRequest) return
      if (result?.ticket.id !== id) throw new Error('工单详情与当前选择不一致，请重新加载。')
      detail.value = result
      detailError.value = ''
    } catch (error) {
      if (active(id, version) && request === detailRequest)
        detailError.value = getRequestErrorMessage(error, '工单加载失败')
    } finally {
      if (active(id, version) && request === detailRequest) loading.value = false
    }
  }

  function validatePage(rows: TicketMessageVO[], id: string, before?: number): number | undefined {
    if (
      !Array.isArray(rows) ||
      rows.some(
        (row) =>
          row.ticketId !== id ||
          !row.messageId ||
          !Number.isSafeInteger(row.id) ||
          row.id <= 0 ||
          (before !== undefined && row.id >= before),
      )
    ) {
      throw new Error('消息记录的分页信息异常，请重新查询。')
    }
    return rows.length ? Math.min(...rows.map((row) => row.id)) : undefined
  }

  async function loadHistory(older = false) {
    const id = ticketId.value
    if (!id || !ready.value || (older && (historyLoading.value || !hasMore.value))) return
    const version = generation
    const request = ++historyRequest
    const knownIds = history.value.filter((message) => message.id > 0).map((message) => message.id)
    let before = older && knownIds.length ? Math.min(...knownIds) : undefined
    const latestKnown = knownIds.length ? Math.max(...knownIds) : undefined
    historyLoading.value = true
    try {
      const collected: TicketMessageVO[] = []
      let more = false
      do {
        const rows = await getTicketMessages(id, before, PAGE_SIZE)
        if (!active(id, version) || request !== historyRequest) return
        const next = validatePage(rows, id, before)
        collected.push(...rows)
        more = rows.length >= PAGE_SIZE
        if (
          older ||
          latestKnown === undefined ||
          next === undefined ||
          next <= latestKnown ||
          !more
        )
          break
        before = next
      } while (true)
      merge(collected)
      if (older || latestKnown === undefined) hasMore.value = more
      historyError.value = ''
    } catch (error) {
      if (active(id, version) && request === historyRequest)
        historyError.value = getRequestErrorMessage(error, '会话记录暂时无法同步，已保留现有内容。')
    } finally {
      if (active(id, version) && request === historyRequest) historyLoading.value = false
    }
  }

  async function synchronize() {
    if (!ticketId.value || !ready.value) return
    await Promise.all([loadDetail(), loadHistory(), replies.reconcileTicket(ticketId.value)])
  }

  const off = [
    ws.on('open', () => {
      void synchronize()
    }),
    ws.on('chat', (data) => {
      const frame = data as WsChatFrameData
      if (!frame || !ready.value || frame.ticketId !== ticketId.value) return
      if (!Number.isSafeInteger(frame.id) || !frame.id || frame.id <= 0 || !frame.sessionId) {
        void loadHistory()
        return
      }
      merge([{ ...frame, id: frame.id, sessionId: frame.sessionId, createdAtMs: frame.ts }])
    }),
    ws.on('ticket_event', (data) => {
      const frame = data as WsTicketEventFrameData
      if (frame && frame.ticketId === ticketId.value) void loadDetail()
    }),
  ]

  watch(
    [ticketId, ready],
    () => {
      if (previousId) snapshots.set(previousId, { rows: history.value, more: hasMore.value })
      if (!ready.value) snapshots.clear()
      generation += 1
      previousId = ticketId.value
      const cached = previousId ? snapshots.get(previousId) : undefined
      detail.value = null
      history.value = cached?.rows ?? []
      loading.value = false
      historyLoading.value = false
      detailError.value = ''
      historyError.value = ''
      hasMore.value = cached?.more ?? false
      void synchronize()
    },
    { immediate: true },
  )

  onScopeDispose(() => {
    disposed = true
    generation += 1
    off.forEach((unsubscribe) => unsubscribe())
  })

  const messages = computed(() => {
    const saved = ticketId.value ? replies.state(ticketId.value).saved : []
    const merged = new Map(
      [...history.value, ...saved].map((message) => [message.messageId, message]),
    )
    return [...merged.values()].sort(
      (left, right) => left.createdAtMs - right.createdAtMs || left.id - right.id,
    )
  })
  return {
    detail,
    messages,
    loading,
    historyLoading,
    detailError,
    historyError,
    hasMore,
    loadDetail,
    loadHistory,
    synchronize,
  }
}
