import { request } from './request'
import type {
  ModelAssetOption,
  ModelCertification,
  ModelCertificationRequest,
  ModelCredentialRotationRequest,
  ModelHealthEvent,
  ModelHealthOverrideRequest,
  ModelHealthSnapshot,
  ModelImpact,
  ModelRouteDryRunRequest,
  ModelRouteDryRunResult,
  ModelRoutePolicy,
  ModelRoutePolicyCreateRequest,
  ModelRouteValidation,
  ModelRouteVersion,
  ModelRouteVersionCreateRequest,
  ModelSaveRequest,
  ModelTestResult,
  ModelVO,
  PageQuery,
  PageResult,
  SecretMetadataVO,
} from '@/types/api'

export function pageModels(query: PageQuery) {
  return request<PageResult<ModelVO>>({ url: '/aiconfig/model', method: 'get', params: query })
}

export function getModel(id: number) {
  return request<ModelVO>({ url: `/aiconfig/model/${id}`, method: 'get' })
}

export function listModelAssetOptions() {
  return request<ModelAssetOption[]>({ url: '/aiconfig/model/asset-options', method: 'get' })
}

export function createModel(data: ModelSaveRequest) {
  return request<void>({ url: '/aiconfig/model', method: 'post', data })
}

export function updateModel(id: number, data: ModelSaveRequest) {
  return request<void>({ url: `/aiconfig/model/${id}`, method: 'put', data })
}

export function deleteModel(id: number) {
  return request<void>({ url: `/aiconfig/model/${id}`, method: 'delete' })
}

export function testModelConnectivity(id: number) {
  return request<ModelTestResult>({ url: `/aiconfig/model/${id}/test-connectivity`, method: 'post' })
}

export function runModelHealthCheck(id: number) {
  return request<ModelTestResult>({ url: `/aiconfig/model/${id}/health-checks`, method: 'post' })
}

export function getModelHealth(id: number) {
  return request<ModelHealthSnapshot>({ url: `/aiconfig/model/${id}/health`, method: 'get' })
}

export function listModelHealthEvents(id: number, limit = 50) {
  return request<ModelHealthEvent[]>({
    url: `/aiconfig/model/${id}/health-events`,
    method: 'get',
    params: { limit },
  })
}

export function updateModelHealthOverride(id: number, data: ModelHealthOverrideRequest) {
  return request<ModelHealthSnapshot>({
    url: `/aiconfig/model/${id}/health-override`,
    method: 'put',
    data,
  })
}

export function getModelImpact(id: number, action: 'DELETE' | 'DISABLE' | 'ROTATE' = 'DELETE') {
  return request<ModelImpact>({
    url: `/aiconfig/model/${id}/impact`,
    method: 'get',
    params: { action },
  })
}

export function rotateModelCredential(id: number, data: ModelCredentialRotationRequest) {
  return request<SecretMetadataVO>({
    url: `/aiconfig/model/${id}/credential`,
    method: 'put',
    data,
  })
}

export function getModelCertification(id: number) {
  return request<ModelCertification>({ url: `/aiconfig/model/${id}/certification`, method: 'get' })
}

export function listModelCertificationRuns(id: number) {
  return request<ModelCertification[]>({
    url: `/aiconfig/model/${id}/certification-runs`,
    method: 'get',
  })
}

export function certifyModel(id: number, data: ModelCertificationRequest) {
  return request<ModelCertification>({
    url: `/aiconfig/model/${id}/certifications`,
    method: 'post',
    data,
  })
}

export function listModelRoutePolicies() {
  return request<ModelRoutePolicy[]>({ url: '/aiconfig/model-routing-policies', method: 'get' })
}

export function getModelRoutePolicy(id: number) {
  return request<ModelRoutePolicy>({ url: `/aiconfig/model-routing-policies/${id}`, method: 'get' })
}

export function listModelRouteVersions(id: number) {
  return request<ModelRouteVersion[]>({
    url: `/aiconfig/model-routing-policies/${id}/versions`,
    method: 'get',
  })
}

export function createModelRoutePolicy(data: ModelRoutePolicyCreateRequest) {
  return request<ModelRoutePolicy>({ url: '/aiconfig/model-routing-policies', method: 'post', data })
}

export function validateModelRouteVersion(id: number, data: ModelRouteVersionCreateRequest) {
  return request<ModelRouteValidation>({
    url: `/aiconfig/model-routing-policies/${id}/versions/validate`,
    method: 'post',
    data,
  })
}

export function createModelRouteVersion(id: number, data: ModelRouteVersionCreateRequest) {
  return request<ModelRouteVersion>({
    url: `/aiconfig/model-routing-policies/${id}/versions`,
    method: 'post',
    data,
  })
}

export function activateModelRouteVersion(policyId: number, versionId: number) {
  return request<ModelRoutePolicy>({
    url: `/aiconfig/model-routing-policies/${policyId}/versions/${versionId}/activate`,
    method: 'put',
  })
}

export function dryRunModelRoutePolicy(id: number, data: ModelRouteDryRunRequest) {
  return request<ModelRouteDryRunResult>({
    url: `/aiconfig/model-routing-policies/${id}/dry-run`,
    method: 'post',
    data,
  })
}
