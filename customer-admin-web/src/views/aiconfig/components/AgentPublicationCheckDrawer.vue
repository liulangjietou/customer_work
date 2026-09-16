<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { checkAgentPublication, type AgentPublicationCheck } from '@/api/agentPublication'
import { getRequestErrorMessage } from '@/api/request'
import { useAuthStore } from '@/store/auth'

const visible = defineModel<boolean>({ required: true })
const props = defineProps<{ agentId: number | null }>()
defineEmits<{ channels: [] }>()
const auth = useAuthStore()
const data = ref<AgentPublicationCheck | null>(null)
const loading = ref(false)
const error = ref('')
const publication = computed(() => data.value?.latestPublication)
let generation = 0
let requests = new AbortController()
const candidateLabels: Record<AgentPublicationCheck['candidateStatus'], string> = {
  READY: '当前候选已核对', PUBLISHING_DISABLED: '环境尚未启用渠道发布', AGENT_DISABLED: '智能体已停用',
  CHANNEL_MISSING: '尚无启用的渠道绑定', UNAVAILABLE: '当前模型或发布配置暂时无法核对',
}
const matchLabels = {
  MATCH: '发布控制的字段与当前正式配置一致', CHANGED: '当前正式配置已变化，下面的发布记录属于旧配置',
  NOT_PREPARED: '任务尚未保存可比较的候选版本', UNAVAILABLE: '当前候选无法核对，不能确认版本一致',
}
const gateLabels: Record<string, string> = {
  NOT_REQUIRED: '本次未要求评测', PENDING: '等待评测', PASSED: '评测已通过', BLOCKED: '评测已阻断', OVERRIDDEN: '已人工豁免',
}
const publishLabels: Record<string, string> = {
  PENDING: '等待发布', PROCESSING: '正在发布', BLOCKED: '发布被门禁阻断', PUBLISHED: '已投递，等待实例确认',
  PARTIAL: '部分实例已生效', APPLIED: '任务记录为已生效', FAILED: '发布失败',
}
const confirmationLabels = {
  CONFIRMED: '冻结目标已全部确认', PARTIAL: '仍有目标未确认', WAITING: '尚未收到目标确认', REJECTED: '存在拒绝应用的目标',
  NO_TARGETS: '任务没有配置确认目标', LEGACY_UNVERIFIED: '历史任务未保存目标清单，无法核对全部实例',
}
const failures = computed(() => publication.value?.gateDecision?.checks.flatMap(check => check.failures) ?? [])
const notices = computed(() => publication.value?.gateDecision?.checks.flatMap(check => check.notices) ?? [])
function time(value: number | null | undefined) {
  return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '尚无记录'
}
function active(id: number) { return id === generation && visible.value }
async function refresh() {
  if (!visible.value || props.agentId === null) return
  const id = ++generation
  requests.abort(); requests = new AbortController()
  loading.value = true; error.value = ''; data.value = null
  try {
    const result = await checkAgentPublication(props.agentId, requests.signal)
    if (active(id)) data.value = result
  } catch (cause) {
    if (active(id)) error.value = getRequestErrorMessage(cause, '发布事实暂时无法加载，请稍后重试')
  } finally {
    if (active(id)) loading.value = false
  }
}
watch([visible, () => props.agentId], ([open]) => {
  generation += 1; requests.abort(); loading.value = false; data.value = null; error.value = ''
  if (open) void refresh()
}, { flush: 'sync' })
watch([() => auth.token, () => auth.loginGeneration, () => auth.permissions.join('\0')], () => {
  visible.value = false
}, { flush: 'sync' })
onBeforeUnmount(() => { generation += 1; requests.abort() })
</script>

<template>
  <el-drawer v-model="visible" title="发布检查" size="min(760px, 100vw)" class="publication-check-drawer">
    <div class="check-toolbar">
      <p>查看正式配置、评测和实例确认的实际记录。</p>
      <el-button :loading="loading" @click="refresh">刷新检查</el-button>
    </div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <div v-loading="loading" class="check-content" :aria-busy="loading">
      <template v-if="data">
        <div class="check-heading">
          <h3>{{ data.agentName }}</h3>
          <p>检查时间：{{ time(data.checkedAtMs) }}</p>
        </div>
        <section aria-label="正式配置" class="check-section">
          <header><span class="step">1</span><h4>正式配置</h4></header>
          <p>已保存修订 {{ data.runtimeRevision ?? '未记录' }} · {{ data.agentEnabled ? '已启用' : '已停用' }}</p>
          <p>{{ candidateLabels[data.candidateStatus] }}</p>
          <div class="channel-tags"><el-tag v-for="channel in data.channels" :key="channel.code" :type="channel.enabled ? 'info' : 'warning'">
            {{ channel.code }} · {{ channel.enabled ? '启用' : '停用' }}
          </el-tag></div>
          <p class="note">个人草稿需要先保存到正式配置。此处不使用草稿试用结果作为发布依据。</p>
        </section>
        <template v-if="publication">
          <el-alert :title="matchLabels[publication.currentMatch]" :type="publication.currentMatch === 'MATCH' ? 'info' : 'warning'"
            :closable="false" show-icon class="match-notice" />
          <section aria-label="评测门禁" class="check-section">
            <header><span class="step">2</span><h4>评测门禁</h4></header>
            <p class="fact-value">{{ gateLabels[publication.gateStatus ?? ''] ?? '门禁状态未记录' }}</p>
            <p v-if="publication.gateStatus === 'NOT_REQUIRED'" class="note">该记录不代表已通过评测。</p>
            <p v-if="publication.gateStatus === 'OVERRIDDEN'" class="note">本次使用人工豁免，不能作为评测通过证明。审计编号：{{ publication.overrideId ?? '未记录' }}</p>
            <p>评测时间：{{ time(publication.evaluatedAtMs) }}</p>
            <ul v-if="failures.length" class="check-failures"><li v-for="(item, index) in failures" :key="index">{{ item }}</li></ul>
            <ul v-if="notices.length" class="note"><li v-for="(item, index) in notices" :key="index">{{ item }}</li></ul>
            <p v-if="publication.evalRunIds.length" class="reference">评测记录：{{ publication.evalRunIds.join('、') }}</p>
          </section>
          <section aria-label="发布投递" class="check-section">
            <header><span class="step">3</span><h4>发布投递</h4></header>
            <p class="fact-value">{{ publishLabels[publication.status ?? ''] ?? '发布状态未记录' }}</p>
            <p class="note">投递成功后还需检查运行实例是否应用了对应版本。</p>
          </section>
          <section aria-label="实例确认" class="check-section">
            <header><span class="step">4</span><h4>实例确认</h4></header>
            <p class="fact-value">{{ confirmationLabels[publication.confirmation] }}</p>
            <el-alert v-if="data.currentRuntimeConfirmed" title="当前发布控制的配置已获目标实例确认" type="success" :closable="false" show-icon />
            <ul v-if="publication.targetInstances?.length" class="instance-list">
              <li v-for="instance in publication.targetInstances" :key="instance">
                <span>{{ instance }}</span>
                <strong>{{ publication.acknowledgements.find(ack => ack.instanceId === instance)?.status === 'APPLIED' ? '已应用'
                  : publication.acknowledgements.find(ack => ack.instanceId === instance)?.status === 'REJECTED' ? '已拒绝' : '待确认' }}</strong>
              </li>
            </ul>
          </section>
          <el-collapse class="version-details">
            <el-collapse-item title="版本与任务记录" name="versions">
              <p>任务：{{ publication.taskId }}</p><p>发布修订：{{ publication.revision ?? '尚未生成' }}</p>
              <p>候选内容指纹：{{ publication.candidateContentHash ?? '尚未生成' }}</p>
              <p>发布意图：{{ publication.intent ?? '历史记录未注明' }}</p>
              <p class="note">版本比较使用当前发布载荷控制的模型、提示词、迭代参数和 MCP/路由字段，不覆盖载荷之外的能力。</p>
            </el-collapse-item>
          </el-collapse>
        </template>
        <el-empty v-else description="尚无发布任务记录" :image-size="72" />
      </template>
    </div>
    <template #footer><el-button v-if="data" @click="$emit('channels')">查看渠道绑定</el-button><el-button @click="visible = false">关闭</el-button></template>
  </el-drawer>
</template>

<style scoped>
.check-toolbar { display: flex; gap: 12px; align-items: center; justify-content: space-between; }
.check-toolbar p, .note, .check-heading > p { color: var(--cw-text-muted); font-size: 13px; line-height: 1.7; }
.check-content { min-height: 120px; }.check-heading { margin: 18px 0; }.check-heading h3 { margin: 0; overflow-wrap: anywhere; }
.check-section { padding: 18px; margin: 14px 0; border: 1px solid var(--cw-line); border-radius: 12px; background: var(--cw-canvas); }
.check-section header { display: flex; align-items: center; gap: 10px; }.check-section h4 { margin: 0; }
.check-section p { font-size: 13px; line-height: 1.7; }.check-section .fact-value { font-size: 15px; font-weight: 600; }
.step { display: grid; place-items: center; width: 24px; height: 24px; border: 1px solid var(--cw-line); border-radius: 50%; font-size: 12px; }
.channel-tags { display: flex; flex-wrap: wrap; gap: 8px; }.match-notice { margin-top: 20px; }
.check-failures { color: var(--cw-danger); line-height: 1.8; font-size: 13px; padding-left: 20px; }
.reference, .version-details p { overflow-wrap: anywhere; }.version-details { margin-top: 22px; }
.instance-list { list-style: none; margin: 12px 0 0; padding: 0; }.instance-list li { display: flex; justify-content: space-between; gap: 14px; padding: 10px 0; font-size: 13px; }
.instance-list span { overflow-wrap: anywhere; min-width: 0; }.instance-list strong { white-space: nowrap; font-weight: 500; }
@media (max-width: 480px) { .check-toolbar { align-items: flex-start; }.check-toolbar .el-button { flex-shrink: 0; }.check-section { padding: 14px; } }
</style>
