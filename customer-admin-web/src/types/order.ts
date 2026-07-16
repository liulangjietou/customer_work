// 用户订单模块类型定义，与 admin-server /api/ticket/orders/** 契约对应。
// 该模块挂载在 /ticket 路径下，是售后/工单坐席查订单用的辅助域，与后端并行开发，
// 字段/枚举以协商契约为准，联调阶段如有出入以后端实际返回为准调整。

// ---------- 状态：后端返回中文文案字符串，非英文枚举 ----------
export const ORDER_STATUS_OPTIONS = ['待支付', '已支付', '待发货', '已发货', '已签收', '已取消', '已退款'] as const

export type OrderStatus = (typeof ORDER_STATUS_OPTIONS)[number]

export const ORDER_STATUS_TAG_TYPE: Record<OrderStatus, '' | 'success' | 'warning' | 'danger' | 'info'> = {
  待支付: 'warning',
  已支付: '',
  待发货: '',
  已发货: 'info',
  已签收: 'success',
  已取消: 'info',
  已退款: 'danger',
}

// ---------- VO / DTO ----------
export interface OrderVO {
  orderId: string
  userId: string
  username: string | null
  productId: string
  productName: string
  amount: string
  status: string
  receiverAddr: string | null
  createdAtMs: number
}

export interface OrderDetailVO extends OrderVO {
  logisticsTrace: string | null
}

export interface OrderPageQuery {
  userId?: string
  username?: string
  orderId?: string
  status?: string
  pageNum?: number
  pageSize?: number
}

export interface OrderPageResult {
  total: number
  items: OrderVO[]
}

export interface OrderModifyAddressRequest {
  newAddress: string
}

export interface OrderCancelRequest {
  reason: string
}
