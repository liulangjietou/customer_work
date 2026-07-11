import { request } from './request'
import type { ModelSaveRequest, ModelTestResult, ModelVO, PageQuery, PageResult } from '@/types/api'

export function pageModels(query: PageQuery) {
  return request<PageResult<ModelVO>>({ url: '/aiconfig/model', method: 'get', params: query })
}

export function getModel(id: number) {
  return request<ModelVO>({ url: `/aiconfig/model/${id}`, method: 'get' })
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
