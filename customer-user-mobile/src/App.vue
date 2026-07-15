<script setup lang="ts"></script>

<template>
  <div class="mobile-shell">
    <router-view />
  </div>
</template>

<style scoped>
.mobile-shell {
  max-width: 480px;
  min-height: 100vh;
  margin: 0 auto;
  background: #f7f8fa;
  box-shadow: 0 0 12px rgba(0, 0, 0, 0.08);
  display: flex;
  flex-direction: column;
}
</style>
