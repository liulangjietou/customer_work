import { request, LLM_TIMEOUT_MS } from './request'

export type AgentDraftTrialPhase = 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'UNKNOWN'
export interface TrialResource {
  id: number
  versionId: number
  name: string
  contentHash: string
}
export interface AgentDraftTrialPreview {
  draftVersion: number
  configurationFingerprint: string
  models: string[]
  skills: TrialResource[]
  knowledgeBases: TrialResource[]
  restrictions: string[]
}
export interface AgentDraftTrialSummary {
  id: string
  draftVersion: number
  inputExcerpt: string
  configurationFingerprint: string
  phase: AgentDraftTrialPhase
  errorCode: string | null
  acceptedAtMs: number
  finishedAtMs: number | null
}
export interface AgentDraftTrialReceipt {
  id: string
  draftVersion: number
  input: string
  configurationFingerprint: string
  configurationMatch: 'MATCH' | 'CHANGED' | 'UNAVAILABLE'
  phase: AgentDraftTrialPhase
  errorCode: string | null
  acceptedAtMs: number
  deadlineAtMs: number
  finishedAtMs: number | null
  result: {
    answer: string
    answerTruncated: boolean
    durationMs: number
    attemptedTools: string[]
    restrictions: string[]
  } | null
  restrictions: string[]
}

const url = (draftId: string) => `/aiconfig/agent-drafts/${encodeURIComponent(draftId)}/trials`
export function previewAgentDraftTrial(draftId: string, signal?: AbortSignal) {
  return request<AgentDraftTrialPreview>({ url: `${url(draftId)}/preview`, method: 'get', signal, suppressErrorMessage: true })
}
export function listAgentDraftTrials(draftId: string, signal?: AbortSignal) {
  return request<AgentDraftTrialSummary[]>({ url: url(draftId), method: 'get', signal, suppressErrorMessage: true })
}
export function getAgentDraftTrial(draftId: string, trialId: string, signal?: AbortSignal) {
  return request<AgentDraftTrialReceipt>({ url: `${url(draftId)}/${encodeURIComponent(trialId)}`,
    method: 'get', signal, suppressErrorMessage: true })
}
export function startAgentDraftTrial(draftId: string, trialId: string,
  data: { expectedDraftVersion: number; input: string }, signal?: AbortSignal) {
  return request<AgentDraftTrialReceipt>({ url: `${url(draftId)}/${encodeURIComponent(trialId)}`,
    method: 'put', data, signal, timeout: LLM_TIMEOUT_MS, suppressErrorMessage: true })
}
