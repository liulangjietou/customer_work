<script setup lang="ts">
import type { HomeAdmissionPresentation } from '../../homePresentation'
import HomeServiceScene from './HomeServiceScene.vue'

defineProps<{
  displayName: string
  username: string
  presentation: HomeAdmissionPresentation
}>()
</script>

<template>
  <div class="admission-canvas">
    <section class="admission-panel">
      <div class="admission-copy">
        <div class="admission-copy__topline">
          <span>{{ presentation.eyebrow }}</span>
          <span class="admission-status">{{ presentation.status }}</span>
        </div>
        <p class="admission-kicker">你好，{{ displayName }}</p>
        <h1 id="home-title">{{ presentation.title }}</h1>
        <p class="admission-description">{{ presentation.description }}</p>

        <dl class="admission-account">
          <div>
            <dt>申请账号</dt>
            <dd>{{ username }}</dd>
          </div>
          <div>
            <dt>准入状态</dt>
            <dd>{{ presentation.status }}</dd>
          </div>
        </dl>

        <p class="admission-note">
          <span aria-hidden="true">i</span>
          {{ presentation.note }}
        </p>
      </div>

      <HomeServiceScene
        eyebrow="CONTROLLED ACCESS"
        title="服务能力从可信准入开始。"
        description="审核完成前，业务导航与历史工作保持隔离。"
      />
    </section>
  </div>
</template>

<style scoped>
.admission-canvas {
  display: grid;
  width: min(100%, 1560px);
  min-height: 100%;
  margin: 0 auto;
  padding: clamp(18px, 2.4vw, 36px);
  place-items: center;
  box-sizing: border-box;
}

.admission-panel {
  display: grid;
  grid-template-columns: minmax(0, 1.48fr) minmax(320px, 0.82fr);
  width: 100%;
  min-height: min(650px, calc(100vh - 180px));
  overflow: hidden;
  border: 1px solid var(--cw-line-strong);
  border-radius: calc(var(--cw-radius-lg) + 6px);
  background: var(--home-paper, var(--cw-paper));
  box-shadow: var(--cw-shadow-lg);
  animation: home-admission-enter 480ms ease-out both;
}

.admission-copy {
  display: flex;
  flex-direction: column;
  justify-content: center;
  padding: clamp(40px, 5vw, 78px);
  color: #ffffff;
  background:
    radial-gradient(circle at 90% 5%, color-mix(in srgb, var(--home-cobalt) 28%, transparent), transparent 35%),
    linear-gradient(145deg, var(--home-ink), var(--home-ink-soft));
}

.admission-copy__topline {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  color: rgb(235 243 250 / 68%);
  font-size: 11px;
  font-weight: 760;
  line-height: 1.2;
  letter-spacing: 0.15em;
  text-transform: uppercase;
}

.admission-status {
  padding: 6px 9px;
  border: 1px solid color-mix(in srgb, var(--home-amber) 34%, transparent);
  border-radius: 999px;
  color: var(--home-signal, var(--home-amber));
  background: color-mix(in srgb, var(--home-amber) 9%, transparent);
  letter-spacing: 0.08em;
}

.admission-kicker {
  margin: clamp(54px, 9vh, 96px) 0 13px;
  color: var(--home-on-ink-muted);
  font-size: 14px;
  font-weight: 650;
  letter-spacing: 0.045em;
}

.admission-copy h1 {
  max-width: 760px;
  margin: 0;
  color: #ffffff;
  font-size: clamp(40px, 4.6vw, 66px);
  font-weight: 760;
  line-height: 1.06;
  letter-spacing: -0.055em;
  text-wrap: balance;
}

.admission-description {
  max-width: 650px;
  margin: 26px 0 0;
  color: rgb(225 235 244 / 72%);
  font-size: 15px;
  line-height: 1.85;
}

.admission-account {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  max-width: 640px;
  margin: 42px 0 0;
  padding: 22px 0;
  border-top: 1px solid rgb(255 255 255 / 14%);
  border-bottom: 1px solid rgb(255 255 255 / 14%);
}

.admission-account > div + div {
  padding-left: 24px;
  border-left: 1px solid rgb(255 255 255 / 14%);
}

.admission-account dt {
  color: rgb(213 226 238 / 50%);
  font-size: 10px;
  letter-spacing: 0.08em;
}

.admission-account dd {
  margin: 8px 0 0;
  overflow-wrap: anywhere;
  color: #ffffff;
  font-size: 13px;
  font-weight: 700;
}

.admission-note {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  max-width: 650px;
  margin: 24px 0 0;
  color: rgb(213 226 238 / 62%);
  font-size: 11px;
  line-height: 1.65;
}

.admission-note span {
  display: grid;
  flex: 0 0 18px;
  width: 18px;
  height: 18px;
  place-items: center;
  border: 1px solid rgb(255 255 255 / 28%);
  border-radius: 50%;
  color: #ffffff;
  font-size: 10px;
  font-style: normal;
}

@media (max-width: 1100px) {
  .admission-panel {
    grid-template-columns: minmax(0, 1.32fr) minmax(300px, 0.68fr);
  }
}

@media (max-width: 820px) {
  .admission-canvas {
    padding: 16px;
  }

  .admission-panel {
    grid-template-columns: 1fr;
    min-height: 0;
  }

  .admission-copy {
    min-height: 520px;
  }
}

@media (max-width: 520px) {
  .admission-canvas {
    padding: 12px;
  }

  .admission-panel {
    border-radius: var(--cw-radius-lg);
  }

  .admission-copy {
    min-height: 0;
    padding: 28px 22px;
  }

  .admission-copy__topline {
    flex-direction: column;
    align-items: flex-start;
    gap: 9px;
    font-size: 9px;
    letter-spacing: 0.09em;
  }

  .admission-status {
    align-self: flex-start;
  }

  .admission-kicker {
    margin-top: 44px;
  }

  .admission-copy h1 {
    font-size: clamp(36px, 11.5vw, 46px);
    letter-spacing: -0.05em;
  }

  .admission-description {
    margin-top: 20px;
    font-size: 13px;
    line-height: 1.75;
  }

  .admission-account {
    grid-template-columns: 1fr;
    margin-top: 30px;
  }

  .admission-account > div + div {
    margin-top: 18px;
    padding-top: 18px;
    padding-left: 0;
    border-top: 1px solid rgb(255 255 255 / 14%);
    border-left: 0;
  }
}

@media (prefers-reduced-motion: reduce) {
  .admission-panel {
    animation: none;
  }
}

@keyframes home-admission-enter {
  from {
    opacity: 0;
    transform: translateY(8px);
  }

  to {
    opacity: 1;
    transform: translateY(0);
  }
}
</style>
