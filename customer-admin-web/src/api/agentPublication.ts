import { request } from './request'
import type { EvalGateDecisionVO, EvalVersionBindingVO } from '@/types/api'

export interface AgentPublicationCheck {
  agentId: number
  agentName: string
  runtimeRevision: number | null
  agentEnabled: boolean
  publishingEnabled: boolean
  channels: { code: string; enabled: boolean }[]
  candidateStatus: 'READY' | 'PUBLISHING_DISABLED' | 'AGENT_DISABLED' | 'CHANNEL_MISSING' | 'UNAVAILABLE'
  currentCandidate: EvalVersionBindingVO | null
  latestPublication: {
    taskId: string
    intent: string | null
    status: string | null
    revision: string | null
    candidateContentHash: string | null
    candidateVersions: EvalVersionBindingVO | null
    currentMatch: 'MATCH' | 'CHANGED' | 'NOT_PREPARED' | 'UNAVAILABLE'
    gateStatus: string | null
    gateDecision: EvalGateDecisionVO | null
    evalRunIds: string[]
    evaluatedAtMs: number | null
    overrideId: number | null
    targetInstances: string[] | null
    acknowledgements: { instanceId: string; status: string; appliedAtMs: number | null }[]
    confirmation: 'CONFIRMED' | 'PARTIAL' | 'WAITING' | 'REJECTED' | 'NO_TARGETS' | 'LEGACY_UNVERIFIED'
    updatedAtMs: number | null
  } | null
  currentRuntimeConfirmed: boolean
  checkedAtMs: number
}

/** 仅查询正式配置与发布状态；不创建评测或发布任务。 */
export function checkAgentPublication(agentId: number, signal?: AbortSignal) {
  return request<AgentPublicationCheck>({ url: `/aiconfig/agent/${agentId}/publication-check`,
    method: 'get', signal, suppressErrorMessage: true })
}
