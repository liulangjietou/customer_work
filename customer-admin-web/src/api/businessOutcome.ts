import { request } from './request'

export type AvailabilityStatus = 'COMPLETE' | 'PARTIAL' | 'UNAVAILABLE'

export interface MetricAvailability {
  status: AvailabilityStatus
  reason: string
}

export interface BusinessOutcomeDefinitions {
  observedSession: string
  successfulSession: string
  autoResolvedProxy: string
  handoffSession: string
  csat: string
  token: string
  cost: string
}

export interface BusinessOutcomeSummary {
  tenantId: string
  agentCode: string | null
  fromMs: number
  toMs: number
  generatedAtMs: number
  dataSource: string
  totalSessions: number
  successfulSessions: number
  successfulSessionRate: number
  autoResolvedProxySessions: number
  autoResolvedProxyRate: number
  handoffSessions: number
  handoffRate: number
  totalCalls: number
  totalTokens: number | null
  tokenAvailability: MetricAvailability
  csatInvitedSessions: number
  csatRespondedSessions: number
  csatResponseRate: number
  averageCsat: number | null
  csatSatisfiedRate: number
  totalCost: number | null
  costCurrency: string | null
  costPerAutoResolvedSession: number | null
  costAvailability: MetricAvailability
  costPerAutoResolvedAvailability: MetricAvailability
  definitions: BusinessOutcomeDefinitions
}

export interface BusinessOutcomeSession {
  sessionId: string
  agentCodes: string
  firstCallAtMs: number
  lastCallAtMs: number
  callCount: number
  successful: boolean
  handedOff: boolean
  autoResolvedProxy: boolean
  totalTokens: number | null
  tokenAvailability: MetricAvailability
  modelCost: number | null
  costCurrency: string | null
  costAvailability: MetricAvailability
  csatScore: number | null
}

export interface BusinessOutcomeSessionPage {
  total: number
  page: number
  size: number
  records: BusinessOutcomeSession[]
}

export interface BusinessOutcomeQuery {
  fromMs: number
  toMs: number
  agentCode?: string
}

export function getBusinessOutcomeSummary(params: BusinessOutcomeQuery) {
  return request<BusinessOutcomeSummary>({
    url: '/business-outcomes/summary',
    method: 'get',
    params,
  })
}

export function listBusinessOutcomeSessions(
  params: BusinessOutcomeQuery & { page: number; size: number },
) {
  return request<BusinessOutcomeSessionPage>({
    url: '/business-outcomes/sessions',
    method: 'get',
    params,
  })
}
