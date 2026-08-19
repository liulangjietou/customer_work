import { download, request } from './request'
import type { PageQuery, PageResult, SkillSaveRequest, SkillUploadParseResult, SkillVO } from '@/types/api'

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

/**
 * 下载技能包 zip（SKILL.md + 全部附属文件，zip 内包一层以 skillCode 命名的目录）。
 *
 * 下载下来的包可以直接用「新建 Skill」的上传入口原样导回——后端导出结构与上传解析是对称的。
 * 文件名以响应头 Content-Disposition 为准，这里的兜底只在响应头缺失时生效。
 */
export function downloadSkill(id: number, skillCode: string) {
  return download({ url: `/aiconfig/skill/${id}/download`, method: 'get' }, `${skillCode}.zip`)
}

/** 解析上传的 .md/.zip 文件为技能包（SKILL.md 正文 + 附属文件）；不落库，由调用方回填表单后仍走 create/update 保存。 */
export function parseSkillUpload(file: File) {
  const formData = new FormData()
  formData.append('file', file)
  return request<SkillUploadParseResult>({ url: '/aiconfig/skill/parse-upload', method: 'post', data: formData })
}
