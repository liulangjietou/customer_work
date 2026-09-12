import { request } from '@/api/request'
import type {
  CustomerAnswerSource,
  CustomerAnswerSourcePreview,
} from '@/types/customerAnswerSources'

function sourcesPath(sessionId: string, messageId: string) {
  return `/customer/user/sessions/${encodeURIComponent(sessionId)}/messages/${encodeURIComponent(messageId)}/sources`
}

/** 每次打开或重试重新核验权限，不把查询错误转换为空来源。 */
export function fetchCustomerAnswerSources(
  sessionId: string,
  messageId: string,
): Promise<CustomerAnswerSource[]> {
  return request({
    url: sourcesPath(sessionId, messageId),
    method: 'get',
    headers: { 'Cache-Control': 'no-store' },
    silentError: true,
  })
}

/** sourceIndex 是当前消息内保存的来源位置；原文权限由本次读取重新确认。 */
export function fetchCustomerAnswerSourcePreview(
  sessionId: string,
  messageId: string,
  sourceIndex: number,
): Promise<CustomerAnswerSourcePreview> {
  return request({
    url: `${sourcesPath(sessionId, messageId)}/${sourceIndex}`,
    method: 'get',
    headers: { 'Cache-Control': 'no-store' },
    silentError: true,
  })
}
