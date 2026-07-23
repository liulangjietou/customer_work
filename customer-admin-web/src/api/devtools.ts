import { request } from './request'

/** 请求头/参数的键值对（允许同名重复，与后端 DTO 对齐） */
export interface HttpKeyValueItem {
  name: string
  value: string
}

export interface HttpSendRequest {
  method: string
  url: string
  headers: HttpKeyValueItem[]
  body?: string
}

export interface HttpSendResponse {
  statusCode: number | null
  headers: Record<string, string[]> | null
  body: string | null
  bodyBytes: number | null
  bodyTruncated: boolean
  durationMs: number
  redirectLocation: string | null
  error: string | null
}

/** 后端单次请求总时限 30s（含读取完整响应），前端在其上留出网络与序列化余量。 */
const HTTP_TOOL_TIMEOUT_MS = 45000

/** 开发者工具箱 · HTTP 请求工具：由后端代理发起真实请求（浏览器直连会被目标站 CORS 拦截）。 */
export function sendHttpRequest(data: HttpSendRequest) {
  return request<HttpSendResponse>({ url: '/devtools/http/send', method: 'post', data, timeout: HTTP_TOOL_TIMEOUT_MS })
}
