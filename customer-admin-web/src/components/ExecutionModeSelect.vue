<script setup lang="ts">
// 执行模式选择器（对话 ChatPanel + VibeCodingPanel 通用）：对齐 Claude Code 的 Mode 下拉交互，
// 输入区放一个模式徽标触发器，点开列出五档、当前档打勾，默认 Auto。会话内记忆——模式挂在会话对象
// 上，由父组件通过 v-model 绑定到当前激活会话，切会话即各自记忆，本组件不持有状态。
import { computed } from 'vue'
import type { ExecutionMode } from '@/types/api'

const props = defineProps<{ modelValue: ExecutionMode; disabled?: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [mode: ExecutionMode] }>()

interface ModeOption {
  value: ExecutionMode
  label: string
  shortLabel: string
  description: string
  danger?: boolean
}

// 文案与后端契约的语义一一对应，不在别处重复定义。
const MODE_OPTIONS: ModeOption[] = [
  { value: 'auto', label: '自动 · 默认', shortLabel: 'Auto', description: '高风险操作才需确认' },
  { value: 'manual', label: '手动确认', shortLabel: 'Manual', description: '每步工具执行都需确认' },
  { value: 'accept_edits', label: '接受编辑', shortLabel: 'Accept Edits', description: '文件编辑自动放行，命令/删除仍确认' },
  { value: 'plan', label: '计划模式', shortLabel: 'Plan', description: '只读研究，不执行写操作，输出方案' },
  { value: 'bypass', label: '跳过确认', shortLabel: 'Bypass', description: '全部自动执行（危险操作）', danger: true },
]

const current = computed<ModeOption>(() => MODE_OPTIONS.find((o) => o.value === props.modelValue) ?? MODE_OPTIONS[0])

function handleCommand(mode: ExecutionMode) {
  if (mode === props.modelValue) return
  emit('update:modelValue', mode)
}
</script>

<template>
  <el-dropdown trigger="click" :disabled="disabled" @command="handleCommand">
    <el-button
      class="mode-trigger"
      :class="{ 'mode-trigger--danger': current.danger }"
      :disabled="disabled"
      title="切换执行模式"
    >
      {{ current.shortLabel }}
      <el-icon class="mode-trigger-arrow"><ArrowDown /></el-icon>
    </el-button>
    <template #dropdown>
      <el-dropdown-menu>
        <el-dropdown-item v-for="opt in MODE_OPTIONS" :key="opt.value" :command="opt.value">
          <div class="mode-option">
            <el-icon class="mode-option-check"><Check v-if="opt.value === modelValue" /></el-icon>
            <div class="mode-option-text">
              <span class="mode-option-label" :class="{ 'mode-option-label--danger': opt.danger }">{{ opt.label }}</span>
              <span class="mode-option-desc">{{ opt.description }}</span>
            </div>
          </div>
        </el-dropdown-item>
      </el-dropdown-menu>
    </template>
  </el-dropdown>
</template>

<style scoped>
.mode-trigger {
  flex-shrink: 0;
}

.mode-trigger--danger {
  color: var(--el-color-danger);
  border-color: var(--el-color-danger-light-5);
}

.mode-trigger-arrow {
  margin-left: 2px;
  font-size: 12px;
}

.mode-option {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  padding: 2px 0;
  max-width: 260px;
  white-space: normal;
}

.mode-option-check {
  flex-shrink: 0;
  width: 14px;
  margin-top: 2px;
  color: var(--theme-primary, var(--el-color-primary));
}

.mode-option-text {
  display: flex;
  flex-direction: column;
  gap: 2px;
  line-height: 1.4;
}

.mode-option-label {
  font-size: 13px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.mode-option-label--danger {
  color: var(--el-color-danger);
}

.mode-option-desc {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  white-space: normal;
}
</style>
