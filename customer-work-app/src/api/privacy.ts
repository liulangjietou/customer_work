import { request } from '@/api/request'
import type { MemoryConsent, MemoryList } from '@/types/api'

/** 与后端 MemoryConsentService 记录的同意版本保持一致；文案有实质变化时应当同步升版重新征求。 */
export const MEMORY_CONSENT_VERSION = 'v1'

/** 查询长期记忆的同意状态。未表态过的用户返回 granted=false。 */
export function fetchMemoryConsent(): Promise<MemoryConsent> {
  return request({ url: '/customer/user/privacy/memory-consent', method: 'get', silentError: true })
}

/**
 * 授权或撤回长期记忆。
 *
 * 撤回是真删除：服务端会连同已记录的记忆一并清理，不是只改一个开关位。
 */
export function updateMemoryConsent(granted: boolean): Promise<MemoryConsent> {
  return request({
    url: '/customer/user/privacy/memory-consent',
    method: 'put',
    data: { granted, consentVersion: MEMORY_CONSENT_VERSION },
  })
}

/** 查看本人的长期记忆与事实记录。 */
export function fetchMyMemories(limit = 50): Promise<MemoryList> {
  return request({ url: '/customer/user/privacy/memory', method: 'get', params: { limit } })
}

/** 删除本人全部长期记忆（不改变同意状态）。 */
export function deleteMyMemories(): Promise<void> {
  return request({ url: '/customer/user/privacy/memory', method: 'delete' })
}
