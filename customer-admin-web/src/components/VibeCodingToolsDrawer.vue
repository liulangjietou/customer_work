<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import {
  cleanupManagedSandbox,
  getSandboxConfig,
  listManagedSandboxes,
} from '@/api/vibecoding'
import { useVibeConversationsStore, type VibeConversation } from '@/store/vibeConversations'
import type { ManagedSandboxView, RefactorTaskRequest, SandboxConfigView } from '@/types/api'

const props = defineProps<{
  agentCode: string
  conversation?: VibeConversation
}>()

const emit = defineEmits<{
  diagnose: [log: string]
  refactor: [request: Omit<RefactorTaskRequest, 'sessionId'>]
}>()

const store = useVibeConversationsStore()
const visible = ref(false)
const activeTab = ref('run')
const command = ref('mvn test')
const diagnosisLog = ref('')
const refactorType = ref<RefactorTaskRequest['taskType']>('REPLACE')
const refactorDescription = ref('')
const refactorTargets = ref('')
const config = ref<SandboxConfigView | null>(null)
const sandboxes = ref<ManagedSandboxView[]>([])
const loadingConfig = ref(false)
const loadingSandboxes = ref(false)
const cleaningSession = ref('')

const commandHistory = computed(() => props.conversation?.commandHistory ?? [])
const commandDisabled = computed(() => !config.value?.features.commandExecutionEnabled)
const busy = computed(() => props.conversation?.streaming || props.conversation?.commandRunning)

function open(tab = 'run') {
  activeTab.value = tab
  visible.value = true
  loadConfig()
}

async function loadConfig() {
  loadingConfig.value = true
  try {
    config.value = await getSandboxConfig(props.agentCode)
    if (activeTab.value === 'sandbox' && config.value.features.managementEnabled) {
      await loadSandboxes()
    }
  } catch (error) {
    ElMessage.error('沙箱配置加载失败：' + errorText(error))
  } finally {
    loadingConfig.value = false
  }
}

async function loadSandboxes() {
  if (!config.value?.features.managementEnabled) return
  loadingSandboxes.value = true
  try {
    sandboxes.value = await listManagedSandboxes(props.agentCode)
  } catch (error) {
    ElMessage.error('沙箱列表加载失败：' + errorText(error))
  } finally {
    loadingSandboxes.value = false
  }
}

function execute() {
  if (!command.value.trim() || busy.value) return
  store.executeCommand(props.agentCode, command.value)
}

function submitDiagnosis() {
  if (!diagnosisLog.value.trim() || busy.value) return
  emit('diagnose', diagnosisLog.value)
  visible.value = false
  diagnosisLog.value = ''
}

function submitRefactor() {
  if (!refactorDescription.value.trim() || busy.value) return
  const targetFiles = refactorTargets.value
    .split(/[\n,]/)
    .map((value) => value.trim())
    .filter(Boolean)
  emit('refactor', {
    taskType: refactorType.value,
    description: refactorDescription.value.trim(),
    targetFiles,
  })
  visible.value = false
  refactorDescription.value = ''
  refactorTargets.value = ''
}

async function cleanup(sessionId: string) {
  try {
    await ElMessageBox.confirm(
      `将停止并删除会话 ${sessionId} 的交互式沙箱；工作区文件会保留。是否继续？`,
      '清理沙箱',
      { type: 'warning', confirmButtonText: '确认清理', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  cleaningSession.value = sessionId
  try {
    await cleanupManagedSandbox(props.agentCode, sessionId)
    ElMessage.success('沙箱已清理')
    await loadSandboxes()
  } catch (error) {
    ElMessage.error('沙箱清理失败：' + errorText(error))
  } finally {
    cleaningSession.value = ''
  }
}

function statusType(status: ManagedSandboxView['status']): 'success' | 'primary' | 'danger' | 'info' {
  if (status === 'RUNNING') return 'primary'
  if (status === 'IDLE') return 'success'
  if (status === 'FAILED') return 'danger'
  return 'info'
}

function commandStatusText(status: string): string {
  const labels: Record<string, string> = {
    RUNNING: '运行中', SUCCESS: '成功', FAILED: '失败', CANCELLED: '已终止',
  }
  return labels[status] ?? status
}

function errorText(error: unknown): string {
  return error instanceof Error ? error.message : String(error)
}

watch(activeTab, (tab) => {
  if (tab === 'sandbox') {
    if (!config.value) loadConfig()
    else loadSandboxes()
  }
})

defineExpose({ open })
</script>

<template>
  <el-drawer v-model="visible" direction="btt" size="72%" title="AI 编码开发工具">
    <el-tabs v-model="activeTab" class="tools-tabs">
      <el-tab-pane label="运行" name="run">
        <el-alert
          v-if="commandDisabled"
          type="info"
          :closable="false"
          title="交互式命令执行默认关闭；配置 ADMIN_SANDBOX_COMMAND_EXECUTION_ENABLED=true 后开放。"
        />
        <div class="quick-commands">
          <span>快捷命令</span>
          <el-button size="small" @click="command = 'javac *.java'">javac</el-button>
          <el-button size="small" @click="command = 'mvn compile'">mvn compile</el-button>
          <el-button size="small" @click="command = 'mvn test'">mvn test</el-button>
        </div>
        <div class="command-line">
          <span class="prompt">$</span>
          <el-input
            v-model="command"
            :disabled="busy || commandDisabled"
            placeholder="输入在当前会话沙箱内执行的命令"
            @keyup.enter="execute"
          />
          <el-button
            v-if="!conversation?.commandRunning"
            type="primary"
            :disabled="busy || commandDisabled || !command.trim()"
            @click="execute"
          >运行</el-button>
          <el-button v-else type="danger" @click="store.stopCommand(agentCode)">终止</el-button>
        </div>

        <el-empty v-if="commandHistory.length === 0" description="本会话暂无命令记录" :image-size="60" />
        <el-collapse v-else accordion class="command-history">
          <el-collapse-item v-for="item in commandHistory" :key="item.id" :name="item.id">
            <template #title>
              <span class="history-title">
                <code>$ {{ item.command }}</code>
                <el-tag
                  size="small"
                  :type="item.status === 'SUCCESS' ? 'success' : item.status === 'RUNNING' ? 'primary' : 'danger'"
                >{{ commandStatusText(item.status) }}</el-tag>
                <span v-if="item.exitCode != null" class="exit-code">Exit {{ item.exitCode }}</span>
              </span>
            </template>
            <pre class="terminal-output">{{ item.output || '(no output)' }}</pre>
            <div v-if="item.testReport" class="test-summary">
              测试报告：{{ item.testReport.success ? '通过' : '失败' }} ·
              passed {{ item.testReport.passed }} / failed {{ item.testReport.failed }} /
              skipped {{ item.testReport.skipped }}
            </div>
          </el-collapse-item>
        </el-collapse>
      </el-tab-pane>

      <el-tab-pane label="Bug / 日志诊断" name="diagnose">
        <el-alert
          v-if="config && !config.features.diagnosisEnabled"
          type="info"
          :closable="false"
          title="诊断能力默认关闭；配置 ADMIN_SANDBOX_DIAGNOSIS_ENABLED=true 后开放。"
        />
        <el-input
          v-model="diagnosisLog"
          type="textarea"
          :rows="14"
          maxlength="100000"
          show-word-limit
          placeholder="粘贴异常堆栈或应用日志。Agent 会定位业务堆栈帧、检索源码、生成最小修复并运行测试。"
        />
        <div class="form-actions">
          <el-button
            type="primary"
            :disabled="busy || !config?.features.diagnosisEnabled || !diagnosisLog.trim()"
            @click="submitDiagnosis"
          >开始诊断</el-button>
        </div>
      </el-tab-pane>

      <el-tab-pane label="自动化重构" name="refactor">
        <el-alert
          type="warning"
          :closable="false"
          title="重构会先在对话流中展示影响计划并挂起；只有明确批准后才会修改文件。"
        />
        <el-form label-position="top" class="refactor-form">
          <el-form-item label="任务类型">
            <el-select v-model="refactorType" style="width: 260px">
              <el-option label="批量替换" value="REPLACE" />
              <el-option label="API 迁移" value="API_MIGRATION" />
              <el-option label="依赖升级" value="DEPENDENCY_UPGRADE" />
              <el-option label="代码风格统一" value="STYLE" />
            </el-select>
          </el-form-item>
          <el-form-item label="目标文件（可选，逗号或换行分隔）">
            <el-input v-model="refactorTargets" type="textarea" :rows="3" placeholder="src/main/java/..." />
          </el-form-item>
          <el-form-item label="重构目标与验收条件">
            <el-input v-model="refactorDescription" type="textarea" :rows="7" maxlength="10000" show-word-limit />
          </el-form-item>
        </el-form>
        <div class="form-actions">
          <el-button
            type="primary"
            :disabled="busy || !config?.features.refactorEnabled || !refactorDescription.trim()"
            @click="submitRefactor"
          >生成计划并等待确认</el-button>
        </div>
      </el-tab-pane>

      <el-tab-pane label="沙箱管理" name="sandbox">
        <div v-loading="loadingConfig" class="sandbox-config">
          <el-descriptions v-if="config" :column="3" border size="small" title="当前生效配置（只读）">
            <el-descriptions-item label="模式">{{ config.mode }}</el-descriptions-item>
            <el-descriptions-item label="命令超时">{{ config.executeTimeoutSeconds }} 秒</el-descriptions-item>
            <el-descriptions-item label="权限模式">{{ config.permissionMode }}</el-descriptions-item>
            <el-descriptions-item label="镜像">{{ config.docker.image }}</el-descriptions-item>
            <el-descriptions-item label="资源上限">{{ config.docker.cpuCount }} CPU / {{ config.docker.memoryMb }} MiB</el-descriptions-item>
            <el-descriptions-item label="网络">{{ config.docker.network }}</el-descriptions-item>
            <el-descriptions-item label="危险命令护栏">{{ config.guard.enabled ? '开启' : '关闭' }}</el-descriptions-item>
            <el-descriptions-item label="空闲回收">{{ config.features.idleTimeoutMinutes }} 分钟</el-descriptions-item>
          </el-descriptions>
        </div>
        <el-alert
          v-if="config && !config.features.managementEnabled"
          type="info"
          :closable="false"
          title="沙箱管理默认关闭；配置 ADMIN_SANDBOX_MANAGEMENT_ENABLED=true 后可查看和清理。"
        />
        <div v-else class="sandbox-list-header">
          <span>会话沙箱</span>
          <el-button link type="primary" :loading="loadingSandboxes" @click="loadSandboxes">刷新</el-button>
        </div>
        <el-table v-if="config?.features.managementEnabled" :data="sandboxes" v-loading="loadingSandboxes" empty-text="暂无会话沙箱">
          <el-table-column prop="sessionId" label="Session" min-width="190" show-overflow-tooltip />
          <el-table-column prop="mode" label="模式" width="90" />
          <el-table-column label="状态" width="100">
            <template #default="scope"><el-tag :type="statusType(scope.row.status)" size="small">{{ scope.row.status }}</el-tag></template>
          </el-table-column>
          <el-table-column prop="containerId" label="容器 ID" min-width="150" show-overflow-tooltip />
          <el-table-column label="资源" min-width="170">
            <template #default="scope">
              {{ scope.row.cpuUsage || '—' }} / {{ scope.row.memoryUsage || '—' }}
            </template>
          </el-table-column>
          <el-table-column prop="lastActiveAt" label="最后活跃" min-width="180" />
          <el-table-column label="操作" width="90" fixed="right">
            <template #default="scope">
              <el-button link type="danger" :loading="cleaningSession === scope.row.sessionId" @click="cleanup(scope.row.sessionId)">清理</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>
  </el-drawer>
</template>

<style scoped>
.tools-tabs { height: 100%; }
.quick-commands, .command-line, .history-title, .sandbox-list-header {
  display: flex;
  align-items: center;
  gap: 8px;
}
.quick-commands { margin: 14px 0 10px; color: var(--el-text-color-secondary); }
.command-line { margin-bottom: 14px; }
.prompt { color: var(--el-color-success); font: 700 18px ui-monospace, SFMono-Regular, Menlo, monospace; }
.command-history { margin-top: 12px; }
.history-title { width: 100%; min-width: 0; }
.history-title code { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.exit-code { color: var(--el-text-color-secondary); font-size: 12px; }
.terminal-output {
  min-height: 100px;
  max-height: 280px;
  overflow: auto;
  padding: 14px;
  margin: 0;
  border-radius: 6px;
  background: #101418;
  color: #d6deeb;
  white-space: pre-wrap;
  word-break: break-all;
  font: 12px/1.6 ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
}
.test-summary { margin-top: 8px; color: var(--el-text-color-secondary); font-size: 13px; }
.form-actions { display: flex; justify-content: flex-end; margin-top: 14px; }
.refactor-form { margin-top: 14px; }
.sandbox-config { margin-bottom: 14px; }
.sandbox-list-header { justify-content: space-between; margin: 14px 0 8px; font-weight: 600; }
</style>
