import { request } from './request'
import type {
  PageQuery,
  PageResult,
  SubjectQuotaHitRank,
  SubjectQuotaHitVO,
  SubjectQuotaLevelSaveRequest,
  SubjectQuotaLevelVO,
  SubjectQuotaUserVO,
  UserLevelSaveRequest,
} from '@/types/api'

// 租户一律取后端的当前视角，前端不传 tenantId——让参数决定读哪个租户等于把越权做成查询参数

// ---------- 等级 ----------

export function fetchSubjectQuotaLevels() {
  return request<SubjectQuotaLevelVO[]>({ url: '/subject-quota/levels', method: 'get' })
}

export function saveSubjectQuotaLevel(data: SubjectQuotaLevelSaveRequest) {
  return request<void>({ url: '/subject-quota/levels', method: 'post', data })
}

export function deleteSubjectQuotaLevel(levelCode: string) {
  return request<void>({ url: '/subject-quota/levels', method: 'delete', params: { levelCode } })
}

// ---------- 用户分档 ----------

export function pageSubjectQuotaUsers(query: PageQuery) {
  return request<PageResult<SubjectQuotaUserVO>>({
    url: '/subject-quota/users', method: 'get', params: query,
  })
}

export function assignUserLevel(data: UserLevelSaveRequest) {
  return request<void>({ url: '/subject-quota/users/level', method: 'post', data })
}

// ---------- 超限命中 ----------

export function fetchSubjectQuotaHits(hours: number, limit: number) {
  return request<SubjectQuotaHitVO[]>({
    url: '/subject-quota/hits', method: 'get', params: { hours, limit },
  })
}

export function fetchSubjectQuotaHitRank(hours: number, limit: number) {
  return request<SubjectQuotaHitRank[]>({
    url: '/subject-quota/hits/rank', method: 'get', params: { hours, limit },
  })
}
