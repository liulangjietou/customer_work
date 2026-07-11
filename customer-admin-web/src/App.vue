<script setup lang="ts">
import { onMounted } from 'vue'
import { useThemeStore } from '@/store/theme'

const themeStore = useThemeStore()
onMounted(() => themeStore.apply())
</script>

<template>
  <router-view />
</template>
