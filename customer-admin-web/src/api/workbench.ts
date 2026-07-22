import { request } from './request'
import type {
  PageQuery,
  PageResult,
  WorkbenchSiteSaveRequest,
  WorkbenchSiteVO,
  WorkbenchTokenCreateRequest,
  WorkbenchTokenCreatedVO,
  WorkbenchTokenVO,
} from '@/types/api'

// ---------- 内网工作台 ----------
export function pageWorkbenchSites(query: PageQuery) {
  return request<PageResult<WorkbenchSiteVO>>({ url: '/workbench/site', method: 'get', params: query })
}

export function createWorkbenchSite(data: WorkbenchSiteSaveRequest) {
  return request<void>({ url: '/workbench/site', method: 'post', data })
}

export function updateWorkbenchSite(id: number, data: WorkbenchSiteSaveRequest) {
  return request<void>({ url: `/workbench/site/${id}`, method: 'put', data })
}

export function deleteWorkbenchSite(id: number) {
  return request<void>({ url: `/workbench/site/${id}`, method: 'delete' })
}

/** 读取明文密码（敏感读，后端已审计），供"复制密码"用。 */
export function getWorkbenchSiteSecret(id: number) {
  return request<string>({ url: `/workbench/site/${id}/secret`, method: 'get' })
}

// ---------- 个人访问令牌（供 ScriptCat 脚本鉴权）----------
export function listWorkbenchTokens() {
  return request<WorkbenchTokenVO[]>({ url: '/workbench/token', method: 'get' })
}

export function createWorkbenchToken(data: WorkbenchTokenCreateRequest) {
  return request<WorkbenchTokenCreatedVO>({ url: '/workbench/token', method: 'post', data })
}

export function revokeWorkbenchToken(id: number) {
  return request<void>({ url: `/workbench/token/${id}`, method: 'delete' })
}

/** 生成内嵌一次性令牌的 ScriptCat 通用登录脚本，返回脚本全文。 */
export function generateWorkbenchScript(data: WorkbenchTokenCreateRequest) {
  return request<string>({ url: '/workbench/script/generate', method: 'post', data })
}
