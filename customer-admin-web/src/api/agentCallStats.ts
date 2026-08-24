import { request } from './request'
import type {
  AgentCallSource,
  AgentCallStatsDetail,
  AgentCallReplayManifest,
  AgentReplayExecution,
  AgentCallStatsPageResult,
  AgentCallStatsQuery,
  AgentCallStatsSummary,
  AgentCallStatsTrendPoint,
  AgentCallTrendGranularity,
} from '@/types/api'

const BASE_URL = '/agent-call-stats'

/** 智能体调用耗时统计分页查询。 */
export function pageAgentCallStats(query: AgentCallStatsQuery) {
  return request<AgentCallStatsPageResult>({ url: `${BASE_URL}/page`, method: 'get', params: query })
}

/** 单条调用全量详情（含回答全文 + 分段耗时明细）。 */
export function getAgentCallStatsDetail(id: number, source: AgentCallSource) {
  return request<AgentCallStatsDetail>({ url: `${BASE_URL}/${id}`, method: 'get', params: { source } })
}

/** 获取不会执行模型/工具的只读重放清单。 */
export function getAgentCallReplayManifest(id: number, source: AgentCallSource) {
  return request<AgentCallReplayManifest>({
    url: `${BASE_URL}/${id}/replay-manifest`,
    method: 'get',
    params: { source },
  })
}

/** 默认执行零外部调用的 MOCK；DRY_RUN 由服务端隔离环境闸门决定是否放行。 */
export function executeAgentCallReplay(
  id: number,
  source: AgentCallSource,
  mode: 'MOCK' | 'DRY_RUN' = 'MOCK',
  mockAnswer?: string,
) {
  return request<AgentReplayExecution>({
    url: `${BASE_URL}/${id}/replay`,
    method: 'post',
    params: { source },
    data: { mode, mockAnswer },
  })
}

/** 汇总卡片：与分页查询共用同一套筛选参数，不含 pageNum/pageSize。 */
export function getAgentCallStatsSummary(query: AgentCallStatsQuery) {
  return request<AgentCallStatsSummary>({ url: `${BASE_URL}/summary`, method: 'get', params: query })
}

/** 趋势图：筛选参数 + 统计粒度（按天/按小时）。 */
export function getAgentCallStatsTrend(query: AgentCallStatsQuery, granularity: AgentCallTrendGranularity) {
  return request<AgentCallStatsTrendPoint[]>({
    url: `${BASE_URL}/trend`,
    method: 'get',
    params: { ...query, granularity },
  })
}

/** 删除一条调用记录。 */
export function deleteAgentCallStats(id: number, source: AgentCallSource) {
  return request<void>({ url: `${BASE_URL}/${id}`, method: 'delete', params: { source } })
}
