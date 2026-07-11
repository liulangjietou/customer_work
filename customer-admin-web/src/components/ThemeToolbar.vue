<script setup lang="ts">
import { ref } from 'vue'
import { useThemeStore, PRESET_COLORS } from '@/store/theme'

const props = defineProps<{
  onNewSession: () => void
}>()

const themeStore = useThemeStore()
const showPicker = ref(false)
</script>

<template>
  <div class="theme-toolbar">
    <!-- 主题色选择 -->
    <div class="theme-picker" @mouseenter="showPicker = true" @mouseleave="showPicker = false">
      <el-button text class="current-color" title="切换主题色">
        <span class="color-dot" :style="{ backgroundColor: themeStore.primaryColor }" />
        <span class="color-label">主题色</span>
      </el-button>
      <transition name="fade">
        <div v-show="showPicker" class="color-popover">
          <div
            v-for="color in PRESET_COLORS"
            :key="color"
            class="color-option"
            :class="{ active: themeStore.primaryColor === color }"
            :style="{ backgroundColor: color }"
            :title="color"
            @click="themeStore.setPrimaryColor(color)"
          />
        </div>
      </transition>
    </div>

    <!-- 新建会话 -->
    <el-button type="primary" size="small" class="new-session-btn" @click="props.onNewSession">
      <el-icon style="margin-right: 4px"><Plus /></el-icon>
      新建会话
    </el-button>
  </div>
</template>

<style scoped>
.theme-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
}

.theme-picker {
  position: relative;
}

.current-color {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 5px 10px;
}

.color-dot {
  width: 14px;
  height: 14px;
  border-radius: 50%;
  border: 2px solid #fff;
  box-shadow: 0 0 0 1px #dcdfe6;
}

.color-label {
  font-size: 13px;
  color: #606266;
}

.color-popover {
  position: absolute;
  top: calc(100% + 6px);
  right: 0;
  z-index: 100;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding: 10px;
  background: #fff;
  border-radius: 8px;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.12);
  border: 1px solid #ebeef5;
  width: 160px;
}

.color-option {
  width: 22px;
  height: 22px;
  border-radius: 50%;
  cursor: pointer;
  transition: transform 0.15s, box-shadow 0.15s;
  border: 2px solid transparent;
}

.color-option:hover {
  transform: scale(1.15);
}

.color-option.active {
  border-color: #fff;
  box-shadow: 0 0 0 2px var(--theme-primary, #409eff);
}

.new-session-btn {
  background-color: var(--theme-primary, #409eff);
  border-color: var(--theme-primary, #409eff);
}

.new-session-btn:hover {
  background-color: var(--theme-primary-light, #79bbff);
  border-color: var(--theme-primary-light, #79bbff);
}

.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.2s, transform 0.2s;
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}
</style>
