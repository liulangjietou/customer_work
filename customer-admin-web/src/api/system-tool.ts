import { request } from './request'
import type { PageQuery, PageResult, SystemToolSaveRequest, SystemToolVO } from '@/types/api'

export function fetchSystemTools(query: PageQuery) {
  return request<PageResult<SystemToolVO>>({ url: '/system-tool', method: 'get', params: query })
}

export function getSystemTool(id: number) {
  return request<SystemToolVO>({ url: `/system-tool/${id}`, method: 'get' })
}

export function updateSystemTool(id: number, data: SystemToolSaveRequest) {
  return request<void>({ url: `/system-tool/${id}`, method: 'put', data })
}
