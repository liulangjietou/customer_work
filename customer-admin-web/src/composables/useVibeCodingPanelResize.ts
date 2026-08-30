import { computed, onBeforeUnmount, onMounted, ref, watch, type Ref } from 'vue'
import {
  DEFAULT_ARTIFACTS_PANEL_WIDTH,
  clampPreferredArtifactsPanelWidth,
  parseStoredArtifactsPanelWidth,
  resolveVibeCodingPanelResize,
} from '@/utils/vibeCodingPanelResize'

const ARTIFACTS_PANEL_WIDTH_STORAGE_KEY = 'customer-admin-vibecoding-artifacts-width'
const KEYBOARD_RESIZE_STEP = 16
const KEYBOARD_RESIZE_LARGE_STEP = 48
const RESIZING_BODY_CLASS = 'is-vibecoding-panel-resizing'

interface UseVibeCodingPanelResizeOptions {
  panelRef: Ref<HTMLElement | undefined>
  historyCollapsed: Ref<boolean>
}

/**
 * 管理 VibeCoding 对话区与产物区之间的分隔线。
 * preferredWidth 保存用户偏好，effectiveWidth 只负责当前容器下的临时夹紧，避免窄屏覆盖宽屏偏好。
 */
export function useVibeCodingPanelResize({ panelRef, historyCollapsed }: UseVibeCodingPanelResizeOptions) {
  const containerWidth = ref(0)
  const preferredWidth = ref(DEFAULT_ARTIFACTS_PANEL_WIDTH)
  const isResizing = ref(false)
  let resizeObserver: ResizeObserver | undefined
  let activePointerId: number | undefined
  let activeHandle: HTMLElement | undefined
  let dragStartX = 0
  let dragStartWidth = DEFAULT_ARTIFACTS_PANEL_WIDTH
  let dragStartPreferredWidth = DEFAULT_ARTIFACTS_PANEL_WIDTH
  let dragChanged = false

  const layout = computed(() => resolveVibeCodingPanelResize({
    containerWidth: containerWidth.value,
    preferredWidth: preferredWidth.value,
    historyCollapsed: historyCollapsed.value,
  }))
  const resizeEnabled = computed(() => layout.value.resizeEnabled)
  const effectiveWidth = computed(() => layout.value.effectiveWidth)
  const panelStyle = computed(() => ({
    '--vibe-artifacts-column-width': `${effectiveWidth.value}px`,
  }))
  const ariaValueText = computed(() => `产物文件区宽度 ${effectiveWidth.value} 像素`)

  function measureContainer() {
    containerWidth.value = panelRef.value?.clientWidth ?? 0
  }

  function readPreferredWidth() {
    try {
      preferredWidth.value = parseStoredArtifactsPanelWidth(window.localStorage.getItem(ARTIFACTS_PANEL_WIDTH_STORAGE_KEY))
    } catch {
      preferredWidth.value = DEFAULT_ARTIFACTS_PANEL_WIDTH
    }
  }

  function persistPreferredWidth() {
    try {
      window.localStorage.setItem(ARTIFACTS_PANEL_WIDTH_STORAGE_KEY, String(preferredWidth.value))
    } catch {
      // 布局偏好写入失败不影响 VibeCoding 主链路，保持本次页面内的有效宽度即可。
    }
  }

  function setPreferredWidth(width: number, persist: boolean) {
    preferredWidth.value = clampPreferredArtifactsPanelWidth(width)
    if (persist) {
      persistPreferredWidth()
    }
  }

  /** 用户主动拖动/按键时按当前布局边界写入；单纯容器变窄不会走这里，因此不会覆盖宽屏偏好。 */
  function setEffectiveWidth(width: number, persist: boolean): boolean {
    const nextWidth = Math.min(
      layout.value.maxWidth,
      Math.max(layout.value.minWidth, clampPreferredArtifactsPanelWidth(width)),
    )
    if (nextWidth === effectiveWidth.value) {
      return false
    }
    preferredWidth.value = nextWidth
    if (persist) {
      persistPreferredWidth()
    }
    return true
  }

  function applyDrag(clientX: number): boolean {
    return setEffectiveWidth(dragStartWidth - (clientX - dragStartX), false)
  }

  function cleanupResize(pointerId?: number) {
    const capturedPointerId = activePointerId
    const handle = activeHandle
    activePointerId = undefined
    activeHandle = undefined
    isResizing.value = false
    document.body.classList.remove(RESIZING_BODY_CLASS)
    if (
      capturedPointerId !== undefined
      && pointerId !== capturedPointerId
      && handle?.hasPointerCapture(capturedPointerId)
    ) {
      handle.releasePointerCapture(capturedPointerId)
    }
  }

  function handlePointerDown(event: PointerEvent) {
    if (!resizeEnabled.value || !event.isPrimary || event.button !== 0 || activePointerId !== undefined) {
      return
    }
    activePointerId = event.pointerId
    activeHandle = event.currentTarget as HTMLElement
    dragStartX = event.clientX
    dragStartWidth = effectiveWidth.value
    dragStartPreferredWidth = preferredWidth.value
    dragChanged = false
    activeHandle.setPointerCapture(event.pointerId)
    activeHandle.focus()
    isResizing.value = true
    document.body.classList.add(RESIZING_BODY_CLASS)
    event.preventDefault()
  }

  function handlePointerMove(event: PointerEvent) {
    if (activePointerId !== event.pointerId) {
      return
    }
    dragChanged = applyDrag(event.clientX) || dragChanged
    event.preventDefault()
  }

  function handlePointerUp(event: PointerEvent) {
    if (activePointerId !== event.pointerId) {
      return
    }
    dragChanged = applyDrag(event.clientX) || dragChanged
    if (activeHandle?.hasPointerCapture(event.pointerId)) {
      activeHandle.releasePointerCapture(event.pointerId)
    }
    cleanupResize(event.pointerId)
    if (dragChanged) {
      persistPreferredWidth()
    }
  }

  function handlePointerCancel(event: PointerEvent) {
    if (activePointerId !== event.pointerId) {
      return
    }
    preferredWidth.value = dragStartPreferredWidth
    if (activeHandle?.hasPointerCapture(event.pointerId)) {
      activeHandle.releasePointerCapture(event.pointerId)
    }
    cleanupResize(event.pointerId)
  }

  function handleLostPointerCapture(event: PointerEvent) {
    if (activePointerId !== event.pointerId) {
      return
    }
    cleanupResize(event.pointerId)
    persistPreferredWidth()
  }

  function handleKeydown(event: KeyboardEvent) {
    if (!resizeEnabled.value) {
      return
    }
    const step = event.shiftKey ? KEYBOARD_RESIZE_LARGE_STEP : KEYBOARD_RESIZE_STEP
    let nextWidth: number | undefined
    switch (event.key) {
      case 'ArrowLeft':
        nextWidth = effectiveWidth.value + step
        break
      case 'ArrowRight':
        nextWidth = effectiveWidth.value - step
        break
      case 'Home':
        nextWidth = layout.value.minWidth
        break
      case 'End':
        nextWidth = layout.value.maxWidth
        break
      case 'Enter':
        event.preventDefault()
        resetWidth()
        return
      default:
        return
    }
    if (nextWidth === undefined) {
      return
    }
    event.preventDefault()
    setEffectiveWidth(nextWidth, true)
  }

  function resetWidth() {
    setPreferredWidth(DEFAULT_ARTIFACTS_PANEL_WIDTH, true)
  }

  onMounted(() => {
    readPreferredWidth()
    measureContainer()
    resizeObserver = new ResizeObserver(measureContainer)
    if (panelRef.value) {
      resizeObserver.observe(panelRef.value)
    }
  })

  watch(resizeEnabled, (enabled) => {
    if (!enabled && activePointerId !== undefined) {
      // 布局在拖动中切为堆叠态等价于取消本次手势，不能留下未持久化的半成品偏好。
      preferredWidth.value = dragStartPreferredWidth
      cleanupResize()
    }
  })

  onBeforeUnmount(() => {
    resizeObserver?.disconnect()
    cleanupResize()
  })

  return {
    ariaValueText,
    effectiveWidth,
    handleKeydown,
    handleLostPointerCapture,
    handlePointerCancel,
    handlePointerDown,
    handlePointerMove,
    handlePointerUp,
    isResizing,
    layout,
    panelStyle,
    resetWidth,
    resizeEnabled,
  }
}
