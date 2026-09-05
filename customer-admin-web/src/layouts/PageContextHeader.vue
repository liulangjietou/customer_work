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
const title = computed(
  () => menuTitle.value ?? (route.meta.title as string | undefined) ?? presentation.value.title,
)
</script>

<template>
  <header class="cw-page-context" aria-labelledby="cw-page-title">
    <div class="cw-page-context__copy">
      <h1 id="cw-page-title">{{ title }}</h1>
      <p>{{ presentation.description }}</p>
    </div>
  </header>
</template>
