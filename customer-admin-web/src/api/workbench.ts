import { request } from './request'
import type { PageQuery, PageResult, WorkbenchSiteSaveRequest, WorkbenchSiteVO } from '@/types/api'

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
