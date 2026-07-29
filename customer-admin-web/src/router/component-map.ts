import type { Component } from 'vue'

/**
 * 静态菜单 path -> 页面组件，与后端种子数据 sys_permission.path 一一对应
 * （db/migration 迁移种子，副本在 mysql/02-customer-admin/）。动态智能体节点（workspace/{agentCode}）
 * 不在这里，走 router/index.ts 里单独的通配路由。
 */
export const staticRouteComponents: Record<string, () => Promise<Component>> = {
  '/system/user': () => import('@/views/system/UserManage.vue'),
  '/system/role': () => import('@/views/system/RoleManage.vue'),
  '/system/log': () => import('@/views/system/OperationLog.vue'),
  '/system/ai-audit': () => import('@/views/system/AiCodingAudit.vue'),
  '/system/agent-call-stats': () => import('@/views/system/AgentCallStats.vue'),
  '/system/menu': () => import('@/views/system/MenuManage.vue'),
  '/system/devtools': () => import('@/views/system/DevToolboxView.vue'),
  '/system/login-image': () => import('@/views/system/LoginImageManage.vue'),
  '/aiconfig/model': () => import('@/views/aiconfig/ModelManage.vue'),
  '/aiconfig/mcp': () => import('@/views/aiconfig/McpManage.vue'),
  '/aiconfig/skill': () => import('@/views/aiconfig/SkillManage.vue'),
  '/aiconfig/agent': () => import('@/views/aiconfig/AgentManage.vue'),
  '/aiconfig/system-tool': () => import('@/views/aiconfig/SystemToolManage.vue'),
  '/aiconfig/knowledge-base': () => import('@/views/aiconfig/KnowledgeBaseManage.vue'),
  '/aiconfig/scheduled-task': () => import('@/views/aiconfig/ScheduledTaskManage.vue'),
  '/aiconfig/agent-task': () => import('@/views/aiconfig/AgentTaskManage.vue'),
  '/contentguard/sensitive-word': () => import('@/views/contentguard/SensitiveWordManage.vue'),
  '/contentguard/rate-limit': () => import('@/views/contentguard/RateLimitRuleManage.vue'),
  '/contentguard/hit-log': () => import('@/views/contentguard/SensitiveHitLogBoard.vue'),
  '/aiconfig/channel-robot': () => import('@/views/aiconfig/ChannelRobotManage.vue'),
  '/ticket/user-ticket': () => import('@/views/ticket/UserTicketManage.vue'),
  '/ticket/user-order': () => import('@/views/ticket/UserOrderManage.vue'),
  '/project': () => import('@/views/project/ProjectManage.vue'),
  '/sql/datasource': () => import('@/views/sql/SqlDatasourceManage.vue'),
  '/sql/define': () => import('@/views/sql/SqlDefineManage.vue'),
  '/workbench/site': () => import('@/views/workbench/WorkbenchSiteManage.vue'),
  '/workbench/sql-console': () => import('@/views/workbench/WorkbenchSqlConsole.vue'),
}
