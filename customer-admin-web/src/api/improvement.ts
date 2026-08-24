import { request } from './request'
import type { EvalTypeCode } from './eval'

export type ImprovementSourceType = 'KNOWLEDGE_GAP' | 'BADCASE'
export type ImprovementCaseStatus =
  | 'OWNED'
  | 'READY_FOR_REEVALUATION'
  | 'REEVALUATING'
  | 'REEVALUATION_FAILED'
  | 'READY_TO_PUBLISH'
  | 'PUBLISHING'
  | 'PUBLISH_FAILED'
  | 'OBSERVING'
  | 'VERIFIED'
  | 'INEFFECTIVE'
  | 'INCONCLUSIVE'
  | 'CANCELLED'
export type ImprovementEffectStatus =
  | 'NOT_STARTED'
  | 'OBSERVING'
  | 'EFFECTIVE'
  | 'INEFFECTIVE'
  | 'INCONCLUSIVE'
export type ImprovementSlaStatus = 'ON_TRACK' | 'OVERDUE' | 'CLOSED'

export interface ImprovementCase {
  id: number
  sourceType: ImprovementSourceType
  sourceKey: string
  sourceSignalCount: number
  ownerId: string
  slaDueAtMs: number
  slaStatus: ImprovementSlaStatus
  overdueMs: number
  status: ImprovementCaseStatus
  agentId: number | null
  agentCode: string | null
  artifactType: string | null
  artifactVersion: string | null
  candidateVersions: Record<string, string> | null
  evalType: EvalTypeCode | null
  evalCaseId: string | null
  evalRunId: string | null
  reevaluationStatus: 'NOT_RUN' | 'RUNNING' | 'PASSED' | 'FAILED'
  reevaluationVerdict: string | null
  reevaluationError: string | null
  publishTaskId: string | null
  publishRevision: string | null
  publishStatus: string | null
  publishedAtMs: number | null
  observationStartedAtMs: number | null
  observationEndsAtMs: number | null
  minExposureCalls: number | null
  maxRecurrenceSignals: number | null
  observedCalls: number
  observedSignals: number
  effectStatus: ImprovementEffectStatus
  lastObservedAtMs: number | null
  lastError: string | null
  createdAtMs: number
  updatedAtMs: number
}

function sourcePath(sourceType: ImprovementSourceType, sourceKey: string) {
  return `/improvement-cases/source/${sourceType}/${encodeURIComponent(sourceKey)}`
}

export function getImprovementCase(sourceType: ImprovementSourceType, sourceKey: string) {
  return request<ImprovementCase | null>({ url: sourcePath(sourceType, sourceKey), method: 'get' })
}

export function triageImprovementCase(
  sourceType: ImprovementSourceType,
  sourceKey: string,
  data: { ownerId?: string; slaDueAtMs: number },
) {
  return request<ImprovementCase>({
    url: `${sourcePath(sourceType, sourceKey)}/triage`,
    method: 'post',
    data,
  })
}

export function createImprovementEvalCase(
  id: number,
  data: { caseId: string; evalType: EvalTypeCode; expected?: string; category?: string },
) {
  return request<ImprovementCase>({ url: `/improvement-cases/${id}/eval-case`, method: 'post', data })
}

export function bindImprovementArtifact(
  id: number,
  data: { agentId: number; evalType: EvalTypeCode; evalCaseId?: string },
) {
  return request<ImprovementCase>({ url: `/improvement-cases/${id}/artifact`, method: 'post', data })
}

export function reevaluateImprovementCase(id: number, remark?: string) {
  return request<ImprovementCase>({
    url: `/improvement-cases/${id}/reevaluate`,
    method: 'post',
    data: { remark },
  })
}

export function publishImprovementCase(id: number) {
  return request<ImprovementCase>({ url: `/improvement-cases/${id}/publish`, method: 'post' })
}

export function refreshImprovementCase(id: number) {
  return request<ImprovementCase>({ url: `/improvement-cases/${id}/refresh`, method: 'post' })
}
