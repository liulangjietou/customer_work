<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { fetchLoginCaptchaChallenge, verifyLoginCaptcha } from '@/api/auth'
import { getRequestErrorMessage } from '@/api/request'
import type { LoginCaptchaChallenge, LoginCaptchaTrackPoint } from '@/types/api'

type CaptchaState = 'loading' | 'ready' | 'dragging' | 'verifying' | 'verified' | 'failed'

const props = withDefaults(defineProps<{
  disabled?: boolean
  primaryTextColor?: string
}>(), {
  disabled: false,
  primaryTextColor: '#ffffff',
})

const emit = defineEmits<{
  verified: [proof: string]
  invalidated: []
}>()

const TRACK_MAX = 1000
const TRACK_COMPLETE = 980
const CLIENT_MAX_POINTS = 80
const HANDLE_OUTER_WIDTH = 46
const MIN_KEYBOARD_TRAJECTORY_MS = 320

const trackRef = ref<HTMLElement>()
const handleRef = ref<HTMLElement>()
const state = ref<CaptchaState>('loading')
const challenge = ref<LoginCaptchaChallenge>()
const progress = ref(0)
const handleOffsetPx = ref(0)
const errorMessage = ref('')

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
let keyboardSubmitTimer: ReturnType<typeof setTimeout> | undefined
let resizeObserver: ResizeObserver | undefined

const interactionDisabled = computed(() => (
  props.disabled
  || !challenge.value
  || state.value === 'loading'
  || state.value === 'verifying'
  || state.value === 'verified'
))

const statusText = computed(() => {
  switch (state.value) {
    case 'loading':
      return '正在准备安全验证…'
    case 'dragging':
      return '继续拖动，到达轨道终点'
    case 'verifying':
      return '正在校验拖动轨迹…'
    case 'verified':
      return '验证通过'
    case 'failed':
      return errorMessage.value || '验证未通过，请重新拖动'
    default:
      return '按住滑块，拖动完成验证'
  }
})

const trackStyle = computed(() => ({
  '--captcha-handle-offset': `${handleOffsetPx.value}px`,
  '--captcha-fill-width': state.value === 'verified' ? '100%' : `${handleOffsetPx.value + 44}px`,
  '--captcha-thumb-text': props.primaryTextColor,
}))

function clearExpiryTimer() {
  if (expiryTimer) {
    clearTimeout(expiryTimer)
    expiryTimer = undefined
  }
}

function clearKeyboardSubmitTimer() {
  if (keyboardSubmitTimer) {
    clearTimeout(keyboardSubmitTimer)
    keyboardSubmitTimer = undefined
  }
}

function scheduleExpiry(ttlSeconds: number, generation: number) {
  clearExpiryTimer()
  expiryTimer = setTimeout(() => {
    if (!disposed && generation === requestGeneration) {
      void reset()
    }
  }, Math.max(1, ttlSeconds) * 1000)
}

function measureTrack() {
  if (!trackRef.value) {
    maxTravelPx = 0
    return
  }
  maxTravelPx = Math.max(0, trackRef.value.clientWidth - HANDLE_OUTER_WIDTH)
  handleOffsetPx.value = maxTravelPx * progress.value / TRACK_MAX
}

function updateProgress(value: number) {
  progress.value = Math.min(TRACK_MAX, Math.max(0, Math.round(value)))
  handleOffsetPx.value = maxTravelPx * progress.value / TRACK_MAX
}

function invalidateProof() {
  emit('invalidated')
}

async function performChallengeLoad() {
  const generation = ++requestGeneration
  clearExpiryTimer()
  clearKeyboardSubmitTimer()
  challenge.value = undefined
  trajectory = []
  activePointerId = undefined
  updateProgress(0)
  invalidateProof()
  try {
    const nextChallenge = await fetchLoginCaptchaChallenge()
    if (disposed || generation !== requestGeneration) {
      return
    }
    challenge.value = nextChallenge
    state.value = preserveFailureIntent ? 'failed' : 'ready'
    scheduleExpiry(nextChallenge.ttlSeconds, generation)
  } catch (error) {
    if (disposed || generation !== requestGeneration) {
      return
    }
    state.value = 'failed'
    errorMessage.value = getRequestErrorMessage(error, '验证服务暂不可用，点击滑块重试')
  }
}

function loadChallenge(preserveFailure = false): Promise<void> {
  // 网络请求保持 single-flight，展示状态以最后一次用户操作为准。
  preserveFailureIntent = preserveFailure
  if (!preserveFailure) {
    state.value = 'loading'
    errorMessage.value = ''
  }
  if (challengeLoadPromise) {
    return challengeLoadPromise
  }
  const pending = performChallengeLoad()
  challengeLoadPromise = pending
  void pending.finally(() => {
    if (challengeLoadPromise === pending) {
      challengeLoadPromise = undefined
    }
  })
  return pending
}

async function reset() {
  await loadChallenge(false)
}

function requireVerification() {
  if (state.value === 'loading' || state.value === 'verifying') {
    return
  }
  state.value = 'failed'
  errorMessage.value = challenge.value ? '请先完成拖动验证' : '验证服务暂不可用，点击滑块重试'
  updateProgress(0)
  void nextTick(() => handleRef.value?.focus())
}

function normalizedPoint(clientX: number, clientY: number, elapsed: number): LoginCaptchaTrackPoint {
  const x = maxTravelPx <= 0 ? 0 : Math.round((clientX - dragStartX) / maxTravelPx * TRACK_MAX)
  const trackHeight = Math.max(trackRef.value?.clientHeight ?? 1, 1)
  const y = Math.round((clientY - dragStartY) / trackHeight * TRACK_MAX)
  return {
    x: Math.min(TRACK_MAX, Math.max(0, x)),
    y: Math.min(TRACK_MAX, Math.max(-TRACK_MAX, y)),
    t: Math.max(0, Math.round(elapsed)),
  }
}

function appendPoint(point: LoginCaptchaTrackPoint, force = false) {
  const previous = trajectory.at(-1)
  if (previous && point.t <= previous.t) {
    point = { ...point, t: previous.t + 1 }
  }
  if (!force && previous && previous.x === point.x && previous.y === point.y) {
    return
  }
  if (trajectory.length >= CLIENT_MAX_POINTS) {
    trajectory[CLIENT_MAX_POINTS - 1] = point
    return
  }
  trajectory.push(point)
}

function beginDrag(clientX: number, clientY: number) {
  measureTrack()
  if (maxTravelPx <= 0) {
    return false
  }
  state.value = 'dragging'
  errorMessage.value = ''
  dragStartX = clientX
  dragStartY = clientY
  dragStartAt = performance.now()
  trajectory = [{ x: 0, y: 0, t: 0 }]
  updateProgress(0)
  return true
}

function handlePointerDown(event: PointerEvent) {
  if (props.disabled || event.button !== 0) {
    return
  }
  if (!challenge.value) {
    void reset()
    return
  }
  if (interactionDisabled.value || !beginDrag(event.clientX, event.clientY)) {
    return
  }
  activePointerId = event.pointerId
  handleRef.value?.setPointerCapture(event.pointerId)
  handleRef.value?.focus()
  event.preventDefault()
}

function handlePointerMove(event: PointerEvent) {
  if (state.value !== 'dragging' || activePointerId !== event.pointerId) {
    return
  }
  const point = normalizedPoint(event.clientX, event.clientY, performance.now() - dragStartAt)
  updateProgress(point.x)
  appendPoint(point)
  event.preventDefault()
}

function releasePointer(pointerId: number) {
  if (handleRef.value?.hasPointerCapture(pointerId)) {
    handleRef.value.releasePointerCapture(pointerId)
  }
  activePointerId = undefined
}

async function submitTrajectory() {
  const currentChallenge = challenge.value
  if (!currentChallenge) {
    await reset()
    return
  }
  const generation = ++requestGeneration
  state.value = 'verifying'
  challenge.value = undefined
  clearExpiryTimer()
  try {
    const result = await verifyLoginCaptcha({
      challengeId: currentChallenge.challengeId,
      trajectory: trajectory.map((point) => ({ ...point })),
    })
    if (disposed || generation !== requestGeneration) {
      return
    }
    state.value = 'verified'
    updateProgress(TRACK_MAX)
    emit('verified', result.proof)
    scheduleExpiry(result.ttlSeconds, generation)
  } catch (error) {
    if (disposed || generation !== requestGeneration) {
      return
    }
    state.value = 'failed'
    errorMessage.value = getRequestErrorMessage(error, '验证未通过，请重新拖动')
    updateProgress(0)
    await loadChallenge(true)
  }
}

function finishDrag(event: PointerEvent) {
  if (state.value !== 'dragging' || activePointerId !== event.pointerId) {
    return
  }
  const point = normalizedPoint(event.clientX, event.clientY, performance.now() - dragStartAt)
  appendPoint(point, true)
  updateProgress(point.x)
  releasePointer(event.pointerId)
  if (progress.value < TRACK_COMPLETE) {
    state.value = 'failed'
    errorMessage.value = '请拖动到轨道终点'
    trajectory = []
    updateProgress(0)
    return
  }
  // 到达终点时把最后一点统一为 1000，避免不同轨道像素宽度影响服务端判断。
  const last = trajectory.at(-1)
  if (last && last.x !== TRACK_MAX) {
    trajectory[trajectory.length - 1] = { ...last, x: TRACK_MAX }
  }
  void submitTrajectory()
}

function handlePointerCancel(event: PointerEvent) {
  if (activePointerId !== event.pointerId) {
    return
  }
  releasePointer(event.pointerId)
  state.value = 'failed'
  errorMessage.value = '拖动已取消，请重新验证'
  trajectory = []
  updateProgress(0)
}

function appendKeyboardPoint(nextProgress: number) {
  const elapsed = performance.now() - dragStartAt
  appendPoint({ x: nextProgress, y: 0, t: Math.round(elapsed) }, true)
  updateProgress(nextProgress)
}

function scheduleKeyboardSubmit() {
  const challengeId = challenge.value?.challengeId
  if (!challengeId) {
    return
  }
  const generation = requestGeneration
  const elapsed = performance.now() - dragStartAt
  state.value = 'verifying'
  clearKeyboardSubmitTimer()
  keyboardSubmitTimer = setTimeout(() => {
    keyboardSubmitTimer = undefined
    if (disposed || generation !== requestGeneration || challenge.value?.challengeId !== challengeId) {
      return
    }
    appendPoint({
      x: TRACK_MAX,
      y: 0,
      t: Math.max(MIN_KEYBOARD_TRAJECTORY_MS, Math.round(performance.now() - dragStartAt)),
    }, true)
    void submitTrajectory()
  }, Math.max(0, MIN_KEYBOARD_TRAJECTORY_MS - elapsed))
}

function handleKeydown(event: KeyboardEvent) {
  if (props.disabled || state.value === 'loading' || state.value === 'verifying' || state.value === 'verified') {
    return
  }
  if (!challenge.value) {
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault()
      void reset()
    }
    return
  }
  if (event.key === 'Home') {
    event.preventDefault()
    state.value = 'ready'
    trajectory = []
    updateProgress(0)
    return
  }
  if (event.key !== 'ArrowRight' && event.key !== 'ArrowLeft') {
    return
  }
  event.preventDefault()
  if (state.value !== 'dragging' && !beginDrag(0, 0)) {
    return
  }
  const direction = event.key === 'ArrowRight' ? 1 : -1
  appendKeyboardPoint(Math.min(TRACK_MAX, Math.max(0, progress.value + direction * 100)))
  if (progress.value === TRACK_MAX) {
    scheduleKeyboardSubmit()
  }
}

onMounted(() => {
  resizeObserver = new ResizeObserver(measureTrack)
  if (trackRef.value) {
    resizeObserver.observe(trackRef.value)
  }
  void reset()
})

onBeforeUnmount(() => {
  disposed = true
  requestGeneration += 1
  clearExpiryTimer()
  clearKeyboardSubmitTimer()
  resizeObserver?.disconnect()
})

defineExpose({ reset, requireVerification })
</script>

<template>
  <div class="login-captcha">
    <div class="captcha-label">
      <span>安全验证</span>
      <small>GUARD · 安全拖动确认</small>
    </div>
    <div
      ref="trackRef"
      class="captcha-track"
      :class="`is-${state}`"
      :style="trackStyle"
      data-login-captcha
    >
      <span class="captcha-fill" aria-hidden="true" />
      <span class="captcha-copy" :class="{ 'is-error': state === 'failed' }" aria-live="polite">
        {{ statusText }}
      </span>
      <span class="captcha-route" aria-hidden="true"><i /><i /><i /><i /><i /></span>
      <span class="captcha-shield" aria-hidden="true">
        <svg viewBox="0 0 24 24">
          <path d="M12 3.4 19 6v5.1c0 4.4-2.8 8-7 9.5-4.2-1.5-7-5.1-7-9.5V6l7-2.6Z" />
          <path d="m9 12 2 2 4-4" />
        </svg>
      </span>
      <span
        ref="handleRef"
        class="captcha-handle"
        role="slider"
        tabindex="0"
        aria-label="拖动验证码"
        aria-valuemin="0"
        aria-valuemax="100"
        :aria-valuenow="Math.round(progress / 10)"
        :aria-valuetext="statusText"
        :aria-disabled="interactionDisabled"
        @pointerdown="handlePointerDown"
        @pointermove="handlePointerMove"
        @pointerup="finishDrag"
        @pointercancel="handlePointerCancel"
        @keydown="handleKeydown"
      >
        <svg v-if="state === 'verified'" viewBox="0 0 24 24" aria-hidden="true">
          <path d="m5 12.5 4.2 4.2L19 7" />
        </svg>
        <svg v-else-if="state === 'failed' && !challenge" viewBox="0 0 24 24" aria-hidden="true">
          <path d="M18.3 8.2A7.6 7.6 0 1 0 19.5 14" />
          <path d="M18.3 4.5v3.7h-3.7" />
        </svg>
        <svg v-else viewBox="0 0 24 24" aria-hidden="true">
          <path d="m6 7 5 5-5 5" />
          <path d="m11 7 5 5-5 5" />
        </svg>
      </span>
    </div>
    <p class="keyboard-hint">可拖动滑块，或聚焦后使用左右方向键完成验证</p>
  </div>
</template>

<style scoped>
.login-captcha {
  margin-bottom: 18px;
}

.captcha-label {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 7px;
  color: var(--el-text-color-primary);
  font-size: 13px;
  font-weight: 650;
}

.captcha-label small {
  color: var(--el-color-primary-dark-2);
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.11em;
}

.captcha-track {
  position: relative;
  box-sizing: border-box;
  width: 100%;
  height: 48px;
  overflow: hidden;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  background: linear-gradient(180deg, var(--el-fill-color-blank), var(--el-fill-color-extra-light));
  box-shadow: inset 0 1px 2px rgba(25, 18, 52, 0.03);
  user-select: none;
}

.captcha-track.is-failed {
  border-color: var(--el-color-danger-light-5);
  background: var(--el-color-danger-light-9);
}

.captcha-track.is-verified {
  border-color: var(--el-color-success-light-5);
  background: var(--el-color-success-light-9);
}

.captcha-fill {
  position: absolute;
  inset: 0 auto 0 0;
  width: var(--captcha-fill-width);
  background: linear-gradient(90deg, var(--el-color-primary-light-8), transparent);
  transition: width 100ms ease-out;
}

.is-verified .captcha-fill {
  background: var(--el-color-success-light-8);
}

.is-failed .captcha-fill {
  background: var(--el-color-danger-light-8);
}

.captcha-copy {
  position: absolute;
  inset: 0 48px 0 50px;
  display: grid;
  place-items: center;
  overflow: hidden;
  color: var(--el-text-color-secondary);
  font-size: 13px;
  font-weight: 500;
  letter-spacing: 0.01em;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.is-dragging .captcha-copy,
.is-verifying .captcha-copy {
  color: var(--el-color-primary-dark-2);
}

.is-verified .captcha-copy {
  color: var(--el-color-success-dark-2, #168d78);
  font-weight: 650;
}

.captcha-copy.is-error {
  color: var(--el-color-danger-dark-2, #bd4f5a);
}

.captcha-route {
  position: absolute;
  right: 70px;
  bottom: 6px;
  left: 66px;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.captcha-route::before {
  position: absolute;
  right: 2px;
  left: 2px;
  height: 1px;
  background: linear-gradient(90deg, var(--el-color-primary-light-5), var(--el-color-primary-light-9));
  content: '';
}

.captcha-route i {
  position: relative;
  z-index: 1;
  width: 3px;
  height: 3px;
  border-radius: 50%;
  background: var(--el-color-primary-light-5);
}

.is-verified .captcha-route i {
  background: var(--el-color-success-light-3);
}

.is-failed .captcha-route i {
  background: var(--el-color-danger-light-5);
}

.captcha-shield {
  position: absolute;
  top: 14px;
  right: 14px;
  width: 18px;
  height: 18px;
  color: var(--el-text-color-placeholder);
}

.captcha-shield svg,
.captcha-handle svg {
  width: 100%;
  height: 100%;
  fill: none;
  stroke: currentColor;
  stroke-linecap: round;
  stroke-linejoin: round;
  stroke-width: 1.8;
}

.captcha-handle {
  position: absolute;
  top: 2px;
  left: 2px;
  display: grid;
  width: 42px;
  height: 42px;
  box-sizing: border-box;
  place-items: center;
  border-radius: 6px;
  background: linear-gradient(135deg, var(--theme-primary-solid, var(--el-color-primary)), var(--theme-primary-solid-active, var(--el-color-primary-dark-2)));
  box-shadow: 0 5px 14px color-mix(in srgb, var(--theme-primary-solid, var(--el-color-primary)) 28%, transparent);
  color: var(--captcha-thumb-text);
  cursor: grab;
  outline: none;
  touch-action: none;
  transform: translate3d(var(--captcha-handle-offset), 0, 0);
  transition: transform 100ms ease-out, background-color 160ms ease-out, box-shadow 160ms ease-out;
}

.captcha-handle:focus-visible {
  box-shadow: 0 0 0 3px var(--el-color-primary-light-7),
    0 5px 14px color-mix(in srgb, var(--el-color-primary) 28%, transparent);
}

.is-dragging .captcha-handle {
  cursor: grabbing;
  transition: none;
}

.is-loading .captcha-handle,
.is-verifying .captcha-handle {
  cursor: wait;
  opacity: 0.78;
}

.is-verified .captcha-handle {
  color: var(--cw-on-success, #fff);
  background: linear-gradient(135deg, var(--cw-success-solid, #16856a), var(--cw-success-solid-active, #127159));
  box-shadow: 0 5px 14px color-mix(in srgb, var(--cw-success-solid, #16856a) 24%, transparent);
  cursor: default;
}

.is-failed .captcha-handle {
  color: var(--cw-on-danger, #fff);
  background: linear-gradient(135deg, var(--cw-danger-solid, #c2414b), var(--cw-danger-solid-active, #a63840));
  box-shadow: 0 5px 14px color-mix(in srgb, var(--cw-danger-solid, #c2414b) 20%, transparent);
  transition: background-color 160ms ease-out, box-shadow 160ms ease-out;
}

.captcha-handle svg {
  width: 21px;
  height: 21px;
}

.keyboard-hint {
  margin: 5px 2px 0;
  color: var(--el-text-color-placeholder);
  font-size: 10px;
  line-height: 1.4;
  text-align: right;
}

@media (prefers-reduced-motion: reduce) {
  .captcha-fill,
  .captcha-handle {
    transition: none;
  }
}

@media (max-width: 480px) {
  .captcha-label small {
    letter-spacing: 0.06em;
  }

  .captcha-copy {
    font-size: 12px;
  }

  .keyboard-hint {
    display: none;
  }
}
</style>
