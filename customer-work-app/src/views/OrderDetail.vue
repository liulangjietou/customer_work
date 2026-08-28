<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { fetchOrderDetail } from '@/api/order'
import type { OrderView } from '@/types/api'

const props = defineProps<{ id: string }>()
const router = useRouter()

const order = ref<OrderView | null>(null)
const initialLoading = ref(true)
const loadError = ref(false)

const orderId = computed(() => props.id)

// 后端以「起点 → 当前节点」顺序返回轨迹；详情页倒序展示，让最新节点优先被看到。
// 仅清理轨迹的包装括号和句末标点，不补写后端未返回的物流时间。
const traceLines = computed(() => {
  const trace = order.value?.logisticsTrace
  if (!trace) {
    return []
  }
  return trace
    .split(/→|\n/)
    .map((line) => line.trim().replace(/^[\[【]+/, '').replace(/[\]】。]+$/, '').trim())
    .filter((line) => line.length > 0)
    .reverse()
})

async function loadDetail() {
  initialLoading.value = true
  loadError.value = false
  try {
    order.value = await fetchOrderDetail(orderId.value)
  } catch {
    order.value = null
    loadError.value = true
  } finally {
    initialLoading.value = false
  }
}

onMounted(loadDetail)

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

function goOrders() {
  router.replace('/orders')
}

// 携带订单号跳转聊天页，Chat 页继续按既有逻辑预填订单咨询文案。
function goChat() {
  router.push({ path: '/chat', query: { orderId: orderId.value } })
}
</script>

<template>
  <div class="order-detail-page">
    <header class="detail-header">
      <button class="icon-button" type="button" aria-label="返回上一页" @click="router.back()">
        <van-icon name="arrow-left" size="20" />
      </button>
      <h1>订单详情</h1>
      <button class="service-button" type="button" @click="goChat">
        <van-icon name="chat-o" size="17" />
        <span>客服</span>
      </button>
    </header>

    <main class="detail-scroll">
      <div v-if="initialLoading" class="skeleton-list" aria-label="订单详情加载中" aria-busy="true">
        <div class="skeleton-hero"></div>
        <div v-for="index in 3" :key="index" class="skeleton-card">
          <span></span><span></span><span></span>
        </div>
      </div>

      <div v-else-if="loadError" class="state-panel" role="alert">
        <span class="state-visual"><van-icon name="orders-o" size="34" /></span>
        <h2>订单详情加载失败</h2>
        <p>暂时无法获取订单信息，请重新加载。</p>
        <button class="primary-button retry-button" type="button" @click="loadDetail">重新加载</button>
      </div>

      <template v-else-if="order">
        <section class="order-status-hero" aria-labelledby="order-status-heading">
          <div>
            <span class="status-eyebrow">订单状态</span>
            <h2 id="order-status-heading">{{ order.status }}</h2>
            <p>{{ order.orderId }}</p>
          </div>
          <span class="status-illustration" aria-hidden="true"><van-icon name="logistics" size="28" /></span>
        </section>

        <section class="detail-section" aria-labelledby="product-heading">
          <h2 id="product-heading">商品信息</h2>
          <div class="info-card">
            <div class="info-row">
              <span class="info-label">商品名称</span>
              <strong class="info-value">{{ order.productName }}</strong>
            </div>
            <div class="info-row">
              <span class="info-label">商品编号</span>
              <strong class="info-value data-value">{{ order.productId }}</strong>
            </div>
            <div class="info-row">
              <span class="info-label">订单金额</span>
              <strong class="info-value amount-value">¥{{ order.amount }}</strong>
            </div>
            <div class="info-row">
              <span class="info-label">下单时间</span>
              <strong class="info-value data-value">{{ formatTime(order.createdAtMs) }}</strong>
            </div>
          </div>
        </section>

        <section class="detail-section" aria-labelledby="receiver-heading">
          <h2 id="receiver-heading">收货信息</h2>
          <div class="info-card">
            <div class="info-row info-row--address">
              <span class="info-label">收货地址</span>
              <strong class="info-value">{{ order.receiverAddr || '—' }}</strong>
            </div>
          </div>
        </section>

        <section class="detail-section" aria-labelledby="logistics-heading">
          <h2 id="logistics-heading">物流轨迹</h2>
          <div class="info-card">
            <div v-if="traceLines.length" class="timeline">
              <div
                v-for="(line, index) in traceLines"
                :key="`${line}-${index}`"
                class="timeline-item"
                :class="{ 'timeline-item--active': index === 0 }"
              >
                <span class="timeline-dot" aria-hidden="true"></span>
                <span class="timeline-copy">
                  <strong>{{ line }}</strong>
                  <span v-if="index === 0">当前节点</span>
                </span>
              </div>
            </div>
            <div v-else class="trace-empty">
              <van-icon name="logistics" size="24" />
              <span>暂无物流轨迹</span>
            </div>
          </div>
        </section>

        <div class="detail-actions">
          <button class="secondary-button" type="button" @click="goOrders">返回订单</button>
          <button class="primary-button" type="button" @click="goChat">
            <van-icon name="chat-o" size="17" />
            <span>咨询客服</span>
          </button>
        </div>
      </template>
    </main>
  </div>
</template>

<style scoped>
.order-detail-page {
  --page-ink: var(--cw-ink, #142033);
  --page-primary: var(--cw-primary, #316cff);
  --page-primary-soft: var(--cw-primary-soft, #eaf1ff);
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

.detail-header {
  min-height: calc(58px + env(safe-area-inset-top));
  display: grid;
  grid-template-columns: 72px minmax(0, 1fr) 72px;
  align-items: center;
  padding: calc(6px + env(safe-area-inset-top)) 8px 6px;
  border-bottom: 1px solid rgba(230, 235, 242, 0.8);
  background: rgba(255, 255, 255, 0.96);
}

.detail-header h1 {
  margin: 0;
  font-size: 17px;
  line-height: 1.3;
  text-align: center;
}

.icon-button,
.service-button {
  min-height: 44px;
  display: inline-flex;
  align-items: center;
  border: 0;
  border-radius: 13px;
  background: transparent;
  color: var(--page-ink);
}

.icon-button {
  width: 44px;
  justify-content: center;
}

.service-button {
  justify-content: flex-end;
  gap: 5px;
  padding: 0 8px;
  color: var(--page-primary);
  font-size: 12px;
  font-weight: 700;
}

.detail-scroll {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 13px 14px calc(26px + env(safe-area-inset-bottom));
}

.order-status-hero {
  position: relative;
  overflow: hidden;
  min-height: 122px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 21px;
  border-radius: 24px;
  background: var(--page-ink);
  color: #fff;
  box-shadow: 0 16px 34px rgba(20, 32, 51, 0.16);
}

.order-status-hero::after {
  content: '';
  position: absolute;
  width: 148px;
  height: 148px;
  right: -64px;
  top: -78px;
  border: 1px solid rgba(255, 255, 255, 0.16);
  border-radius: 50%;
  box-shadow: 0 0 0 24px rgba(255, 255, 255, 0.035);
}

.status-eyebrow {
  color: #94a6c3;
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.1em;
}

.order-status-hero h2 {
  margin: 6px 0 5px;
  font-size: 23px;
  letter-spacing: -0.025em;
}

.order-status-hero p {
  margin: 0;
  color: #aebcd2;
  font-family: 'DIN Alternate', 'SF Pro Display', 'PingFang SC', sans-serif;
  font-size: 10px;
}

.status-illustration {
  position: relative;
  z-index: 1;
  width: 55px;
  height: 55px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 auto;
  border-radius: 19px;
  background: #fff;
  color: var(--page-primary);
  transform: rotate(4deg);
}

.detail-section {
  margin-top: 18px;
}

.detail-section > h2 {
  margin: 0 3px 9px;
  font-size: 13px;
}

.info-card {
  overflow: hidden;
  border: 1px solid rgba(230, 235, 242, 0.9);
  border-radius: 18px;
  background: #fff;
  box-shadow: 0 7px 22px rgba(37, 55, 85, 0.055);
}

.info-row {
  min-height: 49px;
  display: grid;
  grid-template-columns: 84px minmax(0, 1fr);
  align-items: center;
  gap: 14px;
  padding: 11px 14px;
  border-bottom: 1px solid #eef1f5;
}

.info-row:last-child {
  border-bottom: 0;
}

.info-label {
  color: #59687c;
  font-size: 12px;
}

.info-value {
  color: var(--page-ink);
  font-size: 12px;
  font-weight: 650;
  line-height: 1.55;
  text-align: right;
  overflow-wrap: anywhere;
}

.data-value,
.amount-value {
  font-family: 'DIN Alternate', 'SF Pro Display', 'PingFang SC', sans-serif;
}

.amount-value {
  font-size: 14px;
}

.timeline {
  padding: 18px 15px 4px;
}

.timeline-item {
  position: relative;
  min-height: 45px;
  display: grid;
  grid-template-columns: 17px minmax(0, 1fr);
  gap: 10px;
  padding-bottom: 17px;
}

.timeline-item:not(:last-child)::before {
  content: '';
  position: absolute;
  left: 5px;
  top: 12px;
  bottom: -2px;
  width: 1px;
  background: #dbe3ef;
}

.timeline-dot {
  position: relative;
  z-index: 1;
  width: 11px;
  height: 11px;
  margin-top: 3px;
  border: 3px solid #fff;
  border-radius: 50%;
  background: #a9b4c4;
  box-shadow: 0 0 0 1px #ced7e4;
}

.timeline-item--active .timeline-dot {
  background: var(--page-primary);
  box-shadow: 0 0 0 1px var(--page-primary), 0 0 0 6px rgba(49, 108, 255, 0.09);
}

.timeline-copy strong {
  display: block;
  color: #58667b;
  font-size: 12px;
  font-weight: 600;
  line-height: 1.55;
}

.timeline-item--active .timeline-copy strong {
  color: var(--page-ink);
  font-weight: 750;
}

.timeline-copy span {
  display: block;
  margin-top: 4px;
  color: var(--page-primary);
  font-size: 10px;
  font-weight: 700;
}

.trace-empty {
  min-height: 82px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 9px;
  color: #59687c;
  font-size: 12px;
}

.detail-actions {
  display: grid;
  grid-template-columns: 1fr 1.45fr;
  gap: 10px;
  margin-top: 20px;
}

.primary-button,
.secondary-button {
  min-height: 45px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
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

.state-panel {
  min-height: calc(100vh - 110px);
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

.state-panel h2 {
  margin: 0;
  font-size: 17px;
}

.state-panel p {
  margin: 8px 0 18px;
  color: #7d899c;
  font-size: 12px;
  line-height: 1.65;
}

.retry-button {
  padding: 0 18px;
}

.skeleton-list {
  display: grid;
  gap: 17px;
}

.skeleton-hero,
.skeleton-card,
.skeleton-card span {
  background: linear-gradient(90deg, #eef1f5 25%, #f7f8fa 50%, #eef1f5 75%);
  background-size: 200% 100%;
  animation: skeleton 1.35s ease-in-out infinite;
}

.skeleton-hero {
  height: 122px;
  border-radius: 24px;
}

.skeleton-card {
  min-height: 145px;
  display: grid;
  gap: 13px;
  padding: 17px;
  border-radius: 18px;
}

.skeleton-card span {
  width: 100%;
  height: 11px;
  display: block;
  border-radius: 999px;
}

.skeleton-card span:nth-child(2) {
  width: 72%;
}

.skeleton-card span:nth-child(3) {
  width: 86%;
}

@keyframes skeleton {
  to {
    background-position: -200% 0;
  }
}

@media (max-width: 350px) {
  .detail-header {
    grid-template-columns: 60px minmax(0, 1fr) 60px;
  }

  .detail-scroll {
    padding-right: 12px;
    padding-left: 12px;
  }

  .info-row {
    grid-template-columns: 72px minmax(0, 1fr);
    gap: 10px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .skeleton-hero,
  .skeleton-card,
  .skeleton-card span {
    animation: none;
  }
}
</style>
