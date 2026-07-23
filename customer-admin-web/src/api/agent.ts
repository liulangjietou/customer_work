import { request } from './request'
import type { AgentMemoryVO, AgentSaveRequest, AgentVO, PageQuery, PageResult } from '@/types/api'

export function pageAgents(query: PageQuery) {
  return request<PageResult<AgentVO>>({ url: '/aiconfig/agent', method: 'get', params: query })
}

export function getAgent(id: number) {
  return request<AgentVO>({ url: `/aiconfig/agent/${id}`, method: 'get' })
}

export function createAgent(data: AgentSaveRequest) {
  return request<void>({ url: '/aiconfig/agent', method: 'post', data })
}

export function updateAgent(id: number, data: AgentSaveRequest) {
  return request<void>({ url: `/aiconfig/agent/${id}`, method: 'put', data })
}

export function deleteAgent(id: number) {
  return request<void>({ url: `/aiconfig/agent/${id}`, method: 'delete' })
}

export function enableAgent(id: number) {
  return request<void>({ url: `/aiconfig/agent/${id}/enable`, method: 'put' })
}

export function disableAgent(id: number) {
  return request<void>({ url: `/aiconfig/agent/${id}/disable`, method: 'put' })
}

export function getAgentMemory(id: number) {
  return request<AgentMemoryVO>({ url: `/aiconfig/agent/${id}/memory`, method: 'get' })
}

export function clearAgentMemory(id: number) {
  return request<void>({ url: `/aiconfig/agent/${id}/memory`, method: 'delete' })
}
