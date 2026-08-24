import { request } from './request'
import type { ChannelBindingSaveRequest, ChannelBindingVO, RuntimePublishGateVO } from '@/types/api'

const BASE_URL = '/aiconfig/channel-binding'

export function listChannelBindings() {
  return request<ChannelBindingVO[]>({ url: BASE_URL, method: 'get' })
}

const GATE_BASE_URL = '/aiconfig/runtime-publish/gate/tasks'

/** 查询可靠发布任务所冻结的门禁判定、版本与评测运行。 */
export function getRuntimePublishGate(taskId: string) {
  return request<RuntimePublishGateVO>({ url: `${GATE_BASE_URL}/${taskId}`, method: 'get' })
}

/** 重新进入原门禁状态机，不绕过任何门禁规则。 */
export function retryRuntimePublishGate(taskId: string) {
  return request<void>({ url: `${GATE_BASE_URL}/${taskId}/retry`, method: 'post' })
}

/** 紧急豁免只作用于当前任务冻结的候选哈希，reason 会进入独立审计记录。 */
export function overrideRuntimePublishGate(taskId: string, reason: string) {
  return request<void>({ url: `${GATE_BASE_URL}/${taskId}/override`, method: 'post', data: { reason } })
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
