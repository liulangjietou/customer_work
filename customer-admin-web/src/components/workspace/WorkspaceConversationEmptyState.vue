<script setup lang="ts">
defineProps<{ assistantName: string }>()
const emit = defineEmits<{ prompt: [value: string] }>()
const starters = [
  {
    title: '梳理需求',
    description: '把目标整理成可执行的任务',
    prompt: '请帮我梳理以下需求，明确目标、约束和验收标准：\n',
  },
  {
    title: '分析资料',
    description: '提取要点，并列出待确认的问题',
    prompt: '请根据我提供的资料提取关键结论，并标明依据与不确定项：\n',
  },
  {
    title: '定位问题',
    description: '结合现象与上下文查找原因',
    prompt: '请帮我分析以下问题，先定位原因，再给出验证步骤：\n',
  },
  {
    title: '制定方案',
    description: '比较取舍，形成可验证的步骤',
    prompt: '请为以下任务制定方案，说明关键取舍和验收步骤：\n',
  },
]
</script>

<template>
  <section class="workspace-empty-state" aria-label="开始任务">
    <div class="workspace-empty-state__content">
      <span class="empty-agent-mark" aria-hidden="true">✦</span>
      <p class="empty-agent-name">{{ assistantName }}</p>
      <h2 class="workspace-empty-state__title">从一个任务开始</h2>
      <p class="workspace-empty-state__lead">描述目标，或直接补充资料、上下文和约束。</p>
      <div class="task-starters">
        <button
          v-for="starter in starters"
          :key="starter.title"
          type="button"
          @click="emit('prompt', starter.prompt)"
        >
          <strong>{{ starter.title }}<span aria-hidden="true">↗</span></strong
          ><small>{{ starter.description }}</small>
        </button>
      </div>
    </div>
  </section>
</template>

<style scoped>
.workspace-empty-state {
  min-height: 100%;
  display: grid;
  place-items: center;
  padding: 32px 24px;
  box-sizing: border-box;
  color: var(--cw-text);
}
.workspace-empty-state__content {
  width: min(100%, 560px);
  text-align: center;
}
.empty-agent-mark {
  display: inline-grid;
  place-items: center;
  width: 46px;
  height: 46px;
  border: 1px solid var(--cw-line);
  background: var(--cw-canvas);
  border-radius: 13px;
  font-size: 28px;
  color: var(--cw-cobalt);
}
.empty-agent-name {
  margin: 18px 0 10px;
  color: var(--cw-text-muted);
  font-size: 12px;
}
.workspace-empty-state__title {
  margin: 0;
  font-size: clamp(24px, 2.5vw, 30px);
  font-weight: 600;
  letter-spacing: -0.6px;
}
.workspace-empty-state__lead {
  margin: 14px 0 28px;
  font-size: 13px;
  color: var(--cw-text-muted);
  line-height: 1.8;
}
.task-starters {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}
.task-starters button {
  min-width: 0;
  display: grid;
  gap: 9px;
  padding: 16px;
  background: var(--cw-paper);
  border: 1px solid var(--cw-line);
  border-radius: 9px;
  text-align: left;
  color: var(--cw-text);
  font: inherit;
  cursor: pointer;
}
.task-starters button:hover {
  border-color: var(--cw-cobalt);
  background: var(--cw-canvas);
}
.task-starters strong {
  display: flex;
  justify-content: space-between;
  gap: 6px;
  font-size: 13px;
  font-weight: 550;
}
.task-starters strong span,
.task-starters small {
  color: var(--cw-text-muted);
}
.task-starters small {
  font-size: 12px;
  line-height: 1.5;
}
@container workspace-panel (max-width: 600px) {
  .workspace-empty-state {
    padding: 24px 16px;
  }
  .task-starters button {
    padding: 12px;
  }
  .task-starters small {
    display: none;
  }
}
</style>
