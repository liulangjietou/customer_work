import { request } from './request'

// 评测 API，与 admin-server /api/eval/** 契约对应。
// 数据存在客服端库、评测也跑在客服端（那里才有真实的 orchestrator 与模型链），
// 后台只做触发与展示。

/** 评测类型：意图路由（离线确定性）/ 回复质量（LLM-as-Judge，有 token 成本）。 */
export type EvalTypeCode = 'INTENT' | 'QUALITY'

/** 触发来源。 */
export type EvalTriggerCode = 'MANUAL' | 'SCHEDULED' | 'API'

/** 对比结论：首次运行 / 变好 / 变差 / 持平。 */
export type EvalVerdict = 'FIRST_RUN' | 'IMPROVED' | 'REGRESSED' | 'UNCHANGED'

export interface EvalRun {
  runId: string
  evalType: EvalTypeCode
  total: number
  passed: number
  /** 主指标，已归一化到 0-1：INTENT=准确率，QUALITY=平均分/5。 */
  primaryMetric: number
  /** 次指标，已归一化到 0-1：INTENT=快车道覆盖率，QUALITY=通过率。 */
  secondaryMetric: number
  /** 失败用例 ID，版本间回归识别的依据。 */
  failedCaseIds: string[]
  /** 失败明细（人读，含输入与实际/期望值）。 */
  failures: string[]
  /** 该类型的完整原始指标，归一化不丢信息。 */
  metrics: Record<string, number>
  trigger: EvalTriggerCode
  /** 评测集规模：与基线不同则两次指标不可直接比。 */
  datasetSize: number
  remark: string | null
  createdAtMs: number
}

export interface EvalComparison {
  current: EvalRun
  /** 基线运行；首次运行时为 null。 */
  baseline: EvalRun | null
  /** 回归用例：上版通过、这版失败——会被总分上涨掩盖，要单独看。 */
  regressions: string[]
  /** 修复用例：上版失败、这版通过。 */
  fixes: string[]
  verdict: EvalVerdict
  primaryDelta: number
  secondaryDelta: number
  /** 评测集规模变了：两次指标不可直接比较。 */
  datasetChanged: boolean
}

/** 某类型最近若干次运行，时间倒序。 */
export function listRuns(type: EvalTypeCode, limit = 20) {
  return request<EvalRun[]>({ url: '/eval/runs', method: 'get', params: { type, limit } })
}

/** 单次运行详情（含失败明细与完整原始指标）。 */
export function getRun(runId: string) {
  return request<EvalRun>({ url: `/eval/runs/${runId}`, method: 'get' })
}

/** 某次运行与它上一版的对比。 */
export function getComparison(runId: string) {
  return request<EvalComparison>({ url: `/eval/runs/${runId}/comparison`, method: 'get' })
}

/**
 * 立即跑一次评测（转发到客服端执行）。
 *
 * QUALITY 逐条调模型，耗时按分钟计且有真实 token 成本，调用方要给足等待提示。
 */
export function triggerEval(type: EvalTypeCode, remark?: string) {
  return request<EvalComparison>({ url: '/eval/run', method: 'post', params: { type, remark } })
}
