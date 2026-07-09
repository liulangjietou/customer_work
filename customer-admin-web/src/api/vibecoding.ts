import { request } from './request'
import { streamSse, type SseHandlers } from '@/utils/sse'
import type { ChatRequest, SaveFileContentRequest, WorkspaceFileContent, WorkspaceFileNode } from '@/types/api'

export function streamVibeCoding(agentCode: string, req: ChatRequest, handlers: SseHandlers) {
  return streamSse(`/workspace/${agentCode}/vibecoding/stream`, req, handlers)
}

export function listVibeCodingArtifacts(agentCode: string, sessionId: string) {
  return request<string[]>({
    url: `/workspace/${agentCode}/vibecoding/artifacts`,
    method: 'get',
    params: { sessionId },
  })
}

/** 列出会话 workspace 目录树（sessions/{sessionId}/ 下的所有文件和目录）。 */
export function listWorkspaceFiles(agentCode: string, sessionId: string) {
  return request<WorkspaceFileNode[]>({
    url: `/workspace/${agentCode}/vibecoding/files`,
    method: 'get',
    params: { sessionId },
  })
}

/**
 * 保存文件内容（存在则覆盖，不存在则创建）。
 */
export function saveWorkspaceFileContent(agentCode: string, req: SaveFileContentRequest) {
  return request<void>({
    url: `/workspace/${agentCode}/vibecoding/file-content`,
    method: 'put',
    data: req,
  })
}

/**
 * 读取指定文件内容。
 * @param path 相对于会话 workspace 的文件路径（如 src/main/java/Foo.java）
 */
export function readWorkspaceFileContent(agentCode: string, sessionId: string, path: string) {
  return request<WorkspaceFileContent>({
    url: `/workspace/${agentCode}/vibecoding/file-content`,
    method: 'get',
    params: { sessionId, path },
  })
}
