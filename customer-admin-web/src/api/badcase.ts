import { request } from './request'
import type { EvalTypeCode } from './eval'

// badcase 回流 API，与 admin-server /api/badcase/** 契约对应。
// 数据与回流目标（知识库 FAQ、评测用例）都在客服端库，admin 经跨库门面直接操作。

/** 来源通道：用户主动点踩 / 系统质检不通过。信号强度不同，筛选时要区别对待。 */
export type BadcaseSourceCode = 'NEGATIVE_FEEDBACK' | 'QUALITY_FAILURE'

/** 处理状态。补知识与加评测用例不互斥，故它们记在两个 adopted* 字段上而非状态里。 */
export type BadcaseStatusCode = 'PENDING' | 'RESOLVED' | 'IGNORED'

export interface Badcase {
  id: string
  source: BadcaseSourceCode
  sessionId: string | null
  /** 被反馈的消息 ID；质检来源为空（质检针对一批回复）。 */
  messageId: string | null
  /** 用户问了什么（登记时从聊天留痕回查；未开留痕则为空）。 */
  userInput: string | null
  /** AI 答了什么（同上）。 */
  agentReply: string | null
  /** 原始信号明细：点踩存用户留言，质检存得分与扣分项。 */
  detail: string | null
  status: BadcaseStatusCode
  /** 已回流成的知识条目 ID；空表示尚未补知识。 */
  adoptedKnowledgeId: number | null
  /** 已回流成的评测用例编号；空表示尚未加评测用例。 */
  adoptedEvalCaseId: string | null
  handledBy: string | null
  handledAtMs: number
  ignoreReason: string | null
  createdAtMs: number
  pending: boolean
}

export interface BadcasePageQuery {
  status?: BadcaseStatusCode
  source?: BadcaseSourceCode
  pageNum: number
  pageSize: number
}

export interface BadcasePageResult {
  pageNum: number
  pageSize: number
  total: number
  list: Badcase[]
}

export interface AdoptKnowledgeRequest {
  title: string
  content: string
  /** 逗号分隔；决定这条知识能不能被检索到。 */
  keyword: string
}

export interface AdoptEvalCaseRequest {
  caseId: string
  evalType: EvalTypeCode
  /** INTENT 留空表示期望规则快车道不命中、应交 LLM；QUALITY 传期望回复要点。 */
  expected?: string
  category?: string
}

export function pageBadcases(params: BadcasePageQuery) {
  return request<BadcasePageResult>({ url: '/badcase/page', method: 'get', params })
}

export function getBadcase(id: string) {
  return request<Badcase>({ url: `/badcase/${id}`, method: 'get' })
}

/** 转知识库条目：补上答错的那块知识（治本）。 */
export function adoptAsKnowledge(id: string, data: AdoptKnowledgeRequest) {
  return request<Badcase>({ url: `/badcase/${id}/adopt-knowledge`, method: 'post', data })
}

/** 转评测用例：把这次翻车固化成回归防护（防复发）。与转知识库互不排斥。 */
export function adoptAsEvalCase(id: string, data: AdoptEvalCaseRequest) {
  return request<Badcase>({ url: `/badcase/${id}/adopt-eval-case`, method: 'post', data })
}

/** 忽略：噪声反馈或质检误报。 */
export function ignoreBadcase(id: string, reason?: string) {
  return request<Badcase>({ url: `/badcase/${id}/ignore`, method: 'post', params: { reason } })
}
