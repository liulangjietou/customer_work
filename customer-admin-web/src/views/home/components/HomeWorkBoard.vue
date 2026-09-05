<script setup lang="ts">
import type { HomeSnapshot } from '../../homePresentation'
defineProps<{ snapshot: HomeSnapshot }>()
const emit = defineEmits<{ navigate: [path: string] }>()
</script>

<template>
  <section class="work-board" aria-label="继续工作">
    <article class="board-card">
      <header class="board-card__header">
        <h2>最近工作</h2>
        <span>本次登录</span>
      </header>
      <div v-if="snapshot.recentTabs.length" class="recent-list">
        <button
          v-for="tab in snapshot.recentTabs"
          :key="tab.key"
          type="button"
          class="recent-item"
          @click="emit('navigate', tab.path)"
        >
          <span class="entry-icon"
            ><el-icon><Clock /></el-icon
          ></span>
          <span class="entry-copy"
            ><strong>{{ tab.title }}</strong
            ><small>继续上次打开的工作</small></span
          >
          <span class="entry-arrow" aria-hidden="true">↗</span>
        </button>
      </div>
      <div v-else class="board-empty">
        <el-icon><Clock /></el-icon>
        <p>还没有最近工作</p>
        <small>打开智能体或业务页面后，可在这里继续。</small>
      </div>
    </article>
    <article class="board-card">
      <header class="board-card__header">
        <h2>快捷入口</h2>
        <span>{{ snapshot.availableEntryCount }} 个可用入口</span>
      </header>
      <div v-if="snapshot.quickEntries.length" class="quick-grid">
        <button
          v-for="entry in snapshot.quickEntries"
          :key="entry.key"
          type="button"
          class="quick-entry"
          @click="emit('navigate', entry.path)"
        >
          <span class="entry-copy"
            ><strong>{{ entry.title }}</strong
            ><small>{{ entry.sectionTitle }}</small></span
          ><span class="entry-arrow" aria-hidden="true">↗</span>
        </button>
      </div>
      <div v-else class="board-empty">
        <p>暂无可用入口</p>
        <small>请联系管理员分配角色权限。</small>
      </div>
    </article>
  </section>
</template>

<style scoped>
.work-board {
  display: grid;
  grid-template-columns: minmax(0, 1.2fr) minmax(0, 1fr);
  gap: 20px;
  margin-top: 20px;
}
.board-card {
  min-width: 0;
  border: 1px solid var(--cw-line);
  border-radius: 10px;
  background: var(--cw-paper);
  padding: 20px;
}
.board-card__header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  margin-bottom: 18px;
}
h2 {
  font-size: 15px;
  font-weight: 650;
  margin: 0;
}
.board-card__header > span,
small {
  font-size: 12px;
  color: var(--cw-text-muted);
}
.recent-list {
  display: grid;
}
.recent-item,
.quick-entry {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 10px;
  border: 0;
  border-radius: 7px;
  background: transparent;
  text-align: left;
  font: inherit;
  color: var(--cw-text);
  cursor: pointer;
  min-width: 0;
}
.recent-item + .recent-item {
  border-top: 1px solid var(--cw-line);
}
.recent-item:hover,
.quick-entry:hover {
  background: var(--cw-canvas);
}
.entry-icon {
  display: grid;
  place-items: center;
  flex: 0 0 36px;
  height: 36px;
  border: 1px solid var(--cw-line);
  border-radius: 8px;
  color: var(--cw-cobalt);
}
.entry-copy {
  min-width: 0;
  display: grid;
  gap: 6px;
}
.entry-copy strong {
  font-size: 13px;
  font-weight: 550;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.entry-arrow {
  margin-left: auto;
  color: var(--cw-text-muted);
}
.quick-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 6px;
}
.board-empty {
  min-height: 155px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  color: var(--cw-text-muted);
  line-height: 1.7;
}
.board-empty > .el-icon {
  font-size: 24px;
}
.board-empty p {
  margin: 10px 0 3px;
  font-size: 13px;
}
@media (max-width: 900px) {
  .work-board {
    grid-template-columns: minmax(0, 1fr);
  }
}
@media (max-width: 420px) {
  .quick-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
