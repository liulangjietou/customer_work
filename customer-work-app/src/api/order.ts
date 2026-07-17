import { request } from '@/api/request'
import type { OrderView } from '@/types/api'

// baseURL 已配置为 /api（见 request.ts + .env.development），此处 url 不再重复 /api 前缀
export function fetchOrders(): Promise<OrderView[]> {
  return request({ url: '/customer/user/orders', method: 'get' })
}

export function fetchOrderDetail(orderId: string): Promise<OrderView> {
  return request({ url: `/customer/user/orders/${orderId}`, method: 'get' })
}
