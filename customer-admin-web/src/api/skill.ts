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

/** 解析上传的 .md/.zip 文件，返回 SKILL.md 正文；不落库，由调用方回填表单后仍走 create/update 保存。 */
export function parseSkillUpload(file: File) {
  const formData = new FormData()
  formData.append('file', file)
  return request<string>({ url: '/aiconfig/skill/parse-upload', method: 'post', data: formData })
}
