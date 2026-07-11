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
}

export interface SkillSaveRequest {
  skillName: string
  skillCode: string
  content: string
  description?: string | null
  status?: number | null
}

// ---------- aiconfig.agent ----------
export interface AgentVO {
  id: number
  agentName: string
  agentCode: string
  modelId: number
  modelName: string | null
  mcpIds: number[]
  skillIds: number[]
  systemPrompt: string | null
  capabilities: string[]
  icon: string | null
  status: number
  createTime: string
}

export interface AgentSaveRequest {
  agentName: string
  agentCode: string
  modelId: number
  mcpIds?: number[] | null
  skillIds?: number[] | null
  systemPrompt?: string | null
  capabilities?: string[] | null
  icon?: string | null
  status?: number | null
}

// ---------- aiconfig.scheduled-task ----------
export interface ScheduledTaskVO {
  id: number
  taskCode: string
  taskName: string
  agentId: number
  /** 后端联查智能体名，智能体被删除等情况下可能为 null */
  agentName?: string | null
  prompt: string
  enabled: boolean
  remark: string | null
  createTime?: string
  updateTime?: string
}

export interface ScheduledTaskSaveRequest {
  taskCode: string
  taskName: string
  agentId: number
  prompt: string
  enabled?: boolean | null
  remark?: string | null
}

export interface ScheduledTaskRunVO {
  id: number
  taskId: number
  taskCode: string
  triggerType: 'XXL_JOB' | 'MANUAL'
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
