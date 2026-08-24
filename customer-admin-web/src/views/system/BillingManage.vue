<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  createPrice,
  deletePrice,
  deleteQuota,
  acknowledgeCostAlert,
  exportBilling,
  fetchCostForecast,
  fetchPlatformOverview,
  fetchTenantBill,
  fetchUsageReconciliation,
  listCostAlerts,
  listPrice,
  listQuota,
  saveQuota,
  triggerAggregate,
  type CostAlertVO,
  type CostForecastVO,
  type ModelPriceVO,
  type TenantQuotaSaveRequest,
  type TenantQuotaVO,
  type UsageAggregate,
  type UsageReconciliationVO,
} from '@/api/billing'
import { fetchCurrentView, listTenantOptions, type TenantVO } from '@/api/tenant'

// 配额与单价是控制面能力；billing:view 仍允许租户管理员查看自己的账单。
// 三块内容各自独立，用 tab 分开而不是堆在一页——配额是"管上限"、单价是"管口径"、账单是"看结果"。

const PERIOD_LABELS: Record<string, string> = { DAILY: '按日', MONTHLY: '按月' }
const ACTION_LABELS: Record<string, string> = {
  BLOCK: '拦截',
  DEGRADE: '降级备用模型',
  WARN: '仅告警',
}
const ALERT_LABELS: Record<string, string> = {
  BUDGET_WARNING: '预算预警',
  BUDGET_EXCEEDED: '预算超限',
  FORECAST_EXCEEDED: '预测超限',
}

const activeTab = ref('bill')
const crossTenantAuthority = ref(false)
const currentTenantId = ref('')
const tenants = ref<TenantVO[]>([])

async function loadTenants() {
  tenants.value = await listTenantOptions()
}

// ---------- 配额 ----------

const quotaTenant = ref('')
const quotaLoading = ref(false)
const quotas = ref<TenantQuotaVO[]>([])

async function loadQuota() {
  if (!quotaTenant.value) return
  quotaLoading.value = true
  try {
    quotas.value = await listQuota(quotaTenant.value)
  } finally {
    quotaLoading.value = false
  }
}

const quotaDialogVisible = ref(false)
const quotaForm = reactive<TenantQuotaSaveRequest>({
  tenantId: '',
  period: 'MONTHLY',
  tokenLimit: 0,
  amountLimit: 0,
  exceedAction: 'BLOCK',
  warnPercent: 80,
  enabled: true,
})

function openQuotaDialog(row?: TenantQuotaVO) {
  Object.assign(quotaForm, row ?? {
    tenantId: quotaTenant.value,
    period: 'MONTHLY',
    tokenLimit: 0,
    amountLimit: 0,
    exceedAction: 'BLOCK',
    warnPercent: 80,
    enabled: true,
  })
  quotaForm.tenantId = row?.tenantId ?? quotaTenant.value
  quotaDialogVisible.value = true
}

async function submitQuota() {
  if (!quotaForm.tenantId) {
    ElMessage.warning('请先选择租户')
    return
  }
  await saveQuota(quotaForm)
  ElMessage.success('配额已保存')
  quotaDialogVisible.value = false
  await loadQuota()
}

async function removeQuota(row: TenantQuotaVO) {
  await ElMessageBox.confirm(
    `确认删除租户「${row.tenantId}」的${PERIOD_LABELS[row.period] ?? row.period}配额？删除后该周期不再限额。`,
    '删除确认',
    { type: 'warning' },
  )
  await deleteQuota(row.tenantId, row.period)
  ElMessage.success('配额已删除')
  await loadQuota()
}

// ---------- 单价 ----------

const priceLoading = ref(false)
const prices = ref<ModelPriceVO[]>([])

async function loadPrice() {
  priceLoading.value = true
  try {
    prices.value = await listPrice()
  } finally {
    priceLoading.value = false
  }
}

const priceDialogVisible = ref(false)
const priceForm = reactive<Partial<ModelPriceVO>>({
  provider: 'dashscope',
  modelName: '',
  inputPrice: 0,
  outputPrice: 0,
  cachedPrice: 0,
  currency: 'CNY',
  effectiveFrom: '',
  remark: '',
})

function openPriceDialog() {
  Object.assign(priceForm, {
    provider: 'dashscope',
    modelName: '',
    inputPrice: 0,
    outputPrice: 0,
    cachedPrice: 0,
    currency: 'CNY',
    effectiveFrom: '',
    remark: '',
  })
  priceDialogVisible.value = true
}

async function submitPrice() {
  if (!priceForm.modelName) {
    ElMessage.warning('请填写模型名')
    return
  }
  await createPrice(priceForm)
  ElMessage.success('单价已新增')
  priceDialogVisible.value = false
  await loadPrice()
}

async function removePrice(row: ModelPriceVO) {
  await ElMessageBox.confirm(
    `确认删除 ${row.provider}/${row.modelName} 自 ${row.effectiveFrom} 起的单价？` +
      '历史账单已按当时价格结算落库，删除不影响已出的账。',
    '删除确认',
    { type: 'warning' },
  )
  await deletePrice(row.id)
  ElMessage.success('单价已删除')
  await loadPrice()
}

// ---------- 账单 ----------

const billLoading = ref(false)
const billRange = ref<[string, string]>(['', ''])
const billTenant = ref('')
const billRows = ref<UsageAggregate[]>([])
const overviewRows = ref<UsageAggregate[]>([])
const reconciliationRows = ref<UsageReconciliationVO[]>([])

function defaultRange(): [string, string] {
  const now = new Date()
  const first = new Date(now.getFullYear(), now.getMonth(), 1)
  const fmt = (d: Date) => d.toISOString().slice(0, 10)
  return [fmt(first), fmt(now)]
}

async function loadBill() {
  const [from, to] = billRange.value
  if (!from || !to) {
    ElMessage.warning('请选择日期区间')
    return
  }
  billLoading.value = true
  try {
    if (!crossTenantAuthority.value) {
      const [bill, reconciliation] = await Promise.all([
        fetchTenantBill({ from, to }),
        canReconcile(from, to) ? fetchUsageReconciliation({ from, to }) : Promise.resolve([]),
      ])
      billRows.value = bill
      reconciliationRows.value = reconciliation
      overviewRows.value = []
    } else if (billTenant.value) {
      const [bill, reconciliation] = await Promise.all([
        fetchTenantBill({ tenantId: billTenant.value, from, to }),
        canReconcile(from, to)
          ? fetchUsageReconciliation({ tenantId: billTenant.value, from, to })
          : Promise.resolve([]),
      ])
      billRows.value = bill
      reconciliationRows.value = reconciliation
      overviewRows.value = []
    } else {
      overviewRows.value = await fetchPlatformOverview({ from, to })
      billRows.value = []
      reconciliationRows.value = []
    }
  } finally {
    billLoading.value = false
  }
}

function canReconcile(from: string, to: string) {
  const days = Math.floor((new Date(to).getTime() - new Date(from).getTime()) / (24 * 60 * 60 * 1000)) + 1
  return days > 0 && days <= 31
}

async function handleAggregate() {
  await ElMessageBox.confirm(
    '将重新归集最近的用量数据（幂等，重复执行只是覆盖）。用于补数据或验证配置。',
    '手工归集',
    { type: 'info' },
  )
  const count = await triggerAggregate()
  ElMessage.success(`归集完成，写入 ${count} 条`)
  await loadBill()
}

async function handleExport() {
  const [from, to] = billRange.value
  if (!from || !to) {
    ElMessage.warning('请选择日期区间')
    return
  }
  await exportBilling({
    tenantId: billTenant.value || undefined,
    from,
    to,
  })
}

// ---------- 成本预测与告警 ----------

const costLoading = ref(false)
const costTenant = ref('')
const forecastPeriod = ref('MONTHLY')
const alertStatus = ref('OPEN')
const forecast = ref<CostForecastVO | null>(null)
const alerts = ref<CostAlertVO[]>([])

async function loadCost() {
  costLoading.value = true
  try {
    const tenantId = costTenant.value || undefined
    const alertPromise = listCostAlerts({ tenantId, status: alertStatus.value || undefined, limit: 200 })
    const canForecast = Boolean(costTenant.value) || !crossTenantAuthority.value
    const forecastPromise = canForecast
      ? fetchCostForecast({ tenantId, period: forecastPeriod.value })
      : Promise.resolve(null)
    const [alertRows, forecastResult] = await Promise.all([alertPromise, forecastPromise])
    alerts.value = alertRows
    forecast.value = forecastResult
  } finally {
    costLoading.value = false
  }
}

async function handleAcknowledge(row: CostAlertVO) {
  await acknowledgeCostAlert(row.id, row.tenantId)
  ElMessage.success('告警已确认')
  await loadCost()
}

function formatMoney(value: number | null | undefined) {
  return Number(value ?? 0).toFixed(4)
}

function formatExactAmount(value: number | null | undefined) {
  return Number(value ?? 0).toFixed(8)
}

function reconciliationTagType(status: UsageReconciliationVO['status']) {
  if (status === 'MATCHED') return 'success'
  if (status === 'INCOMPLETE' || status === 'STALE') return 'warning'
  return 'danger'
}

function alertTagType(type: CostAlertVO['alertType']) {
  return type === 'BUDGET_WARNING' ? 'warning' : 'danger'
}

onMounted(async () => {
  billRange.value = defaultRange()
  const view = await fetchCurrentView()
  crossTenantAuthority.value = view.crossTenantAuthority === true
  currentTenantId.value = view.effectiveTenantId ?? view.userTenantId ?? ''
  billTenant.value = crossTenantAuthority.value ? '' : currentTenantId.value
  costTenant.value = crossTenantAuthority.value ? '' : currentTenantId.value
  if (crossTenantAuthority.value) {
    activeTab.value = 'quota'
    await loadTenants()
    await Promise.all([loadPrice(), loadBill(), loadCost()])
    return
  }
  await Promise.all([loadBill(), loadCost()])
})
</script>

<template>
  <div class="page">
    <el-card>
      <el-tabs v-model="activeTab">
        <!-- 配额 -->
        <el-tab-pane v-if="crossTenantAuthority" label="租户配额" name="quota">
          <div class="toolbar">
            <el-select
              v-model="quotaTenant"
              placeholder="选择租户"
              style="width: 260px"
              filterable
              @change="loadQuota"
            >
              <el-option
                v-for="t in tenants"
                :key="t.tenantCode"
                :label="`${t.tenantName}（${t.tenantCode}）`"
                :value="t.tenantCode"
              />
            </el-select>
            <el-button
              v-permission="'billing:quota-edit'"
              type="primary"
              :disabled="!quotaTenant"
              @click="openQuotaDialog()"
            >
              新增配额
            </el-button>
          </div>

          <el-table v-loading="quotaLoading" :data="quotas" style="width: 100%">
            <el-table-column label="周期" width="100">
              <template #default="{ row }">{{ PERIOD_LABELS[row.period] ?? row.period }}</template>
            </el-table-column>
            <el-table-column label="token 上限" width="160">
              <template #default="{ row }">
                {{ row.tokenLimit > 0 ? row.tokenLimit.toLocaleString() : '不限' }}
              </template>
            </el-table-column>
            <el-table-column label="金额上限（元）" width="150">
              <template #default="{ row }">
                {{ row.amountLimit > 0 ? row.amountLimit : '不限' }}
              </template>
            </el-table-column>
            <el-table-column label="超额处置" width="150">
              <template #default="{ row }">
                <el-tag :type="row.exceedAction === 'BLOCK' ? 'danger' : 'warning'">
                  {{ ACTION_LABELS[row.exceedAction] ?? row.exceedAction }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="warnPercent" label="预警阈值(%)" width="120" />
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? '启用' : '停用' }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="150" fixed="right">
              <template #default="{ row }">
                <el-button v-permission="'billing:quota-edit'" link type="primary" @click="openQuotaDialog(row)">
                  编辑
                </el-button>
                <el-button v-permission="'billing:quota-edit'" link type="danger" @click="removeQuota(row)">
                  删除
                </el-button>
              </template>
            </el-table-column>
          </el-table>
          <div class="tip">
            实时链路只按 token 拦截；金额上限走 T+1 账单预警——实时算金额需要客服端持有单价表，
            跨库依赖不值当，而 token 本就是成本的直接驱动。
          </div>
        </el-tab-pane>

        <!-- 单价 -->
        <el-tab-pane v-if="crossTenantAuthority" label="模型单价" name="price">
          <div class="toolbar">
            <el-button v-permission="'billing:price-edit'" type="primary" @click="openPriceDialog">
              新增单价
            </el-button>
            <span class="tip">调价请新增一条生效记录，不要改旧记录——历史账单要按当时的价格算得回去。</span>
          </div>

          <el-table v-loading="priceLoading" :data="prices" style="width: 100%">
            <el-table-column prop="provider" label="厂商" width="130" />
            <el-table-column prop="modelName" label="模型" width="180" />
            <el-table-column prop="inputPrice" label="输入价（元/百万token）" width="190" />
            <el-table-column prop="outputPrice" label="输出价（元/百万token）" width="190" />
            <el-table-column prop="cachedPrice" label="缓存价（元/百万token）" width="190" />
            <el-table-column prop="effectiveFrom" label="生效时间" width="180" />
            <el-table-column prop="remark" label="备注" show-overflow-tooltip />
            <el-table-column label="操作" width="90" fixed="right">
              <template #default="{ row }">
                <el-button v-permission="'billing:price-edit'" link type="danger" @click="removePrice(row)">
                  删除
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <!-- 账单 -->
        <el-tab-pane label="账单报表" name="bill">
          <div class="toolbar">
            <el-date-picker
              v-model="billRange"
              type="daterange"
              value-format="YYYY-MM-DD"
              start-placeholder="开始日期"
              end-placeholder="结束日期"
              style="width: 260px"
            />
            <el-select
              v-if="crossTenantAuthority"
              v-model="billTenant"
              placeholder="全部租户（总览）"
              style="width: 260px"
              clearable
              filterable
            >
              <el-option
                v-for="t in tenants"
                :key="t.tenantCode"
                :label="`${t.tenantName}（${t.tenantCode}）`"
                :value="t.tenantCode"
              />
            </el-select>
            <el-tag v-else type="info">当前租户：{{ currentTenantId || 'default' }}</el-tag>
            <el-button type="primary" @click="loadBill">查询</el-button>
            <el-button v-permission="'billing:export'" @click="handleExport">
              导出 CSV
            </el-button>
            <el-button v-if="crossTenantAuthority" v-permission="'billing:aggregate'" @click="handleAggregate">
              手工归集
            </el-button>
          </div>

          <!-- 选了租户看按模型明细，没选看按租户总览 -->
          <el-table v-if="billTenant || !crossTenantAuthority" v-loading="billLoading" :data="billRows" style="width: 100%">
            <el-table-column prop="provider" label="厂商" width="130" />
            <el-table-column prop="modelName" label="模型" width="200" />
            <el-table-column prop="currency" label="币种" width="90" />
            <el-table-column prop="callCount" label="调用次数" width="120" />
            <el-table-column prop="inputTokens" label="输入 token" width="140" />
            <el-table-column prop="outputTokens" label="输出 token" width="140" />
            <el-table-column prop="cachedTokens" label="缓存 token" width="140" />
            <el-table-column prop="totalTokens" label="总 token" width="140" />
            <el-table-column label="已结算金额" width="150">
              <template #default="{ row }">{{ formatExactAmount(row.amount) }}</template>
            </el-table-column>
            <el-table-column label="完整性" width="130">
              <template #default="{ row }">
                <el-tag :type="row.pricingStatus === 'COMPLETE' ? 'success' : 'warning'">
                  {{ row.pricingStatus }}
                </el-tag>
              </template>
            </el-table-column>
          </el-table>

          <el-table v-else v-loading="billLoading" :data="overviewRows" style="width: 100%">
            <el-table-column prop="tenantId" label="租户" width="200" />
            <el-table-column prop="callCount" label="调用次数" width="120" />
            <el-table-column prop="totalTokens" label="总 token" width="160" />
            <el-table-column prop="currency" label="币种" width="90" />
            <el-table-column label="已结算金额" width="160">
              <template #default="{ row }">{{ formatExactAmount(row.amount) }}</template>
            </el-table-column>
          </el-table>

          <div class="tip">
            账单来自客服端真实 MODEL 分段，默认 T+1；每段在调用完成时按冻结价目结算，日账单只做精确求和。
            对账查询单次最多 31 天，超出时仅展示账单。
          </div>

          <template v-if="billTenant || !crossTenantAuthority">
            <h3 class="section-title">账实对账</h3>
            <el-table v-loading="billLoading" :data="reconciliationRows" style="width: 100%">
              <el-table-column prop="statDate" label="日期" width="120" />
              <el-table-column prop="currency" label="币种" width="100" />
              <el-table-column label="调用事实" width="150">
                <template #default="{ row }">{{ formatExactAmount(row.sourceAmount) }}</template>
              </el-table-column>
              <el-table-column label="日账单" width="150">
                <template #default="{ row }">{{ formatExactAmount(row.billAmount) }}</template>
              </el-table-column>
              <el-table-column label="差额" width="150">
                <template #default="{ row }">{{ formatExactAmount(row.difference) }}</template>
              </el-table-column>
              <el-table-column label="未结算分段" width="130">
                <template #default="{ row }">{{ row.sourceUnsettledSegments }} / {{ row.sourceModelSegments }}</template>
              </el-table-column>
              <el-table-column label="状态" width="120">
                <template #default="{ row }">
                  <el-tag :type="reconciliationTagType(row.status)">{{ row.status }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="reason" label="说明" min-width="280" show-overflow-tooltip />
            </el-table>
          </template>
        </el-tab-pane>

        <!-- 成本预测与告警 -->
        <el-tab-pane label="成本告警" name="cost">
          <div class="toolbar">
            <el-select
              v-if="crossTenantAuthority"
              v-model="costTenant"
              placeholder="全部租户告警"
              style="width: 260px"
              clearable
              filterable
              @change="loadCost"
            >
              <el-option
                v-for="t in tenants"
                :key="t.tenantCode"
                :label="`${t.tenantName}（${t.tenantCode}）`"
                :value="t.tenantCode"
              />
            </el-select>
            <el-tag v-else type="info">当前租户：{{ currentTenantId || 'default' }}</el-tag>
            <el-select v-model="forecastPeriod" style="width: 130px" @change="loadCost">
              <el-option label="按月预测" value="MONTHLY" />
              <el-option label="按日实际" value="DAILY" />
            </el-select>
            <el-select v-model="alertStatus" style="width: 130px" @change="loadCost">
              <el-option label="待确认" value="OPEN" />
              <el-option label="已确认" value="ACKED" />
              <el-option label="全部状态" value="" />
            </el-select>
            <el-button type="primary" @click="loadCost">刷新</el-button>
          </div>

          <el-descriptions v-if="forecast" v-loading="costLoading" :column="4" border class="forecast-card">
            <el-descriptions-item label="统计周期">{{ forecast.periodKey }}</el-descriptions-item>
            <el-descriptions-item label="已结算金额">¥ {{ formatMoney(forecast.usedAmount) }}</el-descriptions-item>
            <el-descriptions-item label="预算金额">
              {{ forecast.amountLimit > 0 ? `¥ ${formatMoney(forecast.amountLimit)}` : '未设置' }}
            </el-descriptions-item>
            <el-descriptions-item label="预算使用率">
              {{ forecast.amountLimit > 0 ? `${forecast.utilizationPercent}%` : '-' }}
            </el-descriptions-item>
            <el-descriptions-item label="日均金额">¥ {{ formatMoney(forecast.averageDailyAmount) }}</el-descriptions-item>
            <el-descriptions-item label="周期预测">¥ {{ formatMoney(forecast.forecastAmount) }}</el-descriptions-item>
            <el-descriptions-item label="统计进度">{{ forecast.elapsedDays }} / {{ forecast.totalDays }} 天</el-descriptions-item>
            <el-descriptions-item label="预测状态">
              <el-tag :type="forecast.forecastExceeded ? 'danger' : 'success'">
                {{ forecast.forecastExceeded ? '预计超限' : '预算内' }}
              </el-tag>
            </el-descriptions-item>
          </el-descriptions>
          <el-empty
            v-else-if="crossTenantAuthority && !costTenant"
            description="当前展示全部租户告警；选择一个租户后查看金额预测"
            :image-size="72"
          />

          <el-table v-loading="costLoading" :data="alerts" style="width: 100%">
            <el-table-column prop="tenantId" label="租户" width="160" />
            <el-table-column prop="periodKey" label="周期" width="120" />
            <el-table-column label="类型" width="120">
              <template #default="{ row }">
                <el-tag :type="alertTagType(row.alertType)">
                  {{ ALERT_LABELS[row.alertType] ?? row.alertType }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="已用 / 预算（元）" width="200">
              <template #default="{ row }">
                {{ formatMoney(row.usedAmount) }} / {{ formatMoney(row.limitAmount) }}
              </template>
            </el-table-column>
            <el-table-column label="预测金额（元）" width="150">
              <template #default="{ row }">{{ formatMoney(row.forecastAmount) }}</template>
            </el-table-column>
            <el-table-column prop="firstSeenAt" label="首次触发" width="180" />
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <el-tag :type="row.status === 'OPEN' ? 'danger' : 'info'">
                  {{ row.status === 'OPEN' ? '待确认' : '已确认' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="100" fixed="right">
              <template #default="{ row }">
                <el-button v-if="row.status === 'OPEN'" link type="primary" @click="handleAcknowledge(row)">
                  确认
                </el-button>
              </template>
            </el-table-column>
          </el-table>
          <div class="tip">
            告警在日用量归集事务成功提交后生成；同一租户、周期和告警类型只生成一次，重复补数不会反复通知。
          </div>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <el-dialog v-model="quotaDialogVisible" title="租户配额" width="520px">
      <el-form :model="quotaForm" label-width="130px">
        <el-form-item label="租户">
          <el-input v-model="quotaForm.tenantId" disabled />
        </el-form-item>
        <el-form-item label="周期">
          <el-select v-model="quotaForm.period" style="width: 100%">
            <el-option label="按日" value="DAILY" />
            <el-option label="按月" value="MONTHLY" />
          </el-select>
        </el-form-item>
        <el-form-item label="token 上限">
          <el-input-number v-model="quotaForm.tokenLimit!" :min="0" :step="10000" style="width: 100%" />
          <div class="form-tip">0 表示不限</div>
        </el-form-item>
        <el-form-item label="金额上限（元）">
          <el-input-number v-model="quotaForm.amountLimit!" :min="0" :precision="2" style="width: 100%" />
          <div class="form-tip">0 表示不限；金额维度走 T+1 账单预警，不参与实时拦截</div>
        </el-form-item>
        <el-form-item label="超额处置">
          <el-select v-model="quotaForm.exceedAction" style="width: 100%">
            <el-option label="拦截（拒绝后续调用）" value="BLOCK" />
            <el-option label="降级到备用模型" value="DEGRADE" />
            <el-option label="仅告警" value="WARN" />
          </el-select>
        </el-form-item>
        <el-form-item label="预警阈值(%)">
          <el-input-number v-model="quotaForm.warnPercent!" :min="0" :max="100" style="width: 100%" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="quotaForm.enabled!" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="quotaDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submitQuota">确定</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="priceDialogVisible" title="新增模型单价" width="520px">
      <el-form :model="priceForm" label-width="150px">
        <el-form-item label="厂商">
          <el-input v-model="priceForm.provider!" placeholder="如 dashscope" />
        </el-form-item>
        <el-form-item label="模型名">
          <el-input v-model="priceForm.modelName!" placeholder="如 qwen-max" />
        </el-form-item>
        <el-form-item label="输入价（元/百万）">
          <el-input-number v-model="priceForm.inputPrice!" :min="0" :precision="6" style="width: 100%" />
        </el-form-item>
        <el-form-item label="输出价（元/百万）">
          <el-input-number v-model="priceForm.outputPrice!" :min="0" :precision="6" style="width: 100%" />
        </el-form-item>
        <el-form-item label="缓存价（元/百万）">
          <el-input-number v-model="priceForm.cachedPrice!" :min="0" :precision="6" style="width: 100%" />
        </el-form-item>
        <el-form-item label="生效时间">
          <el-date-picker
            v-model="priceForm.effectiveFrom!"
            type="datetime"
            value-format="YYYY-MM-DDTHH:mm:ss"
            placeholder="留空表示立即生效"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="priceForm.remark!" type="textarea" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="priceDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submitPrice">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  gap: 12px;
  align-items: center;
  margin-bottom: 16px;
}

.tip {
  margin-top: 12px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.7;
}

.form-tip {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.forecast-card {
  margin-bottom: 20px;
}

.section-title {
  margin: 24px 0 12px;
  font-size: 15px;
}
</style>
