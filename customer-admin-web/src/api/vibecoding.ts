import { LLM_TIMEOUT_MS, request } from './request'
import { streamSse, type SseHandlers } from '@/utils/sse'
import type {
  ChatRequest,
  CommitMessageRequest,
  CommitMessageResponse,
  GitDiffSummary,
  PlanConfirmRequest,
  PrDescriptionResponse,
  ReviewTaskVO,
  RollbackResult,
  SandboxModeResponse,
  SaveFileContentRequest,
  WorkspaceFileContent,
  WorkspaceFileNode,
} from '@/types/api'

export function streamVibeCoding(agentCode: string, req: ChatRequest, handlers: SseHandlers) {
  // 显式列出请求体字段：collaboration 为协作模式开关（P3-1），透传给后端多角色流水线。
  const body = {
    sessionId: req.sessionId,
    message: req.message,
    collaboration: req.collaboration ?? false,
  }
  return streamSse(`/workspace/${agentCode}/vibecoding/stream`, body, handlers)
}

/** 安全中断该会话正在执行的流式对话（协作式中断，不保证立即生效）。 */
export function interruptVibeCoding(agentCode: string, sessionId: string) {
  return request<boolean>({
    url: `/workspace/${agentCode}/vibecoding/sessions/${sessionId}/interrupt`,
    method: 'post',
  })
}

/** 当前 VibeCoding 沙箱模式（local/docker），全局配置，不随会话变化。 */
export function getSandboxMode(agentCode: string) {
  return request<SandboxModeResponse>({
    url: `/workspace/${agentCode}/vibecoding/sandbox-mode`,
    method: 'get',
  })
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

/** Git 助手 · diff 摘要：变更文件清单 + AI 生成的 1~3 句话摘要。 */
export function getGitDiffSummary(agentCode: string, sessionId: string) {
  return request<GitDiffSummary>({
    url: `/workspace/${agentCode}/vibecoding/git-diff`,
    method: 'get',
    params: { sessionId },
    timeout: LLM_TIMEOUT_MS,
  })
}

/** Git 助手 · 生成 commit message（style 默认 conventional）。 */
export function generateCommitMessage(agentCode: string, req: CommitMessageRequest) {
  return request<CommitMessageResponse>({
    url: `/workspace/${agentCode}/vibecoding/commit-message`,
    method: 'post',
    data: req,
    timeout: LLM_TIMEOUT_MS,
  })
}

/** Git 助手 · 生成 PR description（Markdown）。 */
export function generatePrDescription(agentCode: string, sessionId: string) {
  return request<PrDescriptionResponse>({
    url: `/workspace/${agentCode}/vibecoding/pr-description`,
    method: 'post',
    data: { sessionId },
    timeout: LLM_TIMEOUT_MS,
  })
}

/** 会话一键回滚：撤销本次会话对 workspace 的全部文件改动，恢复到对话前的 baseline 状态（破坏性操作）。 */
export function rollbackVibeCoding(agentCode: string, sessionId: string) {
  return request<RollbackResult>({
    url: `/workspace/${agentCode}/vibecoding/rollback`,
    method: 'post',
    data: { sessionId },
  })
}

/**
 * AI 代码审查（异步化）：提交本轮 diff 审查任务，立即返回 taskId，不再同步等待模型产出结果。
 * 提交本身是快操作（只是入队），不需要 LLM_TIMEOUT_MS 长超时，用默认 30s 即可；
 * 真正耗时的模型调用在后端异步执行，前端通过 getReviewTask 轮询或站内信通知拿到终态。
 */
export function reviewVibeCoding(agentCode: string, sessionId: string) {
  return request<number>({
    url: `/workspace/${agentCode}/vibecoding/review`,
    method: 'post',
    data: { sessionId },
  })
}

/** 查询 AI 代码审查任务状态/结果（轮询接口，默认超时即可）。 */
export function getReviewTask(agentCode: string, taskId: number) {
  return request<ReviewTaskVO>({
    url: `/workspace/${agentCode}/vibecoding/review-task/${taskId}`,
    method: 'get',
  })
}

/** Plan Mode 计划确认/拒绝（P1-1 HITL）：对 plan 事件里的高风险操作放行或取消。 */
export function confirmVibeCodingPlan(agentCode: string, req: PlanConfirmRequest) {
  return request<void>({
    url: `/workspace/${agentCode}/vibecoding/plan/confirm`,
    method: 'post',
    data: req,
  })
}
