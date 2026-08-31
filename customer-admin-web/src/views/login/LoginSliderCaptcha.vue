<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { fetchLoginCaptchaChallenge, verifyLoginCaptcha } from '@/api/auth'
import { getRequestErrorMessage } from '@/api/request'
import type { LoginCaptchaChallenge, LoginCaptchaTrackPoint } from '@/types/api'

type CaptchaState = 'loading' | 'ready' | 'dragging' | 'verifying' | 'verified' | 'failed'

const props = withDefaults(defineProps<{
  disabled?: boolean
  primaryTextColor?: string
}>(), { disabled: false, primaryTextColor: '#ffffff' })

const emit = defineEmits<{
  verified: [proof: string]
  invalidated: []
  'open-change': [open: boolean]
}>()

const TRACK_MAX = 1000
const CLIENT_MAX_POINTS = 80
const HANDLE_OUTER_WIDTH = 46
const FILL_HANDLE_OFFSET_PX = HANDLE_OUTER_WIDTH - 2
const MIN_KEYBOARD_TRAJECTORY_MS = 320

const modalRef = ref<HTMLElement>()
const closeRef = ref<HTMLButtonElement>()
const canvasRef = ref<HTMLElement>()
const trackRef = ref<HTMLElement>()
const handleRef = ref<HTMLElement>()
const isOpen = ref(false)
const state = ref<CaptchaState>('loading')
const challenge = ref<LoginCaptchaChallenge>()
const progress = ref(0)
const handleOffsetPx = ref(0)
const canvasWidthPx = ref(0)
const remainingSeconds = ref(0)
const errorMessage = ref('')
const refreshMessage = ref('')

let activePointerId: number | undefined
let dragStartX = 0
let dragStartY = 0
let dragStartAt = 0
let maxTravelPx = 0
let trajectory: LoginCaptchaTrackPoint[] = []
let requestGeneration = 0
let disposed = false
let challengeLoadPromise: Promise<void> | undefined
let preserveFailureIntent = false
let expiryTimer: ReturnType<typeof setTimeout> | undefined
let countdownTimer: ReturnType<typeof setInterval> | undefined
let proofExpiryTimer: ReturnType<typeof setTimeout> | undefined
let keyboardSubmitTimer: ReturnType<typeof setTimeout> | undefined
let resizeObserver: ResizeObserver | undefined

const interactionDisabled = computed(() => (
  props.disabled || !challenge.value || state.value === 'loading'
  || state.value === 'verifying' || state.value === 'verified'
))

const statusText = computed(() => {
  switch (state.value) {
    case 'loading': return '正在准备安全验证…'
    case 'dragging': return '拖动拼图块，对准图中的缺口'
    case 'verifying': return '正在校验拖动轨迹…'
    case 'verified': return '验证通过，正在登录'
    case 'failed': return errorMessage.value || '验证未通过，请重新拖动'
    default: return refreshMessage.value || '按住滑块，将拼图块拖到缺口处'
  }
})

const entryText = computed(() => state.value === 'verified' ? '验证通过' : '完成安全验证')
const trackStyle = computed(() => ({
  '--captcha-handle-offset': `${handleOffsetPx.value}px`,
  '--captcha-fill-width': state.value === 'verified' ? '100%' : `${handleOffsetPx.value + FILL_HANDLE_OFFSET_PX}px`,
  '--captcha-thumb-text': props.primaryTextColor,
}))
const canvasStyle = computed(() => ({
  aspectRatio: challenge.value ? `${challenge.value.canvasWidth} / ${challenge.value.canvasHeight}` : '2 / 1',
}))
const pieceStyle = computed(() => {
  const current = challenge.value
  const scale = current && current.canvasWidth > 0 && canvasWidthPx.value > 0
    ? canvasWidthPx.value / current.canvasWidth : 1
  const pieceWidth = (current?.pieceWidth ?? 0) * scale
  const pieceHeight = (current?.pieceHeight ?? 0) * scale
  const travel = Math.max(0, canvasWidthPx.value - pieceWidth)
  return {
    width: `${pieceWidth}px`,
    height: `${pieceHeight}px`,
    top: `${(current?.pieceY ?? 0) * scale}px`,
    transform: `translate3d(${travel * progress.value / TRACK_MAX}px, 0, 0)`,
  }
})

function clearExpiryTimer() {
  if (expiryTimer) clearTimeout(expiryTimer)
  expiryTimer = undefined
  if (countdownTimer) clearInterval(countdownTimer)
  countdownTimer = undefined
  remainingSeconds.value = 0
}

function clearProofExpiryTimer() {
  if (proofExpiryTimer) clearTimeout(proofExpiryTimer)
  proofExpiryTimer = undefined
}

function clearKeyboardSubmitTimer() {
  if (keyboardSubmitTimer) clearTimeout(keyboardSubmitTimer)
  keyboardSubmitTimer = undefined
}

function updateCountdown(expiresAt: number) {
  remainingSeconds.value = Math.max(0, Math.ceil((expiresAt - Date.now()) / 1000))
}

function expireChallenge(generation: number) {
  if (disposed || generation !== requestGeneration) return
  errorMessage.value = '验证已过期，已刷新，请重新拖动'
  state.value = 'failed'
  if (!isOpen.value) {
    // 浮层关闭时不继续签发 challenge，避免长期停留登录页耗尽来源限额。
    challenge.value = undefined
    remainingSeconds.value = 0
    invalidateProof()
    return
  }
  void loadChallenge(true)
}

function scheduleExpiry(ttlSeconds: number, generation: number) {
  clearExpiryTimer()
  const expiresAt = Date.now() + Math.max(1, ttlSeconds) * 1000
  updateCountdown(expiresAt)
  countdownTimer = setInterval(() => updateCountdown(expiresAt), 1000)
  expiryTimer = setTimeout(() => expireChallenge(generation), Math.max(1, ttlSeconds) * 1000)
}

function measureTrack() {
  if (!trackRef.value) { maxTravelPx = 0; return }
  maxTravelPx = Math.max(0, trackRef.value.clientWidth - HANDLE_OUTER_WIDTH)
  handleOffsetPx.value = maxTravelPx * progress.value / TRACK_MAX
}

function measureCanvas() { canvasWidthPx.value = canvasRef.value?.clientWidth ?? 0 }
function measureGeometry() { measureTrack(); measureCanvas() }
function observeGeometry() {
  resizeObserver?.disconnect()
  if (trackRef.value) resizeObserver?.observe(trackRef.value)
  if (canvasRef.value) resizeObserver?.observe(canvasRef.value)
  measureGeometry()
}

function updateProgress(value: number) {
  progress.value = Math.min(TRACK_MAX, Math.max(0, Math.round(value)))
  handleOffsetPx.value = maxTravelPx * progress.value / TRACK_MAX
}

function invalidateProof() {
  clearProofExpiryTimer()
  emit('invalidated')
}

function scheduleProofExpiry(ttlSeconds: number) {
  clearProofExpiryTimer()
  proofExpiryTimer = setTimeout(() => {
    proofExpiryTimer = undefined
    if (disposed || state.value !== 'verified') return
    state.value = 'failed'
    errorMessage.value = '验证结果已过期，请重新验证'
    challenge.value = undefined
    trajectory = []
    updateProgress(0)
    emit('invalidated')
  }, Math.max(1, ttlSeconds) * 1000)
}

async function performChallengeLoad(announceRefresh = false) {
  const generation = ++requestGeneration
  clearExpiryTimer()
  challenge.value = undefined
  trajectory = []
  activePointerId = undefined
  updateProgress(0)
  invalidateProof()
  refreshMessage.value = ''
  try {
    const nextChallenge = await fetchLoginCaptchaChallenge()
    if (disposed || generation !== requestGeneration) return
    challenge.value = nextChallenge
    state.value = preserveFailureIntent ? 'failed' : 'ready'
    if (announceRefresh && !preserveFailureIntent) refreshMessage.value = '验证已刷新，请重新拖动'
    await nextTick()
    measureGeometry()
    scheduleExpiry(nextChallenge.ttlSeconds, generation)
  } catch (error) {
    if (disposed || generation !== requestGeneration) return
    remainingSeconds.value = 0
    state.value = 'failed'
    errorMessage.value = getRequestErrorMessage(error, '验证服务暂不可用，请刷新后重试')
  }
}

function loadChallenge(preserveFailure = false, announceRefresh = false): Promise<void> {
  // 网络请求保持 single-flight，展示状态以最后一次用户操作为准。
  preserveFailureIntent = preserveFailure
  if (!preserveFailure) { state.value = 'loading'; errorMessage.value = '' }
  if (challengeLoadPromise) return challengeLoadPromise
  const pending = performChallengeLoad(announceRefresh)
  challengeLoadPromise = pending
  void pending.finally(() => {
    if (challengeLoadPromise === pending) challengeLoadPromise = undefined
  })
  return pending
}

async function reset(announceRefresh = false) { await loadChallenge(false, announceRefresh) }

function openModal(force = false) {
  if (!force && props.disabled) return
  isOpen.value = true
  emit('open-change', true)
  if (!challenge.value && !challengeLoadPromise) void reset()
  void nextTick(() => {
    observeGeometry()
    closeRef.value?.focus()
  })
}

function cancelActiveVerification() {
  requestGeneration += 1
  clearExpiryTimer()
  clearKeyboardSubmitTimer()
  challenge.value = undefined
  trajectory = []
  state.value = 'failed'
  errorMessage.value = '验证已取消，请重新验证'
  updateProgress(0)
  invalidateProof()
}

function cancelActiveDrag() {
  const pointerId = activePointerId
  activePointerId = undefined
  if (pointerId !== undefined && handleRef.value?.hasPointerCapture(pointerId)) {
    handleRef.value.releasePointerCapture(pointerId)
  }
  clearKeyboardSubmitTimer()
  trajectory = []
  updateProgress(0)
  if (state.value === 'dragging') state.value = challenge.value ? 'ready' : 'loading'
}

function focusEntry() {
  document.querySelector<HTMLButtonElement>('[data-login-captcha-entry]:not(:disabled)')?.focus()
}

function closeModal(restoreFocus = true) {
  if (state.value === 'verifying') cancelActiveVerification()
  else if (state.value === 'dragging' || activePointerId !== undefined) cancelActiveDrag()
  resizeObserver?.disconnect()
  isOpen.value = false
  emit('open-change', false)
  void nextTick(() => {
    if (!disposed && restoreFocus) focusEntry()
  })
}

function handleCloseClick() {
  closeModal()
}

function requireVerification() {
  if (challenge.value && state.value === 'ready') {
    state.value = 'failed'
    errorMessage.value = '请先完成拖动验证'
  }
  openModal(true)
  if (!challenge.value && !challengeLoadPromise) void reset()
}

function normalizedPoint(clientX: number, clientY: number, elapsed: number): LoginCaptchaTrackPoint {
  const x = maxTravelPx <= 0 ? 0 : Math.round((clientX - dragStartX) / maxTravelPx * TRACK_MAX)
  const trackHeight = Math.max(trackRef.value?.clientHeight ?? 1, 1)
  const y = Math.round((clientY - dragStartY) / trackHeight * TRACK_MAX)
  return { x: Math.min(TRACK_MAX, Math.max(0, x)), y: Math.min(TRACK_MAX, Math.max(-TRACK_MAX, y)), t: Math.max(0, Math.round(elapsed)) }
}

function appendPoint(point: LoginCaptchaTrackPoint, force = false) {
  const previous = trajectory.at(-1)
  if (previous && point.t <= previous.t) point = { ...point, t: previous.t + 1 }
  if (!force && previous && previous.x === point.x && previous.y === point.y) return
  if (trajectory.length >= CLIENT_MAX_POINTS) { trajectory[CLIENT_MAX_POINTS - 1] = point; return }
  trajectory.push(point)
}

function beginDrag(clientX: number, clientY: number) {
  measureGeometry()
  if (maxTravelPx <= 0) return false
  state.value = 'dragging'
  errorMessage.value = ''
  refreshMessage.value = ''
  dragStartX = clientX
  dragStartY = clientY
  dragStartAt = performance.now()
  trajectory = [{ x: 0, y: 0, t: 0 }]
  updateProgress(0)
  return true
}

function handlePointerDown(event: PointerEvent) {
  if (props.disabled || event.button !== 0) return
  if (!challenge.value) { void reset(); return }
  if (interactionDisabled.value || !beginDrag(event.clientX, event.clientY)) return
  activePointerId = event.pointerId
  handleRef.value?.setPointerCapture(event.pointerId)
  handleRef.value?.focus()
  event.preventDefault()
}

function handlePointerMove(event: PointerEvent) {
  if (state.value !== 'dragging' || activePointerId !== event.pointerId) return
  const point = normalizedPoint(event.clientX, event.clientY, performance.now() - dragStartAt)
  updateProgress(point.x)
  appendPoint(point)
  event.preventDefault()
}

function releasePointer(pointerId: number) {
  activePointerId = undefined
  if (handleRef.value?.hasPointerCapture(pointerId)) handleRef.value.releasePointerCapture(pointerId)
}

async function submitTrajectory() {
  const currentChallenge = challenge.value
  if (!currentChallenge) { await reset(); return }
  const generation = ++requestGeneration
  state.value = 'verifying'
  clearExpiryTimer()
  try {
    const result = await verifyLoginCaptcha({
      challengeId: currentChallenge.challengeId,
      placementX: progress.value,
      trajectory: trajectory.map((point) => ({ ...point })),
    })
    if (disposed || generation !== requestGeneration) return
    challenge.value = undefined
    state.value = 'verified'
    scheduleProofExpiry(result.ttlSeconds)
    emit('verified', result.proof)
    closeModal(false)
  } catch (error) {
    if (disposed || generation !== requestGeneration) return
    state.value = 'failed'
    errorMessage.value = getRequestErrorMessage(error, '验证未通过，请重新拖动')
    updateProgress(0)
    await loadChallenge(true)
  }
}

function scheduleKeyboardSubmit() {
  const challengeId = challenge.value?.challengeId
  if (!challengeId) return
  const generation = requestGeneration
  const placementX = progress.value
  const elapsed = performance.now() - dragStartAt
  state.value = 'verifying'
  clearKeyboardSubmitTimer()
  keyboardSubmitTimer = setTimeout(() => {
    keyboardSubmitTimer = undefined
    if (disposed || generation !== requestGeneration || challenge.value?.challengeId !== challengeId) return
    appendPoint({ x: placementX, y: 0, t: Math.max(MIN_KEYBOARD_TRAJECTORY_MS, Math.round(performance.now() - dragStartAt)) }, true)
    void submitTrajectory()
  }, Math.max(0, MIN_KEYBOARD_TRAJECTORY_MS - elapsed))
}

function finishDrag(event: PointerEvent) {
  if (state.value !== 'dragging' || activePointerId !== event.pointerId) return
  const point = normalizedPoint(event.clientX, event.clientY, performance.now() - dragStartAt)
  appendPoint(point, true)
  updateProgress(point.x)
  releasePointer(event.pointerId)
  // targetX 不会下发客户端；释放位置就是本次 placementX，由服务端判定是否对准缺口。
  void submitTrajectory()
}

function handlePointerCancel(event: PointerEvent) {
  if (activePointerId !== event.pointerId) return
  releasePointer(event.pointerId)
  state.value = 'failed'
  errorMessage.value = '拖动已取消，请重新验证'
  trajectory = []
  updateProgress(0)
}

function handleLostPointerCapture(event: PointerEvent) {
  if (state.value !== 'dragging' || activePointerId !== event.pointerId) return
  activePointerId = undefined
  trajectory = []
  state.value = 'failed'
  errorMessage.value = '拖动已中断，请重新验证'
  updateProgress(0)
}

function appendKeyboardPoint(nextProgress: number) {
  appendPoint({ x: nextProgress, y: 0, t: Math.round(performance.now() - dragStartAt) }, true)
  updateProgress(nextProgress)
}

function handleKeydown(event: KeyboardEvent) {
  if (props.disabled || state.value === 'loading' || state.value === 'verifying' || state.value === 'verified') return
  if (!challenge.value) {
    if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); void reset() }
    return
  }
  if (event.key === 'Home') {
    event.preventDefault(); state.value = 'ready'; trajectory = []; updateProgress(0); return
  }
  if (event.key === 'End') {
    event.preventDefault()
    if (state.value !== 'dragging' && !beginDrag(0, 0)) return
    appendKeyboardPoint(TRACK_MAX)
    return
  }
  if (event.key === 'Enter') {
    event.preventDefault()
    if (state.value !== 'dragging' && !beginDrag(0, 0)) return
    appendKeyboardPoint(progress.value)
    scheduleKeyboardSubmit()
    return
  }
  if (!['ArrowRight', 'ArrowLeft', 'PageUp', 'PageDown'].includes(event.key)) return
  event.preventDefault()
  if (state.value !== 'dragging' && !beginDrag(0, 0)) return
  const direction = event.key === 'ArrowRight' || event.key === 'PageUp' ? 1 : -1
  const step = event.shiftKey || event.key === 'PageUp' || event.key === 'PageDown' ? 100 : 20
  appendKeyboardPoint(Math.min(TRACK_MAX, Math.max(0, progress.value + direction * step)))
}

function focusableElements() {
  return Array.from(modalRef.value?.querySelectorAll<HTMLElement>('button:not([disabled]), [tabindex]:not([tabindex="-1"])') ?? [])
}

function handleModalKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') { event.preventDefault(); closeModal(); return }
  if (event.key !== 'Tab') return
  const focusables = focusableElements()
  if (!focusables.length) { event.preventDefault(); modalRef.value?.focus(); return }
  const first = focusables[0]
  const last = focusables[focusables.length - 1]
  if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus() }
  else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus() }
}

onMounted(() => {
  resizeObserver = new ResizeObserver(measureGeometry)
  observeGeometry()
  void reset()
})

onBeforeUnmount(() => {
  disposed = true
  requestGeneration += 1
  clearExpiryTimer()
  clearProofExpiryTimer()
  clearKeyboardSubmitTimer()
  resizeObserver?.disconnect()
})

defineExpose({ reset, requireVerification, focusEntry })
</script>

<template>
  <div class="login-captcha">
    <button type="button" class="captcha-entry" data-login-captcha-entry :disabled="disabled" :aria-expanded="isOpen" aria-controls="login-captcha-dialog" @click="openModal()">
      <span class="captcha-entry-icon" aria-hidden="true"><i /><i /></span>
      <span><strong aria-live="polite">{{ entryText }}</strong><small>{{ state === 'failed' ? '验证失败，点击重试' : '完成拼图后自动继续登录' }}</small></span>
      <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m9 6 6 6-6 6" /></svg>
    </button>

    <Teleport to="body">
      <div v-if="isOpen" class="captcha-layer" @click.self="handleCloseClick">
        <section id="login-captcha-dialog" ref="modalRef" class="captcha-dialog" role="dialog" aria-modal="true" aria-labelledby="login-captcha-title" tabindex="-1" @keydown="handleModalKeydown">
          <header class="captcha-dialog-header">
            <div><p class="captcha-kicker">SECURITY CHECK</p><h2 id="login-captcha-title">完成安全验证</h2></div>
            <div class="captcha-header-actions">
              <button type="button" class="captcha-icon-button" data-login-captcha-refresh aria-label="刷新验证" :disabled="state === 'loading' || state === 'verifying'" @click="reset(true)">
                <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M20 11a8 8 0 0 0-14.8-4L4 9" /><path d="M4 4v5h5" /><path d="M4 13a8 8 0 0 0 14.8 4L20 15" /><path d="M20 20v-5h-5" /></svg>
              </button>
              <button ref="closeRef" type="button" class="captcha-icon-button" aria-label="关闭安全验证" @click="handleCloseClick">
                <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m6 6 12 12M18 6 6 18" /></svg>
              </button>
            </div>
          </header>
          <div class="captcha-dialog-body">
            <p class="captcha-instruction">拖动下方滑块，让拼图块与图中的缺口重合</p>
            <div ref="canvasRef" class="captcha-canvas" :class="`is-${state}`" :style="canvasStyle" role="img" aria-label="登录安全验证拼图">
              <img v-if="challenge" class="captcha-background" :src="challenge.backgroundImage" alt="拼图背景" draggable="false">
              <img v-if="challenge" class="captcha-piece" :style="pieceStyle" :src="challenge.puzzlePieceImage" alt="可移动拼图块" draggable="false">
              <div v-if="state === 'loading'" class="captcha-canvas-loading">正在加载拼图…</div>
            </div>
            <div ref="trackRef" class="captcha-track" :class="`is-${state}`" :style="trackStyle" data-login-captcha>
              <span class="captcha-fill" aria-hidden="true" /><span class="captcha-copy" :class="{ 'is-error': state === 'failed' }" aria-live="polite">{{ statusText }}</span>
              <span ref="handleRef" class="captcha-handle" role="slider" tabindex="0" aria-label="拖动验证码" aria-valuemin="0" aria-valuemax="1000" :aria-valuenow="progress" :aria-valuetext="`${statusText}，当前位置 ${progress}/1000`" :aria-disabled="interactionDisabled" @pointerdown="handlePointerDown" @pointermove="handlePointerMove" @pointerup="finishDrag" @pointercancel="handlePointerCancel" @lostpointercapture="handleLostPointerCapture" @keydown="handleKeydown">
                <svg v-if="state === 'verified'" viewBox="0 0 24 24" aria-hidden="true"><path d="m5 12.5 4.2 4.2L19 7" /></svg>
                <svg v-else-if="state === 'failed' && !challenge" viewBox="0 0 24 24" aria-hidden="true"><path d="M18.3 8.2A7.6 7.6 0 1 0 19.5 14" /><path d="M18.3 4.5v3.7h-3.7" /></svg>
                <svg v-else viewBox="0 0 24 24" aria-hidden="true"><path d="m6 7 5 5-5 5" /><path d="m11 7 5 5-5 5" /></svg>
              </span>
            </div>
            <div class="captcha-meta"><span aria-live="polite">{{ remainingSeconds > 0 ? `本次验证剩余 ${remainingSeconds} 秒` : '等待验证挑战' }}</span><span>支持鼠标、触摸和键盘</span></div>
          </div>
        </section>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.login-captcha { margin-bottom: 18px; }
.captcha-entry { display: flex; width: 100%; min-height: 58px; align-items: center; gap: 12px; box-sizing: border-box; padding: 9px 14px; border: 1px solid var(--el-border-color); border-radius: 12px; background: linear-gradient(120deg, var(--el-fill-color-blank), var(--el-color-primary-light-9)); color: var(--el-text-color-primary); font: inherit; text-align: left; cursor: pointer; transition: border-color 160ms ease, box-shadow 160ms ease, transform 160ms ease; }
.captcha-entry:hover:not(:disabled) { border-color: var(--el-color-primary-light-3); box-shadow: 0 8px 24px rgba(49, 69, 160, 0.12); transform: translateY(-1px); }
.captcha-entry:focus-visible, .captcha-icon-button:focus-visible, .captcha-handle:focus-visible { outline: 3px solid var(--el-color-primary-light-5); outline-offset: 2px; }
.captcha-entry:disabled { cursor: wait; opacity: .72; }
.captcha-entry-icon { position: relative; display: grid; width: 30px; height: 30px; flex: none; place-items: center; border-radius: 9px; background: var(--el-color-primary); }
.captcha-entry-icon::before, .captcha-entry-icon::after, .captcha-entry-icon i { position: absolute; display: block; border: 1.5px solid #fff; content: ''; }
.captcha-entry-icon::before { width: 13px; height: 13px; border-radius: 3px; }
.captcha-entry-icon::after { width: 6px; height: 6px; border-radius: 2px; background: var(--el-color-primary); }
.captcha-entry-icon i:first-child, .captcha-entry-icon i:last-child { width: 4px; height: 4px; border: 0; border-radius: 50%; background: #fff; }
.captcha-entry-icon i:first-child { transform: translate(-8px, -8px); }
.captcha-entry-icon i:last-child { transform: translate(8px, 8px); }
.captcha-entry strong, .captcha-entry small { display: block; }
.captcha-entry strong { font-size: 13px; font-weight: 700; }
.captcha-entry small { margin-top: 2px; color: var(--el-text-color-secondary); font-size: 12px; }
.captcha-entry > svg { width: 18px; height: 18px; margin-left: auto; fill: none; stroke: var(--el-color-primary); stroke-linecap: round; stroke-linejoin: round; stroke-width: 2; }
.captcha-layer { position: fixed; z-index: 3000; inset: 0; display: flex; align-items: center; justify-content: flex-end; padding: 24px clamp(20px, 3vw, 52px); box-sizing: border-box; background: rgba(13, 22, 57, .38); backdrop-filter: blur(3px); }
.captcha-dialog { width: min(424px, calc(100vw - 40px)); max-height: min(680px, calc(100svh - 48px)); overflow: hidden auto; border: 1px solid color-mix(in srgb, var(--el-color-primary) 20%, var(--el-border-color)); border-radius: 18px; background: var(--el-bg-color); box-shadow: 0 24px 70px rgba(14, 23, 63, .28); color: var(--el-text-color-primary); }
.captcha-dialog-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; padding: 22px 22px 16px; background: linear-gradient(135deg, var(--el-color-primary-light-9), var(--el-bg-color)); }
.captcha-kicker { margin: 0 0 5px; color: var(--el-color-primary); font-size: 10px; font-weight: 750; letter-spacing: .14em; }
.captcha-dialog h2 { margin: 0; font-size: 20px; line-height: 1.25; }
.captcha-header-actions { display: flex; gap: 4px; }
.captcha-icon-button { display: inline-grid; width: 44px; height: 44px; flex: none; place-items: center; border: 0; border-radius: 10px; background: transparent; color: var(--el-text-color-secondary); cursor: pointer; }
.captcha-icon-button:hover:not(:disabled) { background: var(--el-color-primary-light-9); color: var(--el-color-primary); }
.captcha-icon-button:disabled { cursor: wait; opacity: .5; }
.captcha-icon-button svg { width: 20px; height: 20px; fill: none; stroke: currentColor; stroke-linecap: round; stroke-linejoin: round; stroke-width: 1.8; }
.captcha-dialog-body { padding: 0 22px 23px; }
.captcha-instruction { margin: 0 0 13px; color: var(--el-text-color-secondary); font-size: 13px; line-height: 1.5; }
.captcha-canvas { position: relative; width: 100%; min-height: 120px; overflow: hidden; border: 1px solid var(--el-border-color-lighter); border-radius: 10px; background: var(--el-fill-color-extra-light); }
.captcha-background, .captcha-piece { position: absolute; inset: 0 auto auto 0; display: block; max-width: none; user-select: none; }
.captcha-background { width: 100%; height: 100%; object-fit: fill; }
.captcha-piece { z-index: 1; will-change: transform; }
.captcha-canvas-loading { position: absolute; inset: 0; display: grid; place-items: center; color: var(--el-text-color-secondary); font-size: 13px; }
.captcha-track { position: relative; width: 100%; height: 48px; box-sizing: border-box; margin-top: 16px; overflow: hidden; border: 1px solid var(--el-border-color-lighter); border-radius: 10px; background: linear-gradient(180deg, var(--el-fill-color-blank), var(--el-fill-color-extra-light)); user-select: none; }
.captcha-track.is-failed { border-color: var(--el-color-danger-light-5); background: var(--el-color-danger-light-9); }
.captcha-track.is-verified { border-color: var(--el-color-success-light-5); background: var(--el-color-success-light-9); }
.captcha-fill { position: absolute; inset: 0 auto 0 0; width: var(--captcha-fill-width); background: linear-gradient(90deg, var(--el-color-primary-light-8), transparent); transition: width 100ms ease-out; }
.is-verified .captcha-fill { background: var(--el-color-success-light-8); }
.is-failed .captcha-fill { background: var(--el-color-danger-light-8); }
.captcha-copy { position: absolute; inset: 0 48px 0 50px; display: grid; place-items: center; overflow: hidden; color: var(--el-text-color-secondary); font-size: 13px; font-weight: 500; text-overflow: ellipsis; white-space: nowrap; }
.is-dragging .captcha-copy, .is-verifying .captcha-copy { color: var(--el-color-primary-dark-2); }
.is-verified .captcha-copy { color: var(--el-color-success-dark-2, #168d78); font-weight: 650; }
.captcha-copy.is-error { color: var(--el-color-danger-dark-2, #bd4f5a); }
.captcha-handle { position: absolute; top: 2px; left: 2px; display: grid; width: 42px; height: 42px; box-sizing: border-box; place-items: center; border-radius: 7px; background: linear-gradient(135deg, var(--theme-primary-solid, var(--el-color-primary)), var(--theme-primary-solid-active, var(--el-color-primary-dark-2))); box-shadow: 0 5px 14px color-mix(in srgb, var(--theme-primary-solid, var(--el-color-primary)) 28%, transparent); color: var(--captcha-thumb-text); cursor: grab; outline: none; touch-action: none; transform: translate3d(var(--captcha-handle-offset), 0, 0); transition: transform 100ms ease-out, background-color 160ms ease-out, box-shadow 160ms ease-out; }
.is-dragging .captcha-handle { cursor: grabbing; transition: none; }
.is-loading .captcha-handle, .is-verifying .captcha-handle { cursor: wait; opacity: .78; }
.is-verified .captcha-handle { color: var(--cw-on-success, #fff); background: var(--cw-success-solid, #16856a); cursor: default; }
.is-failed .captcha-handle { color: var(--cw-on-danger, #fff); background: var(--cw-danger-solid, #c2414b); }
.captcha-handle svg { width: 21px; height: 21px; fill: none; stroke: currentColor; stroke-linecap: round; stroke-linejoin: round; stroke-width: 1.8; }
.captcha-meta { display: flex; justify-content: space-between; gap: 10px; margin-top: 9px; color: var(--el-text-color-placeholder); font-size: 12px; line-height: 1.4; }
@media (prefers-reduced-motion: reduce) { .captcha-entry, .captcha-fill, .captcha-handle { transition: none; } }
@media (max-width: 899px) { .captcha-layer { align-items: flex-end; padding: 0; } .captcha-dialog { width: 100%; max-height: min(92svh, 620px); border-radius: 18px 18px 0 0; border-bottom: 0; } }
@media (max-width: 380px), (max-height: 520px) { .captcha-dialog { max-height: 96svh; } .captcha-dialog-header { padding: 14px 16px 10px; } .captcha-dialog-body { padding: 0 16px 16px; } .captcha-dialog h2 { font-size: 18px; } .captcha-instruction { margin-bottom: 8px; } .captcha-track { margin-top: 10px; } .captcha-meta { font-size: 11px; } }
</style>
