<script setup lang="ts">
import { useAuthStore } from '@/store/auth'

const auth = useAuthStore()
</script>

<template>
  <div class="home-page">
    <img src="/home-cover.jpg" alt="首页" class="home-cover" />
    <p class="home-tip">{{ auth.nickname }}，请从左侧菜单选择要管理的模块。</p>
  </div>
</template>

<style scoped>
.home-page {
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 16px;
}

.home-cover {
  max-width: 80%;
  max-height: 70%;
  border-radius: 12px;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.1);
  object-fit: contain;
}

.home-tip {
  margin: 0;
  font-size: 20px;
  font-weight: 700;
  letter-spacing: 2px;
  background: linear-gradient(90deg, #ff6b6b, #feca57, #48dbfb, #ff9ff3, #54a0ff);
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
  text-shadow: 2px 2px 4px rgba(0, 0, 0, 0.15);
  filter: drop-shadow(0 2px 2px rgba(0, 0, 0, 0.1));
}
</style>
