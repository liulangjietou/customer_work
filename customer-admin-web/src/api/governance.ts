import { request } from './request'

export type GovernedChangeStatus =
  | 'PENDING'
  | 'EXECUTING'
  | 'EXECUTED'
  | 'REJECTED'
  | 'FAILED'
  | 'EXPIRED'

export interface GovernedChangeVO {
  id: string
  changeType: 'CONFIG_ROLLBACK' | 'CONFIG_GRAY_RELEASE'
  targetKey: string
  payloadHash: string
  makerId: number
  makerName: string | null
  checkerId: number | null
  checkerName: string | null
  status: GovernedChangeStatus
  decisionReason: string | null
  resultJson: string | null
  failureCode: string | null
  expiresAt: string
  decidedAt: string | null
  executedAt: string | null
  createTime: string
  updateTime: string
}

export interface GovernanceAuditEventVO {
  sequenceNo: number
  eventType: string
  actorId: number | null
  actorName: string | null
  detail: string | null
  previousHash: string
  eventHash: string
  retentionUntil: string
  createTime: string
}

export function listGovernedChanges(status?: GovernedChangeStatus) {
  return request<GovernedChangeVO[]>({ url: '/governance/changes', method: 'get', params: { status } })
}

export function approveGovernedChange(id: string, reason: string) {
  return request<GovernedChangeVO>({
    url: `/governance/changes/${id}/approve`, method: 'post', data: { reason },
  })
}

export function rejectGovernedChange(id: string, reason: string) {
  return request<GovernedChangeVO>({
    url: `/governance/changes/${id}/reject`, method: 'post', data: { reason },
  })
}

export function listGovernanceAudit(id: string) {
  return request<GovernanceAuditEventVO[]>({
    url: `/governance/changes/${id}/audit`, method: 'get',
  })
}
