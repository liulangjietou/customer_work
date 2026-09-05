<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'

const props = defineProps<{
  images: string[]
}>()

const carouselRef = ref<{ setActiveItem: (index: number) => void }>()
const activeImageIndex = ref(0)
const manuallyPaused = ref(false)
const prefersReducedMotion = ref(false)
const pageVisible = ref(true)
const failedImages = ref<string[]>([])
let reducedMotionQuery: MediaQueryList | undefined

const CAROUSEL_INTERVAL_MS = 3000
const FALLBACK_IMAGE = '/home-cover.jpg'

const configuredImages = computed(() => [
  ...new Set(props.images.map((image) => image.trim()).filter(Boolean)),
])
const displayImages = computed(() => {
  const available = configuredImages.value.filter((image) => !failedImages.value.includes(image))
  if (available.length > 0) return available
  // 未配置图片时保留品牌说明；仅在配置图片失效时使用内置图，且兜底不循环重试。
  return configuredImages.value.length > 0 && !failedImages.value.includes(FALLBACK_IMAGE)
    ? [FALLBACK_IMAGE]
    : []
})
const hasMultipleImages = computed(() => displayImages.value.length > 1)
const shouldAutoplay = computed(
  () =>
    hasMultipleImages.value &&
    !manuallyPaused.value &&
    !prefersReducedMotion.value &&
    pageVisible.value,
)

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

function markImageFailed(event: Event) {
  // 使用触发错误的元素地址；旧图片的延迟事件不能误删列表更新后的当前图片。
  const image = (event.currentTarget as HTMLImageElement).getAttribute('src')!
  if (!failedImages.value.includes(image)) failedImages.value.push(image)
}

watch(configuredImages, () => {
  failedImages.value = []
})

watch(displayImages, async () => {
  // 图片失败后列表会缩短，等待轮播项更新再复位，避免指示器指向已移除的图片。
  activeImageIndex.value = 0
  await nextTick()
  carouselRef.value?.setActiveItem(0)
})

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
    :class="{ 'has-images': displayImages.length > 0 }"
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
          <img class="brand-image" :src="image" alt="" @error="markImageFailed" />
        </el-carousel-item>
      </el-carousel>
      <img
        v-else-if="displayImages[0]"
        :key="displayImages[0]"
        class="brand-image"
        :src="displayImages[0]"
        alt=""
        @error="markImageFailed"
      />
      <div v-else class="brand-scrim" />
    </div>

    <div class="brand-content">
      <header class="brand-header">
        <span class="brand-mark" aria-hidden="true">CW</span>
        <span class="brand-identity">
          <strong>Customer Work</strong>
          <small>企业智能体工作台</small>
        </span>
      </header>

      <div class="brand-message">
        <p class="brand-kicker">智能体运营台</p>
        <h1 id="brand-stage-title">让智能体，<br />成为团队的工作伙伴。</h1>
        <p class="brand-summary">连接企业知识与业务工具，从任务到交付，让每一步清晰可见。</p>
      </div>

      <div v-if="displayImages.length === 0" class="execution-trace" aria-label="智能体执行轨迹示意">
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
          <svg
            v-if="!manuallyPaused && !prefersReducedMotion"
            viewBox="0 0 16 16"
            aria-hidden="true"
          >
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
  --brand-text: var(--cw-text);
  --brand-muted: var(--cw-text-muted);
  position: relative;
  min-width: 0;
  height: 100%;
  min-height: 560px;
  overflow: hidden;
  background: var(--cw-canvas);
  color: var(--brand-text);
  border-right: 1px solid var(--cw-line);
}
.brand-stage.has-images {
  display: grid;
  grid-template-rows: auto minmax(220px, 1fr) auto auto;
  gap: 24px;
  box-sizing: border-box;
  padding: 32px clamp(24px, 3vw, 48px);
}
.brand-media {
  position: absolute;
  inset: 0;
}
.brand-media .el-carousel,
.brand-media :deep(.el-carousel__container),
.brand-image {
  height: 100%;
  width: 100%;
}
.brand-image {
  display: block;
  object-fit: contain;
  object-position: center;
}
.brand-scrim {
  position: absolute;
  inset: 0;
  background:
    linear-gradient(
        90deg,
        color-mix(in srgb, var(--cw-cobalt) 3%, transparent) 1px,
        transparent 1px
      )
      0 0 / 40px 40px,
    linear-gradient(color-mix(in srgb, var(--cw-cobalt) 3%, transparent) 1px, transparent 1px) 0 0 /
      40px 40px;
  pointer-events: none;
}
.has-images .brand-media {
  position: relative;
  grid-row: 2;
  min-height: 0;
  overflow: hidden;
  border-radius: 12px;
  background: var(--cw-paper);
  border: 1px solid var(--cw-line);
}
.brand-content {
  position: relative;
  height: 100%;
  box-sizing: border-box;
  padding: 40px clamp(32px, 5vw, 80px);
  display: flex;
  flex-direction: column;
}
.brand-header {
  display: flex;
  align-items: center;
  gap: 12px;
}
.has-images .brand-content {
  display: contents;
}
.has-images .brand-header {
  grid-row: 1;
}
.has-images .brand-message {
  grid-row: 3;
  margin: 0;
  padding: 0;
}
.has-images .brand-kicker {
  display: none;
}
.has-images .brand-message h1 {
  font-size: clamp(24px, 2.2vw, 32px);
}
.has-images .brand-summary {
  max-width: none;
  margin-top: 12px;
}
.has-images .carousel-controls {
  grid-row: 4;
  margin: 0;
}
.brand-mark {
  width: 38px;
  height: 38px;
  display: grid;
  place-items: center;
  border-radius: 10px;
  background: var(--cw-cobalt-solid);
  color: var(--cw-on-primary);
  font-size: 14px;
  font-weight: 750;
  letter-spacing: -1px;
}
.brand-identity {
  display: grid;
  gap: 5px;
}
.brand-identity strong {
  font-size: 16px;
}
.brand-identity small {
  color: var(--brand-muted);
  font-size: 11px;
}
.brand-message {
  margin: auto 0 38px;
  padding-top: 48px;
}
.brand-kicker {
  margin: 0 0 16px;
  color: var(--brand-muted);
  font-size: 13px;
}
.brand-message h1 {
  font-size: clamp(30px, 3vw, 46px);
  font-weight: 600;
  line-height: 1.4;
  letter-spacing: -1.2px;
  margin: 0;
}
.brand-summary {
  font-size: 14px;
  line-height: 1.9;
  max-width: 390px;
  color: var(--brand-muted);
  margin: 20px 0 0;
}
.execution-trace {
  max-width: 430px;
  padding: 22px;
  border: 1px solid var(--cw-line);
  border-radius: 12px;
  background: var(--cw-paper);
  color: var(--cw-text);
  box-shadow: var(--cw-shadow-sm);
  margin-bottom: auto;
}
.trace-heading {
  display: flex;
  justify-content: space-between;
  margin-bottom: 20px;
  font-size: 13px;
}
.trace-heading small {
  font-size: 11px;
  color: var(--cw-text-muted);
}
.execution-trace ol {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px 10px;
}
.execution-trace li {
  display: flex;
  gap: 8px;
  align-items: center;
}
.execution-trace li > span:last-child {
  display: grid;
  gap: 5px;
}
.execution-trace li small {
  color: var(--cw-text-muted);
  font-size: 9px;
}
.execution-trace li strong {
  font-size: 12px;
  font-weight: 550;
}
.trace-node {
  width: 7px;
  height: 7px;
  border: 1px solid var(--cw-cobalt);
  border-radius: 50%;
  flex: 0 0 7px;
}
.is-complete .trace-node {
  display: grid;
  place-items: center;
  border: none;
  color: var(--cw-success);
}
.carousel-controls {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 28px;
}
.motion-toggle {
  display: flex;
  align-items: center;
  gap: 8px;
  border: 0;
  padding: 8px 0;
  color: var(--brand-muted);
  background: transparent;
  cursor: pointer;
  font: inherit;
  font-size: 12px;
}
.motion-toggle svg {
  width: 16px;
  height: 16px;
  fill: currentColor;
}
.carousel-dots {
  display: flex;
  gap: 4px;
}
.carousel-dots button {
  width: 28px;
  height: 28px;
  border: 0;
  background: transparent;
  cursor: pointer;
  position: relative;
}
.carousel-dots button::after {
  content: '';
  position: absolute;
  width: 6px;
  height: 6px;
  top: 11px;
  left: 11px;
  border-radius: 100%;
  background: var(--brand-muted);
  opacity: 0.5;
}
.carousel-dots button.is-active::after {
  opacity: 1;
  background: var(--brand-text);
}
@media (max-height: 760px) and (min-width: 981px) {
  .brand-stage.has-images {
    gap: 16px;
    padding-block: 24px;
  }
  .brand-content {
    padding-block: 24px;
  }
  .brand-message {
    padding-top: 24px;
    margin-bottom: 24px;
  }
  .brand-message h1 {
    font-size: 32px;
  }
}
@media (max-width: 980px) {
  .brand-stage {
    min-height: 0;
    height: auto;
    border-right: 0;
    border-bottom: 1px solid var(--cw-line);
  }
  .brand-content {
    padding: 24px;
  }
  .brand-stage.has-images {
    grid-template-rows: auto 180px auto;
    gap: 12px;
    padding: 20px;
  }
  .has-images .carousel-controls {
    display: flex;
    grid-row: 3;
  }
  .brand-message,
  .execution-trace,
  .carousel-controls {
    display: none;
  }
}
</style>
