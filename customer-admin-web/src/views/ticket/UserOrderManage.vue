<script setup lang="ts">
import { reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance } from 'element-plus'
import { cancelOrder, getOrderDetail, modifyOrderAddress, pageOrders } from '@/api/user-order'
import { ORDER_STATUS_OPTIONS, ORDER_STATUS_TAG_TYPE, type OrderDetailVO, type OrderPageQuery, type OrderVO } from '@/types/order'

const loading = ref(false)
const list = ref<OrderVO[]>([])
const total = ref(0)
const query = reactive<OrderPageQuery>({
  userId: '',
  username: '',
  orderId: '',
  status: '',
  pageNum: 1,
  pageSize: 10,
})

async function loadList() {
  loading.value = true
  try {
    const result = await pageOrders(query)
    list.value = result.items
    total.value = result.total
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  query.pageNum = 1
  loadList()
}

function handlePageChange() {
  loadList()
}

function orderTagType(status: string) {
  return ORDER_STATUS_TAG_TYPE[status as keyof typeof ORDER_STATUS_TAG_TYPE] ?? 'info'
}

function formatTime(ms: number) {
  return new Date(ms).toLocaleString()
}

loadList()

// ---------- 详情 ----------
const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref<OrderDetailVO | null>(null)

async function openDetail(row: OrderVO) {
  detailVisible.value = true
  detailLoading.value = true
  try {
    detail.value = await getOrderDetail(row.orderId)
  } finally {
    detailLoading.value = false
  }
}

// ---------- 改址 ----------
const addressDialogVisible = ref(false)
const addressFormRef = ref<FormInstance>()
const addressTargetId = ref('')
const addressForm = reactive({ newAddress: '' })

function openAddressDialog(row: OrderVO) {
  addressTargetId.value = row.orderId
  addressForm.newAddress = row.receiverAddr || ''
  addressDialogVisible.value = true
}

async function handleAddressSubmit() {
  const valid = await addressFormRef.value?.validate().catch(() => false)
  if (!valid) {
    return
  }
  await modifyOrderAddress(addressTargetId.value, { newAddress: addressForm.newAddress })
  ElMessage.success('改址成功')
  addressDialogVisible.value = false
  await loadList()
}

// ---------- 取消订单 ----------
const cancelDialogVisible = ref(false)
const cancelFormRef = ref<FormInstance>()
const cancelTargetId = ref('')
const cancelForm = reactive({ reason: '' })

function openCancelDialog(row: OrderVO) {
  cancelTargetId.value = row.orderId
  cancelForm.reason = ''
  cancelDialogVisible.value = true
}

async function handleCancelSubmit() {
  const valid = await cancelFormRef.value?.validate().catch(() => false)
  if (!valid) {
    return
  }
  await ElMessageBox.confirm(`确认取消订单「${cancelTargetId.value}」？`, '提示', { type: 'warning' })
  await cancelOrder(cancelTargetId.value, { reason: cancelForm.reason })
  ElMessage.success('订单已取消')
  cancelDialogVisible.value = false
  await loadList()
}
</script>

<template>
  <div class="page">
    <el-card>
      <div class="toolbar">
        <el-input v-model="query.userId" placeholder="用户ID" clearable style="width: 140px" @keyup.enter="handleSearch" />
        <el-input v-model="query.username" placeholder="用户名" clearable style="width: 140px" @keyup.enter="handleSearch" />
        <el-input v-model="query.orderId" placeholder="订单号" clearable style="width: 160px" @keyup.enter="handleSearch" />
        <el-select v-model="query.status" placeholder="状态" clearable style="width: 140px" @change="handleSearch">
          <el-option v-for="status in ORDER_STATUS_OPTIONS" :key="status" :value="status" :label="status" />
        </el-select>
        <el-button v-permission="'user-order:view'" type="primary" @click="handleSearch">查询</el-button>
      </div>

      <el-table v-loading="loading" :data="list" style="width: 100%">
        <el-table-column prop="orderId" label="订单号" width="160" show-overflow-tooltip />
        <el-table-column label="用户" width="160">
          <template #default="{ row }: { row: OrderVO }">
            <div>{{ row.username || '-' }}</div>
            <div class="sub-text">{{ row.userId }}</div>
          </template>
        </el-table-column>
        <el-table-column prop="productName" label="商品" show-overflow-tooltip />
        <el-table-column prop="amount" label="金额" width="100" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }: { row: OrderVO }">
            <el-tag :type="orderTagType(row.status)" size="small">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="下单时间" width="170">
          <template #default="{ row }: { row: OrderVO }">{{ formatTime(row.createdAtMs) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }: { row: OrderVO }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
            <el-button v-permission="'user-order:edit'" link type="primary" @click="openAddressDialog(row)">改址</el-button>
            <el-button v-permission="'user-order:edit'" link type="danger" @click="openCancelDialog(row)">取消订单</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-empty v-if="!loading && list.length === 0" description="暂无订单数据" />

      <el-pagination
        v-model:current-page="query.pageNum"
        v-model:page-size="query.pageSize"
        :total="total"
        layout="total, prev, pager, next"
        style="margin-top: 16px; justify-content: flex-end"
        @current-change="handlePageChange"
      />
    </el-card>

    <el-drawer v-model="detailVisible" title="订单详情" size="480px">
      <el-descriptions v-loading="detailLoading" :column="1" border size="small">
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

    <el-dialog v-model="addressDialogVisible" title="修改收货地址" width="480px">
      <el-form ref="addressFormRef" :model="addressForm" label-width="90px">
        <el-form-item label="新地址" prop="newAddress" :rules="[{ required: true, message: '请输入新地址' }]">
          <el-input v-model="addressForm.newAddress" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="addressDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleAddressSubmit">确定</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="cancelDialogVisible" title="取消订单" width="480px">
      <el-form ref="cancelFormRef" :model="cancelForm" label-width="90px">
        <el-form-item label="取消原因" prop="reason" :rules="[{ required: true, message: '请输入取消原因' }]">
          <el-input v-model="cancelForm.reason" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="cancelDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleCancelSubmit">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  gap: 8px;
  margin-bottom: 16px;
}

.sub-text {
  font-size: 12px;
  color: #909399;
}

.logistics-trace {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-all;
  font-family: inherit;
  font-size: 13px;
}
</style>
