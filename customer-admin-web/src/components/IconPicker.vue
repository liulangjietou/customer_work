<script setup lang="ts">
import { computed, ref } from 'vue'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'

const props = defineProps<{ modelValue: string | null | undefined }>()
const emit = defineEmits<{ 'update:modelValue': [value: string] }>()

const popoverVisible = ref(false)
const iconNames = Object.keys(ElementPlusIconsVue)

const currentIcon = computed(() => props.modelValue || '')

function select(name: string) {
  emit('update:modelValue', name)
  popoverVisible.value = false
}
</script>

<template>
  <el-popover v-model:visible="popoverVisible" placement="bottom-start" width="360" trigger="click">
    <template #reference>
      <el-button>
        <el-icon v-if="currentIcon" style="margin-right: 6px">
          <component :is="currentIcon" />
        </el-icon>
        {{ currentIcon || '选择图标' }}
      </el-button>
    </template>
    <div class="icon-grid">
      <div
        v-for="name in iconNames"
        :key="name"
        class="icon-cell"
        :class="{ active: name === currentIcon }"
        :title="name"
        @click="select(name)"
      >
        <el-icon :size="18">
          <component :is="name" />
        </el-icon>
      </div>
    </div>
  </el-popover>
</template>

<style scoped>
.icon-grid {
  display: grid;
  grid-template-columns: repeat(8, 1fr);
  gap: 4px;
  max-height: 280px;
  overflow-y: auto;
}

.icon-cell {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 32px;
  border-radius: 4px;
  cursor: pointer;
}

.icon-cell:hover {
  background: var(--el-fill-color-light);
}

.icon-cell.active {
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
}
</style>
