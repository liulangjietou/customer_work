<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { useMenuStore } from '@/store/menu'
import { findMenuTrail } from './navigationModel'
import { resolvePagePresentation } from './pagePresentation'

const route = useRoute()
const menuStore = useMenuStore()

const presentation = computed(() => resolvePagePresentation(route.path))
const menuTitle = computed(() => {
  const trail = findMenuTrail(menuStore.tree, route.fullPath, route.path)
  return trail?.at(-1)
})
const title = computed(() => (
  menuTitle.value
  ?? (route.meta.title as string | undefined)
  ?? presentation.value.title
))
</script>

<template>
  <header class="cw-page-context" aria-labelledby="cw-page-title">
    <div class="cw-page-context__copy">
      <span class="cw-page-context__eyebrow">{{ presentation.eyebrow }}</span>
      <h1 id="cw-page-title">{{ title }}</h1>
      <p>{{ presentation.description }}</p>
    </div>
    <div class="cw-page-context__scope" title="数据由后端租户隔离与权限校验共同约束">
      <span aria-hidden="true" />
      当前租户与权限范围
    </div>
  </header>
</template>
