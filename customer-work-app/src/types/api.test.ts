import { describe, expect, it } from 'vitest'
import {
  ticketActorTypeText,
  ticketCategoryText,
  ticketEventTypeText,
  ticketPriorityText,
} from './api'

describe('ticket display text', () => {
  it('把后端工单枚举转换为用户可理解的中文', () => {
    expect(ticketCategoryText('ORDER')).toBe('订单')
    expect(ticketPriorityText('NORMAL')).toBe('普通')
    expect(ticketEventTypeText('REQUEST_HANDOFF')).toBe('申请转人工')
    expect(ticketActorTypeText('AGENT')).toBe('客服')
  })

  it('服务端新增枚举时保留原值而不是伪造含义', () => {
    expect(ticketCategoryText('FUTURE_CATEGORY')).toBe('FUTURE_CATEGORY')
    expect(ticketEventTypeText('FUTURE_EVENT')).toBe('FUTURE_EVENT')
  })
})
