import { request } from './request'

export type ModelExperimentStatus = 'DRAFT' | 'RUNNING' | 'STOPPED' | 'COMPLETED'
export type ModelExperimentEffectiveState =
  | 'INACTIVE'
  | 'ACTIVATING'
  | 'ACTIVE'
  | 'ACTIVATION_FAILED'
  | 'DEACTIVATING'
  | 'DEACTIVATION_FAILED'
export type ModelExperimentMetricsAvailability = 'READY' | 'AWAITING_RUNTIME'

export interface ModelExperimentCreateRequest {
  experimentName: string
  agentId: number
  controlDeploymentId: number
  treatmentDeploymentId: number
  treatmentBps: number
  minSample: number
  maxErrorRate: number
  maxP95LatencyMs: number
  expiresAt: string
  datasetReleaseId: string
}

export interface ModelExperiment {
  id: number
  experimentCode: string
  experimentName: string
  agentId: number
  controlDeploymentId: number
  controlModelRef: string
  controlEndpointRevision: number
  treatmentDeploymentId: number
  treatmentModelRef: string
  treatmentEndpointRevision: number
  datasetReleaseId: string
  datasetVersionName: string
  datasetSnapshotVersionId: string
  datasetContentHash: string
  judgeDeploymentId: number
  judgeModelRef: string
  judgeEndpointRevision: number
  offlineEvalStatus: 'NOT_STARTED' | 'RUNNING' | 'PASSED' | 'FAILED'
  offlineEvalStartedAt: string | null
  offlineEvalCompletedAt: string | null
  offlineEvalError: string | null
  revision: number
  treatmentBps: number
  status: ModelExperimentStatus
  effectiveState: ModelExperimentEffectiveState
  activationTaskId: string | null
  activationTaskStatus: string | null
  activationTaskGateStatus: string | null
  deactivationTaskId: string | null
  deactivationTaskStatus: string | null
  deactivationTaskGateStatus: string | null
  effectiveTaskId: string | null
  effectiveTaskStatus: string | null
  effectiveTaskGateStatus: string | null
  effectiveTaskLastError: string | null
  minSample: number
  maxErrorRate: number
  maxP95LatencyMs: number
  expiresAt: string
  startedAt: string | null
  stoppedAt: string | null
  completedAt: string | null
  stopReason: string | null
  createBy: number | null
  createTime: string
}

export interface ModelExperimentEvent {
  id: number
  eventType: 'START' | 'STOP' | 'AUTO_STOP' | 'EXPIRED'
  fromStatus: ModelExperimentStatus
  toStatus: ModelExperimentStatus
  reason: string | null
  actorId: number | null
  occurredAt: string
}

export interface ModelExperimentArmMetrics {
  samples: number | null
  errorRate: number | null
  p95LatencyMs: number | null
}

export interface ModelExperimentMetrics {
  experimentId: number
  availability: ModelExperimentMetricsAvailability
  message: string
  samples: number | null
  errorRate: number | null
  p95LatencyMs: number | null
  control: ModelExperimentArmMetrics | null
  treatment: ModelExperimentArmMetrics | null
  evaluatedAt: string | null
}

export interface ModelExperimentArmEvaluation {
  id: number
  arm: 'CONTROL' | 'TREATMENT'
  attemptNo: number
  deploymentId: number
  endpointRevision: number
  datasetReleaseId: string
  datasetSnapshotVersionId: string
  datasetContentHash: string
  judgeDeploymentId: number
  judgeEndpointRevision: number
  rubricVersion: string
  status: 'RUNNING' | 'PASSED' | 'FAILED' | 'ERROR'
  total: number | null
  judged: number | null
  passed: number | null
  avgScore: number | null
  passRate: number | null
  failedCaseIds: string[]
  errorCaseIds: string[]
  errorMessage: string | null
  startedAt: string
  completedAt: string | null
}

export function listModelExperiments(params?: { agentId?: number; status?: ModelExperimentStatus }) {
  return request<ModelExperiment[]>({ url: '/aiconfig/model-experiments', method: 'get', params })
}

export function createModelExperiment(data: ModelExperimentCreateRequest) {
  return request<ModelExperiment>({ url: '/aiconfig/model-experiments', method: 'post', data })
}

export function startModelExperiment(id: number) {
  return request<ModelExperiment>({ url: `/aiconfig/model-experiments/${id}/start`, method: 'post' })
}

export function stopModelExperiment(id: number, reason: string) {
  return request<ModelExperiment>({
    url: `/aiconfig/model-experiments/${id}/stop`,
    method: 'post',
    data: { reason },
  })
}

export function listModelExperimentEvents(id: number) {
  return request<ModelExperimentEvent[]>({
    url: `/aiconfig/model-experiments/${id}/events`,
    method: 'get',
  })
}

export function getModelExperimentMetrics(id: number) {
  return request<ModelExperimentMetrics>({
    url: `/aiconfig/model-experiments/${id}/metrics`,
    method: 'get',
  })
}

export function listModelExperimentArmEvaluations(id: number) {
  return request<ModelExperimentArmEvaluation[]>({
    url: `/aiconfig/model-experiments/${id}/arm-evaluations`,
    method: 'get',
  })
}
