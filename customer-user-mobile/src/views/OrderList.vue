<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { fetchOrders } from '@/api/order'
import type { OrderView } from '@/types/api'
import AppTabbar from '@/components/AppTabbar.vue'

const router = useRouter()

const orders = ref<OrderView[]>([])
const loading = ref(true)

async function loadOrders() {
  loading.value = true
  try {
    orders.value = await fetchOrders()
  } finally {
    loading.value = false
  }
}

onMounted(loadOrders)

function goDetail(orderId: string) {
  router.push(`/orders/${orderId}`)
}

// 订单状态标签色：已发货/已签收偏成功，待发货偏警示，已取消/已退款置灰
function statusTagType(status: string): 'primary' | 'success' | 'warning' | 'danger' | 'default' {
  if (status.includes('签收') || status.includes('已发货')) {
    return 'success'
  }
  if (status.includes('待')) {
    return 'warning'
  }
  if (status.includes('取消') || status.includes('退')) {
    return 'default'
  }
  return 'primary'
}

function formatTime(ms: number) {
  return new Date(ms).toLocaleString('zh-CN', { hour12: false })
}
</script>

<template>
  <div class="order-list-page">
    <van-nav-bar title="我的订单" />
    <div class="content">
      <van-loading v-if="loading" class="loading" vertical>加载中...</van-loading>
      <template v-else>
        <van-cell-group v-if="orders.length" inset>
          <van-cell v-for="order in orders" :key="order.orderId" clickable @click="goDetail(order.orderId)">
            <template #title>
              <span class="order-title">{{ order.productName }}</span>
            </template>
            <template #label>
              <div class="order-meta">单号 {{ order.orderId }}</div>
              <div class="order-meta">{{ formatTime(order.createdAtMs) }}</div>
            </template>
            <template #value>
              <div class="order-value">
                <div class="order-amount">¥{{ order.amount }}</div>
                <van-tag :type="statusTagType(order.status)">{{ order.status }}</van-tag>
              </div>
            </template>
          </van-cell>
        </van-cell-group>
        <van-empty v-else description="暂无订单" />
      </template>
    </div>

    <AppTabbar />
  </div>
</template>

<style scoped>
.order-list-page {
  flex: 1;
  display: flex;
  flex-direction: column;
  background: var(--cw-page-bg);
}

.content {
  flex: 1;
  overflow-y: auto;
  padding-top: 12px;
  /* 底部预留 tabbar 高度，防止最后一条被固定导航遮挡 */
  padding-bottom: 66px;
}

.loading {
  margin-top: 40vh;
}

.order-title {
  font-weight: 500;
}

.order-meta {
  font-size: 12px;
  color: var(--cw-text-secondary);
  margin-top: 4px;
}

.order-value {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 6px;
}

.order-amount {
  font-weight: 600;
  color: var(--cw-text-primary);
}
</style>
