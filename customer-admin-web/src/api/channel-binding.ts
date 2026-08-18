import { request } from './request'
import type { ChannelBindingSaveRequest, ChannelBindingVO } from '@/types/api'

const BASE_URL = '/aiconfig/channel-binding'

export function listChannelBindings() {
  return request<ChannelBindingVO[]>({ url: BASE_URL, method: 'get' })
}

export function createChannelBinding(data: ChannelBindingSaveRequest) {
  return request<void>({ url: BASE_URL, method: 'post', data })
}

export function updateChannelBinding(id: number, data: ChannelBindingSaveRequest) {
  return request<void>({ url: `${BASE_URL}/${id}`, method: 'put', data })
}

export function deleteChannelBinding(id: number) {
  return request<void>({ url: `${BASE_URL}/${id}`, method: 'delete' })
}

/**
 * 重新发布：写入可靠发布队列，返回任务 ID；实际投递与实例 ACK 可在列表状态列观察。
 * 失败时后端返回业务错误码（40021/40022/40023），由 request 拦截器统一弹出错误提示。
 */
export function republishChannelBinding(channelCode: string) {
  return request<string>({ url: `${BASE_URL}/${channelCode}/republish`, method: 'post' })
}
