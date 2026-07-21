import { LLM_TIMEOUT_MS, request } from './request'
import type { KnowledgeAskResponse, KnowledgeIndex, KnowledgeSearchHit } from '@/types/api'

/** 代码知识库索引列表（P3-2）。 */
export function listKnowledgeIndexes() {
  return request<KnowledgeIndex[]>({ url: '/knowledge/index/list', method: 'get' })
}

/** 构建代码知识库索引（异步），返回新建索引 id。 */
export function buildKnowledgeIndex(data: { indexName: string; sourcePath: string }) {
  return request<number>({ url: '/knowledge/index/build', method: 'post', data })
}

/** 查询单个索引（进度轮询用）。 */
export function getKnowledgeIndex(id: number) {
  return request<KnowledgeIndex>({ url: `/knowledge/index/${id}`, method: 'get' })
}

/** 删除代码知识库索引。 */
export function deleteKnowledgeIndex(id: number) {
  return request<void>({ url: `/knowledge/index/${id}`, method: 'delete' })
}

/** 语义检索代码切片。 */
export function searchKnowledge(indexId: number, query: string, topK?: number) {
  return request<KnowledgeSearchHit[]>({
    url: '/knowledge/search',
    method: 'get',
    params: { indexId, query, topK },
  })
}

/** 基于代码知识库的问答（RAG）。 */
export function askKnowledge(data: { indexId: number; question: string; topK?: number }) {
  return request<KnowledgeAskResponse>({ url: '/knowledge/ask', method: 'post', data, timeout: LLM_TIMEOUT_MS })
}
