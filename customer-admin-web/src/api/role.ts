import { request } from './request'
import type { PageQuery, PageResult, RoleSaveRequest, RoleVO } from '@/types/api'

export function pageRoles(query: PageQuery) {
  return request<PageResult<RoleVO>>({ url: '/system/role', method: 'get', params: query })
}

export function getRole(id: number) {
  return request<RoleVO>({ url: `/system/role/${id}`, method: 'get' })
}

export function createRole(data: RoleSaveRequest) {
  return request<void>({ url: '/system/role', method: 'post', data })
}

export function updateRole(id: number, data: RoleSaveRequest) {
  return request<void>({ url: `/system/role/${id}`, method: 'put', data })
}

export function deleteRole(id: number) {
  return request<void>({ url: `/system/role/${id}`, method: 'delete' })
}
