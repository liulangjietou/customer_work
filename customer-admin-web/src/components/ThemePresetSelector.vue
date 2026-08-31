<script setup lang="ts">
import {
  computed,
  nextTick,
  onBeforeUnmount,
  onMounted,
  ref,
  useId,
} from 'vue'
import {
  THEME_PRESETS,
  type SelectableThemePreset,
  type ThemePreset,
  useThemeStore,
} from '@/store/theme'

const props = withDefaults(defineProps<{
  compact?: boolean
}>(), {
  compact: false,
})

const themeStore = useThemeStore()
const selectorRef = ref<HTMLElement>()
const triggerRef = ref<HTMLButtonElement>()
const listboxRef = ref<HTMLElement>()
const showOptions = ref(false)
const highlightedIndex = ref(0)
const selectorTop = ref('64px')
const componentId = useId().replace(/[^a-zA-Z0-9_-]/g, '')
const listboxId = `theme-preset-listbox-${componentId}`

const activePreset = computed<ThemePreset>(() => themeStore.activePreset)

function selectedIndex(): number {
  const index = THEME_PRESETS.findIndex((preset) => preset.id === activePreset.value.id)
  return index < 0 ? 0 : index
}

function optionAt(index: number): HTMLButtonElement | undefined {
  return listboxRef.value?.querySelectorAll<HTMLButtonElement>('[role="option"]')[index]
}

function updateMobilePosition() {
  if (!showOptions.value || !triggerRef.value) return
  const triggerRect = triggerRef.value.getBoundingClientRect()
  const desiredTop = triggerRect.bottom + 8
  // 最低保留约三项的可视高度，极矮视口下改为向上贴边展示。
  const highestUsableTop = Math.max(12, window.innerHeight - 216)
  selectorTop.value = `${Math.min(desiredTop, highestUsableTop)}px`
}

async function focusOption(index: number) {
  const normalizedIndex = Math.min(Math.max(index, 0), THEME_PRESETS.length - 1)
  highlightedIndex.value = normalizedIndex
  await nextTick()
  const option = optionAt(normalizedIndex)
  option?.focus()
  option?.scrollIntoView({ block: 'nearest' })
}

async function openOptions(index = selectedIndex()) {
  showOptions.value = true
  await nextTick()
  updateMobilePosition()
  await focusOption(index)
}

function closeOptions(restoreFocus = false) {
  if (!showOptions.value) return
  showOptions.value = false
  if (restoreFocus) {
    nextTick(() => triggerRef.value?.focus())
  }
}

function toggleOptions() {
  if (showOptions.value) {
    closeOptions()
    return
  }
  void openOptions()
}

function selectPreset(preset: SelectableThemePreset) {
  themeStore.selectPreset(preset.id)
  highlightedIndex.value = THEME_PRESETS.findIndex((item) => item.id === preset.id)
  closeOptions(true)
}

function handleTriggerKeydown(event: KeyboardEvent) {
  if (!['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) return
  event.preventDefault()

  if (event.key === 'Home') {
    void openOptions(0)
    return
  }
  if (event.key === 'End') {
    void openOptions(THEME_PRESETS.length - 1)
    return
  }

  const currentIndex = selectedIndex()
  const nextIndex = event.key === 'ArrowDown'
    ? Math.min(currentIndex + 1, THEME_PRESETS.length - 1)
    : Math.max(currentIndex - 1, 0)
  void openOptions(nextIndex)
}

function handleOptionKeydown(event: KeyboardEvent, index: number) {
  if (event.key === 'ArrowDown') {
    event.preventDefault()
    void focusOption(Math.min(index + 1, THEME_PRESETS.length - 1))
    return
  }
  if (event.key === 'ArrowUp') {
    event.preventDefault()
    void focusOption(Math.max(index - 1, 0))
    return
  }
  if (event.key === 'Home') {
    event.preventDefault()
    void focusOption(0)
    return
  }
  if (event.key === 'End') {
    event.preventDefault()
    void focusOption(THEME_PRESETS.length - 1)
    return
  }
  if (event.key === 'Enter' || event.key === ' ') {
    event.preventDefault()
    selectPreset(THEME_PRESETS[index])
  }
}

function handleDocumentPointerDown(event: PointerEvent) {
  if (showOptions.value && !selectorRef.value?.contains(event.target as Node)) {
    closeOptions()
  }
}

function handleDocumentKeydown(event: KeyboardEvent) {
  if (!showOptions.value || event.key !== 'Escape') return
  event.preventDefault()
  event.stopPropagation()
  closeOptions(true)
}

function handleFocusOut(event: FocusEvent) {
  const nextTarget = event.relatedTarget as Node | null
  if (showOptions.value && (!nextTarget || !selectorRef.value?.contains(nextTarget))) {
    closeOptions()
  }
}

onMounted(() => {
  document.addEventListener('pointerdown', handleDocumentPointerDown)
  document.addEventListener('keydown', handleDocumentKeydown)
  document.addEventListener('scroll', updateMobilePosition, true)
  window.addEventListener('resize', updateMobilePosition)
})

onBeforeUnmount(() => {
  document.removeEventListener('pointerdown', handleDocumentPointerDown)
  document.removeEventListener('keydown', handleDocumentKeydown)
  document.removeEventListener('scroll', updateMobilePosition, true)
  window.removeEventListener('resize', updateMobilePosition)
})
</script>

<template>
  <div
    ref="selectorRef"
    class="theme-preset-selector"
    :class="{ 'theme-preset-selector--compact': props.compact }"
    :style="{ '--theme-selector-top': selectorTop }"
    @focusout="handleFocusOut"
  >
    <button
      ref="triggerRef"
      type="button"
      class="theme-preset-trigger"
      aria-label="选择界面主题"
      aria-haspopup="listbox"
      :aria-expanded="showOptions"
      :aria-controls="listboxId"
      :title="`当前主题：${activePreset.label}`"
      data-theme-preset-trigger
      @click="toggleOptions"
      @keydown="handleTriggerKeydown"
    >
      <span
        class="theme-ring theme-trigger-ring"
        :style="{ '--preset-color': activePreset.primaryColor }"
        aria-hidden="true"
      />
      <span class="theme-trigger-label">{{ activePreset.label }}</span>
      <el-icon class="theme-trigger-arrow" aria-hidden="true"><ArrowDown /></el-icon>
    </button>

    <transition name="theme-selector-popover">
      <div
        v-show="showOptions"
        :id="listboxId"
        ref="listboxRef"
        class="theme-preset-listbox"
        role="listbox"
        aria-label="界面主题"
      >
        <button
          v-for="(preset, index) in THEME_PRESETS"
          :id="`${listboxId}-option-${preset.id}`"
          :key="preset.id"
          type="button"
          class="theme-preset-option"
          role="option"
          :aria-selected="activePreset.id === preset.id"
          :tabindex="highlightedIndex === index ? 0 : -1"
          @click="selectPreset(preset)"
          @focus="highlightedIndex = index"
          @keydown="handleOptionKeydown($event, index)"
        >
          <span
            class="theme-ring theme-option-ring"
            :style="{ '--preset-color': preset.primaryColor }"
            aria-hidden="true"
          />
          <span class="theme-option-copy">
            <strong>{{ preset.label }}</strong>
            <span>{{ preset.description }}</span>
          </span>
          <span class="theme-mode-badge" :data-mode="preset.mode">{{ preset.mode.toUpperCase() }}</span>
          <el-icon v-if="activePreset.id === preset.id" class="theme-selected-check" aria-hidden="true"><Check /></el-icon>
        </button>
      </div>
    </transition>
  </div>
</template>

<style scoped>
.theme-preset-selector {
  position: relative;
  flex: 0 0 auto;
}

.theme-preset-trigger {
  width: 158px;
  height: 36px;
  display: inline-flex;
  align-items: center;
  gap: 9px;
  padding: 0 10px 0 11px;
  border: 1px solid var(--cw-line, var(--el-border-color));
  border-radius: var(--cw-radius-md, 10px);
  background: color-mix(in srgb, var(--cw-paper, var(--el-bg-color)) 96%, transparent);
  color: var(--cw-text, var(--el-text-color-primary));
  box-shadow: 0 1px 2px rgb(16 24 40 / 4%);
  cursor: pointer;
  font: inherit;
  transition: border-color 160ms ease, box-shadow 160ms ease, transform 160ms ease;
}

.theme-preset-trigger:hover,
.theme-preset-trigger[aria-expanded="true"] {
  border-color: color-mix(in srgb, var(--theme-primary, var(--el-color-primary)) 54%, var(--el-border-color));
  box-shadow: 0 5px 14px color-mix(in srgb, var(--theme-primary, var(--el-color-primary)) 12%, transparent);
}

.theme-preset-trigger:hover {
  transform: translateY(-1px);
}

.theme-ring {
  --preset-color: var(--theme-primary-solid, var(--el-color-primary));
  position: relative;
  display: inline-flex;
  flex: 0 0 auto;
  border-radius: 50%;
  background: var(--preset-color);
  border: 3px solid var(--cw-paper, var(--el-bg-color));
  box-shadow:
    0 0 0 2px color-mix(in srgb, var(--preset-color) 22%, var(--el-border-color)),
    0 2px 5px color-mix(in srgb, var(--preset-color) 38%, transparent);
}

.theme-trigger-ring {
  width: 17px;
  height: 17px;
}

.theme-trigger-label {
  min-width: 0;
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
  font-weight: 670;
  text-align: left;
}

.theme-trigger-arrow {
  flex: 0 0 auto;
  color: var(--cw-text-muted, var(--el-text-color-secondary));
  font-size: 13px;
  transition: transform 160ms ease;
}

.theme-preset-trigger[aria-expanded="true"] .theme-trigger-arrow {
  transform: rotate(180deg);
}

.theme-preset-listbox {
  position: absolute;
  z-index: 2200;
  top: calc(100% + 9px);
  right: 0;
  width: 340px;
  max-width: calc(100vw - 24px);
  max-height: min(574px, calc(100dvh - 88px));
  box-sizing: border-box;
  display: grid;
  gap: 4px;
  padding: 8px;
  overflow-x: hidden;
  overflow-y: auto;
  overscroll-behavior: contain;
  border: 1px solid var(--cw-line, var(--el-border-color));
  border-radius: 16px;
  background: var(--cw-paper, var(--el-bg-color));
  box-shadow:
    0 24px 54px rgb(15 23 42 / 17%),
    0 8px 20px rgb(15 23 42 / 8%);
  scrollbar-width: thin;
}

.theme-preset-option {
  position: relative;
  width: 100%;
  min-height: 62px;
  display: grid;
  grid-template-columns: 28px minmax(0, 1fr) auto 18px;
  align-items: center;
  column-gap: 12px;
  padding: 9px 10px;
  border: 0;
  border-radius: 11px;
  background: transparent;
  color: var(--cw-text, var(--el-text-color-primary));
  cursor: pointer;
  font: inherit;
  text-align: left;
  transition: background-color 140ms ease, transform 140ms ease;
}

.theme-preset-option:hover,
.theme-preset-option:focus-visible {
  background: color-mix(in srgb, var(--preset-hover, var(--el-fill-color-light)) 82%, transparent);
}

.theme-preset-option[aria-selected="true"] {
  background: color-mix(in srgb, var(--theme-primary, var(--el-color-primary)) 13%, var(--cw-paper, var(--el-bg-color)));
}

.theme-option-ring {
  width: 21px;
  height: 21px;
}

.theme-option-copy {
  min-width: 0;
  display: grid;
  gap: 3px;
}

.theme-option-copy strong {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 14px;
  font-weight: 690;
  line-height: 1.2;
}

.theme-option-copy > span {
  overflow: hidden;
  color: var(--cw-text-muted, var(--el-text-color-secondary));
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 11px;
  line-height: 1.3;
}

.theme-mode-badge {
  min-width: 38px;
  box-sizing: border-box;
  padding: 3px 5px;
  border: 1px solid var(--cw-line, var(--el-border-color-lighter));
  border-radius: 999px;
  background: var(--el-fill-color-extra-light);
  color: var(--cw-text-subtle, var(--el-text-color-secondary));
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 8px;
  font-weight: 760;
  letter-spacing: 0.05em;
  line-height: 1.2;
  text-align: center;
}

.theme-mode-badge[data-mode="dark"] {
  background: color-mix(in srgb, var(--cw-text, var(--el-text-color-primary)) 10%, transparent);
}

.theme-mode-badge[data-mode="auto"] {
  border-color: color-mix(in srgb, var(--theme-primary, var(--el-color-primary)) 34%, var(--el-border-color));
  color: var(--theme-primary, var(--el-color-primary));
}

.theme-selected-check {
  color: var(--theme-primary, var(--el-color-primary));
  font-size: 15px;
}

.theme-preset-trigger:focus-visible,
.theme-preset-option:focus-visible {
  outline: 0;
  box-shadow:
    0 0 0 2px var(--cw-paper, var(--el-bg-color)),
    0 0 0 4px var(--cw-focus-ring, var(--theme-primary, var(--el-color-primary)));
}

.theme-preset-selector--compact .theme-preset-trigger {
  width: 36px;
  padding: 0;
  justify-content: center;
}

.theme-preset-selector--compact .theme-trigger-label,
.theme-preset-selector--compact .theme-trigger-arrow {
  display: none;
}

.theme-selector-popover-enter-active,
.theme-selector-popover-leave-active {
  transform-origin: top right;
  transition: opacity 150ms ease, transform 150ms ease;
}

.theme-selector-popover-enter-from,
.theme-selector-popover-leave-to {
  opacity: 0;
  transform: translateY(-5px) scale(0.985);
}

@media (max-width: 760px) {
  .theme-preset-trigger {
    width: 34px;
    height: 34px;
    padding: 0;
    justify-content: center;
  }

  .theme-trigger-label,
  .theme-trigger-arrow {
    display: none;
  }

  .theme-preset-listbox {
    position: fixed;
    top: var(--theme-selector-top, 64px);
    right: 12px;
    left: 12px;
    width: auto;
    max-width: none;
    max-height: calc(100dvh - var(--theme-selector-top, 64px) - 12px);
    border-radius: 14px;
  }
}

@media (max-width: 390px) {
  .theme-preset-listbox {
    right: 8px;
    left: 8px;
  }

  .theme-preset-option {
    grid-template-columns: 27px minmax(0, 1fr) auto 16px;
    column-gap: 9px;
    padding-inline: 8px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .theme-preset-trigger,
  .theme-trigger-arrow,
  .theme-preset-option,
  .theme-selector-popover-enter-active,
  .theme-selector-popover-leave-active {
    transition: none;
  }

  .theme-preset-trigger:hover {
    transform: none;
  }
}
</style>
