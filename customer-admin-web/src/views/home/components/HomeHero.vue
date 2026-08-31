<script setup lang="ts">
import type { HomeEntry, HomeSnapshot } from '../../homePresentation'
import HomeServiceScene from './HomeServiceScene.vue'

defineProps<{
  displayName: string
  todayLabel: string
  greeting: string
  routesRegistered: boolean
  snapshot: HomeSnapshot
  primaryEntry?: HomeEntry
}>()

const emit = defineEmits<{
  navigate: [path: string]
}>()
</script>

<template>
  <section class="home-hero" aria-label="工作台欢迎区">
    <div class="welcome-panel">
      <div class="welcome-panel__grid" aria-hidden="true" />

      <div class="welcome-panel__content">
        <div class="welcome-meta">
          <span>{{ todayLabel }}</span>
          <span class="welcome-meta__divider" aria-hidden="true" />
          <span class="runtime-status" :class="{ 'runtime-status--ready': routesRegistered }">
            <i aria-hidden="true" />
            {{ routesRegistered ? '权限已同步' : '权限同步中' }}
          </span>
        </div>

        <p class="welcome-kicker">{{ greeting }}，{{ displayName }}</p>
        <h1 id="home-title">从一次判断开始，<br />让每次运行都有据可循。</h1>
        <p class="welcome-summary">
          这里不制造额外的业务数字，只汇总当前账号已经获得的权限入口与本次会话中的工作状态。
        </p>

        <button
          v-if="primaryEntry"
          type="button"
          class="primary-entry"
          :aria-label="`进入${primaryEntry.title}`"
          @click="emit('navigate', primaryEntry.path)"
        >
          <span>进入 {{ primaryEntry.title }}</span>
          <span class="primary-entry__arrow" aria-hidden="true">↗</span>
        </button>

        <dl class="signal-strip" aria-label="当前工作台状态">
          <div>
            <dt>{{ snapshot.availableEntryCount }}</dt>
            <dd>可用入口</dd>
            <small>来自权限菜单</small>
          </div>
          <div>
            <dt>{{ snapshot.agentEntryCount }}</dt>
            <dd>智能体入口</dd>
            <small>当前可进入</small>
          </div>
          <div>
            <dt>{{ snapshot.openTabCount }}</dt>
            <dd>打开的工作</dd>
            <small>可继续处理</small>
          </div>
        </dl>
      </div>

      <ol class="agent-loop" aria-label="智能体工作闭环">
        <li><span>01</span><strong>感知</strong><small>识别上下文</small></li>
        <li><span>02</span><strong>判断</strong><small>形成可解释决策</small></li>
        <li><span>03</span><strong>执行</strong><small>调用业务能力</small></li>
        <li><span>04</span><strong>验证</strong><small>沉淀运行证据</small></li>
      </ol>
    </div>

    <HomeServiceScene
      eyebrow="SERVICE CONTEXT"
      title="AI 服务，最终回到真实客户。"
      description="后台里的每次配置、执行与治理，都应该能够解释一次真实服务体验。"
      :current-user="displayName"
    />
  </section>
</template>

<style scoped>
.home-hero {
  display: grid;
  grid-template-columns: minmax(0, 1.48fr) minmax(320px, 0.82fr);
  min-height: 500px;
  overflow: hidden;
  border: 1px solid var(--cw-line-strong);
  border-radius: calc(var(--cw-radius-lg) + 6px);
  background: var(--home-paper, var(--cw-paper));
  box-shadow: var(--cw-shadow-lg);
  animation: home-hero-enter 480ms ease-out both;
}

.welcome-panel {
  position: relative;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  min-width: 0;
  overflow: hidden;
  padding: clamp(34px, 4vw, 66px);
  color: var(--home-on-ink);
  background:
    radial-gradient(circle at 77% 18%, color-mix(in srgb, var(--home-cobalt) 35%, transparent), transparent 36%),
    radial-gradient(circle at 10% 100%, color-mix(in srgb, var(--home-success) 16%, transparent), transparent 34%),
    linear-gradient(145deg, var(--home-ink) 0%, var(--home-ink-soft) 68%, var(--home-ink) 100%);
}

.welcome-panel__grid {
  position: absolute;
  inset: 0;
  pointer-events: none;
  opacity: 0.26;
  background-image:
    linear-gradient(rgb(255 255 255 / 9%) 1px, transparent 1px),
    linear-gradient(90deg, rgb(255 255 255 / 9%) 1px, transparent 1px);
  background-size: 44px 44px;
  -webkit-mask-image: linear-gradient(to bottom right, transparent 12%, #000 76%);
  mask-image: linear-gradient(to bottom right, transparent 12%, #000 76%);
}

.welcome-panel__content,
.agent-loop {
  position: relative;
  z-index: 1;
}

.welcome-meta {
  display: flex;
  align-items: center;
  gap: 12px;
  color: rgb(235 243 250 / 68%);
  font-size: 11px;
  font-weight: 760;
  line-height: 1.2;
  letter-spacing: 0.15em;
  text-transform: uppercase;
}

.welcome-meta__divider {
  width: 28px;
  height: 1px;
  background: rgb(255 255 255 / 28%);
}

.runtime-status {
  display: inline-flex;
  align-items: center;
  gap: 7px;
}

.runtime-status i {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--home-amber);
  box-shadow: 0 0 0 4px color-mix(in srgb, var(--home-amber) 12%, transparent);
}

.runtime-status--ready i {
  background: var(--home-success);
  box-shadow: 0 0 0 4px color-mix(in srgb, var(--home-success) 12%, transparent);
}

.welcome-kicker {
  margin: clamp(46px, 7vh, 76px) 0 13px;
  color: var(--home-on-ink-muted);
  font-size: 14px;
  font-weight: 650;
  letter-spacing: 0.045em;
}

.welcome-panel h1 {
  max-width: 860px;
  margin: 0;
  color: #ffffff;
  font-size: clamp(42px, 5.2vw, 72px);
  font-weight: 760;
  line-height: 1.06;
  letter-spacing: -0.055em;
  text-wrap: balance;
}

.welcome-summary {
  max-width: 690px;
  margin: 24px 0 0;
  color: rgb(225 235 244 / 70%);
  font-size: 15px;
  line-height: 1.8;
}

.primary-entry {
  display: inline-flex;
  align-items: center;
  justify-content: space-between;
  gap: 30px;
  min-width: 240px;
  max-width: 100%;
  margin-top: 30px;
  padding: 13px 14px 13px 18px;
  border: 1px solid rgb(255 255 255 / 20%);
  border-radius: var(--cw-radius-md);
  color: #ffffff;
  background: rgb(255 255 255 / 8%);
  cursor: pointer;
  font: inherit;
  font-size: 13px;
  font-weight: 700;
  text-align: left;
  transition: border-color 160ms ease, background-color 160ms ease, transform 160ms ease;
}

.primary-entry__arrow {
  display: grid;
  flex: 0 0 30px;
  width: 30px;
  height: 30px;
  place-items: center;
  border-radius: var(--cw-radius-sm);
  color: var(--home-ink);
  background: #ffffff;
  font-size: 15px;
}

.signal-strip {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  max-width: 720px;
  margin: clamp(34px, 5vh, 54px) 0 0;
  padding: 0;
  border-top: 1px solid rgb(255 255 255 / 13%);
}

.signal-strip > div {
  min-width: 0;
  padding: 22px 18px 0 0;
}

.signal-strip > div + div {
  padding-left: 22px;
  border-left: 1px solid rgb(255 255 255 / 13%);
}

.signal-strip dt {
  color: #ffffff;
  font-size: 28px;
  font-weight: 760;
  line-height: 1;
}

.signal-strip dd {
  margin: 8px 0 0;
  color: var(--home-on-ink-soft);
  font-size: 12px;
  font-weight: 700;
}

.signal-strip small {
  display: block;
  margin-top: 5px;
  color: rgb(213 226 238 / 48%);
  font-size: 10px;
}

.agent-loop {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 0;
  margin: 42px 0 0;
  padding: 20px 0 0;
  border-top: 1px solid rgb(255 255 255 / 13%);
  list-style: none;
}

.agent-loop li {
  position: relative;
  display: grid;
  min-width: 0;
  gap: 4px;
  padding-right: 18px;
}

.agent-loop li:not(:last-child)::after {
  content: '';
  position: absolute;
  top: 8px;
  right: 12px;
  width: 28px;
  height: 1px;
  background: linear-gradient(90deg, rgb(255 255 255 / 28%), transparent);
}

.agent-loop span {
  color: var(--home-amber);
  font-size: 10px;
  font-weight: 800;
  letter-spacing: 0.12em;
}

.agent-loop strong {
  color: var(--home-on-ink-strong);
  font-size: 12px;
}

.agent-loop small {
  color: rgb(213 226 238 / 48%);
  font-size: 10px;
}

.primary-entry:focus-visible {
  outline: 3px solid var(--cw-focus-ring);
  outline-offset: 3px;
}

@media (hover: hover) {
  .primary-entry:hover {
    border-color: rgb(255 255 255 / 42%);
    background: rgb(255 255 255 / 13%);
    transform: translateY(-1px);
  }
}

@media (max-width: 1100px) {
  .home-hero {
    grid-template-columns: minmax(0, 1.32fr) minmax(300px, 0.68fr);
  }

  .welcome-panel {
    padding: 40px;
  }
}

@media (max-width: 820px) {
  .home-hero {
    grid-template-columns: 1fr;
    min-height: 0;
  }

  .welcome-panel {
    min-height: 560px;
  }
}

@media (max-width: 520px) {
  .home-hero {
    border-radius: var(--cw-radius-lg);
  }

  .welcome-panel {
    min-height: 0;
    padding: 28px 22px;
  }

  .welcome-meta {
    align-items: flex-start;
    gap: 9px;
    font-size: 9px;
    letter-spacing: 0.09em;
  }

  .welcome-meta__divider {
    width: 14px;
    margin-top: 5px;
  }

  .welcome-kicker {
    margin-top: 44px;
  }

  .welcome-panel h1 {
    font-size: clamp(36px, 11.5vw, 46px);
    letter-spacing: -0.05em;
  }

  .welcome-summary {
    margin-top: 20px;
    font-size: 13px;
    line-height: 1.75;
  }

  .primary-entry {
    width: 100%;
    margin-top: 24px;
  }

  .signal-strip {
    margin-top: 30px;
  }

  .signal-strip > div {
    padding: 18px 8px 0 0;
  }

  .signal-strip > div + div {
    padding-left: 10px;
  }

  .signal-strip dt {
    font-size: 23px;
  }

  .signal-strip dd {
    font-size: 10px;
  }

  .signal-strip small {
    font-size: 8px;
    line-height: 1.35;
  }

  .agent-loop {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 18px 10px;
    margin-top: 32px;
  }

  .agent-loop li::after {
    display: none;
  }
}

@media (prefers-reduced-motion: reduce) {
  .home-hero {
    animation: none;
  }

  .primary-entry {
    transition: none;
  }
}

@keyframes home-hero-enter {
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
