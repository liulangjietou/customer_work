<script setup lang="ts">
defineProps<{ assistantName: string }>()

const LINE_NUMBERS = ['01', '02', '03', '04', '05', '06', '07', '08', '09', '10', '11', '12']
</script>

<template>
  <section class="workspace-empty-state">
    <div class="workspace-empty-state__status" aria-hidden="true">
      <span class="workspace-empty-state__status-dot" />
      AGENT WORKBENCH / READY
    </div>

    <div class="workspace-empty-state__blueprint" aria-hidden="true">
      <div class="workspace-empty-state__line-numbers">
        <span v-for="line in LINE_NUMBERS" :key="line">{{ line }}</span>
      </div>
      <span class="workspace-empty-state__indent-guide workspace-empty-state__indent-guide--first" />
      <span class="workspace-empty-state__indent-guide workspace-empty-state__indent-guide--second" />
      <span class="workspace-empty-state__indent-guide workspace-empty-state__indent-guide--third" />
      <span class="workspace-empty-state__scope-line" />
      <span class="workspace-empty-state__scope-token workspace-empty-state__scope-token--open">{</span>
      <span class="workspace-empty-state__scope-token workspace-empty-state__scope-token--close">}</span>
      <code class="workspace-empty-state__ghost-code">context.read();
intent.resolve();
answer.verify();</code>
    </div>

    <div class="workspace-empty-state__content">
      <div class="workspace-empty-state__eyebrow" aria-hidden="true">
        <span class="workspace-empty-state__eyebrow-name">{{ assistantName }} · READY</span>
      </div>
      <h2 class="workspace-empty-state__title">从一个任务开始</h2>
      <p class="workspace-empty-state__lead">
        描述目标，或直接补充资料、上下文和约束。我会先理解任务，再给出可验证的处理建议。
      </p>
      <p class="workspace-empty-state__example">
        <span aria-hidden="true">//</span>
        例如：梳理需求 · 定位问题 · 完成一个可验证的结果
      </p>
    </div>
  </section>
</template>

<style scoped>
.workspace-empty-state {
  --empty-state-muted: color-mix(in srgb, var(--el-text-color-primary) 72%, var(--el-bg-color));
  --empty-state-quiet: color-mix(in srgb, var(--el-text-color-primary) 68%, var(--el-bg-color));
  --empty-state-line: color-mix(in srgb, var(--el-border-color) 48%, transparent);
  --empty-state-accent: var(--theme-primary, var(--el-color-primary));
  position: absolute;
  z-index: 1;
  inset: 0;
  overflow: hidden;
  color: var(--el-text-color-primary);
  pointer-events: none;
  isolation: isolate;
}

.workspace-empty-state::before {
  position: absolute;
  z-index: -1;
  inset: 0;
  background: linear-gradient(180deg, color-mix(in srgb, var(--cw-paper, var(--el-bg-color)) 34%, transparent), transparent 38%);
  content: '';
}

.workspace-empty-state__status {
  position: absolute;
  z-index: 2;
  top: 24px;
  left: 30px;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: var(--empty-state-muted);
  font-family: 'SFMono-Regular', 'Cascadia Code', 'Roboto Mono', Menlo, Consolas, monospace;
  font-size: 10px;
  font-weight: 600;
  line-height: 1;
  letter-spacing: 0.11em;
}

.workspace-empty-state__status-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--el-color-success);
  box-shadow: 0 0 0 4px color-mix(in srgb, var(--el-color-success) 10%, transparent);
}

.workspace-empty-state__blueprint {
  position: absolute;
  z-index: 1;
  inset: 54px 40px 32px 34px;
  color: var(--empty-state-muted);
  user-select: none;
  pointer-events: none;
}

.workspace-empty-state__line-numbers {
  position: absolute;
  top: 0;
  left: 0;
  display: grid;
  grid-template-rows: repeat(12, 24px);
  width: 24px;
  color: var(--empty-state-quiet);
  font-family: 'SFMono-Regular', 'Cascadia Code', 'Roboto Mono', Menlo, Consolas, monospace;
  font-size: 9px;
  font-weight: 500;
  line-height: 24px;
  text-align: right;
}

.workspace-empty-state__indent-guide {
  position: absolute;
  width: 1px;
  background: var(--empty-state-line);
}

.workspace-empty-state__indent-guide--first {
  top: 0;
  bottom: 0;
  left: 46px;
}

.workspace-empty-state__indent-guide--second {
  top: 24px;
  bottom: 24px;
  left: 70px;
}

.workspace-empty-state__indent-guide--third {
  top: 48px;
  bottom: 48px;
  left: 94px;
}

.workspace-empty-state__scope-line {
  position: absolute;
  top: 71px;
  left: 94px;
  width: min(216px, 34%);
  height: 191px;
  border: 1px solid var(--empty-state-line);
  border-right: 0;
  border-radius: 10px 0 0 10px;
}

.workspace-empty-state__scope-token {
  position: absolute;
  left: 107px;
  color: color-mix(in srgb, var(--empty-state-accent) 38%, transparent);
  font-family: 'SFMono-Regular', 'Cascadia Code', 'Roboto Mono', Menlo, Consolas, monospace;
  font-size: 17px;
  font-weight: 600;
  line-height: 1;
}

.workspace-empty-state__scope-token--open {
  top: 61px;
}

.workspace-empty-state__scope-token--close {
  top: 252px;
}

.workspace-empty-state__ghost-code {
  position: absolute;
  top: 24px;
  right: 6px;
  color: color-mix(in srgb, var(--el-text-color-secondary) 14%, transparent);
  font-family: 'SFMono-Regular', 'Cascadia Code', 'Roboto Mono', Menlo, Consolas, monospace;
  font-size: 11px;
  font-weight: 500;
  line-height: 2.15;
  text-align: left;
  white-space: pre;
}

.workspace-empty-state__content {
  position: absolute;
  z-index: 3;
  top: clamp(112px, 28%, 176px);
  left: 50%;
  width: min(620px, calc(100% - 96px));
  transform: translateX(-50%);
  text-align: left;
}

.workspace-empty-state__eyebrow {
  display: inline-flex;
  align-items: center;
  gap: 9px;
  max-width: 100%;
  margin-bottom: 12px;
  color: var(--empty-state-muted);
  font-family: 'SFMono-Regular', 'Cascadia Code', 'Roboto Mono', Menlo, Consolas, monospace;
  font-size: 10px;
  font-weight: 650;
  line-height: 1;
  letter-spacing: 0.1em;
}

.workspace-empty-state__eyebrow-name {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.workspace-empty-state__eyebrow::before {
  width: 8px;
  height: 14px;
  border-radius: 2px;
  background: var(--empty-state-accent);
  content: '';
  animation: workspace-empty-state-caret 1s step-end 2;
}

.workspace-empty-state__title {
  margin: 0;
  color: var(--el-text-color-primary);
  font-size: 26px;
  font-weight: 680;
  line-height: 1.34;
  letter-spacing: -0.025em;
}

.workspace-empty-state__lead {
  max-width: 570px;
  margin: 9px 0 0;
  color: var(--empty-state-muted);
  font-size: 14px;
  line-height: 1.75;
}

.workspace-empty-state__example {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  margin: 17px 0 0;
  color: var(--empty-state-quiet);
  font-size: 11px;
  line-height: 1.5;
}

.workspace-empty-state__example span {
  color: color-mix(in srgb, var(--empty-state-accent) 62%, transparent);
  font-family: 'SFMono-Regular', 'Cascadia Code', 'Roboto Mono', Menlo, Consolas, monospace;
  font-weight: 650;
}

@keyframes workspace-empty-state-caret {
  50% {
    opacity: 0.18;
  }
}

@container conversation-canvas (max-width: 900px) {
  .workspace-empty-state__status {
    top: 20px;
    left: 22px;
  }

  .workspace-empty-state__blueprint {
    inset: 50px 28px 28px 22px;
  }

  .workspace-empty-state__content {
    top: clamp(104px, 25%, 148px);
    width: min(560px, calc(100% - 64px));
  }

  .workspace-empty-state__title {
    font-size: 24px;
  }
}

@container conversation-canvas (max-width: 620px) {
  .workspace-empty-state__status {
    top: 17px;
    left: 18px;
    font-size: 9px;
  }

  .workspace-empty-state__blueprint {
    inset: 46px 18px 24px 12px;
  }

  .workspace-empty-state__indent-guide--third,
  .workspace-empty-state__ghost-code {
    display: none;
  }

  .workspace-empty-state__content {
    top: clamp(96px, 23%, 126px);
    width: calc(100% - 48px);
  }

  .workspace-empty-state__eyebrow {
    margin-bottom: 10px;
    font-size: 9px;
  }

  .workspace-empty-state__title {
    font-size: 22px;
  }

  .workspace-empty-state__lead {
    font-size: 13px;
    line-height: 1.7;
  }

  .workspace-empty-state__example {
    margin-top: 14px;
    line-height: 1.7;
  }
}

@media (prefers-reduced-motion: reduce) {
  .workspace-empty-state__eyebrow::before {
    animation: none;
  }
}
</style>
