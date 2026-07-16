import { request } from './request'
import type {
  OrderCancelRequest,
  OrderDetailVO,
  OrderModifyAddressRequest,
  OrderPageQuery,
  OrderPageResult,
} from '@/types/order'

const BASE_URL = '/ticket/orders'

export function pageOrders(query: OrderPageQuery) {
  return request<OrderPageResult>({ url: `${BASE_URL}/page`, method: 'get', params: query })
}

export function getOrderDetail(orderId: string) {
  return request<OrderDetailVO>({ url: `${BASE_URL}/${orderId}`, method: 'get' })
}

export function modifyOrderAddress(orderId: string, data: OrderModifyAddressRequest) {
  return request<void>({ url: `${BASE_URL}/${orderId}/modify-address`, method: 'post', data })
}

export function cancelOrder(orderId: string, data: OrderCancelRequest) {
  return request<void>({ url: `${BASE_URL}/${orderId}/cancel`, method: 'post', data })
}
