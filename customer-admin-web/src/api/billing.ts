import { download, request } from './request'

// 配额与计费 API，与 admin-server /api/billing/** 契约对应。
// 配额落在客服端库（运行时要读它拦模型调用），单价与用量归集在 admin 库。

export interface TenantQuotaVO {
  tenantId: string
  period: string
  tokenLimit: number
  amountLimit: number
  exceedAction: string
  warnPercent: number
  enabled: boolean
}

export interface TenantQuotaSaveRequest {
  tenantId: string
  period: string
  tokenLimit?: number
  amountLimit?: number
  exceedAction?: string
  warnPercent?: number
  enabled?: boolean
}

export interface ModelPriceVO {
  id: number
  provider: string
  modelName: string
  /** 单价口径是「元/百万 token」，与各厂商官网报价一致。 */
  inputPrice: number
  outputPrice: number
  cachedPrice: number
  currency: string
  effectiveFrom: string
  remark: string | null
}

export interface UsageAggregate {
  tenantId: string
  provider: string | null
  modelName: string | null
  callCount: number
  inputTokens: number
  outputTokens: number
  cachedTokens: number
  totalTokens: number
  amount: number
}

export interface CostForecastVO {
  tenantId: string
  period: string
  periodKey: string
  periodStart: string
  asOfDate: string
  elapsedDays: number
  totalDays: number
  usedAmount: number
  averageDailyAmount: number
  forecastAmount: number
  amountLimit: number
  utilizationPercent: number
  forecastExceeded: boolean
}

export interface CostAlertVO {
  id: number
  tenantId: string
  period: string
  periodKey: string
  alertType: 'BUDGET_WARNING' | 'BUDGET_EXCEEDED' | 'FORECAST_EXCEEDED'
  usedAmount: number
  limitAmount: number
  forecastAmount: number
  status: 'OPEN' | 'ACKED'
  firstSeenAt: string
  ackBy: number | null
  ackAt: string | null
}

export function listQuota(tenantId: string) {
  return request<TenantQuotaVO[]>({ url: '/billing/quota', method: 'get', params: { tenantId } })
}

export function saveQuota(data: TenantQuotaSaveRequest) {
  return request<void>({ url: '/billing/quota', method: 'post', data })
}

export function deleteQuota(tenantId: string, period: string) {
  return request<void>({ url: '/billing/quota', method: 'delete', params: { tenantId, period } })
}

export function listPrice() {
  return request<ModelPriceVO[]>({ url: '/billing/price', method: 'get' })
}

export function createPrice(data: Partial<ModelPriceVO>) {
  return request<number>({ url: '/billing/price', method: 'post', data })
}

export function deletePrice(id: number) {
  return request<void>({ url: `/billing/price/${id}`, method: 'delete' })
}

export function fetchTenantBill(params: { tenantId?: string; from: string; to: string }) {
  return request<UsageAggregate[]>({ url: '/billing/bill', method: 'get', params })
}

export function fetchPlatformOverview(params: { from: string; to: string }) {
  return request<UsageAggregate[]>({ url: '/billing/overview', method: 'get', params })
}

export function fetchCostForecast(params: { tenantId?: string; period: string; asOf?: string }) {
  return request<CostForecastVO>({ url: '/billing/forecast', method: 'get', params })
}

export function listCostAlerts(params: { tenantId?: string; status?: string; limit?: number }) {
  return request<CostAlertVO[]>({ url: '/billing/alerts', method: 'get', params })
}

export function acknowledgeCostAlert(id: number, tenantId?: string) {
  return request<void>({ url: `/billing/alerts/${id}/ack`, method: 'post', params: { tenantId } })
}

/** 真实 CSV 下载：未指定租户时，控制面导出平台总览，普通租户导出自己的账单。 */
export function exportBilling(params: { tenantId?: string; from: string; to: string }) {
  return download({ url: '/billing/export', method: 'get', params }, 'billing.csv')
}

/** 手工触发归集（补数据用，幂等：同一天重跑就是覆盖）。 */
export function triggerAggregate(date?: string) {
  return request<number>({ url: '/billing/aggregate', method: 'post', params: { date } })
}
