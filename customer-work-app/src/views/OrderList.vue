<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { fetchOrders } from '@/api/order'
import type { OrderView } from '@/types/api'
import AppTabbar from '@/components/AppTabbar.vue'

const router = useRouter()

const orders = ref<OrderView[]>([])
const initialLoading = ref(true)
const loadError = ref(false)
const refreshing = ref(false)
let requestGeneration = 0

const listSummary = computed(() => {
  if (initialLoading.value) {
    return '同步中'
  }
  if (loadError.value) {
    return '获取失败'
  }
  return `${orders.value.length} 笔`
})

async function loadOrders() {
  const generation = ++requestGeneration
  initialLoading.value = true
  loadError.value = false
  try {
    const result = await fetchOrders()
    if (generation === requestGeneration) {
      orders.value = result
    }
  } catch {
    if (generation === requestGeneration) {
      loadError.value = true
    }
  } finally {
    if (generation === requestGeneration) {
      initialLoading.value = false
    }
  }
}

async function onRefresh() {
  const generation = ++requestGeneration
  try {
    const result = await fetchOrders()
    if (generation === requestGeneration) {
      orders.value = result
      loadError.value = false
    }
  } catch {
    // 已有订单仍可继续使用；只有无可用内容时才切换到整页错误态。
    if (generation === requestGeneration && orders.value.length === 0) {
      loadError.value = true
    }
  } finally {
    if (generation === requestGeneration) {
      refreshing.value = false
      initialLoading.value = false
    }
  }
}

onMounted(loadOrders)

function goDetail(orderId: string) {
  router.push(`/orders/${orderId}`)
}

function goMessages() {
  router.push('/messages')
}

function statusClass(status: string) {
  if (status.includes('签收') || status.includes('已发货')) {
    return 'status-pill--success'
  }
  if (status.includes('待')) {
    return 'status-pill--warning'
  }
  if (status.includes('取消') || status.includes('退')) {
    return 'status-pill--muted'
  }
  return 'status-pill--primary'
}

function formatTime(ms: number) {
  const date = new Date(ms)
  if (Number.isNaN(date.getTime())) {
    return '—'
  }
  return date.toLocaleString('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  })
}
</script>

<template>
  <div class="order-list-page">
    <header class="root-header">
      <div>
        <span class="header-eyebrow">服务记录</span>
        <h1>我的订单</h1>
      </div>
    </header>

    <van-pull-refresh v-model="refreshing" class="refresh-area" :disabled="initialLoading" @refresh="onRefresh">
      <main class="page-content">
        <section aria-labelledby="order-list-heading">
          <div class="section-heading">
            <h2 id="order-list-heading">全部订单</h2>
            <span>{{ listSummary }}</span>
          </div>

          <div v-if="initialLoading" class="skeleton-list" aria-label="订单加载中" aria-busy="true">
            <div v-for="index in 3" :key="index" class="skeleton-card">
              <span class="skeleton-top"></span>
              <span class="skeleton-body">
                <i class="skeleton-package"></i>
                <i class="skeleton-lines"></i>
                <i class="skeleton-amount"></i>
              </span>
              <span class="skeleton-bottom"></span>
            </div>
          </div>

          <div v-else-if="loadError && orders.length === 0" class="state-panel" role="alert">
            <span class="state-visual"><van-icon name="orders-o" size="34" /></span>
            <h3>订单加载失败</h3>
            <p>暂时无法获取订单，请稍后重试。</p>
            <button class="primary-button" type="button" @click="loadOrders">重新加载</button>
          </div>

          <div v-else-if="orders.length === 0" class="state-panel">
            <span class="state-visual"><van-icon name="orders-o" size="34" /></span>
            <h3>暂无订单</h3>
            <p>账号下的订单会统一显示在这里。</p>
            <button class="secondary-button" type="button" @click="goMessages">联系客服</button>
          </div>

          <div v-else class="order-list">
            <button
              v-for="order in orders"
              :key="order.orderId"
              class="order-card"
              type="button"
              @click="goDetail(order.orderId)"
            >
              <span class="order-card-top">
                <span class="order-number">订单号 {{ order.orderId }}</span>
                <span class="status-pill" :class="statusClass(order.status)">{{ order.status }}</span>
              </span>
              <span class="order-product">
                <span class="package-symbol" aria-hidden="true"><van-icon name="gift-o" size="25" /></span>
                <span class="product-copy">
                  <strong class="order-product-name">{{ order.productName }}</strong>
                  <span class="order-product-id">{{ order.productId }}</span>
                </span>
                <strong class="order-amount">¥{{ order.amount }}</strong>
              </span>
              <span class="order-card-bottom">
                <span class="order-date">{{ formatTime(order.createdAtMs) }}</span>
                <span class="detail-link">查看详情<van-icon name="arrow" /></span>
              </span>
            </button>
          </div>
        </section>
      </main>
    </van-pull-refresh>

    <AppTabbar />
  </div>
</template>

<style scoped>
.order-list-page {
  --page-ink: var(--cw-ink, #142033);
  --page-primary: var(--cw-primary, #316cff);
  --page-primary-dark: var(--cw-primary-dark, #1748bf);
  --page-primary-soft: var(--cw-primary-soft, #eaf1ff);
  --page-success-soft: var(--cw-success-soft, #e5f8f3);
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  background: var(--cw-page-bg, #f4f7fb);
  color: var(--page-ink);
}

button {
  font: inherit;
}

button:focus-visible {
  outline: 3px solid rgba(49, 108, 255, 0.26);
  outline-offset: 2px;
}

.root-header {
  min-height: calc(77px + env(safe-area-inset-top));
  display: flex;
  align-items: center;
  padding: calc(14px + env(safe-area-inset-top)) 17px 10px;
  border-bottom: 1px solid rgba(230, 235, 242, 0.76);
  background: rgba(255, 255, 255, 0.96);
}

.header-eyebrow {
  display: block;
  margin-bottom: 2px;
  color: #8290a5;
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.12em;
}

.root-header h1 {
  margin: 0;
  font-size: 23px;
  line-height: 1.2;
  letter-spacing: -0.025em;
}

.refresh-area {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
}

.page-content {
  min-height: 100%;
  padding: 14px 14px var(--cw-tabbar-space, 100px);
}

.section-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 0 3px 11px;
}

.section-heading h2 {
  margin: 0;
  font-size: 16px;
  letter-spacing: -0.02em;
}

.section-heading span {
  color: #7d899c;
  font-family: 'DIN Alternate', 'SF Pro Display', 'PingFang SC', sans-serif;
  font-size: 11px;
}

.order-list,
.skeleton-list {
  display: grid;
  gap: 10px;
}

.order-card,
.skeleton-card {
  width: 100%;
  padding: 15px;
  border: 1px solid rgba(230, 235, 242, 0.9);
  border-radius: 18px;
  background: #fff;
  box-shadow: 0 7px 22px rgba(37, 55, 85, 0.055);
}

.order-card {
  color: inherit;
  text-align: left;
  cursor: pointer;
  transition: transform 150ms ease, box-shadow 150ms ease;
}

.order-card:active {
  transform: scale(0.988);
  box-shadow: 0 3px 12px rgba(37, 55, 85, 0.06);
}

.order-card-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding-bottom: 12px;
  border-bottom: 1px solid #eef1f5;
}

.order-number {
  min-width: 0;
  overflow: hidden;
  color: #59687c;
  font-family: 'DIN Alternate', 'SF Pro Display', 'PingFang SC', sans-serif;
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.status-pill {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  min-height: 24px;
  padding: 4px 8px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 700;
  white-space: nowrap;
}

.status-pill::before {
  content: '';
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: currentColor;
}

.status-pill--primary {
  background: var(--page-primary-soft);
  color: var(--page-primary-dark);
}

.status-pill--success {
  background: var(--page-success-soft);
  color: #117d6f;
}

.status-pill--warning {
  background: #fff4df;
  color: #a86300;
}

.status-pill--muted {
  background: #f0f2f5;
  color: #59687c;
}

.order-product {
  display: grid;
  grid-template-columns: 50px minmax(0, 1fr) auto;
  align-items: center;
  gap: 12px;
  padding-top: 14px;
}

.package-symbol {
  width: 50px;
  height: 50px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 16px;
  background: #eef3fb;
  color: #657a9c;
}

.product-copy {
  min-width: 0;
}

.order-product-name {
  display: block;
  overflow: hidden;
  color: var(--page-ink);
  font-size: 14px;
  font-weight: 700;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.order-product-id {
  display: block;
  margin-top: 6px;
  color: #657388;
  font-family: 'DIN Alternate', 'SF Pro Display', 'PingFang SC', sans-serif;
  font-size: 12px;
}

.order-amount {
  color: var(--page-ink);
  font-family: 'DIN Alternate', 'SF Pro Display', 'PingFang SC', sans-serif;
  font-size: 16px;
  font-weight: 800;
  white-space: nowrap;
}

.order-card-bottom {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 13px;
}

.order-date {
  overflow: hidden;
  color: #59687c;
  font-family: 'DIN Alternate', 'SF Pro Display', 'PingFang SC', sans-serif;
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.detail-link {
  display: inline-flex;
  align-items: center;
  flex: 0 0 auto;
  color: var(--page-primary);
  font-size: 12px;
  font-weight: 700;
}

.state-panel {
  min-height: 320px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 28px 22px;
  text-align: center;
}

.state-visual {
  width: 82px;
  height: 82px;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 18px;
  border-radius: 28px;
  background: var(--page-primary-soft);
  color: var(--page-primary);
  transform: rotate(-4deg);
}

.state-panel h3 {
  margin: 0;
  font-size: 17px;
}

.state-panel p {
  margin: 8px 0 18px;
  color: #7d899c;
  font-size: 12px;
  line-height: 1.65;
}

.primary-button,
.secondary-button {
  min-height: 42px;
  padding: 10px 18px;
  border-radius: 14px;
  font-size: 13px;
  font-weight: 700;
}

.primary-button {
  border: 0;
  background: var(--page-primary);
  color: #fff;
  box-shadow: 0 9px 20px rgba(49, 108, 255, 0.2);
}

.secondary-button {
  border: 1px solid #dfe5ee;
  background: #fff;
  color: var(--page-ink);
}

.skeleton-card {
  min-height: 155px;
}

.skeleton-top,
.skeleton-package,
.skeleton-lines,
.skeleton-amount,
.skeleton-bottom {
  display: block;
  border-radius: 999px;
  background: linear-gradient(90deg, #eef1f5 25%, #f7f8fa 50%, #eef1f5 75%);
  background-size: 200% 100%;
  animation: skeleton 1.35s ease-in-out infinite;
}

.skeleton-top {
  width: 46%;
  height: 10px;
}

.skeleton-body {
  display: grid;
  grid-template-columns: 50px minmax(0, 1fr) 52px;
  align-items: center;
  gap: 12px;
  margin: 18px 0;
}

.skeleton-package {
  width: 50px;
  height: 50px;
  border-radius: 16px;
}

.skeleton-lines {
  width: 78%;
  height: 12px;
}

.skeleton-amount {
  width: 52px;
  height: 15px;
}

.skeleton-bottom {
  width: 32%;
  height: 9px;
}

@keyframes skeleton {
  to {
    background-position: -200% 0;
  }
}

@media (max-width: 350px) {
  .order-product {
    grid-template-columns: 44px minmax(0, 1fr);
  }

  .package-symbol {
    width: 44px;
    height: 44px;
  }

  .order-amount {
    grid-column: 2;
    margin-top: -6px;
    font-size: 15px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .order-card,
  .skeleton-top,
  .skeleton-package,
  .skeleton-lines,
  .skeleton-amount,
  .skeleton-bottom {
    animation: none;
    transition: none;
  }
}
</style>
