import { request } from './request'
import type { AddSessionRequest, ProjectSaveRequest, ProjectSessionVO, ProjectVO } from '@/types/api'

export function listProjects(keyword?: string) {
  return request<ProjectVO[]>({ url: '/workspace/project', method: 'get', params: { keyword } })
}

export function createProject(data: ProjectSaveRequest) {
  return request<void>({ url: '/workspace/project', method: 'post', data })
}

export function updateProject(id: number, data: ProjectSaveRequest) {
  return request<void>({ url: `/workspace/project/${id}`, method: 'put', data })
}

export function deleteProject(id: number) {
  return request<void>({ url: `/workspace/project/${id}`, method: 'delete' })
}

export function listProjectSessions(id: number) {
  return request<ProjectSessionVO[]>({ url: `/workspace/project/${id}/sessions`, method: 'get' })
}

export function addSessionToProject(id: number, data: AddSessionRequest) {
  return request<void>({ url: `/workspace/project/${id}/sessions`, method: 'post', data })
}

export function removeSessionFromProject(id: number, agentCode: string, sessionId: string) {
  return request<void>({ url: `/workspace/project/${id}/sessions/${agentCode}/${sessionId}`, method: 'delete' })
}
