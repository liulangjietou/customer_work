import { request } from './request'

/** 登录页轮播图管理页回显 VO，对应后端 LoginCarouselImageVO */
export interface LoginCarouselImageVO {
  id: number
  imageName: string
  imageUrl: string
  sortOrder: number
  enabled: boolean
  createTime: string
  updateTime: string
}

export function fetchLoginImages() {
  return request<LoginCarouselImageVO[]>({ url: '/system/login-image', method: 'get' })
}

/** 上传轮播图，返回新记录（含可直接展示的访问 URL） */
export function uploadLoginImage(file: File) {
  const formData = new FormData()
  formData.append('file', file)
  return request<LoginCarouselImageVO>({ url: '/system/login-image', method: 'post', data: formData })
}

export function updateLoginImageEnabled(id: number, enabled: boolean) {
  return request<void>({ url: `/system/login-image/${id}/enabled`, method: 'put', data: { enabled } })
}

/** 传调整后的完整 id 顺序，后端按下标重写排序 */
export function reorderLoginImages(ids: number[]) {
  return request<void>({ url: '/system/login-image/reorder', method: 'put', data: { ids } })
}

export function deleteLoginImage(id: number) {
  return request<void>({ url: `/system/login-image/${id}`, method: 'delete' })
}

/** 登录页免鉴权实时拉取启用图 URL 列表（后端 Sa-Token 白名单放行） */
export function fetchLoginCarouselUrls() {
  return request<string[]>({ url: '/login-images/list', method: 'get' })
}
