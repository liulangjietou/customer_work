<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useThemeStore, PRESET_COLORS } from '@/store/theme'
import { chooseButtonTextColor } from '@/utils/themeContrast'

const props = defineProps<{
  onNewSession: () => void
}>()

const themeStore = useThemeStore()
const showPicker = ref(false)
const toolbarRef = ref<HTMLElement>()
const themeButtonRef = ref<HTMLButtonElement>()

function togglePicker() {
  showPicker.value = !showPicker.value
}

function closePicker(restoreFocus = false) {
  if (!showPicker.value) return
  showPicker.value = false
  if (restoreFocus) {
    nextTick(() => themeButtonRef.value?.focus())
  }
}

function selectColor(color: string) {
  themeStore.setPrimaryColor(color)
  closePicker(true)
}

function handleDocumentPointerDown(event: PointerEvent) {
  if (showPicker.value && !toolbarRef.value?.contains(event.target as Node)) {
    closePicker()
  }
}

function handleEscape(event: KeyboardEvent) {
  if (!showPicker.value) return
  event.stopPropagation()
  event.preventDefault()
  closePicker(true)
}

function handleFocusOut(event: FocusEvent) {
  const nextTarget = event.relatedTarget as Node | null
  if (showPicker.value && (!nextTarget || !toolbarRef.value?.contains(nextTarget))) {
    closePicker()
  }
}

onMounted(() => {
  document.addEventListener('pointerdown', handleDocumentPointerDown)
  document.addEventListener('keydown', handleEscape)
})
onBeforeUnmount(() => {
  document.removeEventListener('pointerdown', handleDocumentPointerDown)
  document.removeEventListener('keydown', handleEscape)
})
</script>

<template>
  <div ref="toolbarRef" class="theme-toolbar" @focusout="handleFocusOut">
    <div class="theme-picker">
      <button
        ref="themeButtonRef"
        type="button"
        class="theme-btn"
        title="切换主题色"
        aria-label="切换主题色"
        :aria-expanded="showPicker"
        aria-controls="theme-color-palette"
        @click="togglePicker"
      >
        <span class="color-dot" :style="{ backgroundColor: themeStore.primaryColor }" />
      </button>
      <transition name="theme-popover">
        <div
          v-show="showPicker"
          id="theme-color-palette"
          class="color-popover"
          role="group"
          aria-label="选择主题色"
        >
          <button
            v-for="color in PRESET_COLORS"
            :key="color"
            type="button"
            class="color-option"
            :class="{ active: themeStore.primaryColor === color }"
            :style="{ backgroundColor: color, color: chooseButtonTextColor(color) }"
            :title="`使用主题色 ${color}`"
            :aria-label="`使用主题色 ${color}`"
            :aria-pressed="themeStore.primaryColor === color"
            @click="selectColor(color)"
          >
            <el-icon v-if="themeStore.primaryColor === color" class="check-mark" aria-hidden="true"><Check /></el-icon>
          </button>
        </div>
      </transition>
    </div>

    <button
      type="button"
      class="new-session-btn"
      @click="props.onNewSession"
    >
      <el-icon aria-hidden="true"><Plus /></el-icon>
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

.theme-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  padding: 0;
  color: var(--el-text-color-regular);
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color);
  border-radius: 10px;
  cursor: pointer;
  box-shadow: 0 1px 2px rgb(16 24 40 / 3%);
  transition: border-color 160ms ease, transform 160ms ease, box-shadow 160ms ease;
}

.theme-btn:hover {
  border-color: var(--theme-primary, var(--el-color-primary));
  box-shadow: 0 4px 10px rgb(16 24 40 / 8%);
  transform: translateY(-1px);
}

.color-dot {
  width: 16px;
  height: 16px;
  border-radius: 50%;
  border: 3px solid var(--el-bg-color);
  box-shadow: 0 0 0 1px var(--el-border-color), 0 1px 3px rgb(16 24 40 / 18%);
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
  border: 1px solid var(--el-border-color);
  border-radius: 12px;
  box-shadow: 0 12px 28px rgb(16 24 40 / 14%);
  width: 170px;
}

.color-option {
  position: relative;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  padding: 0;
  border-radius: 50%;
  border: 2px solid transparent;
  cursor: pointer;
  transition: transform 150ms ease, box-shadow 150ms ease;
}

.color-option:hover {
  transform: scale(1.12);
}

.color-option.active {
  border-color: var(--el-bg-color);
  box-shadow: 0 0 0 2px var(--el-text-color-primary);
}

.check-mark {
  color: currentColor;
  font-size: 13px;
  filter: drop-shadow(0 1px 2px rgb(0 0 0 / 45%));
}

.new-session-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  min-width: 106px;
  height: 36px;
  padding: 0 14px;
  color: var(--cw-on-primary);
  background: var(--theme-primary-solid, var(--el-color-primary));
  border: 1px solid var(--theme-primary-solid, var(--el-color-primary));
  border-radius: 10px;
  cursor: pointer;
  font-size: 12px;
  font-weight: 600;
  box-shadow: 0 4px 10px color-mix(in srgb, var(--theme-primary-solid, var(--el-color-primary)) 25%, transparent);
  transition: background-color 160ms ease, transform 160ms ease, box-shadow 160ms ease;
}

.new-session-btn:hover {
  background: var(--theme-primary-solid-hover, var(--theme-primary-solid));
  box-shadow: 0 6px 14px color-mix(in srgb, var(--theme-primary-solid, var(--el-color-primary)) 32%, transparent);
  transform: translateY(-1px);
}

.new-session-btn:active {
  background: var(--theme-primary-solid-active, var(--theme-primary-solid));
  transform: translateY(0);
}

.theme-btn:focus-visible,
.new-session-btn:focus-visible,
.color-option:focus-visible {
  outline: 0;
  box-shadow:
    0 0 0 2px var(--el-bg-color),
    0 0 0 4px var(--el-text-color-primary);
}

.theme-popover-enter-active,
.theme-popover-leave-active {
  transition: opacity 160ms ease, transform 160ms ease;
}

.theme-popover-enter-from,
.theme-popover-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}

@media (prefers-reduced-motion: reduce) {
  .theme-btn,
  .new-session-btn,
  .color-option,
  .theme-popover-enter-active,
  .theme-popover-leave-active {
    transition: none;
  }

  .theme-btn:hover,
  .new-session-btn:hover,
  .color-option:hover {
    transform: none;
  }
}
</style>
