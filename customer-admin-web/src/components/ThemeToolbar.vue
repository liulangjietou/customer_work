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

/* 主题色胶囊按钮：白底细边框，悬浮时边框染上主题色并轻微上浮 */
.theme-btn {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  height: 34px;
  padding: 0 14px;
  border-radius: 17px;
  border: 1px solid #dcdfe6;
  background: #fff;
  cursor: pointer;
  font-size: 13px;
  transition: border-color 0.2s, box-shadow 0.2s, transform 0.2s;
}

.theme-btn:hover {
  border-color: var(--theme-primary, #409eff);
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.08);
  transform: translateY(-1px);
}

.color-dot {
  width: 15px;
  height: 15px;
  border-radius: 50%;
  border: 2px solid #fff;
  box-shadow: 0 0 0 1px #dcdfe6, 0 1px 3px rgba(0, 0, 0, 0.15);
}

.color-label {
  color: #606266;
  font-weight: 500;
}

.arrow {
  font-size: 12px;
  color: #909399;
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
  background: #fff;
  border-radius: 10px;
  box-shadow: 0 6px 20px rgba(0, 0, 0, 0.12);
  border: 1px solid #ebeef5;
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
  border-color: #fff;
  box-shadow: 0 0 0 2px var(--theme-primary, #409eff);
}

/* 新建会话：主题色渐变胶囊按钮，悬浮提亮并上浮 */
.new-session-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 34px;
  padding: 0 18px;
  border: none;
  border-radius: 17px;
  background: linear-gradient(
    135deg,
    var(--theme-primary, #409eff),
    var(--theme-primary-light, #79bbff)
  );
  color: #fff;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.12);
  transition: box-shadow 0.2s, transform 0.2s, filter 0.2s;
}

.new-session-btn:hover {
  filter: brightness(1.08);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.18);
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
