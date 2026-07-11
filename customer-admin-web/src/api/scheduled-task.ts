import { request } from './request'
import type { MpPageQuery, MpPageResult, ScheduledTaskRunVO, ScheduledTaskSaveRequest, ScheduledTaskVO } from '@/types/api'

const BASE_URL = '/aiconfig/scheduled-task'

export function pageScheduledTasks(query: MpPageQuery) {
  return request<MpPageResult<ScheduledTaskVO>>({ url: `${BASE_URL}/page`, method: 'get', params: query })
}

export function createScheduledTask(data: ScheduledTaskSaveRequest) {
  return request<ScheduledTaskVO>({ url: BASE_URL, method: 'post', data })
}

export function updateScheduledTask(id: number, data: ScheduledTaskSaveRequest) {
  return request<void>({ url: `${BASE_URL}/${id}`, method: 'put', data })
}

export function deleteScheduledTask(id: number) {
  return request<void>({ url: `${BASE_URL}/${id}`, method: 'delete' })
}

export function enableScheduledTask(id: number) {
  return request<void>({ url: `${BASE_URL}/${id}/enable`, method: 'put' })
}

export function disableScheduledTask(id: number) {
  return request<void>({ url: `${BASE_URL}/${id}/disable`, method: 'put' })
}

/**
 * 手动触发：同步调用智能体执行，耗时可能明显长于普通接口（分钟级）。
 * 单独放宽超时时间，避免被 axios 实例的全局 30s 超时提前掐断、被前端拦截器误判为网络异常。
 */
export function triggerScheduledTask(id: number) {
  return request<ScheduledTaskRunVO>({ url: `${BASE_URL}/${id}/trigger`, method: 'post', timeout: 180000 })
}

export function pageScheduledTaskRuns(id: number, query: MpPageQuery) {
  return request<MpPageResult<ScheduledTaskRunVO>>({ url: `${BASE_URL}/${id}/runs`, method: 'get', params: query })
}
