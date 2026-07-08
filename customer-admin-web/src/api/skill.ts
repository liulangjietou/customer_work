import { request } from './request'
import type { PageQuery, PageResult, SkillSaveRequest, SkillVO } from '@/types/api'

export function pageSkills(query: PageQuery) {
  return request<PageResult<SkillVO>>({ url: '/aiconfig/skill', method: 'get', params: query })
}

export function getSkill(id: number) {
  return request<SkillVO>({ url: `/aiconfig/skill/${id}`, method: 'get' })
}

export function createSkill(data: SkillSaveRequest) {
  return request<void>({ url: '/aiconfig/skill', method: 'post', data })
}

export function updateSkill(id: number, data: SkillSaveRequest) {
  return request<void>({ url: `/aiconfig/skill/${id}`, method: 'put', data })
}

export function deleteSkill(id: number) {
  return request<void>({ url: `/aiconfig/skill/${id}`, method: 'delete' })
}
