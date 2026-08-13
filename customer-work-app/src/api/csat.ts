import { request } from '@/api/request'

// 会话级满意度（CSAT）。与消息级点赞/点踩（feedback.ts）衡量的是不同的东西：
// 那个看单句答得好不好，这个看"这次服务整体解决了没有"。
// baseURL 已配置为 /api（见 request.ts），此处 url 不再重复前缀。

export interface CsatSurvey {
  sessionId: string
  scopeId: string
  /** null 表示已邀请但还没评。 */
  score: number | null
  comment: string | null
  invitedAtMs: number
  submittedAtMs: number
  answered: boolean
  satisfied: boolean
}

/**
 * 查会话的满意度调查状态。
 *
 * 用它决定要不要弹评分卡：404（无记录）= 没被邀请；有记录且 answered=false = 待评价。
 * 失败静默——拿不到状态时不弹卡即可，不该给用户报错。
 */
export function fetchCsatStatus(sessionId: string): Promise<CsatSurvey> {
  return request({ url: `/customer/csat/${sessionId}`, method: 'get', silentError: true })
}

/** 提交满意度评分（1-5）；未被邀请过的会话也接受，后端会补建记录。 */
export function submitCsat(sessionId: string, score: number, comment?: string): Promise<CsatSurvey> {
  return request({ url: `/customer/csat/${sessionId}`, method: 'post', params: { score, comment } })
}
