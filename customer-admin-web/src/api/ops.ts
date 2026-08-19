import { request } from './request'

// B6 运营闭环 API，与 admin-server /api/ops/** 契约对应。
// 五个域的数据都在客服端库，admin 经同一个跨库门面读写。

// ---------- 语义缓存 ----------

export interface SemanticCacheEntry {
  id: number
  scopeId: string
  intent: string
  question: string
  /** 列表接口不返回向量（几 KB 的浮点串，拖回来纯属浪费带宽）。 */
  questionVector: string | null
  answer: string
  /** 命中次数：这条缓存到底有没有价值，全看它。 */
  hitCount: number
  createdAtMs: number
  lastHitAtMs: number
}

/** 一个缓存分区及其条目数（分区选择器用）。 */
export interface SemanticCacheScope {
  scopeId: string
  entries: number
}

/**
 * 实际存在的缓存分区，按条目数降序。
 *
 * 分区键是用户级隔离键（两个用户问同一句话答案未必相同，按用户隔离是安全底线），
 * 运营既猜不到有哪些分区、也不知道哪个里面有东西——列出来让他选，而不是让他手填。
 */
export function listCacheScopes(limit = 100) {
  return request<SemanticCacheScope[]>({
    url: '/ops/semantic-cache/scopes',
    method: 'get',
    params: { limit },
  })
}

/** 缓存条目，按命中次数降序。 */
export function listCacheEntries(scopeId = 'default', limit = 50) {
  return request<SemanticCacheEntry[]>({
    url: '/ops/semantic-cache/list',
    method: 'get',
    params: { scopeId, limit },
  })
}

/** 定点删除单条（某条答得不对时不必清空整个分区）。 */
export function evictCacheEntry(id: number) {
  return request<boolean>({ url: `/ops/semantic-cache/${id}`, method: 'delete' })
}

/** 清空分区：知识库或提示词改过之后，旧答案不再可信。 */
export function clearCacheScope(scopeId: string) {
  return request<number>({ url: `/ops/semantic-cache/scope/${scopeId}`, method: 'delete' })
}

// ---------- 提示词版本 ----------

export interface PromptVersion {
  /** 内容指纹（SHA-256 前 16 位）：评测报告里的 promptFingerprint 就是它。 */
  fingerprint: string
  content: string
  length: number
  /** 首次观测到该版本的时间——即"这版什么时候上线的"。 */
  capturedAtMs: number
}

export function listPromptVersions(limit = 30) {
  return request<PromptVersion[]>({ url: '/ops/prompt-version/list', method: 'get', params: { limit } })
}

export function getPromptVersion(fingerprint: string) {
  return request<PromptVersion>({ url: `/ops/prompt-version/${fingerprint}`, method: 'get' })
}

// ---------- CSAT ----------

export interface CsatSummary {
  invited: number
  answered: number
  satisfied: number
  totalScore: number
  /** 满意数/回收数（行业口径，不是平均分）。 */
  csat: number
  /** 回收数/邀请数——必须和 csat 一起看。 */
  responseRate: number
  averageScore: number
}

export interface CsatSurvey {
  sessionId: string
  scopeId: string
  /** null 表示已邀请未评价。 */
  score: number | null
  comment: string | null
  invitedAtMs: number
  submittedAtMs: number
  answered: boolean
  satisfied: boolean
}

export interface CsatWindowQuery {
  scopeId?: string
  windowStartMs?: number
  windowEndMs?: number
}

export function getCsatSummary(params: CsatWindowQuery) {
  return request<CsatSummary>({ url: '/ops/csat/summary', method: 'get', params })
}

export function listCsatSurveys(params: CsatWindowQuery) {
  return request<CsatSurvey[]>({ url: '/ops/csat/list', method: 'get', params })
}

// ---------- 知识盲区 ----------

export interface KnowledgeGap {
  questionHash: string
  question: string
  scopeId: string
  /** 未命中次数：越大越该优先补。 */
  missCount: number
  firstSeenAtMs: number
  lastSeenAtMs: number
}

export interface FillKnowledgeGapRequest {
  questionHash: string
  title: string
  content: string
  keyword: string
}

export function listKnowledgeGaps(scopeId = 'default', limit = 50) {
  return request<KnowledgeGap[]>({
    url: '/ops/knowledge-gap/top',
    method: 'get',
    params: { scopeId, limit },
  })
}

/** 一键补知识，返回新建的知识条目 ID。 */
export function fillKnowledgeGap(data: FillKnowledgeGapRequest) {
  return request<number>({ url: '/ops/knowledge-gap/fill', method: 'post', data })
}

// ---------- 死信队列 ----------

export type DeadLetterStatusCode = 'PENDING' | 'SUCCEEDED' | 'ABANDONED'

export interface DeadLetter {
  id: string
  type: string
  /** 重投所需的完整载荷（JSON）。 */
  payload: string
  bizKey: string | null
  status: DeadLetterStatusCode
  attempts: number
  lastError: string | null
  nextRetryAtMs: number
  createdAtMs: number
  finishedAtMs: number
}

export function listDeadLetters(status: DeadLetterStatusCode, limit = 50) {
  return request<DeadLetter[]>({ url: '/ops/dead-letter/list', method: 'get', params: { status, limit } })
}

export function getDeadLetterStats() {
  return request<Record<DeadLetterStatusCode, number>>({ url: '/ops/dead-letter/stats', method: 'get' })
}

/** 人工重开：确认下游恢复后把已放弃的放回待重投队列（会清零重试次数）。 */
export function reopenDeadLetter(id: string) {
  return request<DeadLetter>({ url: `/ops/dead-letter/${id}/reopen`, method: 'post' })
}
