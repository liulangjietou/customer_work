import { LLM_TIMEOUT_MS, request } from './request'
import type { ImprovementCase } from './improvement'
import type { EvalComparison } from './eval'

// 与保存接口一致，避免草稿通过后才超出正式 FAQ 的存储容量。
export const KNOWLEDGE_CANDIDATE_LIMITS = { title: 200, content: 20000, keyword: 255 } as const

export interface KnowledgeCandidate {
  id: string
  questionHash: string
  revision: number
  status: string
  sourceReviewRevision: number
  title: string
  content: string
  keyword: string
  contentHash: string
  editedBy: number
  editedAtMs: number
}

export interface KnowledgeCandidateSaveRequest {
  questionHash: string
  expectedRevision: number
  sourceReviewRevision: number
  title: string
  content: string
  keyword: string
}

export function getKnowledgeCandidate(questionHash: string) {
  return request<KnowledgeCandidate | null>({
    url: `/ops/knowledge-gap/candidates/source/${questionHash}`, method: 'get', suppressErrorMessage: true,
  })
}

export function saveKnowledgeCandidate(id: string, data: KnowledgeCandidateSaveRequest) {
  return request<KnowledgeCandidate>({
    url: `/ops/knowledge-gap/candidates/${id}`, method: 'put', data, suppressErrorMessage: true,
  })
}

export interface KnowledgeCandidateBindingRequest {
  candidateId: string
  candidateRevision: number
  agentId: number
  modelDeploymentId: number
  judgeDeploymentId: number
  datasetReleaseId: string
  targetCaseId: string
}

export interface KnowledgeCandidateReview {
  candidateId: string
  candidateRevision: number
  sourceReviewRevision: number
  agentId: number
  agentCode: string
  modelDeploymentId: number
  modelName: string
  judgeDeploymentId: number
  judgeModelName: string
  datasetReleaseId: string
  datasetVersionId: string
  targetCaseId: string
  artifactFingerprint: string
  comparison: EvalComparison | null
  cases: { caseId: string; input: string; expected: string; baselineReply: string | null;
    candidateReply: string | null; candidateRecalled: boolean; baselineFailed: boolean; candidateFailed: boolean }[]
}

export function getKnowledgeCandidateReview(id: number) {
  return request<KnowledgeCandidateReview | null>({
    url: `/improvement-cases/${id}/knowledge-candidate`, method: 'get', suppressErrorMessage: true,
  })
}

export function bindKnowledgeCandidate(id: number, data: KnowledgeCandidateBindingRequest) {
  return request<ImprovementCase>({
    url: `/improvement-cases/${id}/knowledge-candidate`, method: 'post', data, suppressErrorMessage: true,
  })
}

export function reevaluateKnowledgeCandidate(id: number, remark?: string) {
  return request<ImprovementCase>({
    url: `/improvement-cases/${id}/knowledge-candidate/reevaluate`, method: 'post', data: { remark },
    timeout: LLM_TIMEOUT_MS, suppressErrorMessage: true,
  })
}

export interface KnowledgeCandidatePublishRequest {
  expectedArtifactFingerprint: string
  expectedEvaluationRunId: string
}

export function publishKnowledgeCandidate(id: number, data: KnowledgeCandidatePublishRequest) {
  return request<ImprovementCase>({
    url: `/improvement-cases/${id}/knowledge-candidate/publish`, method: 'post', data, suppressErrorMessage: true,
  })
}
