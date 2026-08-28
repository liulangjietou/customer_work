<script setup lang="ts"></script>

<template>
  <div class="mobile-shell">
    <router-view />
  </div>
</template>

<style scoped>
.mobile-shell {
  position: relative;
  width: 100%;
  max-width: var(--cw-shell-width);
  min-height: 100vh;
  min-height: 100dvh;
  margin: 0 auto;
  display: flex;
  flex-direction: column;
  isolation: isolate;
  overflow-x: hidden;
  background: var(--cw-page-bg);
  box-shadow: 0 28px 76px rgba(31, 48, 78, 0.2);
}

@media (max-width: 480px) {
  .mobile-shell {
    box-shadow: none;
  }
}
</style>
