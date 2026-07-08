import { request } from './request'
import type { PageQuery, PageResult, SysOperationLog } from '@/types/api'

export function pageLogs(query: PageQuery) {
  return request<PageResult<SysOperationLog>>({ url: '/system/log', method: 'get', params: query })
}
