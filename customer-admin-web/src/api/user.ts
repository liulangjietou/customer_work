import { request } from './request'
import type { PageQuery, PageResult, UserSaveRequest, UserVO } from '@/types/api'

export function pageUsers(query: PageQuery) {
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
