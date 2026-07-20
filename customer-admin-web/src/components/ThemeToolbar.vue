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
      <button type="button" class="theme-btn" title="切换主题色">
        <span class="color-dot" :style="{ backgroundColor: themeStore.primaryColor }" />
        <span class="color-label">主题色</span>
        <el-icon class="arrow" :class="{ open: showPicker }"><ArrowDown /></el-icon>
      </button>
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
    <button type="button" class="new-session-btn" @click="props.onNewSession">
      <el-icon><Plus /></el-icon>
      新建会话
    </button>
  </div>
</template>

<style scoped>
.theme-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
}

.theme-picker {
  position: relative;
}

/* 主题色胶囊按钮：浅黄底，规格与代码知识库/新建会话一致（34px 高/17px 圆角/0 18px 内距） */
.theme-btn {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  height: 34px;
  padding: 0 18px;
  border-radius: 17px;
  border: none;
  background: #fdf0cd;
  cursor: pointer;
  font-size: 13px;
  font-weight: 500;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.12);
  transition: box-shadow 0.2s, transform 0.2s, filter 0.2s;
}

.theme-btn:hover {
  filter: brightness(1.03);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.18);
  transform: translateY(-1px);
}

.color-dot {
  width: 15px;
  height: 15px;
  border-radius: 50%;
  border: 2px solid var(--el-bg-color);
  box-shadow: 0 0 0 1px var(--el-border-color), 0 1px 3px rgba(0, 0, 0, 0.15);
}

.color-label {
  /* 浅黄底上固定深棕字保证可读性，不随明暗模式切换 */
  color: #8a6116;
  font-weight: 500;
}

.arrow {
  font-size: 12px;
  color: #8a6116;
  transition: transform 0.2s;
}

.arrow.open {
  transform: rotate(180deg);
}

.color-popover {
  position: absolute;
  top: calc(100% + 8px);
  right: 0;
  z-index: 100;
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  padding: 12px;
  background: var(--el-bg-color);
  border-radius: 10px;
  box-shadow: 0 6px 20px rgba(0, 0, 0, 0.12);
  border: 1px solid var(--el-border-color-lighter);
  width: 172px;
}

.color-option {
  width: 24px;
  height: 24px;
  border-radius: 50%;
  cursor: pointer;
  transition: transform 0.15s, box-shadow 0.15s;
  border: 2px solid transparent;
}

.color-option:hover {
  transform: scale(1.18);
}

.color-option.active {
  border-color: var(--el-bg-color);
  box-shadow: 0 0 0 2px var(--theme-primary, var(--el-color-primary));
}

/* 新建会话：白底细边框胶囊，规格与代码知识库/主题色按钮一致；悬浮时描主题色边 */
.new-session-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 34px;
  padding: 0 18px;
  border: 1px solid var(--el-border-color);
  border-radius: 17px;
  background: var(--el-bg-color);
  color: var(--el-text-color-regular);
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.08);
  transition: border-color 0.2s, box-shadow 0.2s, transform 0.2s;
}

.new-session-btn:hover {
  border-color: var(--theme-primary, var(--el-color-primary));
  color: var(--theme-primary, var(--el-color-primary));
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.15);
  transform: translateY(-1px);
}

.new-session-btn:active {
  transform: translateY(0);
  filter: brightness(0.96);
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
