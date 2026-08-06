<script setup lang="ts">
// JSON / YAML / XML 互转：走后端（starter 的 DataFormatDevToolOps），与智能体侧 data_convert 同一实现。
// 后端解析 XML 时已禁用 DTD 与外部实体（防 XXE）。
import { computed, ref } from 'vue'
import { convertFormat, type DataFormat } from '@/api/devtools'
import { usePersistedRef } from './composables/useToolStorage'
import CopyButton from './CopyButton.vue'

const sourceFormat = usePersistedRef<DataFormat>('convert:sourceFormat', 'json')
const targetFormat = usePersistedRef<DataFormat>('convert:targetFormat', 'yaml')
const rootName = usePersistedRef('convert:rootName', 'root')
const input = usePersistedRef('convert:input', '')

const loading = ref(false)
const output = ref('')

const needsRootName = computed(() => targetFormat.value === 'xml')

const formatLabels: Record<DataFormat, string> = { json: 'JSON', yaml: 'YAML', xml: 'XML' }

async function handleConvert() {
  if (!input.value.trim()) {
    ElMessage.warning('请先输入待转换的内容')
    return
  }
  if (sourceFormat.value === targetFormat.value) {
    ElMessage.warning('源格式与目标格式相同，无需转换')
    return
  }
  loading.value = true
  try {
    const response = await convertFormat({
      content: input.value,
      sourceFormat: sourceFormat.value,
      targetFormat: targetFormat.value,
      rootName: needsRootName.value ? rootName.value : undefined,
    })
    output.value = response.result
  } finally {
    loading.value = false
  }
}

/** 互换源与目标格式，并把上次的输出接力成新的输入，便于来回转换核对。 */
function handleSwap() {
  const from = sourceFormat.value
  sourceFormat.value = targetFormat.value
  targetFormat.value = from
  if (output.value) {
    input.value = output.value
    output.value = ''
  }
}

function handleClear() {
  input.value = ''
  output.value = ''
}
</script>

<template>
  <div class="convert-tool">
    <el-form label-width="100px" class="param-form" inline>
      <el-form-item label="源格式">
        <el-radio-group v-model="sourceFormat">
          <el-radio-button value="json">JSON</el-radio-button>
          <el-radio-button value="yaml">YAML</el-radio-button>
          <el-radio-button value="xml">XML</el-radio-button>
        </el-radio-group>
      </el-form-item>

      <el-form-item label="目标格式">
        <el-radio-group v-model="targetFormat">
          <el-radio-button value="json">JSON</el-radio-button>
          <el-radio-button value="yaml">YAML</el-radio-button>
          <el-radio-button value="xml">XML</el-radio-button>
        </el-radio-group>
      </el-form-item>

      <el-form-item v-if="needsRootName" label="XML 根元素">
        <el-input v-model="rootName" placeholder="root" style="width: 160px" />
      </el-form-item>
    </el-form>

    <el-alert type="info" :closable="false" show-icon class="limit-tip">
      <template #title>
        已知语义损耗：XML 没有类型系统，转出的 JSON 里数字与布尔都会变成字符串；XML 同名重复子元素只保留最后一个（数组型 XML 请勿依赖本工具）；YAML 多文档只处理第一个。
      </template>
    </el-alert>

    <div class="panes">
      <div class="pane">
        <div class="pane-header"><span>{{ formatLabels[sourceFormat] }} 输入</span></div>
        <textarea v-model="input" class="code-textarea" spellcheck="false" placeholder="粘贴待转换的内容…" />
      </div>
      <div class="pane">
        <div class="pane-header">
          <span>{{ formatLabels[targetFormat] }} 输出</span>
          <CopyButton :text="output" label="转换结果" />
        </div>
        <textarea class="code-textarea" readonly spellcheck="false" :value="output" />
      </div>
    </div>

    <div class="actions">
      <el-button type="primary" :loading="loading" @click="handleConvert">转换</el-button>
      <el-button @click="handleSwap">互换方向</el-button>
      <el-button @click="handleClear">清空</el-button>
    </div>
  </div>
</template>

<style scoped>
.convert-tool {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.param-form {
  max-width: 960px;
}

.limit-tip {
  line-height: 1.6;
}

.panes {
  display: flex;
  gap: 16px;
}

.pane {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
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

.code-textarea {
  min-height: 300px;
  width: 100%;
  box-sizing: border-box;
  font-family: 'JetBrains Mono', 'Fira Code', 'Courier New', monospace;
  font-size: 13px;
  line-height: 1.6;
  padding: 10px 12px;
  border: 1px solid var(--el-border-color);
  border-radius: 6px;
  resize: vertical;
  outline: none;
  background: var(--el-fill-color-lighter);
  color: var(--el-text-color-primary);
}

.code-textarea:focus {
  border-color: var(--theme-primary, var(--el-color-primary));
  background: var(--el-bg-color);
}

.code-textarea[readonly] {
  background: var(--el-fill-color-light);
}

.actions {
  display: flex;
  gap: 12px;
}
</style>
