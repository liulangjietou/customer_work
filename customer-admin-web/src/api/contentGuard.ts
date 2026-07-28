import { request } from './request'
import type {
  PageQuery,
  PageResult,
  RateLimitRuleSaveRequest,
  RateLimitRuleVO,
  SensitiveWordHitLogPageQuery,
  SensitiveWordHitLogVO,
  SensitiveWordHitStatsVO,
  SensitiveWordPageQuery,
  SensitiveWordSaveRequest,
  SensitiveWordVO,
} from '@/types/api'

// ---------- 敏感词词库 ----------

export function pageSensitiveWords(query: SensitiveWordPageQuery) {
  return request<PageResult<SensitiveWordVO>>({
    url: '/contentguard/sensitive-word/page', method: 'get', params: query,
  })
}

export function createSensitiveWord(data: SensitiveWordSaveRequest) {
  return request<void>({ url: '/contentguard/sensitive-word', method: 'post', data })
}

export function updateSensitiveWord(id: number, data: SensitiveWordSaveRequest) {
  return request<void>({ url: `/contentguard/sensitive-word/${id}`, method: 'put', data })
}

export function deleteSensitiveWord(id: number) {
  return request<void>({ url: `/contentguard/sensitive-word/${id}`, method: 'delete' })
}

export function toggleSensitiveWord(id: number, enabled: boolean) {
  return request<void>({ url: `/contentguard/sensitive-word/${id}/enabled`, method: 'put', params: { enabled } })
}

export function fetchSensitiveWordCategories() {
  return request<string[]>({ url: '/contentguard/sensitive-word/categories', method: 'get' })
}

export function fetchSensitiveWordActions() {
  return request<string[]>({ url: '/contentguard/sensitive-word/actions', method: 'get' })
}

/** 批量导入：每行 `词面,类目,动作`（类目/动作可省）。返回实际处理条数。 */
export function importSensitiveWords(lines: string[]) {
  return request<number>({ url: '/contentguard/sensitive-word/import', method: 'post', data: lines })
}

/** 导出全部词条（导入同格式）。 */
export function exportSensitiveWords() {
  return request<string[]>({ url: '/contentguard/sensitive-word/export', method: 'get' })
}

// ---------- 限流规则 ----------

export function pageRateLimitRules(query: PageQuery) {
  return request<PageResult<RateLimitRuleVO>>({
    url: '/contentguard/rate-limit-rule/page', method: 'get', params: query,
  })
}

export function createRateLimitRule(data: RateLimitRuleSaveRequest) {
  return request<void>({ url: '/contentguard/rate-limit-rule', method: 'post', data })
}

export function updateRateLimitRule(id: number, data: RateLimitRuleSaveRequest) {
  return request<void>({ url: `/contentguard/rate-limit-rule/${id}`, method: 'put', data })
}

export function deleteRateLimitRule(id: number) {
  return request<void>({ url: `/contentguard/rate-limit-rule/${id}`, method: 'delete' })
}

export function toggleRateLimitRule(id: number, enabled: boolean) {
  return request<void>({ url: `/contentguard/rate-limit-rule/${id}/enabled`, method: 'put', params: { enabled } })
}

export function fetchRateLimitDimensions() {
  return request<string[]>({ url: '/contentguard/rate-limit-rule/dimensions', method: 'get' })
}

export function fetchRateLimitAlgorithms() {
  return request<string[]>({ url: '/contentguard/rate-limit-rule/algorithms', method: 'get' })
}

// ---------- 命中看板 ----------

export function pageHitLogs(query: SensitiveWordHitLogPageQuery) {
  return request<PageResult<SensitiveWordHitLogVO>>({
    url: '/contentguard/hit-log/page', method: 'get', params: query,
  })
}

/** 看板统计：与明细列表共用同一套筛选条件，保证图表与列表始终在讲同一批数据。 */
export function fetchHitLogStats(query: SensitiveWordHitLogPageQuery) {
  return request<SensitiveWordHitStatsVO>({ url: '/contentguard/hit-log/stats', method: 'get', params: query })
}
