import { request } from '@/api/request'
import type { UserQuota } from '@/types/api'

/**
 * 查我的额度：当前滚动窗口内的 token 与提问次数用量。
 *
 * <p>该路径在服务端豁免限流判定——否则查一次扣一次，且额度耗尽后连"还剩多少"都看不到。</p>
 *
 * silentError：额度是锦上添花的信息，查不到就不显示，不该弹一个用户看不懂的错误提示。
 */
export function fetchMyQuota(): Promise<UserQuota> {
  return request({ url: '/customer/user/quota', method: 'get', silentError: true })
}
