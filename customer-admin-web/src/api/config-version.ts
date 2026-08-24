import { request } from './request'
import type { GovernedChangeVO } from './governance'

// 配置版本 API，与 admin-server /api/config-version/** 契约对应。
// 发布历史只增不删；安全回滚只复用历史行为补丁，其余运行资产均取目标租户当前权威配置。

export interface ConfigVersionVO {
  id: number
  configType: string
  targetCode: string
  targetId: number | null
  version: number
  /** 结构化脱敏快照，仅详情接口返回（列表不带，密文/请求头/实验盐已移除）。 */
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

export interface PendingConfigPublishTask {
  taskId: string
  tenantCode: string
  targetId: number
  status: 'PENDING'
}

/** 入队成功不等于生效；实例 ACK APPLIED 后才是真实运行状态。 */
export interface ConfigPublishOperationResult {
  operationId: string
  publishIntent: 'SAFE_ROLLBACK' | 'SAFE_GRAY'
  status: 'PENDING'
  sourceConfigVersionId: number
  sourceContentHash: string
  tasks: PendingConfigPublishTask[]
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

/** 安全回滚先提交 maker-checker 请求；不同用户复核后才创建可靠发布任务。 */
export function rollbackVersion(id: number, remark?: string) {
  return request<GovernedChangeVO>({
    url: `/config-version/${id}/rollback`,
    method: 'post',
    params: { remark },
  })
}

/** 安全灰度先提交 maker-checker 请求；复核执行时才做整批预检与入队。 */
export function grayRelease(id: number, tenantCodes: string[], remark?: string) {
  return request<GovernedChangeVO>({
    url: `/config-version/${id}/gray`,
    method: 'post',
    data: { tenantCodes, remark },
  })
}
