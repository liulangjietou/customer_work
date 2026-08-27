import type { ModelCertification } from '@/types/api'

/**
 * 上线认证结果的展示判定。
 *
 * <p>两个状态字段语义不同，混用是这里出过的缺陷：{@code status} 是<b>本次运行</b>的结论
 * （PASSED / FAILED），{@code effectiveStatus} 是<b>该部署当前的门禁态</b>
 * （PASSED / FAILED / STALE / EXPIRED / UNKNOWN / NOT_REQUIRED）。存量豁免部署的门禁态恒为
 * NOT_REQUIRED，拿它判「这次跑得怎么样」会把每一次成功的认证都报成失败。</p>
 */
export interface CertificationResultMessage {
  type: 'success' | 'error'
  text: string
}

/** 本次认证运行是否通过——只看运行结论，与该部署是否纳入门禁无关。 */
export function isCertificationRunPassed(result: ModelCertification): boolean {
  return result.status === 'PASSED'
}

/** 认证结束后的提示：先说本次结论，再按门禁态补上「这次结果对部署意味着什么」。 */
export function certificationResultMessage(result: ModelCertification): CertificationResultMessage {
  if (!isCertificationRunPassed(result)) {
    return {
      type: 'error',
      text: `认证失败：${result.failureMessage || result.failureCode || '请查看检查项'}`,
    }
  }
  if (result.effectiveStatus === 'PASSED') {
    return { type: 'success', text: '上线认证通过，可以激活部署' }
  }
  if (result.effectiveStatus === 'NOT_REQUIRED') {
    return {
      type: 'success',
      text: '上线认证通过；该部署为存量豁免，尚未纳入认证门禁（改配置或轮换凭据后自动纳入）',
    }
  }
  if (result.effectiveStatus === 'STALE') {
    return {
      type: 'success',
      text: `上线认证通过，但本次结果未晋级：${result.staleReason || '认证期间配置或凭据已变化'}`,
    }
  }
  return { type: 'success', text: `上线认证通过（当前门禁态：${result.effectiveStatus}）` }
}

/**
 * 能否激活部署。NOT_REQUIRED 与 PASSED 都放行：后端 {@code requireCurrent} 对存量豁免部署
 * 直接放行，前端只认 PASSED 会让存量部署停用后再也激活不回来。
 */
export function canActivateByCertification(effectiveStatus: string): boolean {
  return effectiveStatus === 'PASSED' || effectiveStatus === 'NOT_REQUIRED'
}
