<script setup lang="ts">
defineProps<{
  dark?: boolean
}>()
</script>

<template>
  <div class="footer-copyright" :class="{ dark }">
    <span>© customer_work · owlzhangfq@gmail.com</span>
  </div>
</template>

<style scoped>
.footer-copyright {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: 100%;
  padding: 0 16px;
  font-size: 12px;
  line-height: 1;
  color: var(--el-text-color-secondary);
}

.footer-copyright.dark {
  color: rgba(255, 255, 255, 0.55);
}
</style>
