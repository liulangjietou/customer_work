<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import type { InputInstance } from 'element-plus'
import { useMenuStore } from '@/store/menu'
import {
  buildNavigationCommands,
  buildNavigationSections,
  searchNavigationCommands,
} from './navigationModel'

const router = useRouter()
const menuStore = useMenuStore()
const visible = ref(false)
const query = ref('')
const selectedIndex = ref(0)
const searchInput = ref<InputInstance>()
const triggerButton = ref<HTMLButtonElement>()
const resultPanel = ref<HTMLElement>()
let restoreFocusTarget: HTMLElement | null = null

const sections = computed(() => buildNavigationSections(menuStore.tree))
const commands = computed(() => buildNavigationCommands(sections.value))
const results = computed(() => searchNavigationCommands(commands.value, query.value))
const shortcutLabel = computed(() => (
  typeof navigator !== 'undefined' && /Mac|iPhone|iPad/.test(navigator.platform) ? '⌘ K' : 'Ctrl K'
))

watch(query, () => {
  selectedIndex.value = 0
})

watch(results, () => {
  selectedIndex.value = Math.min(selectedIndex.value, Math.max(results.value.length - 1, 0))
})

watch(selectedIndex, (index) => {
  void nextTick(() => {
    resultPanel.value
      ?.querySelector<HTMLElement>(`#navigation-command-${index}`)
      ?.scrollIntoView({ block: 'nearest' })
  })
})

function focusSearch() {
  void nextTick(() => searchInput.value?.focus())
}

function openSearch() {
  restoreFocusTarget = document.activeElement instanceof HTMLElement
    ? document.activeElement
    : triggerButton.value ?? null
  visible.value = true
  query.value = ''
  selectedIndex.value = 0
  focusSearch()
}

function closeSearch() {
  visible.value = false
}

function restoreFocus() {
  restoreFocusTarget?.focus()
  restoreFocusTarget = null
}

async function selectResult(index: number) {
  const command = results.value[index]
  if (!command) return
  closeSearch()
  await router.push(command.path)
}

function handleSearchKeydown(event: KeyboardEvent) {
  if (event.key === 'ArrowDown') {
    event.preventDefault()
    selectedIndex.value = results.value.length === 0
      ? 0
      : (selectedIndex.value + 1) % results.value.length
  } else if (event.key === 'ArrowUp') {
    event.preventDefault()
    selectedIndex.value = results.value.length === 0
      ? 0
      : (selectedIndex.value - 1 + results.value.length) % results.value.length
  } else if (event.key === 'Enter') {
    event.preventDefault()
    void selectResult(selectedIndex.value)
  } else if (event.key === 'Escape') {
    event.preventDefault()
    closeSearch()
  }
}

function handleGlobalShortcut(event: KeyboardEvent) {
  if ((event.metaKey || event.ctrlKey) && event.key.toLocaleLowerCase() === 'k') {
    event.preventDefault()
    visible.value ? closeSearch() : openSearch()
  }
}

function resultMeta(index: number): string {
  const command = results.value[index]
  if (!command) return ''
  const ancestors = command.trail.slice(0, -1)
  return [command.sectionTitle, ...ancestors].join(' · ')
}

onMounted(() => window.addEventListener('keydown', handleGlobalShortcut))
onBeforeUnmount(() => window.removeEventListener('keydown', handleGlobalShortcut))
</script>

<template>
  <button
    ref="triggerButton"
    type="button"
    class="global-search-trigger"
    aria-label="搜索菜单、智能体或配置"
    @click="openSearch"
  >
    <el-icon><Search /></el-icon>
    <span class="global-search-placeholder">搜索菜单、智能体或配置</span>
    <kbd>{{ shortcutLabel }}</kbd>
  </button>

  <el-dialog
    v-model="visible"
    class="navigation-command-dialog"
    width="min(640px, calc(100vw - 32px))"
    top="12vh"
    :show-close="false"
    :close-on-click-modal="true"
    append-to-body
    @open="focusSearch"
    @closed="restoreFocus"
  >
    <template #header>
      <span class="sr-only">全局导航搜索</span>
    </template>

    <div class="command-search-box">
      <el-input
        ref="searchInput"
        v-model="query"
        size="large"
        clearable
        autocomplete="off"
        placeholder="输入菜单、智能体、路径或能力名称"
        aria-label="全局导航关键词"
        :aria-activedescendant="results[selectedIndex] ? `navigation-command-${selectedIndex}` : undefined"
        @keydown="handleSearchKeydown"
      >
        <template #prefix><el-icon><Search /></el-icon></template>
      </el-input>
      <span class="command-escape">ESC 关闭</span>
    </div>

    <div ref="resultPanel" class="command-results" role="listbox" aria-label="导航搜索结果">
      <button
        v-for="(command, index) in results"
        :id="`navigation-command-${index}`"
        :key="command.key"
        type="button"
        class="command-result"
        :class="{ 'is-selected': index === selectedIndex }"
        role="option"
        :aria-selected="index === selectedIndex"
        @mouseenter="selectedIndex = index"
        @click="selectResult(index)"
      >
        <span class="command-result-icon" :class="{ 'is-agent': command.dynamic }">
          <el-icon><Cpu v-if="command.dynamic" /><Document v-else /></el-icon>
        </span>
        <span class="command-result-copy">
          <span class="command-result-title">{{ command.title }}</span>
          <span class="command-result-meta">{{ resultMeta(index) }}</span>
        </span>
        <span class="command-result-path">{{ command.path }}</span>
        <el-icon class="command-enter"><Right /></el-icon>
      </button>

      <el-empty
        v-if="results.length === 0"
        description="没有匹配的导航入口"
        :image-size="56"
      />
    </div>

    <div class="command-footer" aria-hidden="true">
      <span><kbd>↑</kbd><kbd>↓</kbd> 选择</span>
      <span><kbd>Enter</kbd> 打开</span>
      <span>仅搜索当前账号有权访问的入口</span>
    </div>
  </el-dialog>
</template>

<style scoped>
.global-search-trigger {
  width: min(420px, 30vw);
  min-width: 220px;
  height: 38px;
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 0 10px 0 12px;
  border: 1px solid var(--el-border-color);
  border-radius: 9px;
  background: var(--el-fill-color-extra-light);
  color: var(--el-text-color-secondary);
  cursor: pointer;
  transition: border-color 160ms ease, background-color 160ms ease, box-shadow 160ms ease;
}

.global-search-trigger:hover {
  border-color: color-mix(in srgb, var(--theme-primary, var(--el-color-primary)) 55%, var(--el-border-color));
  background: var(--el-bg-color);
}

.global-search-placeholder {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  text-align: left;
  font-size: 13px;
}

kbd {
  min-width: 22px;
  height: 21px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 0 5px;
  border: 1px solid var(--el-border-color);
  border-radius: 5px;
  background: var(--el-bg-color);
  color: var(--el-text-color-secondary);
  font: 11px/1 ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  box-shadow: 0 1px 0 color-mix(in srgb, var(--el-border-color) 80%, transparent);
}

.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
  border: 0;
}

:global(.navigation-command-dialog) {
  overflow: hidden;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 14px;
  box-shadow: 0 24px 80px rgb(15 23 42 / 24%);
}

:global(.navigation-command-dialog .el-dialog__header) {
  height: 0;
  padding: 0;
  margin: 0;
}

:global(.navigation-command-dialog .el-dialog__body) {
  padding: 12px 12px 0;
}

.command-search-box {
  display: flex;
  align-items: center;
  gap: 10px;
  padding-bottom: 10px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.command-search-box :deep(.el-input) {
  flex: 1;
}

.command-search-box :deep(.el-input__wrapper) {
  box-shadow: none;
  background: transparent;
  padding-left: 4px;
}

.command-search-box :deep(.el-input__inner) {
  font-size: 16px;
}

.command-escape {
  padding-right: 4px;
  color: var(--el-text-color-placeholder);
  font-size: 11px;
  white-space: nowrap;
}

.command-results {
  max-height: min(54vh, 480px);
  min-height: 132px;
  overflow-y: auto;
  padding: 8px 0;
}

.command-result {
  width: 100%;
  min-height: 58px;
  display: flex;
  align-items: center;
  gap: 11px;
  padding: 8px 10px;
  border: 0;
  border-radius: 9px;
  background: transparent;
  color: var(--el-text-color-primary);
  text-align: left;
  cursor: pointer;
}

.command-result.is-selected {
  background: var(--el-color-primary-light-9);
}

.command-result-icon {
  width: 34px;
  height: 34px;
  flex: 0 0 34px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 9px;
  background: var(--el-bg-color);
  color: var(--el-text-color-secondary);
}

.command-result-icon.is-agent {
  border-color: color-mix(in srgb, var(--theme-primary, var(--el-color-primary)) 30%, transparent);
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
}

.command-result-copy {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.command-result-title {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 14px;
  font-weight: 600;
}

.command-result-meta,
.command-result-path {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.command-result-path {
  max-width: 180px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
}

.command-enter {
  flex: 0 0 auto;
  color: var(--el-text-color-placeholder);
}

.command-footer {
  min-height: 38px;
  display: flex;
  align-items: center;
  gap: 14px;
  margin: 0 -12px;
  padding: 0 14px;
  border-top: 1px solid var(--el-border-color-lighter);
  background: var(--el-fill-color-extra-light);
  color: var(--el-text-color-secondary);
  font-size: 11px;
}

.command-footer span:last-child {
  margin-left: auto;
}

.command-footer kbd + kbd {
  margin-left: 3px;
}

@media (max-width: 1120px) {
  .global-search-trigger {
    width: min(300px, 26vw);
    min-width: 164px;
  }

  .global-search-trigger kbd {
    display: none;
  }
}

@media (max-width: 760px) {
  .command-result-path,
  .command-footer span:last-child {
    display: none;
  }
}

@media (prefers-reduced-motion: reduce) {
  .global-search-trigger {
    transition: none;
  }
}
</style>
