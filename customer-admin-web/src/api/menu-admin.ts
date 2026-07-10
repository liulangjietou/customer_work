import { request } from './request'
import type { MenuChangeLogVO, MenuReorderItem, MenuSaveRequest, PageQuery, PageResult, PermissionVO } from '@/types/api'

export function menuAdminTree() {
  return request<PermissionVO[]>({ url: '/system/menu/tree', method: 'get' })
}

export function createMenuNode(data: MenuSaveRequest) {
  return request<void>({ url: '/system/menu', method: 'post', data })
}

export function updateMenuNode(id: number, data: MenuSaveRequest) {
  return request<void>({ url: `/system/menu/${id}`, method: 'put', data })
}

export function deleteMenuNode(id: number) {
  return request<void>({ url: `/system/menu/${id}`, method: 'delete' })
}

export function reorderMenuNodes(items: MenuReorderItem[]) {
  return request<void>({ url: '/system/menu/reorder', method: 'put', data: { items } })
}

export function publishMenu() {
  return request<void>({ url: '/system/menu/publish', method: 'post' })
}

/** 上传图标图片，返回可直接 <img> 展示的访问 URL。 */
export function uploadMenuIcon(file: File) {
  const formData = new FormData()
  formData.append('file', file)
  return request<string>({ url: '/system/menu/icon', method: 'post', data: formData })
}

export function fetchMenuChangeLog(menuId: number | undefined, query: PageQuery) {
  return request<PageResult<MenuChangeLogVO>>({
    url: '/system/menu/change-log',
    method: 'get',
    params: { menuId, ...query },
  })
}
