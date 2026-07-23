// Plan Mode 确认卡片（P1-1 HITL）共用的类型与纯函数：对话面板（chatConversations）与 VibeCoding
// 面板（vibeConversations）的 plan/plan_result 事件处理逻辑各自独立（会话状态形状不同，同 store
// 内 send() 已有的重复模式），但卡片本身的数据结构、展示文案是完全一致的，抽在这里给两边 + 共享的
// PlanConfirmCard.vue 组件复用，避免文案/类型标签出现两份定义、后续改一处漏一处。
import type { PlanEvent } from '@/types/api'

/** 单张 plan 确认卡片：一条 plan 事件对应一张，用户批准/拒绝或超时后翻成终态。 */
export interface PlanCard {
  planId: string
  actions: PlanEvent['actions']
  reason: string
  status: 'PENDING' | 'APPROVED' | 'REJECTED' | 'TIMEOUT'
  remainingSeconds: number
  submitting: boolean
}

/** 从 plan SSE 事件载荷构造一张待确认卡片（初始态 PENDING）。 */
export function createPlanCard(event: PlanEvent): PlanCard {
  return {
    planId: event.planId,
    actions: event.actions ?? [],
    reason: event.reason ?? '',
    status: 'PENDING',
    remainingSeconds: event.timeoutSeconds ?? 300,
    submitting: false,
  }
}

/** 计划卡片操作类型 → 中文标签，未知类型兜底显示原始值。 */
export function planActionLabel(type: string): string {
  switch (type) {
    case 'DELETE': return '删除文件'
    case 'RUN_COMMAND': return '执行命令'
    case 'MODIFY_DEPENDENCY': return '修改依赖'
    case 'BATCH_MODIFY': return '批量修改'
    case 'EXECUTE_TOOL': return '执行工具'
    default: return type
  }
}

/** 计划卡片终态 → 中文文案。 */
export function planStatusText(status: PlanCard['status']): string {
  switch (status) {
    case 'APPROVED': return '已批准'
    case 'REJECTED': return '已拒绝'
    case 'TIMEOUT': return '已超时（自动拒绝）'
    default: return '等待确认'
  }
}
