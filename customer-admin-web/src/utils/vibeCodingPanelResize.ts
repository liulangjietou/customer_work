export const DEFAULT_ARTIFACTS_PANEL_WIDTH = 260
export const MIN_ARTIFACTS_PANEL_WIDTH = 220
export const MAX_ARTIFACTS_PANEL_WIDTH = 520

export const STACKED_LAYOUT_MAX_WIDTH = 700
export const COMPACT_LAYOUT_MAX_WIDTH = 1040

export const WIDE_CONVERSATION_MIN_WIDTH = 520
export const COMPACT_CONVERSATION_MIN_WIDTH = 480
export const EXPANDED_HISTORY_RESERVED_WIDTH = 300
export const COLLAPSED_HISTORY_RESERVED_WIDTH = 0

export type VibeCodingLayoutMode = 'wide' | 'compact' | 'stacked'

export interface VibeCodingPanelResizeInput {
  containerWidth: number
  preferredWidth: number
  historyCollapsed: boolean
}

export interface VibeCodingPanelResizeResult {
  mode: VibeCodingLayoutMode
  resizeEnabled: boolean
  minWidth: number
  maxWidth: number
  effectiveWidth: number
}

/**
 * 将拖动产生的宽度归一到用户可保存的偏好范围。
 * 非有限数不代表有效拖动结果，回落默认值，避免 NaN 进入样式或持久化。
 */
export function clampPreferredArtifactsPanelWidth(width: number): number {
  if (!Number.isFinite(width)) return DEFAULT_ARTIFACTS_PANEL_WIDTH
  return Math.min(MAX_ARTIFACTS_PANEL_WIDTH, Math.max(MIN_ARTIFACTS_PANEL_WIDTH, width))
}

/** localStorage 只接受完整、有限且位于偏好范围内的数值。损坏数据统一回落默认值。 */
export function parseStoredArtifactsPanelWidth(raw: string | null | undefined): number {
  if (raw == null || raw.trim() === '') return DEFAULT_ARTIFACTS_PANEL_WIDTH

  const width = Number(raw)
  if (!Number.isFinite(width)
    || width < MIN_ARTIFACTS_PANEL_WIDTH
    || width > MAX_ARTIFACTS_PANEL_WIDTH) {
    return DEFAULT_ARTIFACTS_PANEL_WIDTH
  }
  return width
}

export function resolveVibeCodingLayoutMode(containerWidth: number): VibeCodingLayoutMode {
  const safeContainerWidth = normalizeContainerWidth(containerWidth)
  if (safeContainerWidth <= STACKED_LAYOUT_MAX_WIDTH) return 'stacked'
  if (safeContainerWidth <= COMPACT_LAYOUT_MAX_WIDTH) return 'compact'
  return 'wide'
}

/**
 * 同时给出当前布局和产物列的临时有效宽度。
 * preferredWidth 不会被窄容器的 maxWidth 覆盖；容器恢复后用同一偏好重新计算即可复原。
 */
export function resolveVibeCodingPanelResize(
  input: VibeCodingPanelResizeInput,
): VibeCodingPanelResizeResult {
  const containerWidth = normalizeContainerWidth(input.containerWidth)
  const preferredWidth = clampPreferredArtifactsPanelWidth(input.preferredWidth)
  const mode = resolveVibeCodingLayoutMode(containerWidth)

  if (mode === 'stacked') {
    return {
      mode,
      resizeEnabled: false,
      minWidth: MIN_ARTIFACTS_PANEL_WIDTH,
      maxWidth: MAX_ARTIFACTS_PANEL_WIDTH,
      effectiveWidth: preferredWidth,
    }
  }

  const conversationMinWidth = mode === 'wide'
    ? WIDE_CONVERSATION_MIN_WIDTH
    : COMPACT_CONVERSATION_MIN_WIDTH
  const historyReservedWidth = mode === 'wide' && !input.historyCollapsed
    ? EXPANDED_HISTORY_RESERVED_WIDTH
    : COLLAPSED_HISTORY_RESERVED_WIDTH
  const availableWidth = containerWidth - conversationMinWidth - historyReservedWidth
  const maxWidth = Math.min(
    MAX_ARTIFACTS_PANEL_WIDTH,
    Math.max(MIN_ARTIFACTS_PANEL_WIDTH, availableWidth),
  )

  return {
    mode,
    resizeEnabled: true,
    minWidth: MIN_ARTIFACTS_PANEL_WIDTH,
    maxWidth,
    effectiveWidth: Math.min(preferredWidth, maxWidth),
  }
}

function normalizeContainerWidth(containerWidth: number): number {
  return Number.isFinite(containerWidth) && containerWidth > 0 ? containerWidth : 0
}
