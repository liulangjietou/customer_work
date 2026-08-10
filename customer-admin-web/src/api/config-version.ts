import { request } from './request'

// 配置版本 API，与 admin-server /api/config-version/** 契约对应。
// 发布历史只增不删：回滚是"把旧内容作为新版本再发一次"，因此任何时刻都能回答线上跑的是哪一版。

export interface ConfigVersionVO {
  id: number
  configType: string
  targetCode: string
  targetId: number | null
  version: number
  /** 完整快照，仅详情接口返回（列表不带，快照可能几十 KB）。 */
  content: string | null
  contentHash: string
  publishScope: string
  grayTenants: string | null
  dataId: string
  status: string
  /** 回滚来源版本号；非回滚产生的版本为空。 */
  sourceVersion: number | null
  remark: string | null
  createTime: string
}

export interface ConfigVersionPageQuery {
  pageNum: number
  pageSize: number
  configType?: string
  targetCode?: string
}

export interface ConfigVersionPageResult {
  pageNum: number
  pageSize: number
  total: number
  list: ConfigVersionVO[]
}

export function pageVersions(params: ConfigVersionPageQuery) {
  return request<ConfigVersionPageResult>({ url: '/config-version/page', method: 'get', params })
}

export function getVersion(id: number) {
  return request<ConfigVersionVO>({ url: `/config-version/${id}`, method: 'get' })
}

export function listVersionsByTarget(configType: string, targetCode: string) {
  return request<ConfigVersionVO[]>({
    url: '/config-version/list',
    method: 'get',
    params: { configType, targetCode },
  })
}

/** 回滚到指定版本，返回新产生的版本号。 */
export function rollbackVersion(id: number, remark?: string) {
  return request<number>({ url: `/config-version/${id}/rollback`, method: 'post', params: { remark } })
}

/** 灰度发布，返回实际下发的租户数。 */
export function grayRelease(id: number, tenantCodes: string[], remark?: string) {
  return request<number>({
    url: `/config-version/${id}/gray`,
    method: 'post',
    data: { tenantCodes, remark },
  })
}
