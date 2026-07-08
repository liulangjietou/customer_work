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

// ---------- auth ----------
export interface LoginRequest {
  username: string
  password: string
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
  sort: number | null
  children: PermissionVO[]
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
  temperature: number | null
  maxTokens: number | null
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
  temperature?: number | null
  maxTokens?: number | null
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
  createTime: string
}

export interface McpSaveRequest {
  mcpName: string
  mcpType: string
  config: string
  description?: string | null
  status?: number | null
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

// ---------- menu ----------
export interface MenuNode {
  id: number
  name: string
  path: string | null
  icon: string | null
  permCode: string | null
  sort: number | null
  agentCode: string | null
  capabilities: string[] | null
  dynamic: boolean
  children: MenuNode[]
}

// ---------- workspace.chat ----------
export interface ChatRequest {
  sessionId: string
  message: string
}
