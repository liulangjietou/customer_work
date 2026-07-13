import { request } from './request'
import type { AiCodingAuditLog, AiCodingAuditQuery, PageResult } from '@/types/api'

/** AI 编码审计日志分页查询（后端 /api/workspace/ai-coding-audit，只读）。 */
export function pageAiCodingAudit(query: AiCodingAuditQuery) {
  return request<PageResult<AiCodingAuditLog>>({ url: '/workspace/ai-coding-audit', method: 'get', params: query })
}
