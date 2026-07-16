<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { fetchOrderDetail } from '@/api/order'
import type { OrderView } from '@/types/api'

const props = defineProps<{ id: string }>()
const router = useRouter()

const order = ref<OrderView | null>(null)
const loading = ref(true)

const orderId = computed(() => props.id)

// 物流轨迹按分隔符拆成多行展示（后端文案用「→」或换行拼接）
const traceLines = computed(() => {
  const trace = order.value?.logisticsTrace
  if (!trace) {
    return []
  }
  return trace
    .split(/→|\n/)
    .map((line) => line.trim())
    .filter((line) => line.length > 0)
})

async function loadDetail() {
  loading.value = true
  try {
    order.value = await fetchOrderDetail(orderId.value)
  } finally {
    loading.value = false
  }
}

onMounted(loadDetail)

function formatTime(ms: number) {
  return new Date(ms).toLocaleString('zh-CN', { hour12: false })
}

// 右上角"智能客服"：带订单号跳转聊天页，聊天页据此预填咨询文案
function goChat() {
  router.push({ path: '/chat', query: { orderId: orderId.value } })
}
</script>

<template>
  <div class="order-detail-page">
    <van-nav-bar title="订单详情" left-arrow @click-left="router.back()">
      <template #right>
        <span class="nav-service" @click="goChat">智能客服</span>
      </template>
    </van-nav-bar>

    <div class="content">
      <van-loading v-if="loading" class="loading" vertical>加载中...</van-loading>
      <template v-else-if="order">
        <van-cell-group inset title="商品信息">
          <van-cell title="商品名称" :value="order.productName" />
          <van-cell title="商品编号" :value="order.productId" />
          <van-cell title="金额" :value="`¥${order.amount}`" />
          <van-cell title="订单状态" :value="order.status" />
          <van-cell title="订单号" :value="order.orderId" />
          <van-cell title="下单时间" :value="formatTime(order.createdAtMs)" />
        </van-cell-group>

        <van-cell-group inset title="收货信息">
          <van-cell title="收货地址" :label="order.receiverAddr" />
        </van-cell-group>

        <van-cell-group inset title="物流轨迹">
          <div v-if="traceLines.length" class="trace">
            <div v-for="(line, index) in traceLines" :key="index" class="trace-line">{{ line }}</div>
          </div>
          <van-cell v-else title="暂无物流轨迹" />
        </van-cell-group>
      </template>
    </div>
  </div>
</template>

<style scoped>
.order-detail-page {
  flex: 1;
  display: flex;
  flex-direction: column;
  background: var(--cw-page-bg);
}

.nav-service {
  color: var(--cw-primary);
  font-size: 14px;
}

.content {
  flex: 1;
  overflow-y: auto;
  padding-bottom: 24px;
}

.loading {
  margin-top: 40vh;
}

.trace {
  padding: 12px 16px;
}

.trace-line {
  font-size: 13px;
  color: #646566;
  line-height: 1.8;
  position: relative;
  padding-left: 14px;
}

.trace-line::before {
  content: '';
  position: absolute;
  left: 0;
  top: 10px;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--cw-primary);
}
</style>
