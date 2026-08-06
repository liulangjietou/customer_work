<script setup lang="ts">
// cron 解析：解析与推算一律走后端（starter 的 CronDevToolOps），与 XXL-JOB 的 6 段 Quartz 语义
// 保持同源。浏览器端 cron 库多按 Unix 5 段解析，若在前端算，页面显示的"下次执行时间"可能与调度
// 中心实际触发时间不符——排查问题时这种偏差比没有工具更糟。
import { ref } from 'vue'
import { explainCron, type CronExplainResponse } from '@/api/devtools'
import { usePersistedRef } from './composables/useToolStorage'
import CopyButton from './CopyButton.vue'

const expression = usePersistedRef('cron:expression', '0 0 2 * * ?')
const count = usePersistedRef('cron:count', 5)
const timezone = usePersistedRef('cron:timezone', 'Asia/Shanghai')

const loading = ref(false)
const result = ref<CronExplainResponse | null>(null)

/** 常用表达式，点一下直接填入——多数排查场景就是拿这几个改改。 */
const presets: { label: string; value: string }[] = [
  { label: '每分钟', value: '0 * * * * ?' },
  { label: '每 5 分钟', value: '0 */5 * * * ?' },
  { label: '每小时整点', value: '0 0 * * * ?' },
  { label: '每天 02:00', value: '0 0 2 * * ?' },
  { label: '每周一 09:00', value: '0 0 9 ? * MON' },
  { label: '每月 1 号 00:00', value: '0 0 0 1 * ?' },
]

async function handleExplain() {
  if (!expression.value.trim()) {
    ElMessage.warning('请先输入 cron 表达式')
    return
  }
  loading.value = true
  try {
    result.value = await explainCron(expression.value, count.value, timezone.value)
  } finally {
    loading.value = false
  }
}

function applyPreset(value: string) {
  expression.value = value
  handleExplain()
}
</script>

<template>
  <div class="cron-tool">
    <el-form label-width="100px" class="param-form">
      <el-form-item label="cron 表达式">
        <div class="expression-row">
          <el-input
            v-model="expression"
            placeholder="6 段：秒 分 时 日 月 周，如 0 0 2 * * ?"
            clearable
            @keyup.enter="handleExplain"
          />
          <el-button type="primary" :loading="loading" @click="handleExplain">解析</el-button>
        </div>
        <span class="form-tip">本项目调度中心（XXL-JOB）用 6 段 Quartz 风格；5 段的 Unix cron 需在最前面补一段秒</span>
      </el-form-item>

      <el-form-item label="常用示例">
        <div class="presets">
          <el-tag
            v-for="preset in presets"
            :key="preset.value"
            class="preset-tag"
            type="info"
            effect="plain"
            @click="applyPreset(preset.value)"
          >
            {{ preset.label }}
          </el-tag>
        </div>
      </el-form-item>

      <el-form-item label="推算条数">
        <el-input-number v-model="count" :min="1" :max="20" />
        <el-select v-model="timezone" class="timezone-select">
          <el-option label="Asia/Shanghai" value="Asia/Shanghai" />
          <el-option label="UTC" value="UTC" />
          <el-option label="America/New_York" value="America/New_York" />
          <el-option label="Europe/London" value="Europe/London" />
        </el-select>
      </el-form-item>
    </el-form>

    <div v-if="result" class="panes">
      <div class="pane">
        <div class="pane-header"><span>字段释义</span></div>
        <el-table :data="result.fields" size="small" border>
          <el-table-column prop="name" label="字段" width="80" />
          <el-table-column prop="value" label="取值" width="110" />
          <el-table-column prop="description" label="含义" />
          <el-table-column prop="range" label="合法范围" width="150" />
        </el-table>
      </div>

      <div class="pane">
        <div class="pane-header">
          <span>后续执行时间（{{ result.timezone }}）</span>
          <CopyButton :text="result.nextTimes.join('\n')" label="执行时间" />
        </div>
        <ul v-if="result.nextTimes.length" class="next-times">
          <li v-for="(time, index) in result.nextTimes" :key="time + index">
            <span class="seq">{{ index + 1 }}</span>{{ time }}
          </li>
        </ul>
        <el-alert v-else type="warning" :closable="false" show-icon title="该表达式今后不会再触发（指定的日期已过去）" />
      </div>
    </div>
  </div>
</template>

<style scoped>
.cron-tool {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.param-form {
  max-width: 860px;
}

.expression-row {
  display: flex;
  gap: 8px;
  width: 100%;
}

.presets {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.preset-tag {
  cursor: pointer;
}

.timezone-select {
  width: 200px;
  margin-left: 12px;
}

.form-tip {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.6;
}

.panes {
  display: flex;
  gap: 16px;
  align-items: flex-start;
}

.pane {
  flex: 1;
  min-width: 0;
}

.pane-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 13px;
  font-weight: 600;
  color: var(--el-text-color-secondary);
  margin-bottom: 6px;
  min-height: 24px;
}

.next-times {
  margin: 0;
  padding: 8px 12px;
  list-style: none;
  border: 1px solid var(--el-border-color);
  border-radius: 6px;
  background: var(--el-fill-color-lighter);
  font-family: 'JetBrains Mono', 'Fira Code', 'Courier New', monospace;
  font-size: 13px;
}

.next-times li {
  padding: 4px 0;
  color: var(--el-text-color-primary);
}

.seq {
  display: inline-block;
  width: 24px;
  color: var(--el-text-color-secondary);
}
</style>
