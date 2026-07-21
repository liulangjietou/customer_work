<script setup lang="ts">
// 开发者工具箱：左侧工具导航（带搜索）+ 右侧当前工具面板。全部本地计算，不调任何后端接口。
// 当前工具用 route query ?tool= 同步，支持浏览器收藏直达/刷新保持；query 缺失或指向不存在的工具时
// 兜底规整成默认工具（第一个），保持 URL 与实际展示始终一致。
import { computed, defineAsyncComponent, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { defaultToolKey, devTools } from './devtools/toolRegistry'

const route = useRoute()
const router = useRouter()
const search = ref('')

const filteredTools = computed(() => {
  const keyword = search.value.trim().toLowerCase()
  if (!keyword) return devTools
  return devTools.filter(
    (tool) => tool.label.toLowerCase().includes(keyword) || tool.description.toLowerCase().includes(keyword),
  )
})

function queryToolKey(): string | undefined {
  const q = route.query.tool
  const value = Array.isArray(q) ? q[0] : q
  return value ?? undefined
}

const activeKey = computed(() => {
  const key = queryToolKey()
  return key && devTools.some((t) => t.key === key) ? key : defaultToolKey
})

const activeTool = computed(() => devTools.find((t) => t.key === activeKey.value) ?? devTools[0])

const activeComponent = computed(() => defineAsyncComponent(activeTool.value.component))

function selectTool(key: string) {
  if (key === activeKey.value) return
  router.replace({ query: { ...route.query, tool: key } })
}

// query.tool 缺失或非法（如手改成不存在的 key）时，规整到默认工具，避免展示与地址栏不一致
watch(
  () => route.query.tool,
  (q) => {
    const key = Array.isArray(q) ? q[0] : q
    if (!key || !devTools.some((t) => t.key === key)) {
      router.replace({ query: { ...route.query, tool: defaultToolKey } })
    }
  },
  { immediate: true },
)
</script>

<template>
  <div class="devtoolbox-page">
    <div class="devtoolbox-sidebar">
      <el-input v-model="search" placeholder="搜索工具" clearable :prefix-icon="'Search'" class="devtoolbox-search" />
      <el-scrollbar class="devtoolbox-list">
        <div
          v-for="tool in filteredTools"
          :key="tool.key"
          class="devtoolbox-item"
          :class="{ active: tool.key === activeKey }"
          @click="selectTool(tool.key)"
        >
          <div class="devtoolbox-item-label">{{ tool.label }}</div>
          <div class="devtoolbox-item-desc">{{ tool.description }}</div>
        </div>
        <el-empty v-if="filteredTools.length === 0" description="没有匹配的工具" :image-size="60" />
      </el-scrollbar>
    </div>

    <div class="devtoolbox-panel">
      <el-card shadow="never">
        <template #header>
          <span class="panel-title">{{ activeTool.label }}</span>
        </template>
        <component :is="activeComponent" :key="activeTool.key" />
      </el-card>
    </div>
  </div>
</template>

<style scoped>
.devtoolbox-page {
  display: flex;
  gap: 16px;
  align-items: flex-start;
}

.devtoolbox-sidebar {
  width: 240px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  padding: 12px;
  height: calc(100vh - 140px);
}

.devtoolbox-search {
  flex-shrink: 0;
}

.devtoolbox-list {
  flex: 1;
}

.devtoolbox-item {
  padding: 8px 10px;
  border-radius: 6px;
  cursor: pointer;
  margin-bottom: 4px;
  transition: background-color 0.15s ease;
}

.devtoolbox-item:hover {
  background: var(--el-fill-color-light);
}

.devtoolbox-item.active {
  background: var(--theme-primary-lighter, var(--el-color-primary-light-9));
}

.devtoolbox-item.active .devtoolbox-item-label {
  color: var(--theme-primary, var(--el-color-primary));
  font-weight: 600;
}

.devtoolbox-item-label {
  font-size: 14px;
  color: var(--el-text-color-primary);
}

.devtoolbox-item-desc {
  margin-top: 2px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.devtoolbox-panel {
  flex: 1;
  min-width: 0;
}

.panel-title {
  font-size: 15px;
  font-weight: 600;
}
</style>
