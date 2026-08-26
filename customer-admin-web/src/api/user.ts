import { request } from './request'
import type { PageResult, UserApprovalRequest, UserPageQuery, UserSaveRequest, UserVO } from '@/types/api'

export function pageUsers(query: UserPageQuery) {
  return request<PageResult<UserVO>>({ url: '/system/user', method: 'get', params: query })
}

export function getUser(id: number) {
  return request<UserVO>({ url: `/system/user/${id}`, method: 'get' })
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

/** 审核自助注册用户；批准时角色分配与审核结论由后端在同一事务提交。 */
export function reviewUser(id: number, data: UserApprovalRequest) {
  return request<void>({ url: `/system/user/${id}/approval`, method: 'put', data })
}
