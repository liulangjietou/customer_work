import { request } from './request'

// 租户管理 API，与 admin-server /api/tenant/** 契约对应。
// 除 current-view 外全部要求控制面角色与对应权限点，后端会二次校验调用者身份。

export interface TenantVO {
  id: number
  tenantCode: string
  tenantName: string
  status: string
  contactName: string | null
  contactPhone: string | null
  contactEmail: string | null
  remark: string | null
  expireTime: string | null
  createTime: string | null
  /** 保留租户 default 不允许改编码、冻结、退租或删除。 */
  reserved: boolean
}

export interface TenantSaveRequest {
  id?: number
  tenantCode: string
  tenantName: string
  contactName?: string | null
  contactPhone?: string | null
  contactEmail?: string | null
  remark?: string | null
  expireTime?: string | null
}

export interface TenantPageQuery {
  pageNum: number
  pageSize: number
  keyword?: string
  tenantStatus?: string
}

export interface TenantPageResult {
  pageNum: number
  pageSize: number
  total: number
  list: TenantVO[]
}

/** 当前登录用户的租户视角，前端据此决定是否渲染租户切换器。 */
export interface TenantViewVO {
  userTenantId: string | null
  effectiveTenantId: string | null
  crossTenantAuthority: boolean
}

export function pageTenants(params: TenantPageQuery) {
  return request<TenantPageResult>({ url: '/tenant/page', method: 'get', params })
}

export function getTenant(id: number) {
  return request<TenantVO>({ url: `/tenant/${id}`, method: 'get' })
}

/** 可切换的租户下拉（仅 ACTIVE）。 */
export function listTenantOptions() {
  return request<TenantVO[]>({ url: '/tenant/options', method: 'get' })
}

export function createTenant(data: TenantSaveRequest) {
  return request<number>({ url: '/tenant', method: 'post', data })
}

export function updateTenant(data: TenantSaveRequest) {
  return request<void>({ url: '/tenant', method: 'put', data })
}

/** 冻结 / 恢复 / 退租：只改状态不动数据。 */
export function changeTenantStatus(id: number, status: string) {
  return request<void>({ url: `/tenant/${id}/status`, method: 'put', params: { status } })
}

export function deleteTenant(id: number) {
  return request<void>({ url: `/tenant/${id}`, method: 'delete' })
}

export function fetchCurrentView() {
  return request<TenantViewVO>({ url: '/tenant/current-view', method: 'get' })
}

/** 控制面用户切换目标租户视角；不传 tenantCode 回到自身租户视角。 */
export function switchTenantView(tenantCode?: string) {
  return request<void>({ url: '/tenant/switch-view', method: 'put', params: { tenantCode } })
}
