<script setup lang="ts">
import type { HomeEntry, HomeSnapshot } from '../../homePresentation'

defineProps<{
  snapshot: HomeSnapshot
}>()

const emit = defineEmits<{
  navigate: [path: string]
}>()

const sectionCode: Record<HomeEntry['sectionKey'], string> = {
  overview: 'OVR',
  agents: 'AGT',
  build: 'BLD',
  operate: 'OPS',
  govern: 'GOV',
  settings: 'SYS',
}
</script>

<template>
  <section class="work-board" aria-label="继续工作">
    <article class="board-card board-card--recent">
      <header class="board-card__header">
        <div>
          <span class="section-index">01</span>
          <div>
            <h2>继续最近的工作</h2>
            <p>仅展示当前仍有权限访问的标签。</p>
          </div>
        </div>
        <span class="record-count">{{ snapshot.recentTabs.length }} / 3</span>
      </header>

      <div v-if="snapshot.recentTabs.length" class="recent-list">
        <button
          v-for="tab in snapshot.recentTabs"
          :key="tab.key"
          type="button"
          class="recent-item"
          @click="emit('navigate', tab.path)"
        >
          <span class="recent-item__mark" aria-hidden="true" />
          <span class="recent-item__content">
            <strong>{{ tab.title }}</strong>
            <small>{{ tab.path }}</small>
          </span>
          <span class="recent-item__action" aria-hidden="true">继续 ↗</span>
        </button>
      </div>
      <div v-else class="board-empty">
        <span aria-hidden="true">—</span>
        <p>本次会话还没有可继续的工作，从右侧真实权限入口开始即可。</p>
      </div>
    </article>

    <article class="board-card board-card--quick">
      <header class="board-card__header">
        <div>
          <span class="section-index">02</span>
          <div>
            <h2>按生命周期进入</h2>
            <p>每个分区先展示一个代表入口。</p>
          </div>
        </div>
      </header>

      <div v-if="snapshot.quickEntries.length" class="quick-grid">
        <button
          v-for="entry in snapshot.quickEntries"
          :key="entry.key"
          type="button"
          class="quick-entry"
          @click="emit('navigate', entry.path)"
        >
          <span class="quick-entry__code">{{ sectionCode[entry.sectionKey] }}</span>
          <span class="quick-entry__body">
            <strong>{{ entry.title }}</strong>
            <small>{{ entry.sectionTitle }}</small>
          </span>
          <span class="quick-entry__arrow" aria-hidden="true">↗</span>
        </button>
      </div>
      <div v-else class="board-empty board-empty--compact">
        <span aria-hidden="true">—</span>
        <p>当前账号尚未分配可用菜单，请联系管理员配置角色权限。</p>
      </div>
    </article>
  </section>
</template>

<style scoped>
.work-board {
  display: grid;
  grid-template-columns: minmax(0, 1.18fr) minmax(360px, 0.82fr);
  gap: 18px;
  margin-top: 18px;
  animation: home-board-enter 520ms 80ms ease-out both;
}

.board-card {
  min-width: 0;
  padding: clamp(22px, 2.4vw, 32px);
  border: 1px solid var(--home-line, var(--cw-line));
  border-radius: var(--cw-radius-lg);
  background: var(--home-paper, var(--cw-paper));
  box-shadow: var(--cw-shadow-xs);
}

.board-card__header,
.board-card__header > div {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 14px;
}

.board-card__header > div {
  justify-content: flex-start;
}

.section-index {
  display: grid;
  flex: 0 0 32px;
  width: 32px;
  height: 32px;
  place-items: center;
  border: 1px solid var(--home-line, var(--cw-line));
  border-radius: var(--cw-radius-md);
  color: var(--home-cobalt);
  font-size: 10px;
  font-weight: 800;
  letter-spacing: 0.08em;
}

.board-card h2 {
  margin: 0;
  color: var(--home-text, var(--cw-text));
  font-size: 18px;
  font-weight: 740;
  line-height: 1.25;
  letter-spacing: -0.02em;
}

.board-card__header p {
  margin: 6px 0 0;
  color: var(--home-muted, var(--cw-text-muted));
  font-size: 11px;
}

.record-count {
  flex: 0 0 auto;
  padding: 5px 8px;
  border-radius: var(--cw-radius-sm);
  color: var(--home-muted, var(--cw-text-muted));
  background: var(--home-canvas, var(--cw-canvas));
  font-size: 10px;
  font-variant-numeric: tabular-nums;
}

.recent-list,
.quick-grid {
  display: grid;
  gap: 8px;
  margin-top: 22px;
}

.recent-item,
.quick-entry {
  display: flex;
  align-items: center;
  width: 100%;
  min-width: 0;
  padding: 12px;
  border: 1px solid transparent;
  border-radius: var(--cw-radius-md);
  color: inherit;
  background: transparent;
  cursor: pointer;
  font: inherit;
  text-align: left;
  transition: border-color 160ms ease, background-color 160ms ease, transform 160ms ease;
}

.recent-item + .recent-item {
  border-top-color: var(--home-line, var(--cw-line));
  border-top-left-radius: 0;
  border-top-right-radius: 0;
}

.recent-item__mark {
  flex: 0 0 8px;
  width: 8px;
  height: 8px;
  margin-right: 13px;
  border: 2px solid var(--home-paper, var(--cw-paper));
  border-radius: 50%;
  background: var(--home-cobalt);
  box-shadow: 0 0 0 1px var(--home-cobalt);
}

.recent-item__content,
.quick-entry__body {
  display: grid;
  min-width: 0;
  gap: 4px;
}

.recent-item__content strong,
.quick-entry__body strong {
  overflow: hidden;
  color: var(--home-text, var(--cw-text));
  font-size: 13px;
  font-weight: 700;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.recent-item__content small,
.quick-entry__body small {
  overflow: hidden;
  color: var(--home-muted, var(--cw-text-muted));
  font-size: 10px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.recent-item__action {
  flex: 0 0 auto;
  margin-left: auto;
  padding-left: 16px;
  color: var(--home-cobalt);
  font-size: 10px;
  font-weight: 700;
}

.quick-grid {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.quick-entry {
  position: relative;
  align-items: flex-start;
  min-height: 84px;
  padding: 14px;
  border-color: var(--home-line, var(--cw-line));
  background: var(--home-canvas, var(--cw-canvas));
}

.quick-entry__code {
  flex: 0 0 auto;
  margin-right: 11px;
  color: var(--home-cobalt);
  font-size: 9px;
  font-weight: 850;
  letter-spacing: 0.08em;
}

.quick-entry__arrow {
  position: absolute;
  right: 12px;
  bottom: 10px;
  color: var(--home-muted, var(--cw-text-muted));
  font-size: 11px;
}

.board-empty {
  display: flex;
  align-items: center;
  gap: 14px;
  min-height: 94px;
  margin-top: 22px;
  padding: 18px;
  border: 1px dashed var(--home-line, var(--cw-line));
  border-radius: var(--cw-radius-md);
  color: var(--home-muted, var(--cw-text-muted));
  background: var(--home-canvas, var(--cw-canvas));
}

.board-empty span {
  color: var(--home-cobalt);
  font-size: 20px;
}

.board-empty p {
  margin: 0;
  font-size: 12px;
  line-height: 1.65;
}

.board-empty--compact {
  min-height: 82px;
}

.recent-item:focus-visible,
.quick-entry:focus-visible {
  outline: 3px solid var(--cw-focus-ring);
  outline-offset: 3px;
}

@media (hover: hover) {
  .recent-item:hover,
  .quick-entry:hover {
    border-color: color-mix(in srgb, var(--home-cobalt) 34%, var(--home-line, var(--cw-line)));
    background: color-mix(in srgb, var(--home-cobalt) 6%, transparent);
    transform: translateY(-1px);
  }
}

@media (max-width: 1100px) {
  .work-board {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 520px) {
  .work-board {
    gap: 12px;
    margin-top: 12px;
  }

  .board-card {
    padding: 20px 17px;
    border-radius: var(--cw-radius-lg);
  }

  .board-card__header p,
  .record-count {
    display: none;
  }

  .recent-item {
    padding-right: 6px;
    padding-left: 6px;
  }

  .recent-item__action {
    padding-left: 8px;
  }

  .quick-grid {
    grid-template-columns: 1fr;
  }
}

@media (prefers-reduced-motion: reduce) {
  .work-board {
    animation: none;
  }

  .recent-item,
  .quick-entry {
    transition: none;
  }
}

@keyframes home-board-enter {
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
