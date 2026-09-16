import type { RefundApprovalPage } from '@/types/businessProgress'
import { request } from '@/api/request'

/** 会话退款审批的只读目录，不触发退款或重新执行。 */
export function fetchRefundApprovals(sessionId: string, page = 1, size = 10): Promise<RefundApprovalPage> {
  return request({
    url: `/customer/user/sessions/${encodeURIComponent(sessionId)}/refund-approvals`,
    method: 'get', params: { page, size },
    headers: { 'Cache-Control': 'no-store' }, silentError: true,
  })
}
