import { request } from './request'
import type {
  PageResult,
  UserApprovalOptions,
  UserApprovalRequest,
  UserPageQuery,
  UserSaveRequest,
  UserVO,
} from '@/types/api'

export function pageUsers(query: UserPageQuery) {
  return request<PageResult<UserVO>>({ url: '/system/user', method: 'get', params: query })
}

export function getUser(id: number) {
  return request<UserVO>({ url: `/system/user/${id}`, method: 'get' })
}

/** 审核可选租户及目标租户内的启用角色。 */
export function getUserApprovalOptions(tenantId?: string) {
  return request<UserApprovalOptions>({
    url: '/system/user/approval-options',
    method: 'get',
    params: tenantId ? { tenantId } : undefined,
  })
}

export function createUser(data: UserSaveRequest) {
  return request<void>({ url: '/system/user', method: 'post', data })
}

export function updateUser(id: number, data: UserSaveRequest) {
  return request<void>({ url: `/system/user/${id}`, method: 'put', data })
}

export function deleteUser(id: number) {
  return request<void>({ url: `/system/user/${id}`, method: 'delete' })
}

/** 审核自助注册用户；批准时租户归属、角色分配与审核结论由后端在同一事务提交。 */
export function reviewUser(id: number, data: UserApprovalRequest) {
  return request<void>({ url: `/system/user/${id}/approval`, method: 'put', data })
}
