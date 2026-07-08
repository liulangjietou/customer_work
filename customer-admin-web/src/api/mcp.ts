import { request } from './request'
import type { McpSaveRequest, McpTestResult, McpVO, PageQuery, PageResult } from '@/types/api'

export function pageMcps(query: PageQuery) {
  return request<PageResult<McpVO>>({ url: '/aiconfig/mcp', method: 'get', params: query })
}

export function getMcp(id: number) {
  return request<McpVO>({ url: `/aiconfig/mcp/${id}`, method: 'get' })
}

export function createMcp(data: McpSaveRequest) {
  return request<void>({ url: '/aiconfig/mcp', method: 'post', data })
}

export function updateMcp(id: number, data: McpSaveRequest) {
  return request<void>({ url: `/aiconfig/mcp/${id}`, method: 'put', data })
}

export function deleteMcp(id: number) {
  return request<void>({ url: `/aiconfig/mcp/${id}`, method: 'delete' })
}

export function testMcpConnectivity(id: number) {
  return request<McpTestResult>({ url: `/aiconfig/mcp/${id}/test-connectivity`, method: 'post' })
}
