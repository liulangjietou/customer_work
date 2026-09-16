import type { BusinessProgressState, RefundApprovalView } from '@/types/businessProgress'

/** 人工审批和下游执行分别解释，不从审批状态推断退款到账。 */
export function refundApprovalState(record: RefundApprovalView): BusinessProgressState {
  if (record.approvalStatus === 'PENDING') {
    return { label: '待人工审核', description: '申请已保存，等待人工核对。', tone: 'pending' }
  }
  if (record.approvalStatus === 'DENIED') {
    return { label: '审批未通过', description: '如需了解处理原因，请在工单详情中联系人工。', tone: 'attention' }
  }
  if (record.approvalStatus === 'APPROVED') {
    switch (record.executionStatus) {
      case 'EXECUTING':
        return { label: '正在处理', description: '审批已通过，正在执行后续处理。', tone: 'active' }
      case 'EXECUTE_FAILED':
        return { label: '处理未完成', description: '审批已通过，后续处理需要人工核对。', tone: 'attention' }
      case 'EXECUTED':
        return { label: '处理已执行', description: '系统已记录处理完成，到账情况请核对支付渠道记录。', tone: 'complete' }
      default:
        return { label: '已批准，执行待核对', description: '人工审批已通过，执行结果尚待确认。', tone: 'pending' }
    }
  }
  return { label: '状态待核对', description: '请在工单详情中联系人工核对办理记录。', tone: 'neutral' }
}
