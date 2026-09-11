<script setup lang="ts">
import type { AgentVO } from '@/types/api'
defineProps<{ agent: AgentVO; capabilityLabels: string[] }>()
</script>

<template>
  <article class="agent-overview-card" :aria-label="agent.agentName">
    <header>
      <span class="agent-symbol"
        ><el-icon><component :is="agent.icon || 'Cpu'" /></el-icon
      ></span>
      <el-tag :type="agent.status === 1 ? 'success' : 'info'" size="small">{{
        agent.status === 1 ? '已启用' : '已停用'
      }}</el-tag>
    </header>
    <h2>{{ agent.agentName }}</h2>
    <p class="agent-code">{{ agent.agentCode }}</p>
    <dl>
      <div>
        <dt>主模型</dt>
        <dd>{{ agent.modelName || '未绑定' }}</dd>
      </div>
      <div>
        <dt>备用模型</dt>
        <dd>
          {{ agent.backupModelNames?.length ? `${agent.backupModelNames.length} 个` : '未配置' }}
        </dd>
      </div>
      <div>
        <dt>知识库</dt>
        <dd>{{ agent.knowledgeBaseNames?.length || 0 }} 个</dd>
      </div>
      <div>
        <dt>工具与技能</dt>
        <dd>
          {{
            (agent.mcpIds?.length || 0) +
            (agent.systemToolIds?.length || 0) +
            (agent.skillIds?.length || 0)
          }}
          项
        </dd>
      </div>
    </dl>
    <div class="capability-list" aria-label="已配置能力">
      <span v-for="label in capabilityLabels" :key="label">{{ label }}</span>
      <span v-if="!capabilityLabels.length">未配置能力</span>
    </div>
    <footer><slot name="actions" /></footer>
  </article>
</template>

<style scoped>
.agent-overview-card {
  display: flex;
  flex-direction: column;
  min-width: 0;
  padding: 22px;
  border: 1px solid var(--cw-line);
  border-radius: 10px;
  background: var(--cw-paper);
  transition:
    border-color 160ms ease,
    box-shadow 160ms ease;
}
.agent-overview-card:focus-within {
  border-color: var(--cw-focus-ring);
}
header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.agent-symbol {
  display: grid;
  place-items: center;
  width: 40px;
  height: 40px;
  color: var(--cw-cobalt);
  background: color-mix(in srgb, var(--cw-cobalt) 9%, var(--cw-paper));
  border-radius: 10px;
  font-size: 22px;
}
h2 {
  font-size: 17px;
  font-weight: 600;
  margin: 18px 0 4px;
  overflow-wrap: anywhere;
}
.agent-code {
  margin: 0 0 20px;
  font-size: 12px;
  color: var(--cw-text-muted);
  overflow-wrap: anywhere;
}
dl {
  display: grid;
  gap: 9px;
  margin: 0;
  font-size: 13px;
}
dl > div {
  display: grid;
  grid-template-columns: 84px minmax(0, 1fr);
  gap: 12px;
}
dt {
  color: var(--cw-text-muted);
}
dd {
  margin: 0;
  text-align: right;
  overflow-wrap: anywhere;
}
.capability-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin: 20px 0;
  font-size: 12px;
  color: var(--cw-text-muted);
}
.capability-list > span {
  padding: 3px 8px;
  border-radius: 5px;
  background: var(--cw-canvas);
}
footer {
  margin-top: auto;
  padding-top: 16px;
  border-top: 1px solid var(--cw-line);
}
</style>
