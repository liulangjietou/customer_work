import { request } from './request'
import type { AgentSaveRequest } from '@/types/api'

export type AgentDraftConfiguration = Omit<AgentSaveRequest, 'modelId'> & {
  modelId?: number | null
}

export interface AgentDraft {
  id: string
  agentId: number | null
  baseRevision: number | null
  title: string
  version: number
  updatedAtMs: number
  configuration: AgentDraftConfiguration | null
}

const url = '/aiconfig/agent-drafts'
export function listAgentDrafts() {
  return request<AgentDraft[]>({
    url,
    method: 'get',
    suppressErrorMessage: true,
  })
}
export function getAgentDraft(id: string) {
  return request<AgentDraft>({
    url: `${url}/${id}`,
    method: 'get',
    suppressErrorMessage: true,
  })
}
export function saveAgentDraft(
  id: string,
  data: {
    expectedVersion: number
    agentId: number | null
    baseRevision: number | null
    configuration: AgentDraftConfiguration
  },
) {
  return request<AgentDraft>({
    url: `${url}/${id}`,
    method: 'put',
    data,
    suppressErrorMessage: true,
  })
}
export function deleteAgentDraft(id: string, expectedVersion: number) {
  return request<void>({
    url: `${url}/${id}`,
    method: 'delete',
    params: { expectedVersion },
    suppressErrorMessage: true,
  })
}
