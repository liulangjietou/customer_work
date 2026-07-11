import { request } from './request'
import type { McpDebugCallResult, McpDebugToolVO, McpSaveRequest, McpTestResult, McpVO, PageQuery, PageResult } from '@/types/api'

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

/** 调试面板 · 连接并列出这个 MCP 提供的全部工具（含 inputSchema，用于动态渲染参数表单）。 */
export function debugMcpTools(id: number) {
  return request<McpDebugToolVO[]>({ url: `/aiconfig/mcp/${id}/debug/tools`, method: 'post' })
}

/** 调试面板 · 单次调用一个工具。 */
export function debugMcpCallTool(id: number, toolName: string, args: Record<string, unknown>) {
  return request<McpDebugCallResult>({
    url: `/aiconfig/mcp/${id}/debug/call`,
    method: 'post',
    data: { toolName, arguments: args },
  })
}
