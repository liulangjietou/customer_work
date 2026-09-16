import { describe, expect, it } from 'vitest'
import { refundApprovalState } from './businessProgress'
import type { RefundApprovalView } from '@/types/businessProgress'

const record: RefundApprovalView = {
  id: 'APR-1', orderId: 'ORDER-1', amount: '89.00', approvalStatus: 'PENDING',
  executionStatus: 'NOT_APPLICABLE', createdAtMs: 1, decidedAtMs: 0,
}

describe('退款办理事实的展示', () => {
  it.each([
    ['PENDING', 'NOT_APPLICABLE', '待人工审核'],
    ['DENIED', 'NOT_APPLICABLE', '审批未通过'],
    ['APPROVED', 'NOT_APPLICABLE', '已批准，执行待核对'],
    ['APPROVED', 'EXECUTING', '正在处理'],
    ['APPROVED', 'EXECUTE_FAILED', '处理未完成'],
    ['APPROVED', 'EXECUTED', '处理已执行'],
    ['UNRECOGNIZED', 'EXECUTED', '状态待核对'],
  ])('%s / %s 只显示该记录能够证明的阶段', (approvalStatus, executionStatus, label) => {
    const state = refundApprovalState({ ...record, approvalStatus, executionStatus })
    expect(state.label).toBe(label)
    expect(state.label + state.description).not.toMatch(/已到账|已原路退回|退款成功|[1-9].*工作日/)
  })

  it('人工已拒绝的矛盾执行字段不能显示处理成功', () => {
    const state = refundApprovalState({ ...record, approvalStatus: 'DENIED', executionStatus: 'EXECUTED' })
    expect(state.tone).not.toBe('complete')
  })
})
