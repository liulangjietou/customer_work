import type { Component } from 'vue'

/**
 * 静态菜单 path -> 页面组件，与后端种子数据 sys_permission.path 一一对应
 * （mysql/admin-schema.sql / V2__seed_data.sql）。动态智能体节点（workspace/{agentCode}）
 * 不在这里，走 router/index.ts 里单独的通配路由。
 */
export const staticRouteComponents: Record<string, () => Promise<Component>> = {
  '/system/user': () => import('@/views/system/UserManage.vue'),
  '/system/role': () => import('@/views/system/RoleManage.vue'),
  '/system/log': () => import('@/views/system/OperationLog.vue'),
  '/system/ai-audit': () => import('@/views/system/AiCodingAudit.vue'),
  '/system/menu': () => import('@/views/system/MenuManage.vue'),
  '/aiconfig/model': () => import('@/views/aiconfig/ModelManage.vue'),
  '/aiconfig/mcp': () => import('@/views/aiconfig/McpManage.vue'),
  '/aiconfig/skill': () => import('@/views/aiconfig/SkillManage.vue'),
  '/aiconfig/agent': () => import('@/views/aiconfig/AgentManage.vue'),
  '/aiconfig/system-tool': () => import('@/views/aiconfig/SystemToolManage.vue'),
  '/aiconfig/scheduled-task': () => import('@/views/aiconfig/ScheduledTaskManage.vue'),
  '/ticket/user-ticket': () => import('@/views/ticket/UserTicketManage.vue'),
  '/ticket/user-order': () => import('@/views/ticket/UserOrderManage.vue'),
  '/project': () => import('@/views/project/ProjectManage.vue'),
  '/sql/datasource': () => import('@/views/sql/SqlDatasourceManage.vue'),
  '/sql/define': () => import('@/views/sql/SqlDefineManage.vue'),
}
