import { request } from './request'
import type { AgentTaskPageQuery, AgentTaskVO, MpPageResult } from '@/types/api'

const BASE_URL = '/aiconfig/agent-task'

/** 分页查询后台任务；列表里的 result 是截断预览，全文要走详情。 */
export function pageAgentTasks(query: AgentTaskPageQuery) {
  return request<MpPageResult<AgentTaskVO>>({ url: `${BASE_URL}/page`, method: 'get', params: query })
}

/** 任务详情：result / errorMessage 取全文。 */
export function getAgentTask(taskId: string) {
  return request<AgentTaskVO>({ url: `${BASE_URL}/${taskId}`, method: 'get' })
}

/** 状态字典：由后端枚举透出，避免前后端各维护一份状态字符串。 */
export function listAgentTaskStatuses() {
  return request<string[]>({ url: `${BASE_URL}/statuses`, method: 'get' })
}

/** 取消任务：幂等，已终态的任务也会正常返回（刷新后可看到实际状态）。 */
export function cancelAgentTask(taskId: string) {
  return request<void>({ url: `${BASE_URL}/${taskId}/cancel`, method: 'post' })
}
