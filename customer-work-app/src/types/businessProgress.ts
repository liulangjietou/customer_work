/** 仅包含当前用户有权读取的会话退款审批事实，不携带内部审批备注或执行错误。 */
export interface RefundApprovalView {
  id: string
  orderId: string
  amount: string | null
  approvalStatus: string
  executionStatus: string
  createdAtMs: number
  decidedAtMs: number | null
}

export interface RefundApprovalPage {
  total: number
  items: RefundApprovalView[]
}

export interface BusinessProgressState {
  label: string
  description: string
  tone: 'neutral' | 'pending' | 'active' | 'attention' | 'complete'
}
