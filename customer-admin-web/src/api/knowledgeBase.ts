import { request } from './request'
import type {
  KnowledgeBaseOption,
  KnowledgeBaseSaveRequest,
  KnowledgeBaseTestResult,
  KnowledgeBaseVO,
  PageQuery,
  PageResult,
} from '@/types/api'

/** 保存/测试连通性专用超时：后端会同步实测 RAG 服务连通性，远慢于普通 CRUD（对齐 LLM_TIMEOUT_MS 同类取舍）。 */
export const KNOWLEDGE_BASE_TIMEOUT_MS = 60000

export function pageKnowledgeBases(query: PageQuery) {
  return request<PageResult<KnowledgeBaseVO>>({ url: '/aiconfig/knowledge-base/page', method: 'get', params: query })
}

export function createKnowledgeBase(data: KnowledgeBaseSaveRequest) {
  return request<void>({ url: '/aiconfig/knowledge-base', method: 'post', data, timeout: KNOWLEDGE_BASE_TIMEOUT_MS })
}

export function updateKnowledgeBase(id: number, data: KnowledgeBaseSaveRequest) {
  return request<void>({ url: `/aiconfig/knowledge-base/${id}`, method: 'put', data, timeout: KNOWLEDGE_BASE_TIMEOUT_MS })
}

export function deleteKnowledgeBase(id: number) {
  return request<void>({ url: `/aiconfig/knowledge-base/${id}`, method: 'delete' })
}

export function testKnowledgeBaseConnectivity(id: number) {
  return request<KnowledgeBaseTestResult>({
    url: `/aiconfig/knowledge-base/${id}/test-connectivity`,
    method: 'post',
    timeout: KNOWLEDGE_BASE_TIMEOUT_MS,
  })
}

export function updateKnowledgeBaseStatus(id: number, status: number) {
  return request<void>({ url: `/aiconfig/knowledge-base/${id}/status`, method: 'put', params: { status } })
}

/** 智能体表单知识库多选下拉项：仅启用且测试通过的知识库。 */
export function fetchKnowledgeBaseOptions() {
  return request<KnowledgeBaseOption[]>({ url: '/aiconfig/knowledge-base/options', method: 'get' })
}
