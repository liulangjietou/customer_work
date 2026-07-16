// 与 customer-admin-server 后端 DTO 一一对应的类型定义。
// 命名/字段严格对齐后端 record/VO，避免前后端字段名漂移。

export interface Result<T> {
  code: number
  message: string
  data: T
  timestamp: number
}

export interface PageResult<T> {
  pageNum: number
  pageSize: number
  total: number
  list: T[]
}

export interface PageQuery {
  pageNum?: number
  pageSize?: number
  keyword?: string
  status?: number
  sortOrder?: 'asc' | 'desc'
}

// MyBatis-Plus IPage 原生分页格式（current/size/records，与上面 PageResult 的
// pageNum/pageSize/list 命名不同）——scheduled-task 模块的分页接口按此契约透出，不做二次包装。
export interface MpPageResult<T> {
  records: T[]
  total: number
  current: number
  size: number
}

export interface MpPageQuery {
  current?: number
  size?: number
  keyword?: string
}

// ---------- auth ----------
export interface LoginRequest {
  username: string
  password: string
  /** 记住我：勾选后登录态有效期延长（见后端 admin.remember-me-timeout-seconds），默认 7 天免登录。 */
  rememberMe?: boolean
}

// OA 域账号（LDAP/AD）单点登录请求，字段与本地登录保持一致。
export interface SsoLoginRequest {
  username: string
  password: string
  rememberMe?: boolean
}

export interface LoginResponse {
  token: string
  nickname: string
  forceChangePassword: boolean
}

export interface ChangePasswordRequest {
  oldPassword: string
  newPassword: string
}

// ---------- system.user ----------
export interface UserVO {
  id: number
  username: string
  nickname: string
  status: number
  lastLoginTime: string | null
  lastLoginIp: string | null
  createTime: string
  roleIds: number[]
  roleNames: string[]
}

export interface UserSaveRequest {
  username: string
  password?: string | null
  nickname?: string | null
  status?: number | null
  roleIds?: number[] | null
}

// ---------- system.role ----------
export interface RoleVO {
  id: number
  roleName: string
  roleCode: string
  remark: string | null
  status: number
  createTime: string
  permissionIds: number[]
}

export interface RoleSaveRequest {
  roleName: string
  roleCode: string
  remark?: string | null
  status?: number | null
  permissionIds?: number[] | null
}

// ---------- system.permission ----------
export interface PermissionVO {
  id: number
  parentId: number
  permName: string
  permCode: string
  type: number
  path: string | null
  icon: string | null
  iconType: 'library' | 'image' | null
  sort: number | null
  children: PermissionVO[]
}

// ---------- system.menu ----------
export interface MenuSaveRequest {
  parentId: number | null
  permName: string
  permCode: string
  type: number
  path?: string | null
  icon?: string | null
  iconType?: 'library' | 'image' | null
  sort?: number | null
}

export interface MenuReorderItem {
  id: number
  parentId: number
  sort: number
}

export interface MenuChangeLogVO {
  id: number
  menuId: number
  action: 'CREATE' | 'UPDATE' | 'DELETE' | 'MOVE'
  beforeSnapshot: string | null
  afterSnapshot: string | null
  operatorName: string | null
  createTime: string
}

// ---------- system.log ----------
export interface SysOperationLog {
  id: number
  userId: number | null
  username: string | null
  operation: string
  method: string | null
  target: string | null
  params: string | null
  result: number
  errorMsg: string | null
  ip: string | null
  createTime: string
}

// ---------- workspace.ai-coding-audit ----------
/** AI 编码操作审计日志（操作人、时间、变更文件、token 用量，需求 §5.2/§5.3）。 */
export interface AiCodingAuditLog {
  id: number
  userId: number | null
  username: string | null
  agentCode: string
  sessionId: string | null
  operation: 'CHAT_STREAM' | 'FILE_SAVE' | 'GIT_DIFF_SUMMARY' | 'COMMIT_MESSAGE' | 'PR_DESCRIPTION'
  changedFiles: string | null
  inputTokens: number | null
  outputTokens: number | null
  totalTokens: number | null
  durationMs: number | null
  result: number
  errorCode: string | null
  createTime: string
}

/** AI 编码审计查询条件：通用分页之上追加三个精确过滤维度。 */
export interface AiCodingAuditQuery extends PageQuery {
  agentCode?: string
  sessionId?: string
  operation?: string
}

// ---------- aiconfig.model ----------
export interface ModelVO {
  id: number
  modelName: string
  provider: string
  apiKeyMasked: string
  baseUrl: string
  model: string
  isDefault: boolean
  status: number
  testStatus: number
  testTime: string | null
  createTime: string
}

export interface ModelSaveRequest {
  modelName: string
  provider?: string | null
  apiKey?: string | null
  baseUrl: string
  model: string
  isDefault?: boolean | null
  status?: number | null
}

export interface ModelTestResult {
  testStatus: number
  testTime: string | null
  message: string | null
}

// ---------- aiconfig.mcp ----------
export interface McpVO {
  id: number
  mcpName: string
  mcpType: string
  config: string
  description: string | null
  status: number
  testStatus: number
  testTime: string | null
  createTime: string
}

export interface McpSaveRequest {
  mcpName: string
  mcpType: string
  config: string
  description?: string | null
  status?: number | null
}

export interface McpTestResult {
  testStatus: number
  testTime: string | null
  message: string | null
}

// ---------- aiconfig.mcp.debug ----------
export interface McpDebugToolVO {
  name: string
  description: string | null
  schemaType: string
  /** JSON Schema properties：每个 value 是一段 schema 片段（{type, description, enum?, default?, ...}）。 */
  properties: Record<string, Record<string, unknown>>
  required: string[]
}

/** MCP 协议 ImageContent 内容块：base64 图片数据 + mimeType，前端拼成 data URI 渲染成 <img>。 */
export interface McpDebugImageVO {
  mimeType: string
  data: string
}

export interface McpDebugCallResult {
  success: boolean
  output: string | null
  errorMessage: string | null
  images: McpDebugImageVO[]
  /** true 表示 output 疑似是二进制文件内容被 MCP 服务端误当文本读出、已损毁，原始字节不可还原。 */
  outputLooksBinary: boolean
}

// ---------- aiconfig.skill ----------
export interface SkillVO {
  id: number
  skillName: string
  skillCode: string
  content: string
  description: string | null
  status: number
  createTime: string
  /** 上传目标：local(本地Workspace) / nacos / sftp，见 SkillStorageTarget。 */
  storageTargets: string[]
}

export interface SkillSaveRequest {
  skillName: string
  skillCode: string
  content: string
  description?: string | null
  status?: number | null
  storageTargets: string[]
}

// ---------- aiconfig.system-tool ----------
export interface SystemToolVO {
  id: number
  toolCode: string
  toolName: string
  description: string | null
  /** 0禁用 / 1启用 */
  enabled: number
  remark: string | null
  createTime: string
  updateTime: string
}

export interface SystemToolSaveRequest {
  toolName: string
  description?: string | null
  enabled?: number | null
  remark?: string | null
}

// ---------- aiconfig.agent ----------
export interface AgentVO {
  id: number
  agentName: string
  agentCode: string
  modelId: number
  modelName: string | null
  /** 备用模型：主模型连通性异常时的降级候选，可空。 */
  backupModelIds: number[]
  backupModelNames: string[]
  mcpIds: number[]
  skillIds: number[]
  systemToolIds: number[]
  systemPrompt: string | null
  capabilities: string[]
  icon: string | null
  status: number
  createTime: string
  /** 子Agent协作可用的子智能体ID列表，仅当 capabilities 包含 subagent 时生效 */
  subAgentIds?: number[] | null
  /** 最大迭代次数，为空使用系统默认值 */
  maxIters?: number | null
  /** 工具调用超时时间（秒），为空使用系统默认值 */
  toolTimeoutSeconds?: number | null
  /** 工具最大尝试次数，为空使用系统默认值 */
  toolMaxAttempts?: number | null
  /** 压缩触发消息数，为空表示不压缩 */
  compressTriggerMsgs?: number | null
  /** 压缩保留消息数，为空使用系统默认值 */
  compressKeepMsgs?: number | null
}

export interface AgentSaveRequest {
  agentName: string
  agentCode: string
  modelId: number
  backupModelIds?: number[] | null
  mcpIds?: number[] | null
  skillIds?: number[] | null
  systemToolIds?: number[] | null
  systemPrompt?: string | null
  capabilities?: string[] | null
  icon?: string | null
  status?: number | null
  subAgentIds?: number[] | null
  maxIters?: number | null
  toolTimeoutSeconds?: number | null
  toolMaxAttempts?: number | null
  compressTriggerMsgs?: number | null
  compressKeepMsgs?: number | null
}

// ---------- aiconfig.scheduled-task ----------
/** 定时任务调度模式：internal=内置动态调度器，xxl-job=外部 XXL-JOB 控制台 */
export type ScheduleMode = 'internal' | 'xxl-job'

export interface ScheduledTaskVO {
  id: number
  taskCode: string
  taskName: string
  agentId: number
  /** 后端联查智能体名，智能体被删除等情况下可能为 null */
  agentName?: string | null
  prompt: string
  /** cron 表达式（Spring 6 位），为空表示不参与内置周期调度 */
  cron: string | null
  enabled: boolean
  remark: string | null
  /** 当前全局调度模式，前端据此切换提示文案 */
  scheduleMode: ScheduleMode
  createTime?: string
  updateTime?: string
}

export interface ScheduledTaskSaveRequest {
  taskCode: string
  taskName: string
  agentId: number
  prompt: string
  /** cron 表达式（Spring 6 位，可空）：internal 模式下按此周期执行 */
  cron?: string | null
  enabled?: boolean | null
  remark?: string | null
}

export interface ScheduledTaskRunVO {
  id: number
  taskId: number
  taskCode: string
  triggerType: 'XXL_JOB' | 'MANUAL' | 'INTERNAL'
  startTime: string
  endTime: string | null
  costMs: number | null
  status: 'SUCCESS' | 'FAILED'
  output: string | null
  errorMessage: string | null
}

// ---------- menu ----------
export interface MenuNode {
  id: number
  name: string
  path: string | null
  icon: string | null
  iconType: 'library' | 'image' | null
  permCode: string | null
  sort: number | null
  agentCode: string | null
  capabilities: string[] | null
  dynamic: boolean
  children: MenuNode[]
}

// ---------- workspace.vibecoding ----------
export interface WorkspaceFileNode {
  name: string
  relativePath: string
  directory: boolean
  children: WorkspaceFileNode[]
}

export interface WorkspaceFileContent {
  relativePath: string
  language: string
  content: string
  truncated: boolean
}

export interface SaveFileContentRequest {
  sessionId: string
  relativePath: string
  content: string
}

/** file_change SSE 事件的 data 载荷。 */
export interface FileChangeEvent {
  operation: 'CREATE' | 'MODIFY' | 'DELETE'
  path: string
  description: string
}

export interface GitDiffSummary {
  summary: string
  changedFiles: string[]
}

export interface CommitMessageRequest {
  sessionId: string
  style?: 'conventional' | 'simple'
}

export interface CommitMessageResponse {
  message: string
}

export interface PrDescriptionResponse {
  description: string
}

/** 会话一键回滚结果：恢复的已跟踪文件清单 + 删除的新增文件清单。 */
export interface RollbackResult {
  restoredFiles: string[]
  deletedFiles: string[]
}

/** 当前 VibeCoding 沙箱模式：local（无隔离，跑宿主机）｜docker（容器隔离）。 */
export interface SandboxModeResponse {
  mode: 'local' | 'docker'
}

/** test_report SSE 事件的 data 载荷：沙箱内编译/测试命令的结构化执行报告（P0-3）。 */
export interface TestReport {
  command: string
  exitCode: number | null
  success: boolean
  passed: number
  failed: number
  skipped: number
  durationMs: number | null
  failureDetails: string[]
  rawOutput: string | null
  /** 本轮对话内第几次编译/测试执行（1 基）。 */
  round: number
  /** 是否已达失败自动修复轮数上限（仍失败）。 */
  exhausted: boolean
}

/** AI 代码审查单条问题（P0-2）。 */
export interface ReviewIssue {
  severity: 'CRITICAL' | 'WARNING' | 'SUGGESTION'
  file: string
  line: number | null
  category: 'SECURITY' | 'PERFORMANCE' | 'READABILITY' | 'BUG' | 'STYLE'
  message: string
  suggestion: string
}

/** AI 代码审查结果（P0-2）：结构化问题清单 + 总述（解析失败降级时 issues 为空、summary 为模型原文）。 */
export interface ReviewResult {
  issues: ReviewIssue[]
  summary: string
}

// ---------- workspace.chat ----------
export interface ChatRequest {
  sessionId: string
  message: string
}

export interface ChatSessionSummary {
  sessionId: string
  preview: string
  lastMessageTime: string | null
  messageCount: number
}

export interface ChatMessageVO {
  role: 'user' | 'assistant'
  text: string
  timestamp: string
}

// ---------- workspace.project ----------
export interface ProjectVO {
  id: number
  projectName: string
  description: string | null
  sessionCount: number
  createTime: string
}

export interface ProjectSaveRequest {
  projectName: string
  description?: string | null
}

export interface ProjectSessionVO {
  agentCode: string
  agentName: string
  sessionId: string
  preview: string | null
  lastMessageTime: string | null
  messageCount: number | null
  addedTime: string
  stale: boolean
}

export interface AddSessionRequest {
  agentCode: string
  sessionId: string
}

// ---------- sql.datasource ----------
export interface SqlDatasourceVO {
  id: number
  name: string
  jdbcUrl: string
  username: string
  passwordMasked: string
  enabled: boolean
  remark: string | null
  createTime: string
  updateTime: string
}

export interface SqlDatasourceSaveRequest {
  name: string
  jdbcUrl: string
  username: string
  /** 新建必填；编辑留空表示不修改密码，与 ModelManage 的 apiKey 约定一致。 */
  password?: string | null
  enabled?: boolean | null
  remark?: string | null
}

// ---------- sql.define ----------
export interface SqlDefineVO {
  id: number
  defineKey: string
  datasourceId: number
  datasourceName: string | null
  sqlDescribe: string | null
  querySql: string
  countSql: string | null
  autoLoad: boolean
  enabled: boolean
  remark: string | null
  createTime: string
  updateTime: string
}

export interface SqlDefineSaveRequest {
  defineKey: string
  datasourceId: number
  sqlDescribe?: string | null
  querySql: string
  countSql?: string | null
  autoLoad?: boolean | null
  enabled?: boolean | null
  remark?: string | null
}

export type SqlParamType = 'STRING' | 'INTEGER' | 'DATETIME'

export interface SqlDefineParamVO {
  id: number
  defineId: number
  paramName: string
  paramDesc: string | null
  paramType: SqlParamType
  required: boolean
  defaultValue: string | null
  /** 下拉选项，后端存 JSON 字符串（如 {"1":"启用","0":"禁用"}），可空表示不是下拉参数。 */
  dropDown: string | null
  isPageNum: boolean
  isPageSize: boolean
  sort: number
}

export interface SqlDefineParamSaveRequest {
  paramName: string
  paramDesc?: string | null
  paramType: SqlParamType
  required?: boolean | null
  defaultValue?: string | null
  dropDown?: string | null
  isPageNum?: boolean | null
  isPageSize?: boolean | null
  sort?: number | null
}

export type SqlTransformType = 'DATE_FORMAT' | 'VALUE_MAP'

export interface SqlFieldTransformVO {
  id: number
  defineId: number
  fieldName: string
  transformType: SqlTransformType
  transformConfig: string
}

export interface SqlFieldTransformSaveRequest {
  fieldName: string
  transformType: SqlTransformType
  transformConfig: string
}

// ---------- sql.query（通用查询页：一个页面 + defineKey 参数驱动，服务几十个报表菜单） ----------
export interface SqlQueryMetaParam {
  paramName: string
  paramDesc: string | null
  paramType: SqlParamType
  required: boolean
  /** 已解析为实际值的字符串（如 ${now-14d} 表达式已在后端算成具体日期），前端直接预填。 */
  defaultValue: string | null
  /** 下拉选项：{ 值: 显示名 }，非下拉参数为 null。 */
  dropDown: Record<string, string> | null
  isPageNum: boolean
  isPageSize: boolean
  sort: number
}

export interface SqlQueryMetaVO {
  defineKey: string
  sqlDescribe: string | null
  autoLoad: boolean
  hasCountSql: boolean
  params: SqlQueryMetaParam[]
}

export interface SqlQueryExecuteRequest {
  defineKey: string
  params: Record<string, unknown>
}

export interface SqlQueryResultVO {
  columns: string[]
  rows: Record<string, unknown>[]
  /** -1 表示 count_sql 未配置，无总数（前端只显示上一页/下一页）。 */
  total: number
  useMillis: number
}

// ---------- channel-binding（渠道绑定）----------
// 与后端 ChannelBindingVO 字段一一对应：渠道编码 -> 智能体的绑定关系。
export interface ChannelBindingVO {
  id: number
  channelCode: string
  agentId: number
  agentName: string
  /** 0 停用 / 1 启用 */
  status: number
  createTime: string
  updateTime: string
}

export interface ChannelBindingSaveRequest {
  channelCode: string
  agentId: number
  /** 可空，新建默认由后端置为启用。 */
  status?: number
}
