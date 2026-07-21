import { request } from './request'
import type { PageResult, SiteMessagePageQuery, SiteMessageVO } from '@/types/api'

const BASE_URL = '/message'

/** 站内消息分页（顶栏铃铛弹层用）。readFlag 省略时查全部（已读+未读）。 */
export function pageSiteMessages(query: SiteMessagePageQuery) {
  return request<PageResult<SiteMessageVO>>({ url: `${BASE_URL}/page`, method: 'get', params: query })
}

/** 未读消息数，供铃铛徽标与轮询使用。 */
export function getUnreadMessageCount() {
  return request<number>({ url: `${BASE_URL}/unread-count`, method: 'get' })
}

/** 标记单条消息已读。 */
export function markMessageRead(id: number) {
  return request<void>({ url: `${BASE_URL}/${id}/read`, method: 'post' })
}

/** 全部标记已读。 */
export function markAllMessagesRead() {
  return request<void>({ url: `${BASE_URL}/read-all`, method: 'post' })
}
