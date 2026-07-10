<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { debugMcpCallTool, debugMcpTools } from '@/api/mcp'
import type { McpDebugCallResult, McpDebugToolVO } from '@/types/api'

const props = defineProps<{ mcpId: number | null; mcpName: string }>()
const visible = defineModel<boolean>({ default: false })

const connecting = ref(false)
const connected = ref(false)
const connectError = ref('')
const tools = ref<McpDebugToolVO[]>([])
const selectedToolName = ref('')
const selectedTool = computed(() => tools.value.find((t) => t.name === selectedToolName.value) ?? null)

/** 每个字段的原始输入值：string/boolean/number 直接存对应类型，array/object 等复杂类型存 JSON 文本，调用前再解析。 */
const formValues = reactive<Record<string, unknown>>({})
const calling = ref(false)
const result = ref<McpDebugCallResult | null>(null)

function fieldKind(schema: Record<string, unknown> | undefined): 'string' | 'number' | 'boolean' | 'json' {
  const type = schema?.type
  if (type === 'integer' || type === 'number') return 'number'
  if (type === 'boolean') return 'boolean'
  if (type === 'string') return 'string'
  return 'json'
}

function fieldOptions(schema: Record<string, unknown> | undefined): string[] | null {
  const enumValues = schema?.enum
  return Array.isArray(enumValues) ? enumValues.map(String) : null
}

async function connect() {
  if (!props.mcpId) return
  connecting.value = true
  connectError.value = ''
  connected.value = false
  tools.value = []
  selectedToolName.value = ''
  result.value = null
  try {
    tools.value = await debugMcpTools(props.mcpId)
    connected.value = true
    if (tools.value.length > 0) {
      selectTool(tools.value[0])
    }
  } catch (error) {
    connectError.value = error instanceof Error ? error.message : String(error)
  } finally {
    connecting.value = false
  }
}

function selectTool(tool: McpDebugToolVO) {
  selectedToolName.value = tool.name
  result.value = null
  Object.keys(formValues).forEach((key) => delete formValues[key])
  for (const [key, schema] of Object.entries(tool.properties)) {
    const kind = fieldKind(schema)
    const defaultValue = (schema as { default?: unknown }).default
    if (defaultValue !== undefined) {
      formValues[key] = kind === 'json' ? JSON.stringify(defaultValue, null, 2) : defaultValue
    } else if (kind === 'boolean') {
      formValues[key] = false
    } else if (kind === 'json') {
      formValues[key] = ''
    } else {
      formValues[key] = ''
    }
  }
}

async function callTool() {
  if (!props.mcpId || !selectedTool.value) return
  const args: Record<string, unknown> = {}
  for (const [key, schema] of Object.entries(selectedTool.value.properties)) {
    const raw = formValues[key]
    const kind = fieldKind(schema)
    if (kind === 'json') {
      const text = String(raw ?? '').trim()
      if (!text) continue
      try {
        args[key] = JSON.parse(text)
      } catch {
        ElMessage.error(`参数「${key}」不是合法 JSON`)
        return
      }
    } else if (kind === 'string') {
      if (String(raw ?? '').trim() === '') continue
      args[key] = raw
    } else {
      args[key] = raw
    }
  }
  const missing = selectedTool.value.required.filter((key) => args[key] === undefined || args[key] === '')
  if (missing.length > 0) {
    ElMessage.error(`缺少必填参数：${missing.join('、')}`)
    return
  }

  calling.value = true
  result.value = null
  try {
    result.value = await debugMcpCallTool(props.mcpId, selectedTool.value.name, args)
  } catch (error) {
    result.value = { success: false, output: null, errorMessage: error instanceof Error ? error.message : String(error) }
  } finally {
    calling.value = false
  }
}

watch(visible, (val) => {
  if (val) {
    connect()
  }
})
</script>

<template>
  <el-dialog v-model="visible" :title="`调试 MCP · ${mcpName}`" width="1100px" top="5vh" class="mcp-debug-dialog">
    <div class="debug-body">
      <!-- 左：工具列表 -->
      <div class="tool-list-pane">
        <div class="pane-title">
          <span>工具列表</span>
          <el-button link type="primary" :loading="connecting" @click="connect">
            <el-icon><Refresh /></el-icon>
          </el-button>
        </div>
        <div v-if="connecting" class="pane-empty">
          <el-icon class="is-loading"><Loading /></el-icon>
          <span>连接中…</span>
        </div>
        <div v-else-if="connectError" class="pane-empty pane-error">
          <el-icon><CircleClose /></el-icon>
          <span>{{ connectError }}</span>
        </div>
        <div v-else-if="tools.length === 0" class="pane-empty">
          <span>该 MCP 未提供任何工具</span>
        </div>
        <el-scrollbar v-else class="tool-list-scroll">
          <div
            v-for="tool in tools"
            :key="tool.name"
            class="tool-item"
            :class="{ active: tool.name === selectedToolName }"
            @click="selectTool(tool)"
          >
            <div class="tool-item-name">{{ tool.name }}</div>
            <div v-if="tool.description" class="tool-item-desc" :title="tool.description">{{ tool.description }}</div>
          </div>
        </el-scrollbar>
      </div>

      <!-- 右：参数表单 + 调用结果 -->
      <div class="detail-pane">
        <template v-if="selectedTool">
          <el-scrollbar class="detail-scroll">
            <div class="tool-header">
              <div class="tool-header-name">{{ selectedTool.name }}</div>
              <div v-if="selectedTool.description" class="tool-header-desc">{{ selectedTool.description }}</div>
            </div>

            <el-form label-position="top" size="default">
              <template v-if="Object.keys(selectedTool.properties).length === 0">
                <el-text type="info" size="small">该工具无需参数</el-text>
              </template>
              <el-form-item
                v-for="(schema, key) in selectedTool.properties"
                :key="key"
                :label="`${key}${selectedTool.required.includes(String(key)) ? ' *' : ''}`"
              >
                <template #label>
                  <span class="field-label">
                    {{ key }}
                    <el-tag v-if="selectedTool.required.includes(String(key))" size="small" type="danger" effect="plain">必填</el-tag>
                    <span v-if="(schema as any).description" class="field-desc">{{ (schema as any).description }}</span>
                  </span>
                </template>

                <el-select v-if="fieldOptions(schema)" v-model="formValues[key]" style="width: 100%" clearable>
                  <el-option v-for="opt in fieldOptions(schema)!" :key="opt" :label="opt" :value="opt" />
                </el-select>
                <el-input-number
                  v-else-if="fieldKind(schema) === 'number'"
                  v-model="formValues[key]"
                  style="width: 100%"
                  controls-position="right"
                />
                <el-switch v-else-if="fieldKind(schema) === 'boolean'" v-model="formValues[key]" />
                <el-input
                  v-else-if="fieldKind(schema) === 'json'"
                  v-model="formValues[key] as string"
                  type="textarea"
                  :rows="3"
                  placeholder="JSON 格式，如 [1,2,3] 或 {&quot;a&quot;:1}"
                />
                <el-input v-else v-model="formValues[key] as string" placeholder="string" />
              </el-form-item>
            </el-form>

            <el-button type="primary" :loading="calling" style="margin: 8px 0 16px" @click="callTool">
              <el-icon style="margin-right: 4px"><VideoPlay /></el-icon>
              调用
            </el-button>

            <div v-if="result" class="result-block">
              <div class="result-title">
                <el-tag :type="result.success ? 'success' : 'danger'" size="small">
                  {{ result.success ? '调用成功' : '调用失败' }}
                </el-tag>
              </div>
              <pre class="result-content">{{ result.success ? result.output : result.errorMessage }}</pre>
            </div>
          </el-scrollbar>
        </template>
        <div v-else class="pane-empty">
          <span>{{ connected ? '选择左侧工具开始调试' : '请先连接 MCP 服务器' }}</span>
        </div>
      </div>
    </div>

    <template #footer>
      <el-button @click="visible = false">关闭</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.debug-body {
  display: flex;
  gap: 16px;
  height: 65vh;
}

.pane-title {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 12px;
  font-size: 13px;
  font-weight: 600;
  color: #606266;
  background: #f5f7fa;
  border-bottom: 1px solid #ebeef5;
}

.tool-list-pane {
  flex: 0 0 260px;
  display: flex;
  flex-direction: column;
  border: 1px solid #ebeef5;
  border-radius: 4px;
  overflow: hidden;
}

.tool-list-scroll {
  flex: 1;
  min-height: 0;
}

.tool-item {
  padding: 10px 12px;
  cursor: pointer;
  border-bottom: 1px solid #f2f3f5;
}

.tool-item:hover {
  background: #f5f7fa;
}

.tool-item.active {
  background: #ecf5ff;
  border-left: 3px solid #409eff;
  padding-left: 9px;
}

.tool-item-name {
  font-size: 13px;
  font-weight: 600;
  color: #303133;
  font-family: 'JetBrains Mono', 'Fira Code', 'Courier New', monospace;
}

.tool-item-desc {
  margin-top: 2px;
  font-size: 12px;
  color: #909399;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.detail-pane {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  border: 1px solid #ebeef5;
  border-radius: 4px;
  overflow: hidden;
}

.detail-scroll {
  flex: 1;
  min-height: 0;
  padding: 16px;
}

.tool-header {
  margin-bottom: 16px;
  padding-bottom: 12px;
  border-bottom: 1px solid #ebeef5;
}

.tool-header-name {
  font-size: 15px;
  font-weight: 700;
  font-family: 'JetBrains Mono', 'Fira Code', 'Courier New', monospace;
  color: #303133;
}

.tool-header-desc {
  margin-top: 4px;
  font-size: 13px;
  color: #909399;
}

.field-label {
  display: flex;
  align-items: baseline;
  gap: 6px;
  font-family: 'JetBrains Mono', 'Fira Code', 'Courier New', monospace;
}

.field-desc {
  font-family: initial;
  font-weight: 400;
  color: #909399;
  font-size: 12px;
}

.result-block {
  margin-top: 8px;
}

.result-title {
  margin-bottom: 6px;
}

.result-content {
  margin: 0;
  padding: 12px;
  background: #f5f7fa;
  border-radius: 4px;
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 320px;
  overflow: auto;
}

.pane-empty {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: #909399;
  font-size: 13px;
}

.pane-error {
  color: #f56c6c;
  padding: 0 16px;
  text-align: center;
}
</style>
