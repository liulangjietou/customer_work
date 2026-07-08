import { request } from './request'
import type { MenuNode } from '@/types/api'

export function fetchMenuRoutes() {
  return request<MenuNode[]>({ url: '/menu/routes', method: 'get' })
}

export function fetchMenuVersion() {
  return request<number>({ url: '/menu/version', method: 'get' })
}
