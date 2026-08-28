<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'

const props = defineProps<{
  images: string[]
}>()

const carouselRef = ref<{ setActiveItem: (index: number) => void }>()
const activeImageIndex = ref(0)
const manuallyPaused = ref(false)
const prefersReducedMotion = ref(false)
const pageVisible = ref(true)
let reducedMotionQuery: MediaQueryList | undefined

const CAROUSEL_INTERVAL_MS = 3000

const displayImages = computed(() => props.images.map((image) => image.trim()).filter(Boolean))
const hasMultipleImages = computed(() => displayImages.value.length > 1)
const shouldAutoplay = computed(() => (
  hasMultipleImages.value
  && !manuallyPaused.value
  && !prefersReducedMotion.value
  && pageVisible.value
))

function syncReducedMotion(event: MediaQueryListEvent | MediaQueryList) {
  prefersReducedMotion.value = event.matches
}

function selectImage(index: number) {
  activeImageIndex.value = index
  carouselRef.value?.setActiveItem(index)
}

function toggleAutoplay() {
  if (!prefersReducedMotion.value) {
    manuallyPaused.value = !manuallyPaused.value
  }
}

function syncPageVisibility() {
  pageVisible.value = document.visibilityState === 'visible'
}

onMounted(() => {
  reducedMotionQuery = window.matchMedia('(prefers-reduced-motion: reduce)')
  syncReducedMotion(reducedMotionQuery)
  reducedMotionQuery.addEventListener('change', syncReducedMotion)
  syncPageVisibility()
  document.addEventListener('visibilitychange', syncPageVisibility)
})

onBeforeUnmount(() => {
  reducedMotionQuery?.removeEventListener('change', syncReducedMotion)
  document.removeEventListener('visibilitychange', syncPageVisibility)
})
</script>

<template>
  <section
    class="brand-stage"
    aria-labelledby="brand-stage-title"
  >
    <div class="brand-media" aria-hidden="true">
      <el-carousel
        v-if="hasMultipleImages"
        ref="carouselRef"
        height="100%"
        :autoplay="shouldAutoplay"
        :interval="CAROUSEL_INTERVAL_MS"
        :pause-on-hover="false"
        arrow="never"
        indicator-position="none"
        @change="activeImageIndex = $event"
      >
        <el-carousel-item v-for="image in displayImages" :key="image">
          <div class="brand-image" :style="{ backgroundImage: `url(${JSON.stringify(image)})` }" />
        </el-carousel-item>
      </el-carousel>
      <div
        v-else-if="displayImages[0]"
        class="brand-image"
        :style="{ backgroundImage: `url(${JSON.stringify(displayImages[0])})` }"
      />
      <div class="brand-scrim" />
    </div>

    <div class="brand-content">
      <header class="brand-header">
        <span class="brand-mark" aria-hidden="true">CW</span>
        <span class="brand-identity">
          <strong>customer_work</strong>
          <small>Agent Console</small>
        </span>
      </header>

      <div class="brand-message">
        <p class="brand-kicker">智能体运营台</p>
        <h1 id="brand-stage-title">让每一次智能服务，<br>都有迹可循。</h1>
        <p class="brand-summary">
          从意图识别到最终回复，在一条清晰链路中完成知识、工具与安全能力的协同。
        </p>
      </div>

      <div class="execution-trace" aria-label="智能体执行轨迹示意">
        <div class="trace-heading">
          <span>执行轨迹</span>
          <small>示意链路</small>
        </div>
        <ol>
          <li>
            <span class="trace-node" aria-hidden="true" />
            <span><small>INTENT</small><strong>识别意图</strong></span>
          </li>
          <li>
            <span class="trace-node" aria-hidden="true" />
            <span><small>KNOWLEDGE</small><strong>检索知识</strong></span>
          </li>
          <li>
            <span class="trace-node" aria-hidden="true" />
            <span><small>GUARD</small><strong>安全校验</strong></span>
          </li>
          <li>
            <span class="trace-node" aria-hidden="true" />
            <span><small>TOOL</small><strong>调用工具</strong></span>
          </li>
          <li class="is-complete">
            <span class="trace-node" aria-hidden="true">✓</span>
            <span><small>ANSWER</small><strong>生成回复</strong></span>
          </li>
        </ol>
      </div>

      <div v-if="hasMultipleImages" class="carousel-controls" aria-label="品牌背景图控制">
        <button
          class="motion-toggle"
          type="button"
          :disabled="prefersReducedMotion"
          :aria-pressed="manuallyPaused"
          :title="prefersReducedMotion ? '已遵循系统的减少动态效果设置' : undefined"
          @click="toggleAutoplay"
        >
          <svg v-if="!manuallyPaused && !prefersReducedMotion" viewBox="0 0 16 16" aria-hidden="true">
            <path d="M4.5 3.25h2.25v9.5H4.5zm4.75 0h2.25v9.5H9.25z" />
          </svg>
          <svg v-else viewBox="0 0 16 16" aria-hidden="true">
            <path d="m5 3 7 5-7 5z" />
          </svg>
          <span>
            {{ prefersReducedMotion ? '自动切换已关闭' : manuallyPaused ? '继续轮播' : '暂停轮播' }}
          </span>
        </button>

        <div class="carousel-dots" role="group" aria-label="选择品牌背景图">
          <button
            v-for="(_image, index) in displayImages"
            :key="index"
            type="button"
            :class="{ 'is-active': activeImageIndex === index }"
            :aria-label="`查看第 ${index + 1} 张品牌背景图`"
            :aria-current="activeImageIndex === index ? 'true' : undefined"
            @click="selectImage(index)"
          />
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.brand-stage {
  position: relative;
  width: 100%;
  height: 100%;
  min-height: 560px;
  overflow: hidden;
  color: #fff;
  background: var(--cw-brand-ink);
  isolation: isolate;
}

.brand-media,
.brand-scrim {
  position: absolute;
  inset: 0;
}

.brand-media {
  z-index: -1;
  background:
    radial-gradient(circle at 72% 20%, rgba(111, 138, 255, 0.22), transparent 34%),
    var(--cw-brand-ink);
}

.brand-media :deep(.el-carousel),
.brand-media :deep(.el-carousel__container),
.brand-image {
  width: 100%;
  height: 100%;
}

.brand-image {
  background-position: 62% center;
  background-size: cover;
}

.brand-scrim {
  background:
    linear-gradient(90deg, rgba(11, 23, 40, 0.97) 0%, rgba(11, 23, 40, 0.88) 48%, rgba(11, 23, 40, 0.68) 100%),
    linear-gradient(180deg, rgba(11, 23, 40, 0.22) 0%, rgba(11, 23, 40, 0.58) 100%);
}

.brand-content {
  box-sizing: border-box;
  display: grid;
  grid-template-rows: auto 1fr auto auto;
  gap: 30px;
  height: 100%;
  padding: clamp(28px, 4vw, 54px) clamp(30px, 5vw, 72px) 34px;
}

.brand-header {
  display: flex;
  align-items: center;
  gap: 11px;
  width: fit-content;
}

.brand-mark {
  display: inline-flex;
  flex: 0 0 34px;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  border-radius: 10px;
  background: linear-gradient(145deg, var(--cw-brand-logo-start), var(--cw-brand-logo-end));
  box-shadow: 0 10px 24px rgba(60, 94, 232, 0.28);
  font-size: 11px;
  font-weight: 800;
  letter-spacing: -0.04em;
}

.brand-identity {
  display: flex;
  flex-direction: column;
  line-height: 1.15;
}

.brand-identity strong {
  font-size: 14px;
  font-weight: 700;
  letter-spacing: 0.02em;
}

.brand-identity small {
  margin-top: 3px;
  color: rgba(255, 255, 255, 0.58);
  font-size: 10px;
  letter-spacing: 0.12em;
  text-transform: uppercase;
}

.brand-message {
  align-self: center;
  max-width: 620px;
  margin-top: 8px;
}

.brand-kicker {
  display: flex;
  align-items: center;
  gap: 9px;
  margin: 0 0 18px;
  color: rgba(255, 255, 255, 0.72);
  font-size: 13px;
  font-weight: 650;
  letter-spacing: 0.16em;
}

.brand-kicker::before {
  width: 26px;
  height: 2px;
  border-radius: 999px;
  background: var(--cw-brand-signal);
  content: '';
}

.brand-message h1 {
  max-width: 580px;
  margin: 0;
  font-size: clamp(36px, 4.1vw, 58px);
  font-weight: 720;
  line-height: 1.16;
  letter-spacing: -0.045em;
  text-wrap: balance;
}

.brand-summary {
  max-width: 500px;
  margin: 22px 0 0;
  color: rgba(255, 255, 255, 0.68);
  font-size: 14px;
  line-height: 1.85;
}

.execution-trace {
  max-width: 680px;
  padding: 17px 18px 16px;
  border: 1px solid rgba(255, 255, 255, 0.11);
  border-radius: 14px;
  background: rgba(7, 16, 29, 0.48);
  box-shadow: 0 20px 48px rgba(2, 8, 23, 0.18);
  backdrop-filter: blur(10px);
}

.trace-heading {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 15px;
}

.trace-heading > span {
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.08em;
}

.trace-heading small {
  color: rgba(255, 255, 255, 0.43);
  font-size: 10px;
}

.execution-trace ol {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 12px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.execution-trace li {
  position: relative;
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.execution-trace li:not(:last-child)::after {
  position: absolute;
  top: 8px;
  left: 17px;
  width: calc(100% - 13px);
  height: 1px;
  background: linear-gradient(90deg, rgba(111, 138, 255, 0.65), rgba(111, 138, 255, 0.13));
  content: '';
}

.trace-node {
  position: relative;
  z-index: 1;
  display: inline-flex;
  flex: 0 0 16px;
  align-items: center;
  justify-content: center;
  width: 16px;
  height: 16px;
  border: 4px solid rgba(111, 138, 255, 0.32);
  border-radius: 50%;
  background: var(--cw-brand-signal);
  color: #08201e;
  font-size: 9px;
  font-weight: 900;
}

.execution-trace li > span:last-child {
  display: flex;
  min-width: 0;
  flex-direction: column;
}

.execution-trace small {
  overflow: hidden;
  color: rgba(255, 255, 255, 0.38);
  font-size: 8px;
  letter-spacing: 0.06em;
  text-overflow: ellipsis;
}

.execution-trace strong {
  margin-top: 2px;
  color: rgba(255, 255, 255, 0.86);
  font-size: 11px;
  font-weight: 600;
  white-space: nowrap;
}

.execution-trace .is-complete .trace-node {
  border-color: rgba(45, 212, 191, 0.24);
  background: #2dd4bf;
}

.carousel-controls {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 30px;
}

.motion-toggle,
.carousel-dots button {
  appearance: none;
  border: 0;
  color: inherit;
  cursor: pointer;
}

.motion-toggle {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  min-height: 30px;
  padding: 5px 9px;
  border: 1px solid rgba(255, 255, 255, 0.12);
  border-radius: 8px;
  background: rgba(4, 12, 23, 0.36);
  color: rgba(255, 255, 255, 0.66);
  font: inherit;
  font-size: 11px;
}

.motion-toggle:hover:not(:disabled) {
  border-color: rgba(255, 255, 255, 0.28);
  color: #fff;
}

.motion-toggle:disabled {
  cursor: default;
  opacity: 0.72;
}

.motion-toggle svg {
  width: 13px;
  height: 13px;
  fill: currentColor;
}

.motion-toggle:focus-visible,
.carousel-dots button:focus-visible {
  outline: 2px solid #fff;
  outline-offset: 3px;
}

.carousel-dots {
  display: flex;
  align-items: center;
  gap: 8px;
}

.carousel-dots button {
  width: 20px;
  height: 4px;
  padding: 0;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.24);
  transition: width 180ms ease, background-color 180ms ease;
}

.carousel-dots button.is-active {
  width: 34px;
  background: var(--cw-brand-signal);
}

@media (prefers-reduced-motion: no-preference) {
  .brand-header,
  .brand-message,
  .execution-trace {
    animation: brand-reveal 560ms both cubic-bezier(0.22, 1, 0.36, 1);
  }

  .brand-message {
    animation-delay: 80ms;
  }

  .execution-trace {
    animation-delay: 160ms;
  }
}

@keyframes brand-reveal {
  from {
    opacity: 0;
    transform: translateY(10px);
  }

  to {
    opacity: 1;
    transform: translateY(0);
  }
}

@media (max-width: 1160px) {
  .brand-content {
    padding-right: 34px;
    padding-left: 34px;
  }

  .brand-message h1 {
    font-size: clamp(34px, 4vw, 48px);
  }

  .execution-trace {
    padding-right: 14px;
    padding-left: 14px;
  }

  .execution-trace ol {
    gap: 7px;
  }
}

@media (min-width: 900px) and (max-width: 1023px) {
  .brand-content {
    grid-template-rows: auto 1fr auto;
    gap: 22px;
    padding: 30px 30px 24px;
  }

  .brand-message {
    align-self: center;
  }

  .brand-message h1 {
    font-size: clamp(38px, 4.8vw, 46px);
  }

  .brand-summary {
    font-size: 13px;
  }

  .execution-trace {
    display: none;
  }
}

@media (max-width: 899px) {
  .brand-stage {
    height: 210px;
    min-height: 210px;
  }

  .brand-content {
    grid-template-rows: auto 1fr auto;
    gap: 12px;
    padding: 20px clamp(22px, 6vw, 54px) 16px;
  }

  .brand-message {
    align-self: end;
    margin-top: 0;
  }

  .brand-kicker {
    margin-bottom: 8px;
    font-size: 11px;
  }

  .brand-message h1 {
    font-size: clamp(28px, 5.5vw, 38px);
    line-height: 1.12;
  }

  .brand-summary,
  .execution-trace {
    display: none;
  }

  .carousel-controls {
    position: absolute;
    right: clamp(22px, 6vw, 54px);
    bottom: 16px;
  }

  .motion-toggle {
    margin-right: 12px;
  }
}

@media (max-width: 560px) {
  .brand-stage {
    height: 204px;
    min-height: 204px;
  }

  .brand-content {
    padding: 16px 20px 14px;
  }

  .brand-identity small,
  .motion-toggle span {
    position: absolute;
    width: 1px;
    height: 1px;
    overflow: hidden;
    clip: rect(0 0 0 0);
    white-space: nowrap;
  }

  .brand-message h1 {
    font-size: clamp(27px, 8.3vw, 34px);
  }

  .carousel-controls {
    right: 20px;
    bottom: 14px;
  }

  .motion-toggle {
    width: 30px;
    justify-content: center;
    margin-right: 8px;
    padding: 5px;
  }

  .carousel-dots button {
    width: 14px;
  }

  .carousel-dots button.is-active {
    width: 24px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .brand-stage *,
  .brand-stage *::before,
  .brand-stage *::after,
  .brand-media :deep(*) {
    scroll-behavior: auto !important;
    animation-duration: 0.001ms !important;
    animation-iteration-count: 1 !important;
    transition-duration: 0.001ms !important;
  }
}
</style>
