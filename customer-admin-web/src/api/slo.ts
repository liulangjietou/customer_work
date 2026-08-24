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
  lastEvaluatedAt: string | null
  lastEvaluationStatus: SloEvaluation['status'] | 'FAILED' | null
  lastEvaluationError: string | null
  updateTime: string
}

export interface SloPolicySaveRequest extends Omit<SloPolicy,
  'id' | 'lastEvaluatedAt' | 'lastEvaluationStatus' | 'lastEvaluationError' | 'updateTime'> {
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
  alertTransition: 'NONE' | 'OPENED' | 'RESOLVED'
}

export type SloAlertStatus = 'OPEN' | 'ACKED' | 'RESOLVED'

export interface SloAlert {
  id: number
  policyId: number
  policyName: string | null
  scopeType: SloScopeType | null
  scopeKey: string | null
  status: SloAlertStatus
  shortBurnRate: number
  longBurnRate: number
  firstSeenAt: string
  lastSeenAt: string
  ackBy: number | null
  ackAt: string | null
  resolvedAt: string | null
}

export interface SloAlertEvent {
  id: number
  eventType: 'OPENED' | 'ACKED' | 'RESOLVED'
  actorUserId: number | null
  shortBurnRate: number
  longBurnRate: number
  occurredAt: string
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

export function listSloAlerts(status?: SloAlertStatus) {
  return request<SloAlert[]>({ url: '/slo/alerts', method: 'get', params: { status, limit: 200 } })
}

export function acknowledgeSloAlert(id: number) {
  return request<void>({ url: `/slo/alerts/${id}/ack`, method: 'post' })
}

export function listSloAlertEvents(id: number) {
  return request<SloAlertEvent[]>({ url: `/slo/alerts/${id}/events`, method: 'get' })
}
