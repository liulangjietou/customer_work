import { request } from './request'
import type {
  KnowledgeBaseOption,
  KnowledgeBaseSaveRequest,
  KnowledgeBaseTestResult,
  KnowledgeBaseVersionVO,
  KnowledgeDocumentRevisionVO,
  KnowledgeSourceSaveRequest,
  KnowledgeSourceVO,
  KnowledgeSyncRequest,
  KnowledgeSyncRunVO,
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

export function fetchKnowledgeBaseVersions(id: number) {
  return request<KnowledgeBaseVersionVO[]>({ url: `/aiconfig/knowledge-base/${id}/versions`, method: 'get' })
}

export function fetchKnowledgeSources(knowledgeBaseId: number) {
  return request<KnowledgeSourceVO[]>({
    url: `/aiconfig/knowledge-base/${knowledgeBaseId}/sources`, method: 'get',
  })
}

export function createKnowledgeSource(knowledgeBaseId: number, data: KnowledgeSourceSaveRequest) {
  return request<number>({
    url: `/aiconfig/knowledge-base/${knowledgeBaseId}/sources`, method: 'post', data,
  })
}

export function updateKnowledgeSource(knowledgeBaseId: number, sourceId: number,
                                      data: KnowledgeSourceSaveRequest) {
  return request<void>({
    url: `/aiconfig/knowledge-base/${knowledgeBaseId}/sources/${sourceId}`, method: 'put', data,
  })
}

export function deleteKnowledgeSource(knowledgeBaseId: number, sourceId: number) {
  return request<void>({
    url: `/aiconfig/knowledge-base/${knowledgeBaseId}/sources/${sourceId}`, method: 'delete',
  })
}

export function syncKnowledgeSource(knowledgeBaseId: number, sourceId: number,
                                    data: KnowledgeSyncRequest) {
  return request<KnowledgeSyncRunVO>({
    url: `/aiconfig/knowledge-base/${knowledgeBaseId}/sources/${sourceId}/sync`,
    method: 'post', data, timeout: KNOWLEDGE_BASE_TIMEOUT_MS,
  })
}

/**
 * 上传文档文件入库。
 *
 * 与 syncKnowledgeSource 是同一条 UPSERT 链路的两个入口，区别只是正文由服务端
 * 从文件里提取还是调用方直接给，因此共用同一个超时（解析 + 向量化都在这一次请求里完成）。
 */
export function uploadKnowledgeDocuments(knowledgeBaseId: number, sourceId: number, files: File[]) {
  const formData = new FormData()
  files.forEach((file) => formData.append('files', file))
  return request<KnowledgeSyncRunVO>({
    url: `/aiconfig/knowledge-base/${knowledgeBaseId}/sources/${sourceId}/documents/upload`,
    method: 'post', data: formData, timeout: KNOWLEDGE_BASE_TIMEOUT_MS,
  })
}

/** 可上传的文件扩展名，由服务端给出，避免前后端各维护一份清单。 */
export function fetchDocumentUploadOptions(knowledgeBaseId: number) {
  return request<string[]>({
    url: `/aiconfig/knowledge-base/${knowledgeBaseId}/sources/document-upload-options`,
    method: 'get',
  })
}

export function fetchKnowledgeSyncRuns(knowledgeBaseId: number, sourceId: number) {
  return request<KnowledgeSyncRunVO[]>({
    url: `/aiconfig/knowledge-base/${knowledgeBaseId}/sources/${sourceId}/sync-runs`, method: 'get',
  })
}

export function fetchKnowledgeDocumentLineage(knowledgeBaseId: number, sourceId: number,
                                              externalId: string) {
  return request<KnowledgeDocumentRevisionVO[]>({
    url: `/aiconfig/knowledge-base/${knowledgeBaseId}/sources/${sourceId}/documents/lineage`,
    method: 'get', params: { externalId },
  })
}
