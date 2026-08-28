<script setup lang="ts">
// 底部导航保留 Vant route 模式和原路由行为；外层船坞负责居中、移动壳约束与安全区。
</script>

<template>
  <div class="app-tabbar-dock">
    <van-tabbar route :fixed="false" :border="false" :safe-area-inset-bottom="false" aria-label="主导航">
      <van-tabbar-item to="/messages" icon="chat-o">消息</van-tabbar-item>
      <van-tabbar-item to="/orders" icon="orders-o">订单</van-tabbar-item>
      <van-tabbar-item to="/profile" icon="user-o">我的</van-tabbar-item>
    </van-tabbar>
  </div>
</template>

<style scoped>
.app-tabbar-dock {
  position: fixed;
  z-index: 100;
  left: 50%;
  bottom: 9px;
  bottom: max(9px, constant(safe-area-inset-bottom));
  bottom: max(9px, env(safe-area-inset-bottom));
  width: min(calc(100% - 20px), calc(var(--cw-shell-width) - 20px));
  height: var(--cw-tabbar-height);
  transform: translateX(-50%);
  pointer-events: none;
}

:deep(.van-tabbar) {
  --van-tabbar-height: var(--cw-tabbar-height);
  --van-tabbar-background: rgba(255, 255, 255, 0.94);
  --van-tabbar-item-font-size: 10px;
  --van-tabbar-item-text-color: var(--cw-text-muted);
  --van-tabbar-item-active-color: var(--cw-primary);
  --van-tabbar-item-active-background: var(--cw-primary-soft);
  --van-tabbar-item-icon-size: 22px;
  --van-tabbar-item-icon-margin-bottom: 3px;
  box-sizing: border-box;
  height: var(--cw-tabbar-height);
  padding: 6px;
  overflow: hidden;
  border: 1px solid rgba(225, 231, 240, 0.9);
  border-radius: 22px;
  background: var(--van-tabbar-background);
  box-shadow: 0 16px 34px rgba(37, 55, 85, 0.17);
  backdrop-filter: blur(18px);
  -webkit-backdrop-filter: blur(18px);
  pointer-events: auto;
}

:deep(.van-tabbar-item) {
  position: relative;
  height: 58px;
  border-radius: 17px;
  letter-spacing: 0.02em;
  transition:
    color 160ms ease,
    background-color 160ms ease,
    transform 160ms ease;
}

:deep(.van-tabbar-item--active) {
  font-weight: 700;
}

:deep(.van-tabbar-item--active::before) {
  position: absolute;
  top: 4px;
  left: 50%;
  width: 12px;
  height: 3px;
  border-radius: 999px;
  background: var(--cw-primary);
  content: '';
  transform: translateX(-50%);
}

:deep(.van-tabbar-item--active .van-tabbar-item__icon) {
  transform: translateY(-1px);
}

:deep(.van-tabbar-item:focus-visible) {
  outline: 3px solid var(--cw-focus-ring);
  outline-offset: -2px;
}

@media (hover: hover) and (pointer: fine) {
  :deep(.van-tabbar-item:not(.van-tabbar-item--active):hover) {
    color: var(--cw-ink-soft);
    background: rgba(234, 241, 255, 0.62);
  }
}

@media (max-width: 359px) {
  .app-tabbar-dock {
    width: calc(100% - 14px);
  }

  :deep(.van-tabbar) {
    border-radius: 20px;
  }
}
</style>
