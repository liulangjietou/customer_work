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
/** 角色数据范围：ALL 当前租户视角内全部 / TENANT 本租户全部 / SELF 仅本人创建。 */
export type DataScope = 'ALL' | 'TENANT' | 'SELF'

export interface RoleVO {
  id: number
  roleName: string
  roleCode: string
  remark: string | null
  status: number
  dataScope: DataScope
  /** 是否为控制面角色；只读字段，不能由角色编辑接口修改。 */
  controlPlane: boolean
  createTime: string
  permissionIds: number[]
}

export interface RoleSaveRequest {
  roleName: string
  roleCode: string
  remark?: string | null
  status?: number | null
  dataScope?: DataScope | null
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
  /** 命中缓存的输入 token（inputTokens 的子集，不计入 totalTokens）。 */
  cachedTokens: number | null
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

// ---------- system.agent-call-stats ----------
// 契约由后端并行开发分支约定死（/api/agent-call-stats/*），字段命名与下方一一对应，不自行发明。

/** 调用来源：ADMIN=后台工作台（客服坐席/内部使用），APP=客服端 H5（面向终端用户）。 */
export type AgentCallSource = 'ADMIN' | 'APP'
/** 会话类型：CHAT=普通对话，VIBE_CODING=编码协作。APP 来源恒为 CHAT。 */
export type AgentCallSessionType = 'CHAT' | 'VIBE_CODING'
/** 单次调用内的分段耗时类型：大模型/工具/MCP/技能。 */
export type AgentCallSegmentKind = 'MODEL' | 'TOOL' | 'MCP' | 'SKILL'
/** 趋势图统计粒度。 */
export type AgentCallTrendGranularity = 'day' | 'hour'

/** 智能体调用耗时统计查询条件：分页/汇总/趋势三个接口共用同一套筛选参数。 */
export interface AgentCallStatsQuery extends PageQuery {
  source?: AgentCallSource
  username?: string
  agentCode?: string
  sessionType?: AgentCallSessionType
  requestId?: string
  sessionId?: string
  /** 格式 yyyy-MM-dd HH:mm:ss，与后端 SQL 通用日期参数约定一致（见 SqlQueryView 同类用法）。 */
  startTime?: string
  endTime?: string
}

/** 分页明细行（不含 answer 全文与分段明细，详情走单条接口）。 */
export interface AgentCallStatsRow {
  id: number
  requestId: string
  /** 注意：后端此字段为字符串（admin 链路实际取 agentCode 作状态隔离键），非数字用户 id。 */
  userId: string | null
  username: string | null
  agentCode: string
  agentName: string | null
  sessionId: string
  sessionType: AgentCallSessionType
  question: string
  answerPreview: string
  startTime: string
  endTime: string
  durationMs: number
  modelMs: number
  toolMs: number
  mcpMs: number
  skillMs: number
  /** 请求级 token 消耗（缺失为 null）。 */
  inputTokens: number | null
  outputTokens: number | null
  totalTokens: number | null
  /** 命中缓存的输入 token：是 inputTokens 的**子集**，不是额外量，也不计入 totalTokens。 */
  cachedTokens: number | null
  /** 模型自报耗时（毫秒）；与 modelMs 之差即网络/排队开销。 */
  modelReportedMs: number | null
}

// 契约固定 { total, rows }，与通用 PageResult 的 pageNum/pageSize/list 命名不同（同 MpPageResult
// 的先例：不同后端模块按各自契约透出，不强行套通用形状）。
export interface AgentCallStatsPageResult {
  total: number
  rows: AgentCallStatsRow[]
}

/** 单次调用内的一段耗时（一次模型调用/一次工具调用/一次 MCP 调用/一次技能调用）。 */
export interface AgentCallStatsSegment {
  seq: number
  kind: AgentCallSegmentKind
  name: string
  startTime: string
  durationMs: number
  /** token 消耗（仅 MODEL 段有值，缺失为 null）。 */
  inputTokens: number | null
  outputTokens: number | null
  /** 命中缓存的输入 token（inputTokens 的子集，仅 MODEL 段）。 */
  cachedTokens: number | null
  /** 模型自报耗时（毫秒，仅 MODEL 段）。 */
  modelReportedMs: number | null
  success: boolean
  errorMsg: string | null
}

/** 单条调用详情：分页行字段 + 回答全文 + 分段耗时明细。 */
export interface AgentCallStatsDetail extends AgentCallStatsRow {
  answer: string
  segments: AgentCallStatsSegment[]
}

/** 汇总卡片：总调用数/耗时均值峰值/各分段耗时均值。 */
export interface AgentCallStatsSummary {
  totalCalls: number
  avgDurationMs: number
  maxDurationMs: number
  avgModelMs: number
  avgToolMs: number
  avgMcpMs: number
  avgSkillMs: number
  /** 总 token 消耗 / 平均每次 token 消耗。 */
  totalTokens: number
  avgTotalTokens: number
  /** 总输入 token（缓存命中率的分母）。 */
  inputTokens: number
  /** 总命中缓存的输入 token（inputTokens 的子集，各家通常按 1/10 计价）。 */
  cachedTokens: number
  /** 缓存命中率 = cachedTokens / inputTokens，后端算好下发，前端不重复推导口径。 */
  cacheHitRate: number
}

/** 趋势图单个时间桶。 */
export interface AgentCallStatsTrendPoint {
  bucket: string
  count: number
  avgDurationMs: number
  avgModelMs: number
  avgToolMs: number
  avgMcpMs: number
  avgSkillMs: number
  /** 该桶内 token 消耗合计。 */
  totalTokens: number
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

// ---------- aiconfig.knowledge-base ----------
// RAG 知识库检索：一条记录 = 一个外部 RAG 服务，保存时后端会同步强制实测连通性
// （耗时可达数秒，前端调用需用更长 timeout）。智能体只能挂载测试通过的知识库。
export interface KnowledgeBaseVO {
  id: number
  kbName: string
  baseUrl: string
  appId: string | null
  apiKeyMasked: string
  contentType: string
  /** 自定义请求头，JSON 字符串，如 {"X-Tenant":"abc"}，可为空 */
  extraHeaders: string | null
  topN: number
  /** 召回分数阈值，该 RAG 服务 score 尺度很小（0.13~0.19 量级），低于此分数的召回结果不注入 */
  scoreThreshold: number
  status: number
  testStatus: number
  testTime: string | null
  remark: string | null
  createTime: string
  updateTime: string
}

export interface KnowledgeBaseSaveRequest {
  kbName: string
  baseUrl: string
  appId?: string | null
  /** 编辑时留空 = 不修改，与模型配置 apiKey 语义一致 */
  apiKey?: string | null
  contentType?: string | null
  extraHeaders?: string | null
  topN?: number | null
  scoreThreshold?: number | null
  status?: number | null
  remark?: string | null
}

export interface KnowledgeBaseTestResult {
  testStatus: number
  testTime: string | null
  message: string | null
  hitCount: number | null
}

/** 智能体表单知识库多选下拉项：仅启用且测试通过的知识库 */
export interface KnowledgeBaseOption {
  id: number
  kbName: string
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
/** Skill 附属文件展示项（不含内容）。 */
export interface SkillFileVO {
  filePath: string
  fileSize: number
}

/** Skill 附属文件传输载体（parse-upload 响应与保存请求共用，内容 base64）。 */
export interface SkillUploadFile {
  filePath: string
  fileSize: number
  contentBase64: string
}

/** parse-upload 解析结果：SKILL.md 正文 + zip 内全部附属文件（.md 直传时 files 为空）。 */
export interface SkillUploadParseResult {
  content: string
  files: SkillUploadFile[]
}

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
  /** 附属文件清单（zip 上传的 references/scripts 等，不含内容）。 */
  files: SkillFileVO[]
}

export interface SkillSaveRequest {
  skillName: string
  skillCode: string
  content: string
  description?: string | null
  status?: number | null
  storageTargets: string[]
  /** 附属文件：null/undefined = 保持现有不变（编辑未重新上传）；数组（含空）= 全量替换。 */
  files?: SkillUploadFile[] | null
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
  /** 挂载的 RAG 知识库 ID 列表，检索由后端在对话时自动执行 */
  knowledgeBaseIds: number[]
  knowledgeBaseNames: string[]
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

/** 智能体长期记忆查看结果：exists=false 表示 MEMORY.md 尚未生成（刚开启记忆能力或已清空）。 */
export interface AgentMemoryVO {
  exists: boolean
  content: string
  updateTime: string | null
}

export interface AgentSaveRequest {
  agentName: string
  agentCode: string
  modelId: number
  backupModelIds?: number[] | null
  mcpIds?: number[] | null
  skillIds?: number[] | null
  systemToolIds?: number[] | null
  knowledgeBaseIds?: number[] | null
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

/** 交互式命令的实时输出事件。 */
export interface CommandOutputEvent {
  stream: 'combined'
  text: string
  timestamp: number
}

/** 交互式命令唯一终态。 */
export interface CommandResultEvent {
  exitCode: number
  success: boolean
  durationMs: number
  timedOut: boolean
  containerId: string | null
}

export interface RefactorTaskRequest {
  sessionId: string
  taskType: 'REPLACE' | 'API_MIGRATION' | 'DEPENDENCY_UPGRADE' | 'STYLE'
  description: string
  targetFiles: string[]
}

/** 当前用户可见的会话沙箱运行态。 */
export interface ManagedSandboxView {
  sessionId: string
  mode: 'local' | 'docker'
  containerId: string | null
  status: 'STARTING' | 'RUNNING' | 'IDLE' | 'FAILED' | 'STOPPING' | 'STOPPED'
  createdAt: string
  lastActiveAt: string
  command: string | null
  cpuUsage: string | null
  memoryUsage: string | null
  memoryLimitMb: number | null
  cpuLimit: number | null
}

/** admin.sandbox.* 的安全只读生效配置。 */
export interface SandboxConfigView {
  mode: 'local' | 'docker' | 'disabled'
  executeTimeoutSeconds: number
  permissionMode: 'BYPASS' | 'HITL'
  docker: {
    image: string
    memoryMb: number
    cpuCount: number
    network: string
  }
  guard: { enabled: boolean }
  features: {
    commandExecutionEnabled: boolean
    diagnosisEnabled: boolean
    refactorEnabled: boolean
    managementEnabled: boolean
    idleTimeoutMinutes: number
  }
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

/**
 * AI 代码审查任务（异步化）：review 接口提交后不再同步返回结果，而是返回 taskId，
 * 由前端轮询本类型或站内信跳转回查任务终态。status=SUCCESS 时 result 才有值，
 * FAILED 时 errorMsg 有值，RUNNING 时两者都为 null。
 */
export interface ReviewTaskVO {
  id: number
  status: 'RUNNING' | 'SUCCESS' | 'FAILED'
  result: ReviewResult | null
  errorMsg: string | null
  createTime: string
  finishTime: string | null
}

/** plan SSE 事件里的单条高风险操作项（P1-1 HITL）。 */
export interface PlanAction {
  /** DELETE（删除文件）｜RUN_COMMAND（非只读/破坏性命令）｜MODIFY_DEPENDENCY（改依赖）｜BATCH_MODIFY（批量修改超阈值）
   * ｜EXECUTE_TOOL（Manual 模式下全量工具执行确认）；未知类型前端兜底显示原始值。 */
  type: string
  target: string
  reason: string
}

/** plan SSE 事件的 data 载荷：高风险操作待人工确认，流已挂起（P1-1 HITL）。 */
export interface PlanEvent {
  planId: string
  actions: PlanAction[]
  reason: string
  requiresConfirmation: boolean
  /** 确认超时秒数，前端据此显示倒计时；超时后服务端自动按拒绝处理。 */
  timeoutSeconds: number
}

/** plan_result SSE 事件的 data 载荷：某个 planId 的终态（用户批准/拒绝或服务端超时）。 */
export interface PlanResultEvent {
  planId: string
  status: 'APPROVED' | 'REJECTED' | 'TIMEOUT'
}

/** Plan Mode 计划确认请求体（P1-1）。 */
export interface PlanConfirmRequest {
  sessionId: string
  planId: string
  approved: boolean
  note?: string
}

/**
 * role_stage SSE 事件的 data 载荷（P3-1 协作模式）：多角色顺序协作的阶段进度。
 * CODING 角色的产物仍走既有 file_change/test_report/message 事件，其 output 为 null。
 */
export interface RoleStageEvent {
  role: string
  type: 'PLAN' | 'CODING' | 'REVIEW'
  index: number
  total: number
  status: 'START' | 'DONE' | 'FAILED'
  /** DONE 时携带 PLAN/REVIEW 角色的文本产物；FAILED 时为错误信息；CODING 角色为 null。 */
  output: string | null
}

/**
 * 执行模式（对话/VibeCoding 通用，对齐 Claude Code 的 Mode 交互）：
 * auto（自动·默认，高风险操作才需确认）｜manual（每步工具执行都需确认）｜
 * accept_edits（文件编辑自动放行，命令/删除仍确认）｜plan（只读研究，不执行写操作，输出方案）｜
 * bypass（全部自动执行，危险）。会话内记忆，默认 auto。
 */
export type ExecutionMode = 'auto' | 'manual' | 'accept_edits' | 'plan' | 'bypass'

// ---------- workspace.chat ----------
export interface ChatRequest {
  sessionId: string
  message: string
  /** 协作模式（P3-1）：开启后后端按 需求分析→方案设计→编码实现→自测审查 多角色顺序协作。 */
  collaboration?: boolean
  /** 执行模式（会话内记忆，未传时后端按 auto 处理）。 */
  mode?: ExecutionMode
  /** 本次消息携带的解析成功附件 id 列表（对话附件预览，未携带附件时省略）。 */
  attachmentIds?: string[]
}

export interface ChatSessionSummary {
  sessionId: string
  preview: string
  lastMessageTime: string | null
  messageCount: number
}

/**
 * 前端内存里"活着"的会话（进行中或本次加载过的），供历史侧边栏与后端已落库列表合并展示。
 * 进行中的新会话（尤其第一轮还没落库）后端列表拉不到，全靠这份内存登记让它在侧边栏可见并标「进行中」。
 */
export interface LiveSession {
  sessionId: string
  preview: string
  messageCount: number
  /** 是否正在流式生成中——决定是否置顶并打「进行中」标。 */
  streaming: boolean
}

/** 历史消息里挂的附件摘要（不含正文 content，需要文本预览时另调详情接口）。 */
export interface ChatMessageAttachment {
  id: string
  fileName: string
  mimeType: string
  fileSize: number
  parseStatus: 'SUCCESS' | 'FAILED'
}

export interface ChatMessageVO {
  id: string
  role: 'user' | 'assistant'
  text: string
  timestamp: string
  /** 该条消息携带的附件，恒为数组（可能为空数组）。 */
  attachments: ChatMessageAttachment[]
}

/** 附件上传解析结果（落盘+落库，解析失败时 content 为空、errorMessage 说明原因，由调用方决定是否拼进消息） */
export interface ChatAttachmentResult {
  id: string
  fileName: string
  content: string
  parseStatus: 'SUCCESS' | 'FAILED'
  errorMessage: string | null
  mimeType: string
  fileSize: number
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

// ---------- workbench.site ----------
export interface WorkbenchSiteVO {
  id: number
  name: string
  category: string | null
  url: string
  account: string | null
  passwordMasked: string
  /** 是否配置了密码：前端据此决定"复制密码"按钮是否可用。 */
  hasPassword: boolean
  remark: string | null
  enabled: boolean
  // 自动登录高级配置（留空走脚本内启发式）
  usernameSelector: string | null
  passwordSelector: string | null
  submitSelector: string | null
  fillMode: string
  submitMode: string
  initDelayMs: number
  submitDelayMs: number
  createTime: string
  updateTime: string
}

export interface WorkbenchSiteSaveRequest {
  name: string
  category?: string | null
  url: string
  account?: string | null
  /** 新建时可留空（无密码站点）；编辑留空表示不修改密码，与数据源约定一致。 */
  password?: string | null
  remark?: string | null
  enabled?: boolean | null
  usernameSelector?: string | null
  passwordSelector?: string | null
  submitSelector?: string | null
  fillMode?: string | null
  submitMode?: string | null
  initDelayMs?: number | null
  submitDelayMs?: number | null
}

// ---------- workbench.token ----------
export interface WorkbenchTokenVO {
  id: number
  name: string
  tokenPrefix: string
  expireTime: string | null
  lastUsedTime: string | null
  revoked: boolean
  createTime: string
}

export interface WorkbenchTokenCreateRequest {
  name: string
  /** 有效期天数，null 表示永不过期。 */
  expireDays?: number | null
}

/** 令牌创建的一次性响应：token 明文仅此刻返回一次。 */
export interface WorkbenchTokenCreatedVO {
  id: number
  name: string
  token: string
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
  /** 日期格式（仅 DATETIME 类型生效，如 yyyy-MM-dd HH:mm:ss / yyyy-MM-dd），空表示默认 yyyy-MM-dd HH:mm:ss。 */
  dateFormat: string | null
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
  /** 日期格式，仅 paramType 为 DATETIME 时可传（后端校验）。 */
  dateFormat?: string | null
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
  /** 日期格式（仅 DATETIME 生效），驱动日期控件粒度与提交格式；空默认 yyyy-MM-dd HH:mm:ss。 */
  dateFormat: string | null
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

/** 即席（adhoc）SQL 查询请求：选已配置数据源 + 手写只读 SQL。 */
export interface SqlAdhocQueryRequest {
  datasourceId: number
  sql: string
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
  publishStatus?: 'PENDING' | 'PROCESSING' | 'PUBLISHED' | 'PARTIAL' | 'APPLIED' | 'FAILED'
  publishRevision?: string
  publishLastError?: string
  publishUpdatedAtMs?: number
  createTime: string
  updateTime: string
}

export interface ChannelBindingSaveRequest {
  channelCode: string
  agentId: number
  /** 可空，新建默认由后端置为启用。 */
  status?: number
}

// ---------- channel-robot（渠道机器人接入）----------
// 渠道类型枚举 code（与后端约定：当前仅钉钉可接入，企业微信/微信占位待支持）。
export type ChannelType = 'dingtalk' | 'wecom' | 'wechat'

/** 会话模式：continuous 持续会话（多轮携带上下文）/ per_message 单次问答（每条消息全新上下文）。 */
export type ChannelSessionMode = 'continuous' | 'per_message'

// 分页列表行：后端出于安全不回传 appSecret，仅用 hasSecret 标识是否已配置。
export interface ChannelRobotVO {
  id: number
  channelType: ChannelType
  robotName: string
  appKey: string
  robotCode: string
  agentCode: string
  sessionMode: ChannelSessionMode
  /** 0 停用 / 1 启用 */
  status: number
  remark: string | null
  /** 是否已配置 appSecret：编辑时据此显示「已配置」标识、决定 placeholder 文案。 */
  hasSecret: boolean
  createTime: string
  updateTime: string
}

export interface ChannelRobotSaveRequest {
  channelType: ChannelType
  robotName: string
  appKey: string
  /** 新建必填；编辑留空表示不修改，与 ModelManage 的 apiKey / 数据源密码约定一致。 */
  appSecret?: string | null
  /** 留空后端与 appKey 一致，前端提交时也默认回填 appKey。 */
  robotCode?: string | null
  agentCode: string
  /** 留空后端取默认 continuous。 */
  sessionMode?: ChannelSessionMode | null
  status?: number | null
  remark?: string | null
}

/** 渠道机器人分页查询：MP 分页（current/size）之上追加渠道类型过滤。 */
export interface ChannelRobotPageQuery extends MpPageQuery {
  channelType?: string
}

// ---------- knowledge（代码知识库）----------
/** 代码知识库索引（P3-2）：一次构建对应一个索引，异步构建，status 反映进度。 */
export interface KnowledgeIndex {
  id: number
  indexName: string
  sourcePath: string
  embeddingModel?: string
  dimensions?: number
  chunkCount: number
  status: 'BUILDING' | 'READY' | 'FAILED'
  message?: string
  createTime?: string
  updateTime?: string
}

/** 代码知识库检索命中项：一个代码切片及其相似度得分。 */
export interface KnowledgeSearchHit {
  sourcePath: string
  symbol?: string
  lang?: string
  chunkIndex: number
  score: number
  snippet: string
}

/** 代码知识库问答结果：模型答案 + 引用的检索命中项。 */
export interface KnowledgeAskResponse {
  answer: string
  citations: KnowledgeSearchHit[]
}

// ---------- 站内消息 ----------
/** 站内消息（顶栏铃铛）：bizType/bizId 标识来源业务（如 AI 代码审查任务），link 非空时点击可跳转。 */
export interface SiteMessageVO {
  id: number
  title: string
  content: string
  bizType: string
  bizId: string | null
  link: string | null
  readFlag: 0 | 1
  createTime: string
}

/** 站内消息分页查询：page/size 与后端 /message/page 契约一致，与 MpPageQuery 的 current/size 命名不同，单独定义。 */
export interface SiteMessagePageQuery {
  page: number
  size: number
  readFlag?: 0 | 1
}

// ---------- 内容风控（敏感词词库 / 限流规则 / 命中看板）----------
// 三张表都在客服端库 agent_scope_customer_work，后台是它们的维护端；改动经 starter 侧
// 轮询版本指纹自动生效（默认 60 秒内），后台不需要知道任何客服实例地址。

/** 敏感词类目。 */
export type SensitiveWordCategory = 'POLITICS' | 'PORN' | 'ABUSE' | 'COMPETITOR' | 'CUSTOM'

/** 命中处置动作：拦截 / 打码 / 放行标记。 */
export type SensitiveWordAction = 'BLOCK' | 'MASK' | 'REVIEW'

export interface SensitiveWordVO {
  id: number
  word: string
  category: string
  action: string
  enabled: boolean
  createdAtMs: number | null
  updatedAtMs: number | null
}

export interface SensitiveWordSaveRequest {
  word: string
  category: string
  action: string
  enabled?: boolean
}

export interface SensitiveWordPageQuery extends PageQuery {
  category?: string
  action?: string
}

/** 限流计数维度：按 Key / 按 IP / 整条路径共享一份配额。 */
export type RateLimitDimension = 'API_KEY' | 'IP' | 'GLOBAL'

export type RateLimitAlgorithm = 'FIXED_WINDOW' | 'SLIDING_WINDOW'

export interface RateLimitRuleVO {
  id: number
  ruleName: string
  pathPrefix: string
  dimension: string
  limitCount: number
  algorithm: string
  windowSeconds: number
  priority: number
  enabled: boolean
  createdAtMs: number | null
  updatedAtMs: number | null
}

export interface RateLimitRuleSaveRequest {
  ruleName: string
  pathPrefix: string
  dimension: string
  limitCount: number
  algorithm: string
  windowSeconds: number
  priority: number
  enabled?: boolean
}

export interface SensitiveWordHitLogVO {
  id: number
  direction: string
  action: string
  words: string[]
  categories: string[]
  hitCount: number | null
  agentName: string | null
  sessionId: string | null
  userId: string | null
  snippet: string | null
  createdAtMs: number | null
}

export interface SensitiveWordHitLogPageQuery extends PageQuery {
  direction?: string
  action?: string
  sessionId?: string
  startMs?: number
  endMs?: number
}

/** 看板通用计数项：分组键 + 条数。 */
export interface ContentGuardCountVO {
  label: string
  total: number
}

/** 命中看板统计；trendGranularity 由后端按查询区间跨度自动决定（day / hour），前端不传。 */
export interface SensitiveWordHitStatsVO {
  total: number
  byAction: ContentGuardCountVO[]
  byDirection: ContentGuardCountVO[]
  topWords: ContentGuardCountVO[]
  trend: ContentGuardCountVO[]
  trendGranularity: string
}

// ---------- 智能体后台委派任务（agent_spawn 异步模式）----------

/** 任务状态；与后端 io.agentscope.harness TaskStatus 枚举一一对应。 */
export type AgentTaskStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED'

export interface AgentTaskVO {
  id: number
  taskId: string
  parentAgentCode?: string
  subAgentId?: string
  parentSessionId?: string
  status: AgentTaskStatus
  /** 列表接口是预览（配合 resultTruncated 判断），详情接口是全文。 */
  result?: string
  resultTruncated?: boolean
  errorMessage?: string
  cancelRequested?: boolean
  createdAt?: string
  startedAt?: string
  finishedAt?: string
  /** 执行耗时（毫秒）；排队中/执行中为空。 */
  costMs?: number
}

export interface AgentTaskPageQuery extends MpPageQuery {
  status?: string
  agentCode?: string
}

// ---------- 主体级速率配额（用户额度） ----------

/** 一档额度定义。窗口是滚动的（最近 N 秒），与租户配额的自然日/月对齐不同。 */
export interface SubjectQuotaLevelVO {
  tenantId: string
  levelCode: string
  levelName: string
  /** USER 登录用户 / IP 匿名 / API_KEY 接入方 */
  subjectType: string
  windowSeconds: number
  /** 0 = 不限 */
  tokenLimit: number
  /** 0 = 不限 */
  requestLimit: number
  /** BLOCK 拦截 / WARN 仅记录 */
  exceedAction: string
  enabled: boolean
  remark?: string
}

export interface SubjectQuotaLevelSaveRequest {
  levelCode: string
  levelName: string
  subjectType: string
  windowSeconds: number
  tokenLimit: number
  requestLimit: number
  exceedAction: string
  enabled: boolean
  remark?: string
}

/** 用户分档视图（只含分配等级所需字段，不含账户敏感信息）。 */
export interface SubjectQuotaUserVO {
  userId: string
  username: string
  nickname?: string
  /** 空表示走配置里的默认档 */
  levelCode?: string
  status: string
  createdAtMs?: number
}

export interface UserLevelSaveRequest {
  userId: string
  /** 传空表示回到默认档 */
  levelCode?: string
}

/** 超限命中明细。 */
export interface SubjectQuotaHitVO {
  subjectType: string
  subjectId: string
  levelCode?: string
  /** TOKEN / REQUEST */
  limitKind: string
  used: number
  limitValue: number
  windowSeconds: number
  action: string
  resource?: string
  createdAtMs: number
}

/** 超限命中排行（谁在刷）。 */
export interface SubjectQuotaHitRank {
  subjectType: string
  subjectId: string
  levelCode?: string
  hitCount: number
  lastHitAtMs: number
}

/** 后台用户分档视图（sys_user，与终端用户的主键类型/状态取值都不同，故单列一个类型）。 */
export interface AdminQuotaUserVO {
  userId: number
  username: string
  nickname?: string
  /** 空表示走配置里的 default-admin-level */
  levelCode?: string
  /** 0 禁用 / 1 启用 */
  status: number
  createTime?: string
}

export interface AdminUserLevelSaveRequest {
  userId: number
  /** 传空表示回到默认档 */
  levelCode?: string
}
