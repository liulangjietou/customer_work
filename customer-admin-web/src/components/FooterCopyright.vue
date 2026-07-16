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
  padding: 12px 16px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.footer-copyright.dark {
  color: rgba(255, 255, 255, 0.55);
}
</style>
