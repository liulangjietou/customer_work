import { request } from './request'
import type { PermissionVO } from '@/types/api'

export function permissionTree() {
  return request<PermissionVO[]>({ url: '/system/permission/tree', method: 'get' })
}
