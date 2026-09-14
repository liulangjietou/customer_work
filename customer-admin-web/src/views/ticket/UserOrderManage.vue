<script setup lang="ts">
import { computed, h, nextTick, onScopeDispose, reactive, ref, watch } from 'vue'
import type { FormInstance } from 'element-plus'
import { cancelOrder, getOrderDetail, modifyOrderAddress, pageOrders } from '@/api/user-order'
import { ORDER_STATUS_OPTIONS, ORDER_STATUS_TAG_TYPE, type OrderDetailVO, type OrderPageQuery, type OrderVO } from '@/types/order'
import { useDict } from '@/composables/useDict'
import { usePagedList } from '@/composables/usePagedList'
import CrudLoadState from '@/components/CrudLoadState.vue'
import { getRequestErrorMessage } from '@/api/request'
import { useAuthStore } from '@/store/auth'

const auth = useAuthStore()
const canView = computed(() => !!auth.token && auth.hasPermission('user-order:view'))
const canEdit = computed(() => canView.value && auth.hasPermission('user-order:edit'))
let scopeEpoch = 0
let disposed = false

// 状态筛选项走字典（类型编码 order_status，后台"系统管理→字典管理"维护）；
// 字典未配置或接口失败时回落到本地硬编码 ORDER_STATUS_OPTIONS，页面不受影响。
const { options: orderStatusOptions } = useDict(
  'order_status',
  ORDER_STATUS_OPTIONS.map((s) => ({ value: s, label: s })),
)

const { loading, loadError, list, total, query, loadList, handleSearch } = usePagedList<
  OrderVO, OrderPageQuery
>({
  initQuery: () => ({ userId: '', username: '', orderId: '', status: '', pageNum: 1, pageSize: 10 }),
  page: async (params) => {
    const epoch = scopeEpoch
    if (!canView.value) return { list: [], total: 0 }
    const result = await pageOrders(params)
    if (disposed || epoch !== scopeEpoch) return { list: [], total: 0 }
    return { list: result.items, total: result.total }
  },
})

function orderTagType(status: string) {
  return ORDER_STATUS_TAG_TYPE[status as keyof typeof ORDER_STATUS_TAG_TYPE] ?? 'info'
}

function formatTime(ms: number) {
  return new Date(ms).toLocaleString('zh-CN', { hour12: false })
}

// ---------- 详情 ----------
const detailVisible = ref(false)
const detailLoading = ref(false)
const detailError = ref('')
const detailTargetId = ref('')
const detail = ref<OrderDetailVO | null>(null)
let detailRequest = 0

function openDetail(row: OrderVO) {
  if (!canView.value) return
  detailTargetId.value = row.orderId
  detailVisible.value = true
  void loadDetail()
}

async function loadDetail() {
  if (!canView.value || !detailVisible.value) return
  const request = ++detailRequest
  const epoch = scopeEpoch
  const current = () => !disposed && epoch === scopeEpoch && request === detailRequest
  detail.value = null
  detailError.value = ''
  detailLoading.value = true
  try {
    const result = await getOrderDetail(detailTargetId.value)
    if (current()) detail.value = result
  } catch (error) {
    if (current()) detailError.value = getRequestErrorMessage(error, '暂时无法获取订单详情，请重试。')
  } finally {
    if (current()) detailLoading.value = false
  }
}

watch(detailVisible, (visible) => {
  if (visible) return
  detailRequest += 1
  detail.value = null
  detailTargetId.value = ''
  detailError.value = ''
  detailLoading.value = false
}, { flush: 'sync' })

// ---------- 改址 ----------
const addressDialogVisible = ref(false)
const addressFormRef = ref<FormInstance>()
const addressTargetId = ref('')
const addressForm = reactive({ newAddress: '' })
const addressSubmitting = ref(false)
const addressError = ref('')
let addressRequest = 0

function openAddressDialog(row: OrderVO) {
  if (!canEdit.value) return
  addressRequest += 1
  addressTargetId.value = row.orderId
  addressForm.newAddress = row.receiverAddr || ''
  addressError.value = ''
  addressDialogVisible.value = true
  void nextTick(() => addressFormRef.value?.clearValidate())
}

async function handleAddressSubmit() {
  if (!canEdit.value || addressSubmitting.value || !addressDialogVisible.value) return
  const request = ++addressRequest
  const epoch = scopeEpoch
  const targetId = addressTargetId.value
  const current = () => !disposed && epoch === scopeEpoch && request === addressRequest
  addressSubmitting.value = true
  addressError.value = ''
  try {
    const valid = await addressFormRef.value?.validate().catch(() => false)
    if (!valid || !current()) return
    await modifyOrderAddress(targetId, { newAddress: addressForm.newAddress })
    if (!current()) return
    ElMessage.success('改址成功')
    addressDialogVisible.value = false
    // 写入已结束，列表刷新独立处理错误；不能让迟到刷新锁住下一次打开的表单。
    void loadList()
  } catch (error) {
    if (current()) addressError.value = getRequestErrorMessage(error, '改址未完成，当前地址已保留，请重试。')
  } finally {
    if (current()) addressSubmitting.value = false
  }
}

// ---------- 取消订单 ----------
const cancelDialogVisible = ref(false)
const cancelFormRef = ref<FormInstance>()
const cancelTargetId = ref('')
const cancelForm = reactive({ reason: '' })
const cancelSubmitting = ref(false)
const cancelError = ref('')
let cancelRequest = 0
let closeCancelConfirmation: (() => void) | null = null

function openCancelDialog(row: OrderVO) {
  if (!canEdit.value) return
  cancelRequest += 1
  cancelTargetId.value = row.orderId
  cancelForm.reason = ''
  cancelError.value = ''
  cancelDialogVisible.value = true
  void nextTick(() => cancelFormRef.value?.clearValidate())
}

async function handleCancelSubmit() {
  if (!canEdit.value || cancelSubmitting.value || !cancelDialogVisible.value) return
  const request = ++cancelRequest
  const epoch = scopeEpoch
  const targetId = cancelTargetId.value
  const current = () => !disposed && epoch === scopeEpoch && request === cancelRequest
  cancelSubmitting.value = true
  cancelError.value = ''
  try {
    const valid = await cancelFormRef.value?.validate().catch(() => false)
    if (!valid || !current()) return
    const confirmed = await ElMessageBox.confirm(({ close }) => {
      // 保存这个确认框自己的关闭动作，权限变化时不能关闭其他业务的消息框。
      closeCancelConfirmation = close
      return h('span', `确认取消订单「${targetId}」？`)
    }, '提示', { type: 'warning' }).then(() => true, () => false)
    if (!current()) return
    closeCancelConfirmation = null
    if (!confirmed) return
    await cancelOrder(targetId, { reason: cancelForm.reason })
    if (!current()) return
    ElMessage.success('订单已取消')
    cancelDialogVisible.value = false
    void loadList()
  } catch (error) {
    if (current()) cancelError.value = getRequestErrorMessage(error, '取消未完成，当前原因已保留，请重试。')
  } finally {
    if (current()) cancelSubmitting.value = false
  }
}

/** 身份或操作权限变化时撤销本页旧上下文，迟到响应不能恢复旧客户数据或确认操作。 */
function invalidateScope() {
  scopeEpoch += 1
  detailVisible.value = false
  addressRequest += 1
  cancelRequest += 1
  addressDialogVisible.value = false
  cancelDialogVisible.value = false
  addressSubmitting.value = false
  cancelSubmitting.value = false
  addressForm.newAddress = ''
  cancelForm.reason = ''
  addressTargetId.value = ''
  cancelTargetId.value = ''
  addressError.value = ''
  cancelError.value = ''
  closeCancelConfirmation?.()
  closeCancelConfirmation = null
  list.value = []
  total.value = 0
  loadError.value = null
}

watch(() => [auth.token, canView.value, canEdit.value], () => {
  invalidateScope()
  void loadList()
}, { flush: 'sync' })

onScopeDispose(() => {
  disposed = true
  invalidateScope()
})

void loadList()
</script>

<template>
  <div class="page order-management">
    <el-card>
      <div class="order-list-heading">
        <div><h2>订单记录</h2><p>查询履约信息，核对客户的订单与收货安排。</p></div>
        <el-tag v-if="canView && !canEdit" type="info" effect="plain">只读访问</el-tag>
      </div>
      <el-alert v-if="!canView" title="暂无订单查看权限" type="info" :closable="false" show-icon />
      <template v-else>
        <div class="toolbar">
          <el-input v-model="query.userId" aria-label="用户ID" placeholder="用户ID" clearable @keyup.enter="handleSearch" />
          <el-input v-model="query.username" aria-label="用户名" placeholder="用户名" clearable @keyup.enter="handleSearch" />
          <el-input v-model="query.orderId" aria-label="订单号" placeholder="订单号" clearable @keyup.enter="handleSearch" />
          <el-select v-model="query.status" aria-label="订单状态" placeholder="状态" clearable @change="handleSearch">
            <el-option v-for="opt in orderStatusOptions" :key="opt.value" :value="opt.value" :label="opt.label" />
          </el-select>
          <el-button type="primary" @click="handleSearch">查询</el-button>
        </div>

        <CrudLoadState :error="loadError" :has-stale-data="list.length > 0" :loading="loading" @retry="loadList" />
        <el-table v-loading="loading" :data="list" class="data-table" :empty-text="loading ? '正在加载订单…' : loadError ? '暂时无法加载订单' : '暂无符合条件的订单'">
          <el-table-column prop="orderId" label="订单号" width="160" show-overflow-tooltip class-name="primary-column" />
          <el-table-column label="用户" width="160">
            <template #default="{ row }: { row: OrderVO }">
              <div>{{ row.username || '-' }}</div>
              <div class="sub-text">{{ row.userId }}</div>
            </template>
          </el-table-column>
          <el-table-column prop="productName" label="商品" min-width="160" show-overflow-tooltip />
          <el-table-column prop="amount" label="金额" width="100" />
          <el-table-column label="状态" width="100" align="center">
            <template #default="{ row }: { row: OrderVO }">
              <el-tag :type="orderTagType(row.status)" size="small">{{ row.status }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="下单时间" width="170">
            <template #default="{ row }: { row: OrderVO }">{{ formatTime(row.createdAtMs) }}</template>
          </el-table-column>
          <el-table-column label="操作" :width="canEdit ? 200 : 80" fixed="right">
            <template #default="{ row }: { row: OrderVO }">
              <el-button link type="primary" @click="openDetail(row)">详情</el-button>
              <el-button v-if="canEdit" link type="primary" @click="openAddressDialog(row)">改址</el-button>
              <el-button v-if="canEdit" link type="danger" @click="openCancelDialog(row)">取消订单</el-button>
            </template>
          </el-table-column>
        </el-table>

        <el-pagination
          v-model:current-page="query.pageNum"
          v-model:page-size="query.pageSize"
          :total="total"
          layout="total, prev, pager, next"
          class="pagination"
          @current-change="loadList"
        />
      </template>
    </el-card>

    <el-drawer v-model="detailVisible" title="订单详情" size="480px" class="order-detail-drawer">
      <el-skeleton v-if="detailLoading" :rows="8" animated aria-label="正在加载订单详情" />
      <el-alert v-else-if="detailError" title="订单详情加载失败" type="error" :closable="false" show-icon>
        <p>{{ detailError }}</p>
        <el-button type="primary" plain @click="loadDetail">重新加载</el-button>
      </el-alert>
      <el-descriptions v-else-if="detail" class="order-details" :column="1" :label-width="88" border size="small">
        <template v-if="detail">
          <el-descriptions-item label="订单号">{{ detail.orderId }}</el-descriptions-item>
          <el-descriptions-item label="用户ID">{{ detail.userId }}</el-descriptions-item>
          <el-descriptions-item label="用户名">{{ detail.username || '-' }}</el-descriptions-item>
          <el-descriptions-item label="商品ID">{{ detail.productId }}</el-descriptions-item>
          <el-descriptions-item label="商品名称">{{ detail.productName }}</el-descriptions-item>
          <el-descriptions-item label="金额">{{ detail.amount }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="orderTagType(detail.status)" size="small">{{ detail.status }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="收货地址">{{ detail.receiverAddr || '-' }}</el-descriptions-item>
          <el-descriptions-item label="下单时间">{{ formatTime(detail.createdAtMs) }}</el-descriptions-item>
          <el-descriptions-item label="物流轨迹">
            <pre class="logistics-trace">{{ detail.logisticsTrace || '暂无物流信息' }}</pre>
          </el-descriptions-item>
        </template>
      </el-descriptions>
    </el-drawer>

    <el-dialog v-model="addressDialogVisible" title="修改收货地址" width="480px"
      :show-close="!addressSubmitting" :close-on-click-modal="!addressSubmitting" :close-on-press-escape="!addressSubmitting">
      <p class="action-order">订单：{{ addressTargetId }}</p>
      <el-alert v-if="addressError" :title="addressError" class="action-error" type="error" :closable="false" show-icon />
      <el-form ref="addressFormRef" :model="addressForm" :disabled="addressSubmitting" label-width="90px">
        <el-form-item label="新地址" prop="newAddress" :rules="[{ required: true, message: '请输入新地址' }]">
          <el-input v-model="addressForm.newAddress" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="addressSubmitting" @click="addressDialogVisible = false">取消</el-button>
        <el-button class="cw-final-action" type="primary" :loading="addressSubmitting" :disabled="addressSubmitting || !canEdit" @click="handleAddressSubmit">确认改址</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="cancelDialogVisible" title="取消订单" width="480px"
      :show-close="!cancelSubmitting" :close-on-click-modal="!cancelSubmitting" :close-on-press-escape="!cancelSubmitting">
      <p class="action-order">订单：{{ cancelTargetId }}</p>
      <el-alert v-if="cancelError" :title="cancelError" class="action-error" type="error" :closable="false" show-icon />
      <el-form ref="cancelFormRef" :model="cancelForm" :disabled="cancelSubmitting" label-width="90px">
        <el-form-item label="取消原因" prop="reason" :rules="[{ required: true, message: '请输入取消原因' }]">
          <el-input v-model="cancelForm.reason" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="cancelSubmitting" @click="cancelDialogVisible = false">取消</el-button>
        <el-button class="cw-final-action" type="danger" :loading="cancelSubmitting" :disabled="cancelSubmitting || !canEdit" @click="handleCancelSubmit">确认取消</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.order-list-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 20px;
}

.order-list-heading h2 {
  margin: 0 0 6px;
  font-size: 17px;
  color: var(--cw-text);
}

.order-list-heading p {
  margin: 0;
  color: var(--cw-text-muted);
  font-size: 13px;
  line-height: 1.6;
}

.order-list-heading .el-tag {
  flex-shrink: 0;
}

.action-order {
  margin: 0 0 18px;
  color: var(--cw-text-muted);
  overflow-wrap: anywhere;
}

.action-error {
  margin-bottom: 18px;
}

.order-details :deep(.el-descriptions__table) {
  table-layout: fixed;
}

.order-details :deep(.el-descriptions__content) {
  overflow-wrap: anywhere;
}
.toolbar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 16px;
}

.toolbar > .el-input {
  width: 160px;
}

.toolbar > .el-select {
  width: 140px;
}

.data-table {
  min-width: 0;
  max-width: 100%;
}

:deep(.primary-column .cell) {
  color: var(--cw-text);
  font-weight: 650;
}

.sub-text {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.logistics-trace {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-all;
  font-family: inherit;
  font-size: 13px;
}

@media (max-width: 640px) {
  .toolbar {
    display: grid;
    grid-template-columns: 1fr 1fr;
  }

  .toolbar > .el-input,
  .toolbar > .el-select {
    width: 100%;
    min-width: 0;
  }

  .toolbar > .el-button {
    grid-column: 1 / -1;
  }

  .order-management :deep(.el-button) {
    min-height: 44px;
  }
}
</style>
