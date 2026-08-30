<script setup lang="ts">
import { useRouter } from 'vue-router'

const router = useRouter()
</script>

<template>
  <div class="not-found">
    <section class="not-found-panel" aria-labelledby="not-found-title">
      <span class="error-code">404 / ROUTE GAP</span>
      <h1 id="not-found-title">这条工作路径没有可用页面</h1>
      <p>链接可能已经失效，或当前账号不在该页面的权限范围内。返回首页可重新从已授权入口进入。</p>
      <div class="route-trace" aria-hidden="true"><span></span><span></span><span></span><i></i></div>
      <el-button class="cw-final-action" type="primary" @click="router.push('/')">返回工作首页</el-button>
    </section>
  </div>
</template>

<style scoped>
.not-found {
  box-sizing: border-box;
  height: 100%;
  min-height: 100%;
  overflow-y: auto;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background: var(--cw-canvas);
}

.not-found-panel {
  width: min(560px, 100%);
  padding: clamp(30px, 7vw, 58px);
  text-align: center;
  border: 1px solid var(--cw-line);
  border-radius: var(--cw-radius-lg);
  background: var(--cw-paper);
  box-shadow: var(--cw-shadow-sm);
}

.error-code {
  color: var(--cw-danger);
  font-size: 11px;
  font-weight: 800;
  letter-spacing: .14em;
}

.not-found-panel h1 {
  margin: 10px 0;
  color: var(--cw-text);
  font-size: clamp(24px, 5vw, 34px);
  letter-spacing: -.035em;
}

.not-found-panel p {
  margin: 0;
  color: var(--cw-text-muted);
  line-height: 1.7;
}

.route-trace {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  margin: 28px auto;
}

.route-trace span {
  width: 9px;
  height: 9px;
  border-radius: 50%;
  background: var(--cw-cobalt);
}

.route-trace i {
  width: 68px;
  height: 2px;
  background: repeating-linear-gradient(90deg, var(--cw-danger) 0 7px, transparent 7px 12px);
}

@media (max-width: 480px) {
  .not-found {
    align-items: flex-start;
    padding: 14px 12px;
  }

  .not-found-panel {
    padding: 32px 20px;
  }

  .cw-final-action {
    width: 100%;
  }
}
</style>
