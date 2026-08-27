import { describe, expect, it } from 'vitest'
import type { ModelCertification } from '@/types/api'
import {
  canActivateByCertification,
  certificationResultMessage,
  isCertificationRunPassed,
} from './certificationResult'

function certification(patch: Partial<ModelCertification>): ModelCertification {
  return {
    runId: 1,
    status: 'PASSED',
    effectiveStatus: 'PASSED',
    staleReason: null,
    certifiedEndpointRevision: 1,
    certifiedSecretVersion: 1,
    validUntil: null,
    completedAt: null,
    passedChecks: 8,
    failedChecks: 0,
    latencyP95Ms: 1200,
    verifiedContextTokens: 1000000,
    inputPrice: null,
    outputPrice: null,
    currency: null,
    failureCode: null,
    failureMessage: null,
    checks: [],
    ...patch,
  } as ModelCertification
}

describe('certificationResultMessage', () => {
  /**
   * 直接回归：存量豁免部署（certification_required=0）的门禁态恒为 NOT_REQUIRED。
   * 早期实现拿 effectiveStatus 判本次结果，导致每一次成功的认证都弹「认证失败：请查看检查项」，
   * 而检查项列表里一条失败都没有。
   */
  it('存量豁免部署认证通过时报成功而不是失败', () => {
    const result = certification({ status: 'PASSED', effectiveStatus: 'NOT_REQUIRED' })

    const message = certificationResultMessage(result)

    expect(message.type).toBe('success')
    expect(message.text).toContain('上线认证通过')
    expect(message.text).toContain('存量豁免')
  })

  it('已纳入门禁且通过时提示可以激活', () => {
    const message = certificationResultMessage(
      certification({ status: 'PASSED', effectiveStatus: 'PASSED' }),
    )

    expect(message.type).toBe('success')
    expect(message.text).toBe('上线认证通过，可以激活部署')
  })

  it('本次通过但结果未晋级时说明原因', () => {
    const message = certificationResultMessage(certification({
      status: 'PASSED',
      effectiveStatus: 'STALE',
      staleReason: '认证期间配置、凭据或更新认证运行已发生变化，本次结果未晋级',
    }))

    expect(message.type).toBe('success')
    expect(message.text).toContain('未晋级')
    expect(message.text).toContain('本次结果未晋级')
  })

  it('本次失败时报出失败原因', () => {
    const message = certificationResultMessage(certification({
      status: 'FAILED',
      effectiveStatus: 'FAILED',
      failureCode: 'TOOL_CALL',
      failureMessage: '工具调用能力检查失败',
    }))

    expect(message.type).toBe('error')
    expect(message.text).toBe('认证失败：工具调用能力检查失败')
  })

  it('失败但没有摘要时回落到错误码，再回落到兜底文案', () => {
    expect(certificationResultMessage(certification({
      status: 'FAILED', effectiveStatus: 'FAILED', failureCode: 'LATENCY', failureMessage: null,
    })).text).toBe('认证失败：LATENCY')

    expect(certificationResultMessage(certification({
      status: 'FAILED', effectiveStatus: 'FAILED', failureCode: null, failureMessage: null,
    })).text).toBe('认证失败：请查看检查项')
  })
})

describe('isCertificationRunPassed', () => {
  it('只看本次运行结论，不受门禁态影响', () => {
    expect(isCertificationRunPassed(
      certification({ status: 'PASSED', effectiveStatus: 'NOT_REQUIRED' }))).toBe(true)
    expect(isCertificationRunPassed(
      certification({ status: 'PASSED', effectiveStatus: 'STALE' }))).toBe(true)
    expect(isCertificationRunPassed(
      certification({ status: 'FAILED', effectiveStatus: 'NOT_REQUIRED' }))).toBe(false)
  })
})

describe('canActivateByCertification', () => {
  it('放行 PASSED 与存量豁免的 NOT_REQUIRED，与后端 requireCurrent 对齐', () => {
    expect(canActivateByCertification('PASSED')).toBe(true)
    expect(canActivateByCertification('NOT_REQUIRED')).toBe(true)
  })

  it('拦住失败、过期与配置漂移', () => {
    expect(canActivateByCertification('FAILED')).toBe(false)
    expect(canActivateByCertification('EXPIRED')).toBe(false)
    expect(canActivateByCertification('STALE')).toBe(false)
    expect(canActivateByCertification('UNKNOWN')).toBe(false)
  })
})
