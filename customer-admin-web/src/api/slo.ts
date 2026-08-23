import { request } from './request'

export type SloScopeType = 'TENANT' | 'AGENT' | 'CHANNEL'

export interface SloPolicy {
  id: number
  policyName: string
  scopeType: SloScopeType
  scopeKey: string | null
  availabilityTarget: number
  latencyTarget: number
  latencyThresholdMs: number
  shortWindowMinutes: number
  longWindowMinutes: number
  minimumSampleCount: number
  burnRateThreshold: number
  enabled: boolean
  updateTime: string
}

export interface SloPolicySaveRequest extends Omit<SloPolicy, 'id' | 'updateTime'> {
  id?: number
}

export interface SloWindowEvaluation {
  windowMinutes: number
  total: number
  good: number
  bad: number
  availabilityGood: number
  latencyGood: number
  availabilityRatio: number
  latencyRatio: number
  remainingErrorBudget: number
  burnRate: number
}

export interface SloEvaluation {
  policyId: number
  policyName: string
  scopeType: SloScopeType
  scopeKey: string | null
  evaluatedAt: string
  status: 'HEALTHY' | 'BURNING' | 'NO_DATA' | 'INSUFFICIENT_DATA'
  minimumSampleCount: number
  shortWindow: SloWindowEvaluation
  longWindow: SloWindowEvaluation
  alertCreated: boolean
}

export function listSloPolicies() {
  return request<SloPolicy[]>({ url: '/slo/policies', method: 'get' })
}

export function saveSloPolicy(data: SloPolicySaveRequest) {
  return request<number>({ url: '/slo/policies', method: 'post', data })
}

export function evaluateSloPolicy(id: number) {
  return request<SloEvaluation>({ url: `/slo/policies/${id}/evaluate`, method: 'post' })
}
